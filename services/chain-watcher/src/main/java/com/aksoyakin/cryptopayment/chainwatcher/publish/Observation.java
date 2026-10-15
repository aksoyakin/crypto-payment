package com.aksoyakin.cryptopayment.chainwatcher.publish;

import java.math.BigInteger;
import java.util.UUID;

/**
 * One thing the watcher saw, in the form the api reads it. Observations are appended and never changed: a
 * transfer a reorganisation removed is a retraction, not an edit (ADR 0003).
 */
public record Observation(
        Kind kind,
        String transactionHash,
        BigInteger logIndex,
        BigInteger blockNumber,
        String blockHash,
        String toAddress,
        UUID assetId,
        BigInteger amount,
        Finality finality) {

    public enum Kind {
        TRANSFER_OBSERVED,
        FINALITY_CHANGED,
        TRANSFER_RETRACTED;

        String wireName() {
            return name().toLowerCase();
        }
    }

    public enum Finality {
        SEEN,
        SAFE,
        FINALIZED;

        String wireName() {
            return name().toLowerCase();
        }
    }
}
