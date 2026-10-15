# Deployment

Crypto Payment runs as a single Docker Compose stack on a VPS managed by [Dokploy](https://dokploy.com).
CI builds and tests every commit; images are built from `main` only after all tests pass, and only the ones the
push actually changed ([ADR 0012](adr/0012-build-and-deployment.md)).

```
git push main ─► GitHub Actions ─► tests (Maven, Biome, type check, web build)
                                 ─► the changed images → ghcr.io/<owner>/crypto-payment-{api,chain-watcher,signer,web}
                                 ─► Dokploy deploy API ─► docker compose pull && up
```

## Topology

| Public URL | Routed to | Notes |
|---|---|---|
| `https://cryptopayment.aksoyakin.com` | `web:3000` | Marketing, documentation, dashboard, admin |
| `https://pay.cryptopayment.aksoyakin.com` | `web:3000` | Hosted checkout, selected by host inside the web app |
| `https://api.cryptopayment.aksoyakin.com/v1` | `api:8080` | The public API. **Only `/v1` is routed**; `/internal` is reached by `web` over the Docker network (ADR 0007) |
| — | `chain-watcher`, `signer` | Internal only. The signer is not even attached to the proxy's network (ADR 0001) |
| — | PostgreSQL | Internal only, no published port |
| — | `api:9080`, `chain-watcher:9081`, `signer:9082` | Management ports, internal only; container health checks use them |

## One-time setup

### 1. DNS (Cloudflare)
Three `A` records pointing at the VPS: `cryptopayment`, `api.cryptopayment` and `pay.cryptopayment` under
`aksoyakin.com`. Keep them **DNS only** (grey cloud): Cloudflare's free certificate does not cover second-level
subdomains, and Traefik obtains certificates from Let's Encrypt itself.

### 2. Container images
CI pushes to GitHub Container Registry. New GHCR packages are private: either make the four
`crypto-payment-*` packages public, or add GHCR credentials under Dokploy → Registry.

### 3. Dokploy service
Create a **Compose** service:

- Provider: GitHub, this repository, branch `main`
- Compose path: `infra/docker-compose.prod.yml`
- Environment:

  ```
  IMAGE_PREFIX=ghcr.io/<owner>/crypto-payment
  IMAGE_TAG=latest
  POSTGRES_PASSWORD=<random>
  API_MIGRATOR_DB_PASSWORD=<random>
  API_DB_PASSWORD=<random>
  CHAIN_WATCHER_MIGRATOR_DB_PASSWORD=<random>
  CHAIN_WATCHER_DB_PASSWORD=<random>
  SIGNER_MIGRATOR_DB_PASSWORD=<random>
  SIGNER_DB_PASSWORD=<random>
  ```

  Database passwords are applied only when the PostgreSQL volume is first initialised.

  The chain-watcher also takes the chain it follows and the nodes to reach it through:

  ```
  CHAIN_ID=ethereum-sepolia
  CHAIN_RPC_URLS=https://<provider>/<key>,https://<second provider>/<key>
  ```

  Endpoints are tried in the order given, so a second provider is a fallback rather than a round robin. With
  `CHAIN_RPC_URLS` empty the service starts and follows nothing, which is the right state before an RPC
  provider is chosen.

### 4. Domains (Dokploy → Domains, HTTPS with Let's Encrypt)

| Service | Host | Path | Port |
|---|---|---|---|
| `web` | `cryptopayment.aksoyakin.com` | `/` | 3000 |
| `web` | `pay.cryptopayment.aksoyakin.com` | `/` | 3000 |
| `api` | `api.cryptopayment.aksoyakin.com` | `/v1` (do not strip the path) | 8080 |

Nothing else is routed: not the api's `/internal` prefix, not the management ports, not the chain-watcher or
the signer.

### 5. Continuous deployment
CI redeploys through the Dokploy API after the images are pushed. Add three repository secrets
(GitHub → Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `DOKPLOY_URL` | Dokploy panel URL, e.g. `https://dokploy.example.com` (HTTPS: the API key is sent with every deploy) |
| `DOKPLOY_API_KEY` | Dokploy → Settings → Profile → API/CLI → generate a token |
| `DOKPLOY_COMPOSE_ID` | The id at the end of the Compose service URL: `.../services/compose/<composeId>` |

Keep Dokploy's *Autodeploy* off: a push would otherwise deploy before CI has built the new images. Without the
secrets, CI still publishes images and skips the deploy step.

## Backups

The ledger is the only record of which share of the pooled funds belongs to whom, so the database is backed up
off the server ([ADR 0018](adr/0018-backups.md)):

1. Dokploy → Settings → S3 Destinations: add an S3-compatible bucket outside the VPS.
2. The compose service's `postgres` → Backups: daily, database `cryptopayment`, keep 30 days.
3. Restore the first backup into a scratch database and check that it opens. Repeat after any change to the
   backup configuration; a backup that has never been restored is not yet a backup.

Encryption keys ([ADR 0017](adr/0017-encrypting-application-secrets.md)) are kept outside the server as well, and
apart from the database backups.

## Upgrading a database that already exists

`infra/postgres/init/01-roles-and-schemas.sh` only runs when the PostgreSQL volume is created. A role or schema
added to it later has to be created by hand once on an existing database. Since the first deployment, the
`audit` schema was added, the `fees` schema was dropped (fee schedules live in `payments`), and each service
got a schema for Flyway's history table:

```sql
CREATE SCHEMA audit AUTHORIZATION cryptopayment_api_migrator;
GRANT USAGE ON SCHEMA audit TO cryptopayment_api;
ALTER DEFAULT PRIVILEGES FOR ROLE cryptopayment_api_migrator IN SCHEMA audit
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cryptopayment_api;
ALTER DEFAULT PRIVILEGES FOR ROLE cryptopayment_api_migrator IN SCHEMA audit
    GRANT USAGE, SELECT ON SEQUENCES TO cryptopayment_api;
DROP SCHEMA fees;

-- Flyway's bookkeeping, kept out of the domain schemas. The runtime roles are granted nothing here.
CREATE SCHEMA api_migrations           AUTHORIZATION cryptopayment_api_migrator;
CREATE SCHEMA chain_watcher_migrations AUTHORIZATION cryptopayment_chain_watcher_migrator;
CREATE SCHEMA signer_migrations        AUTHORIZATION cryptopayment_signer_migrator;
```

## Migrations

Each service runs its own migrations on startup, as its **migrator** role, and then serves traffic as its
**runtime** role, which owns nothing. A deployment therefore needs no migration step of its own; a service whose
migration fails does not pass its health check, and Dokploy keeps the previous container serving.
