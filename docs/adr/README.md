# Architecture Decision Records

| # | Decision | Status |
|---|---|---|
| [0000](0000-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0001](0001-three-services-split-by-trust-and-failure-profile.md) | Three services, split by trust and failure profile; a modular monolith inside | Accepted, amended by 0020 |
| [0002](0002-custodial-model-with-tiered-wallets.md) | A custodial model with tiered wallets and a ledger as the source of truth | Accepted, amended by 0014 |
| [0003](0003-chain-observations-through-a-postgresql-log.md) | Chain observations reach the api through a PostgreSQL log, not a message broker | Accepted, amended by 0009 |
| [0004](0004-first-network-and-assets.md) | The first network and assets, and how transfers are detected | Accepted |
| [0005](0005-key-custody.md) | Key custody | Accepted, amended by 0014 |
| [0006](0006-the-signer-signs-intents-not-bytes.md) | The signer signs intents, not bytes, and keeps its own journal | Accepted, amended by 0014 |
| [0007](0007-hosts-and-the-two-faces-of-the-api.md) | Three hosts, and the two faces of the api | Accepted |
| [0008](0008-a-double-entry-ledger-enforced-by-the-database.md) | A double-entry ledger, enforced by the database | Accepted, amended by 0013 |
| [0009](0009-observations-are-ordered-per-chain.md) | Observations are ordered per chain | Accepted, amended by 0015 |
| [0010](0010-test-mode-without-a-chain.md) | Test mode without a chain | Accepted |
| [0011](0011-no-redis.md) | No Redis | Accepted |
| [0012](0012-build-and-deployment.md) | Build and deployment | Accepted |
| [0013](0013-pending-and-available-balances.md) | Pending and available balances | Accepted |
| [0014](0014-deposit-addresses-are-create2-forwarders.md) | Deposit addresses are CREATE2 forwarders | Accepted |
| [0015](0015-one-watcher-per-chain-enforced-by-an-advisory-lock.md) | One watcher per chain, enforced by an advisory lock | Accepted |
| [0016](0016-webhook-delivery-cannot-reach-the-internal-network.md) | Webhook delivery cannot reach the internal network | Accepted |
| [0017](0017-encrypting-application-secrets.md) | Encrypting application secrets | Accepted |
| [0018](0018-backups.md) | Backups | Accepted |
| [0019](0019-account-security-and-an-audit-log.md) | Account security and an audit log | Accepted |
| [0020](0020-modules-are-opened-with-their-first-code.md) | Modules are opened with their first code | Accepted |
