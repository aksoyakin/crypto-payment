# Crypto Payment

A payment gateway that lets merchants accept stablecoins and native crypto: payment intents over an API, a hosted
checkout, signed webhooks, a double-entry ledger and scheduled payouts.

> Work in progress. Runs on testnets only. Operating a custodial payment service with real funds requires
> licensing in most jurisdictions; this project does not hold one and does not handle real money.

## Architecture

| Component | Responsibility |
|---|---|
| `services/api` | Modular monolith: identity, merchants, payments, ledger, fees, treasury, settlement, webhooks |
| `services/chain-watcher` | Follows each supported chain, detects transfers to deposit addresses, handles reorganisations |
| `services/signer` | Holds the private keys and signs transactions after checking its own policy; unreachable from outside |
| `libs/event-contracts` | Versioned messages exchanged between services |
| `web` | Next.js: marketing site, documentation, merchant dashboard, admin panel, hosted checkout |
| `infra` | Docker Compose for local development: PostgreSQL and a local EVM chain (Anvil) |
| `docs` | Architecture Decision Records and diagrams |

Design decisions: see [docs/adr](docs/adr/README.md). Deployment: see [docs/deployment.md](docs/deployment.md).

## Tech stack

Java 27 · Spring Boot 4.1 · Spring Modulith · Maven · PostgreSQL · Next.js · Anvil

## Build

```bash
docker compose -f infra/docker-compose.yml up -d   # PostgreSQL and Anvil for local development
./mvnw verify                                      # backend
corepack pnpm install && corepack pnpm check       # frontend workspace (web)
corepack pnpm --filter @cryptopayment/web dev      # http://localhost:3000
```

## Licence

Crypto Payment is licensed under the [MIT License](LICENSE).
