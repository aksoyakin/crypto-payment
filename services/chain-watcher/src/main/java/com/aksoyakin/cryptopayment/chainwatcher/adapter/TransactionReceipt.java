package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;

/** The outcome of a transaction, once it is in a block. */
public record TransactionReceipt(
        String transactionHash,
        BigInteger blockNumber,
        String blockHash,
        BigInteger gasUsed,
        boolean successful) {
}
