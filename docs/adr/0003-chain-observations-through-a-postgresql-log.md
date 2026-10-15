# 0003. Chain observations reach the api through a PostgreSQL log, not a message broker

- Status: Accepted, amended by 0009
- Date: 2026-10-15

## Context
The chain-watcher reports what it sees on chain: a transfer to a watched address, a change in its
confirmations, a transfer that a reorganisation removed. The api turns each observation into a payment state
change and, when a payment succeeds, into ledger entries. In the other direction, the watcher needs the set of
addresses to watch, which the api creates.

The volume is low: Ethereum produces a block every twelve seconds, and a payment produces a handful of
observations. Throughput is not a reason to add a broker. What matters is that every observation is applied
exactly once, and that observations can be applied again after a matching bug is fixed.

## Decision
- The watcher appends every observation to a table in its own schema, `chain.observations`, ordered by a
  monotonically increasing sequence. Rows are never updated.
- The api reads observations through a view, which is the contract between the two services, and keeps its own
  cursor. Applying an observation (payment state, ledger entries, outbox rows for webhooks) and advancing the
  cursor happen in **one database transaction**.
- Watched addresses flow the other way: the api writes them to its `treasury` schema and the watcher reads them
  through a view.
- Each service connects with its own database role and can only write to its own schema.

## Consequences
- Exactly-once application follows from the transaction rather than from idempotent consumers: there is no
  window between "processed" and "acknowledged".
- Replay is a cursor reset.
- The two services share a database server. The coupling is limited to two views and enforced by roles.
- Polling adds about a second of latency, which is small next to the block time.
- Kafka was considered and rejected: offsets and database writes cannot commit together, so every consumer would
  have to be idempotent, and the broker would add an operational component without a throughput need. If the
  load ever requires it, the observations table can become an outbox that feeds a broker.
