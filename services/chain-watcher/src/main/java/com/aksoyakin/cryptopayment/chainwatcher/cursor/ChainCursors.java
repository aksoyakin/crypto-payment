package com.aksoyakin.cryptopayment.chainwatcher.cursor;

import java.math.BigInteger;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and moves the per-chain cursor. Moving it is part of the transaction that writes the observations. */
public final class ChainCursors {

    private final JdbcClient db;

    public ChainCursors(JdbcClient db) {
        this.db = db;
    }

    public Optional<ChainCursor> of(String chain) {
        return db.sql("""
                        SELECT chain, block_number, block_hash, next_seq
                          FROM chain.cursors
                         WHERE chain = ?
                        """)
                .param(chain)
                .query((rs, row) -> new ChainCursor(
                        rs.getString("chain"),
                        rs.getBigDecimal("block_number").toBigInteger(),
                        rs.getString("block_hash"),
                        rs.getLong("next_seq")))
                .optional();
    }

    /**
     * Starts following a chain at a block that has already happened. Nothing before it is reported: a payment
     * cannot arrive at an address the api has not handed out yet, and the api hands them out while the watcher
     * is running.
     */
    public ChainCursor start(String chain, BigInteger blockNumber, String blockHash) {
        db.sql("""
                        INSERT INTO chain.cursors (chain, block_number, block_hash)
                        VALUES (?, ?, ?)
                        ON CONFLICT (chain) DO NOTHING
                        """)
                .params(chain, blockNumber, blockHash)
                .update();
        return of(chain).orElseThrow(() -> new IllegalStateException("cursor for " + chain + " disappeared"));
    }

    /** @param nextSeq the sequence number the following observation will take */
    public void moveTo(String chain, BigInteger blockNumber, String blockHash, long nextSeq) {
        int updated = db.sql("""
                        UPDATE chain.cursors
                           SET block_number = ?, block_hash = ?, next_seq = ?, updated_at = now()
                         WHERE chain = ?
                        """)
                .params(blockNumber, blockHash, nextSeq, chain)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("no cursor to move for chain " + chain);
        }
    }
}
