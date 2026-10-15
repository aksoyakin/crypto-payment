# 0020. Modules are opened with their first code

- Status: Accepted
- Date: 2026-10-16
- Amends: 0001

## Context
ADR 0001 listed eight modules in the api, and the skeleton created all of them before any code existed. An
empty module's boundary is a guess; it is easier to draw once there is code on both sides of it.

## Decision
- The api starts with the modules the first milestone needs: merchant, payments, ledger, treasury, webhooks and
  the shared kernel.
- Identity (dashboard users), settlement and audit are added when their first code is written.
- Fee schedules are part of payments rather than a module of their own: they decide what a payment costs, and
  nothing else uses them.

## Consequences
- Every module in the code base has code in it.
- The modules that will come are still named in the architecture documentation, so their boundaries are not
  forgotten.
