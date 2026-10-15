# 0012. Build and deployment

- Status: Accepted
- Date: 2026-10-15

## Context
The system is four containers (three Spring Boot services and the web app) and PostgreSQL, run on one VPS
managed by Dokploy. A deployment must not run untested code, and should not restart containers whose code did
not change.

## Decision
- **Images:** one Dockerfile for every Spring Boot service, selected by a build argument, with layered jars and
  a non-root user; one for the web app, using Next.js's standalone output. Temurin 27 is installed from
  Adoptium's release binaries and verified against their published checksums, until an official image exists.
- **CI (GitHub Actions):** every push and pull request runs the Maven build with its tests and the web
  workspace's lint, type check and build. On `main`, only the images whose inputs changed are built and pushed
  to GitHub Container Registry, and Dokploy is asked to redeploy through its API.
- **Production compose** (`infra/docker-compose.prod.yml`): PostgreSQL, the three services and web. Only web
  and the api are attached to the reverse proxy's network, and only the api's `/v1` prefix is routed to it
  (ADR 0007). The chain-watcher, the signer and the database have no route from outside.
- Management endpoints run on their own ports in every service; no route points at them.

## Consequences
- A push that changes one service restarts one container.
- Routes are configured in Dokploy rather than in labels in the compose file; `docs/deployment.md` lists them.
- Images are built for `linux/amd64` only, which is what the server runs.
