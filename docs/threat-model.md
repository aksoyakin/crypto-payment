# Threat model

What the platform protects, from whom, and which decision answers each threat. A threat without an answer is
listed as open rather than left out.

## What is worth stealing or breaking

| Asset | Why it matters | Where it lives |
|---|---|---|
| Hot wallet key | Signs every payout and flush; whoever holds it can move the hot wallet's balance | The signer only, encrypted at rest ([0005](adr/0005-key-custody.md), [0017](adr/0017-encrypting-application-secrets.md)) |
| Cold wallet keys | Hold most of the funds | Not on the platform at all: owners of a 2-of-3 Safe ([0005](adr/0005-key-custody.md)) |
| The ledger | The only record of which share of the pooled funds belongs to whom | PostgreSQL ([0008](adr/0008-a-double-entry-ledger-enforced-by-the-database.md)) |
| Merchant secret API keys | Create payments and read a merchant's data | Stored as SHA-256 hashes; shown once |
| Webhook signing secrets | Let a merchant trust what the platform sends | Encrypted at rest ([0017](adr/0017-encrypting-application-secrets.md)) |
| Merchant payout addresses | Decide where a merchant's money goes | PostgreSQL, behind two-factor authentication ([0019](adr/0019-account-security-and-an-audit-log.md)) |

## Threats and answers

| # | Threat | Answer |
|---|---|---|
| T1 | The api is compromised and asks the signer to send funds to the attacker | The signer signs intents, not bytes; it builds each transaction itself, checks destinations it can verify, and bounds merchant payouts with limits computed from its own journal ([0006](adr/0006-the-signer-signs-intents-not-bytes.md)) |
| T2 | The signer's host is compromised | The hot wallet is exposed, by design kept small; the cold wallet is not, because its keys are not on the platform ([0002](adr/0002-custodial-model-with-tiered-wallets.md), [0005](adr/0005-key-custody.md)) |
| T3 | A deposit address's key leaks | Deposit addresses have no keys: they are CREATE2 forwarders that can only send to the hot wallet ([0014](adr/0014-deposit-addresses-are-create2-forwarders.md)) |
| T4 | A bug writes an unbalanced or rewritten ledger | Balance is checked by a deferred constraint at commit; the application's database role cannot update or delete entries ([0008](adr/0008-a-double-entry-ledger-enforced-by-the-database.md), migrator and runtime roles) |
| T5 | A payment is credited twice (redelivery, crash, concurrent processing) | Observations are applied in the same transaction that advances the cursor; ledger transactions are unique per payment and kind ([0003](adr/0003-chain-observations-through-a-postgresql-log.md), [0008](adr/0008-a-double-entry-ledger-enforced-by-the-database.md)) |
| T6 | A reorganisation removes a payment the merchant was already paid for | Funds become withdrawable only at finality; before that they are pending and can be reversed ([0013](adr/0013-pending-and-available-balances.md)) |
| T7 | An observation is skipped and a payment is never credited | Observations are ordered per chain with one writer per chain, enforced by an advisory lock ([0009](adr/0009-observations-are-ordered-per-chain.md), [0015](adr/0015-one-watcher-per-chain-enforced-by-an-advisory-lock.md)); daily reconciliation compares the chain with the ledger |
| T8 | A merchant's dashboard account is taken over and payouts are redirected | Two-factor authentication for sensitive actions, an e-mail on each, a waiting period on new payout addresses, and an audit log ([0019](adr/0019-account-security-and-an-audit-log.md)) |
| T9 | A webhook URL is used to reach the platform's internal network | Delivery refuses internal addresses, pins the resolved address and follows no redirects ([0016](adr/0016-webhook-delivery-cannot-reach-the-internal-network.md)) |
| T10 | Internal endpoints are reached from the internet | Only the api's `/v1` prefix is routed; the signer is not attached to the proxy's network; management endpoints run on separate ports ([0007](adr/0007-hosts-and-the-two-faces-of-the-api.md), [0012](adr/0012-build-and-deployment.md)) |
| T11 | A database dump leaks | Secret API keys are hashes; webhook secrets and the seed are encrypted with keys stored elsewhere ([0017](adr/0017-encrypting-application-secrets.md)) |
| T12 | The database is lost | Daily off-site backups, restored at least once; keys backed up separately ([0018](adr/0018-backups.md)) |
| T13 | A merchant's request is replayed or sent twice | Idempotency keys; the same key with a different request is rejected |

## Open

- **Price manipulation.** A quote relies on an exchange rate; which sources, and how a stablecoin that loses its
  peg is handled, is not decided yet.
- **Administrator compromise.** An administrator approves merchants and changes fees; administrator actions are
  audited, but approving large payouts by a second person is not yet designed.
- **Denial of service.** Rate limits exist for the API; nothing beyond that is planned while the project runs on
  testnets.
