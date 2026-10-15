package com.aksoyakin.cryptopayment.chainwatcher.publish;

import java.math.BigInteger;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to the log the api reads. Called inside the transaction that also moves the cursor, which is where
 * exactly-once comes from (ADR 0003): there is no window between "written" and "acknowledged".
 */
public final class ObservationLog {

    private final JdbcClient db;

    public ObservationLog(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param firstSeq the sequence number of the first observation; they take consecutive numbers in the order
     *                 given, which for a block range is block then log index
     * @return the sequence number the next observation will take
     */
    public long append(String chain, long firstSeq, List<Observation> observations) {
        long seq = firstSeq;
        for (Observation observation : observations) {
            db.sql("""
                            INSERT INTO chain.observations (chain, seq, kind, tx_hash, log_index, block_number,
                                                            block_hash, to_address, asset_id, amount, finality)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(chain,
                            seq,
                            observation.kind().wireName(),
                            observation.transactionHash(),
                            observation.logIndex(),
                            observation.blockNumber(),
                            observation.blockHash(),
                            observation.toAddress(),
                            observation.assetId(),
                            observation.amount(),
                            observation.finality().wireName())
                    .update();
            seq++;
        }
        return seq;
    }

    /**
     * Every transfer reported above a fork that has not already been retracted, in the order it was reported.
     * A transfer seen again on the new branch is reported again as its own observation, so retracting and
     * re-observing can alternate without either being lost.
     */
    public List<Observation> transfersToRetractAbove(String chain, BigInteger blockNumber) {
        return db.sql("""
                        SELECT observed.tx_hash, observed.log_index, observed.block_number, observed.block_hash,
                               observed.to_address, observed.asset_id, observed.amount
                          FROM chain.observations observed
                         WHERE observed.chain = ?
                           AND observed.kind = 'transfer_observed'
                           AND observed.block_number > ?
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM chain.observations retracted
                                WHERE retracted.chain = observed.chain
                                  AND retracted.kind = 'transfer_retracted'
                                  AND retracted.tx_hash = observed.tx_hash
                                  AND retracted.log_index = observed.log_index
                                  AND retracted.seq > observed.seq)
                         ORDER BY observed.seq
                        """)
                .params(chain, blockNumber)
                .query((rs, row) -> new Observation(
                        Observation.Kind.TRANSFER_RETRACTED,
                        rs.getString("tx_hash"),
                        rs.getBigDecimal("log_index").toBigInteger(),
                        rs.getBigDecimal("block_number").toBigInteger(),
                        rs.getString("block_hash"),
                        rs.getString("to_address"),
                        rs.getObject("asset_id", java.util.UUID.class),
                        rs.getBigDecimal("amount").toBigInteger(),
                        Observation.Finality.SEEN))
                .list();
    }
}
