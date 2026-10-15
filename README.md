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
| `docs` | Architecture Decision Records and diagrams |

Design decisions: see [docs/adr](docs/adr/README.md).

## Tech stack

Java 27 · Spring Boot 4.1 · Spring Modulith · Maven

## Build

```bash
./mvnw verify
```

## Licence

Crypto Payment is licensed under the [MIT License](LICENSE).
