# 0011. No Redis

- Status: Accepted
- Date: 2026-10-15

## Context
Without a message broker (ADR 0003), what would remain for Redis is dashboard sessions and rate limiting.

## Decision
- Dashboard sessions use Spring Session with JDBC, in PostgreSQL.
- Rate limits are counted in PostgreSQL.

## Consequences
- The stack is PostgreSQL plus the three services; one fewer component to run, secure and back up.
- A rate-limit check costs a database round trip. At this volume that is acceptable; if measurement says
  otherwise, Redis can be added behind the same interface.
