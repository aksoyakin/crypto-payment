package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.time.Instant;

/**
 * A block, reduced to what reorganisation detection needs: where it sits, what it is called and what it
 * follows (ADR 0009).
 */
public record ChainBlock(BigInteger number, String hash, String parentHash, Instant timestamp) {
}
