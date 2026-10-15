-- What the watcher saw, and how far it has read (ADR 0003, ADR 0009).
--
-- The api reads this log through a view and keeps a cursor of its own; applying an observation and advancing
-- that cursor happen in one transaction, which is where exactly-once comes from. The order is per chain and
-- assigned by the watcher, because a database sequence hands out values when they are requested rather than
-- when they commit: with two writers, a reader that saw seq 11 would never see a later-committed seq 10. One
-- active watcher per chain is therefore a correctness requirement, enforced by an advisory lock (ADR 0015).

CREATE TABLE chain.cursors (
    chain        text        PRIMARY KEY,
    block_number bigint      NOT NULL,
    block_hash   text        NOT NULL,
    next_seq     bigint      NOT NULL DEFAULT 1,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN chain.cursors.next_seq IS
    'The sequence number the next observation of this chain takes. Not a database sequence, on purpose.';

-- The last blocks seen, by number: a block whose hash no longer matches is how a reorganisation is noticed.
CREATE TABLE chain.blocks (
    chain       text        NOT NULL,
    number      bigint      NOT NULL,
    hash        text        NOT NULL,
    parent_hash text        NOT NULL,
    block_time  timestamptz NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chain, number)
);

CREATE TABLE chain.observations (
    chain        text          NOT NULL,
    seq          bigint        NOT NULL,
    kind         text          NOT NULL CHECK (kind IN ('transfer_observed', 'finality_changed', 'transfer_retracted')),
    tx_hash      text          NOT NULL,
    log_index    bigint        NOT NULL,
    block_number bigint        NOT NULL,
    block_hash   text          NOT NULL,
    to_address   text          NOT NULL,
    -- The asset as the api knows it. Not a foreign key: the two services share a server, not tables, and the
    -- only contract between them is a view.
    asset_id     uuid          NOT NULL,
    amount       numeric(78,0) NOT NULL CHECK (amount > 0),
    finality     text          NOT NULL CHECK (finality IN ('seen', 'safe', 'finalized')),
    observed_at  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (chain, seq)
);

COMMENT ON TABLE chain.observations IS
    'Append-only. A transfer removed by a reorganisation is a new row (transfer_retracted), never an edit; the '
    'same transfer seen again on another branch is another transfer_observed.';

CREATE INDEX observations_by_transfer ON chain.observations (chain, tx_hash, log_index);

-- The contract with the api: a view, not the table.
CREATE VIEW chain.api_observations AS
SELECT chain,
       seq,
       kind,
       tx_hash,
       log_index,
       block_number,
       block_hash,
       to_address,
       asset_id,
       amount,
       finality,
       observed_at
  FROM chain.observations;

GRANT SELECT ON chain.api_observations TO cryptopayment_api;

-- Append-only, enforced by privilege: the watcher's own role cannot rewrite what it has reported. Cursors and
-- blocks stay writable, since both are a position rather than a record of an event.
REVOKE UPDATE, DELETE ON chain.observations FROM cryptopayment_chain_watcher;
