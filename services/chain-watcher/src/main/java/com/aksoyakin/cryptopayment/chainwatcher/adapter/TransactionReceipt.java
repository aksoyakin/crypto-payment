package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.util.Optional;

/**
 * The outcome of a transaction, once it is in a block.
 *
 * @param contractAddress set only when the transaction deployed a contract
 */
public record TransactionReceipt(
        String transactionHash,
        BigInteger blockNumber,
        String blockHash,
        BigInteger gasUsed,
        String contractAddress,
        boolean successful) {

    public Optional<String> deployedContract() {
        return Optional.ofNullable(contractAddress);
    }
}
