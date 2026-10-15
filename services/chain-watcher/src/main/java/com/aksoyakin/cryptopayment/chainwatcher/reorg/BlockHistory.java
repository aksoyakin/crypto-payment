package com.aksoyakin.cryptopayment.chainwatcher.reorg;

import java.math.BigInteger;
import java.util.Optional;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainBlock;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The hashes of the blocks the watcher has followed recently. A reorganisation is found by comparing them with
 * what the chain says now, so the history only has to reach back as far as a reorganisation can plausibly go.
 */
public final class BlockHistory {

    private final JdbcClient db;

    public BlockHistory(JdbcClient db) {
        this.db = db;
    }

    public void record(String chain, ChainBlock block) {
        db.sql("""
                        INSERT INTO chain.blocks (chain, number, hash, parent_hash, block_time)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (chain, number)
                            DO UPDATE SET hash = excluded.hash,
                                          parent_hash = excluded.parent_hash,
                                          block_time = excluded.block_time,
                                          observed_at = now()
                        """)
                .params(chain, block.number(), block.hash(), block.parentHash(),
                        java.sql.Timestamp.from(block.timestamp()))
                .update();
    }

    public Optional<String> hashAt(String chain, BigInteger number) {
        return db.sql("SELECT hash FROM chain.blocks WHERE chain = ? AND number = ?")
                .params(chain, number)
                .query(String.class)
                .optional();
    }

    public Optional<BigInteger> oldestKnownBlock(String chain) {
        return db.sql("SELECT min(number) FROM chain.blocks WHERE chain = ?")
                .param(chain)
                .query(java.math.BigDecimal.class)
                .optional()
                .map(java.math.BigDecimal::toBigInteger);
    }

    /** After a reorganisation, the blocks above the fork never existed as far as this platform is concerned. */
    public void forgetAbove(String chain, BigInteger blockNumber) {
        db.sql("DELETE FROM chain.blocks WHERE chain = ? AND number > ?")
                .params(chain, blockNumber)
                .update();
    }

    /** Keeps the history bounded; blocks this old can no longer be reorganised away. */
    public void prune(String chain, BigInteger below) {
        db.sql("DELETE FROM chain.blocks WHERE chain = ? AND number < ?")
                .params(chain, below)
                .update();
    }
}
