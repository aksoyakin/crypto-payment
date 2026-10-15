# 0008. A double-entry ledger, enforced by the database

- Status: Accepted
- Date: 2026-10-15

## Context
Funds of every merchant are pooled on chain (ADR 0002). Which share belongs to whom exists only in the ledger,
so the ledger has to be right even when the application code around it is not.

## Decision
- Four tables: `accounts`, `transactions`, `entries` and `balances`. An entry has a direction (debit or credit)
  and a positive amount in the asset's smallest unit, stored as `numeric(78,0)` because a `uint256` has up to
  78 digits.
- A transaction is unique per `(reference_type, reference_id, kind)`, so one payment cannot be credited twice.
- **Balance is checked by the database:** a deferred constraint trigger verifies at commit that, within each
  transaction and for each asset, debits equal credits.
- **Append-only is enforced by privileges:** the api's role has no `UPDATE` or `DELETE` on entries and
  transactions. A mistake is corrected with a reversing transaction.
- `balances` is a cache of `SUM(entries)`, updated in the same transaction. Merchant liability balances carry
  `CHECK (amount >= 0)`. A test compares the cache with the sum.
- Nothing is written while a payment is only observed. When it reaches finality the full amount is debited to
  the platform's custody account and credited to the merchant's available balance and to fee revenue in one
  transaction. Finality comes from the chain, so there is no separate pending balance.
- Network fees the platform pays (sweeps, payouts) are posted to an expense account; without it the custody
  account would drift from the chain.

## Consequences
- An unbalanced or rewritten ledger requires a privileged database role, not a bug.
- Reconciliation compares on-chain holdings with custody accounts directly.
- The deferred trigger costs a query per committed transaction, which this volume affords.
