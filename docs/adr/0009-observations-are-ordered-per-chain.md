# 0009. Observations are ordered per chain

- Status: Accepted
- Date: 2026-10-15
- Amends: 0003

## Context
ADR 0003 has the api read `chain.observations` past a cursor. If the order came from a single database
sequence, the cursor could skip rows: sequence values are handed out when requested, not when committed. With
two writers, `seq = 11` can commit while `seq = 10` is still open; a reader that sees 11 and moves its cursor
past it will never see 10.

## Decision
- Observations are keyed by `(chain, seq)`. The sequence is per chain and assigned by the watcher itself.
- Each chain has one active watcher that writes one transaction at a time, so within a chain commit order and
  sequence order are the same.
- The api keeps one cursor per chain.

## Consequences
- No gap-detection or "wait for stragglers" logic is needed on the reading side.
- Running two watchers for the same chain would break the guarantee; leadership per chain becomes a correctness
  requirement, not only an efficiency one.
