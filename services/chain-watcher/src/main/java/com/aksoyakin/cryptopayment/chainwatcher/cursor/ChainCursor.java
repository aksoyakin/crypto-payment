package com.aksoyakin.cryptopayment.chainwatcher.cursor;

import java.math.BigInteger;

/**
 * How far a chain has been followed: the last block whose logs were reported, the hash it had when it was read,
 * and the sequence number the next observation of this chain will take.
 * <p>
 * The hash is what makes a reorganisation visible: the same block number answering with a different hash means
 * the branch the watcher followed is gone.
 */
public record ChainCursor(String chain, BigInteger blockNumber, String blockHash, long nextSeq) {
}
