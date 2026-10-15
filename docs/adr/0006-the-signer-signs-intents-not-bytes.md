# 0006. The signer signs intents, not bytes, and keeps its own journal

- Status: Accepted
- Date: 2026-10-15

## Context
The signer exists so that a compromised api cannot move funds at will (ADR 0001). That only holds if the signer
can judge a request on its own. An endpoint that signs whatever bytes it is given cannot: whoever controls the api
controls the signatures.

## Decision
- The signer accepts **intents** and builds each transaction itself:
  - sweep a deposit address (by derivation index) to the hot wallet;
  - fund a deposit address with gas from the hot wallet;
  - move funds from the hot wallet to the cold wallet;
  - pay a merchant out from the hot wallet.
- Each intent is checked against what the signer knows without asking the api: the hot wallet and the cold
  wallet are in its configuration, deposit addresses are ones it derives itself, and gas funding has a small
  ceiling. A merchant payout has a destination the signer cannot verify, so it is bounded by a per-transaction
  limit and a daily limit; payouts above a threshold will require an administrator's approval.
- The signer keeps an **append-only journal** of what it has signed, in a `signer` schema that only its own
  database role can read or write. Daily limits are computed from this journal, never from figures the api sends.
- Every request carries an identifier (the payout or sweep it belongs to). A repeated request returns the same
  signature. A fee bump for a stuck transaction (same nonce, higher fee) carries the same identifier and does not
  count against the daily limit a second time.
- The api chooses nonces and fees and broadcasts; the signer never talks to a chain.
- Transport: HTTP and JSON over the internal network, with **mutual TLS**. Certificates come from an internal CA
  and are mounted as secrets. The signer has no route from the outside world.

## Consequences
- The signer is no longer stateless; the journal is the price of limits the api cannot falsify.
- The api and the signer must agree on how a transaction is built; this is covered by contract tests.
- gRPC was considered and rejected: one consumer and a handful of operations do not repay a second schema
  toolchain.
