# 0015. One watcher per chain, enforced by an advisory lock

- Status: Accepted
- Date: 2026-10-16
- Amends: 0009

## Context
ADR 0009 makes the order of observations correct only while one watcher writes each chain. During a deploy the
old and the new container run side by side for a moment, which is exactly when two writers would appear.

## Decision
- Before following a chain, a watcher takes `pg_try_advisory_lock` on a key derived from the chain, on a
  connection it keeps open. Without the lock it does not write and retries later.
- The lock is released when the connection ends, so a crashed watcher frees its chain without intervention.
- A watcher that loses its connection stops writing for that chain before it tries to take the lock again.

## Consequences
- The single-writer rule is enforced by the database rather than by deployment discipline.
- A test runs two watchers against one chain and asserts that only one of them writes.
