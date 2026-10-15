package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.util.List;

/**
 * An event emitted by a contract. A token transfer is identified by {@code (chain, transactionHash, logIndex)},
 * which is what uniqueness is enforced on everywhere downstream.
 */
public record ChainLog(
        String address,
        List<String> topics,
        String data,
        BigInteger blockNumber,
        String blockHash,
        String transactionHash,
        BigInteger logIndex,
        boolean removed) {
}
