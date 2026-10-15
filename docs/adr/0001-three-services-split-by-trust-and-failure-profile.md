# 0001. Three services, split by trust and failure profile; a modular monolith inside

- Status: Accepted
- Date: 2026-10-15

## Context
The system has three kinds of work that differ in what they are trusted with and in how they fail:

- serving merchants and their customers: an HTTP API, a dashboard and the state of every payment and balance;
- following blockchains: long-running, stateful per chain, bound by third-party RPC providers that are slow,
  rate-limited or down, and exposed to reorganisations;
- signing transactions: the only work that needs private keys.

Bounded contexts alone would suggest many more services. Most of them, however, share a transaction: a payment
that succeeds and the ledger entries that credit the merchant must be written together or not at all.

## Decision
Three deployable services:

- **api** — a modular monolith holding identity, merchant, payments, ledger, fees, treasury, settlement and
  webhooks. Module boundaries are enforced with Spring Modulith and tested.
- **chain-watcher** — follows each chain from a persisted cursor, reports transfers to watched addresses and
  retracts what a reorganisation removed. One active instance per chain.
- **signer** — the only process that loads key material. It accepts requests from the internal network only and
  checks its own policy (allowed destinations, per-transaction and daily limits) before signing. It returns signed
  transactions and does not broadcast them.

## Consequences
- A compromised api cannot drain the hot wallet beyond the signer's limits: the signer does not trust its caller.
- An RPC outage or a burst of reorganisations does not affect the latency of the merchant API.
- Payments and the ledger stay in one transaction; there is no saga and no distributed transaction.
- The chain-watcher is not split out to avoid data loss during deploys: it resumes from its cursor, so a restart
  loses nothing. The reason for the split is isolation, not durability.
- Webhook delivery stays in the api, behind a transactional outbox. A separate service would add a deployable
  without adding a boundary.
