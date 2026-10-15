# 0018. Backups

- Status: Accepted
- Date: 2026-10-16

## Context
The ledger is the only record of which share of the pooled funds belongs to which merchant (ADR 0002). Losing
the database would not lose the funds, but it would lose who owns them.

## Decision
- PostgreSQL is backed up daily with Dokploy's scheduled backups to S3-compatible storage outside the server,
  and backups are kept for 30 days.
- The encryption keys of ADR 0017 are kept outside the server too, separately from the database backups.
- A backup counts only once it has been restored: a restore into a scratch database is part of setting this
  up, and is repeated after any change to the backup configuration.

## Consequences
- Losing the server costs at most a day of records, which reconciliation against the chain can rebuild for
  incoming payments.
- Backups and keys are stored apart, so one leak does not expose both.
