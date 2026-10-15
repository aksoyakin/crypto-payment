# 0013. Pending and available balances

- Status: Accepted
- Date: 2026-10-16
- Amends: 0008

## Context
ADR 0008 credited a payment straight to the merchant's available balance once it was final, and allowed small
payments to count as final at the `safe` block tag. A `safe` block can still be reorganised away. If the merchant
had already been paid out, the reversing entry would take the available balance below zero, which the
`CHECK (amount >= 0)` constraint rejects: the ledger could not record what the chain did.

Two questions were folded into one: "may the merchant ship the order?" and "may the merchant withdraw the
money?". Card processors answer them at different times for the same reason.

## Decision
- A payment is `succeeded` (and the merchant is told by webhook) when its block is `safe`, or `finalized` for
  amounts above a threshold. At that point the ledger debits custody and credits the merchant's **pending**
  balance and fee revenue.
- When the block is `finalized`, the amount moves from pending to **available** in a second transaction.
- Only the available balance can be paid out.
- A reorganisation before finality reverses the pending entries and turns the payment back to `processing`
  (or `failed` if the transfer is gone for good); the merchant is notified by webhook.

## Consequences
- The available balance can never be undermined by a reorganisation, so the non-negative constraint holds.
- Merchants see two balances, as they would with a card processor.
- A reversal after `succeeded` is possible, rare, and visible to the merchant as its own event.
