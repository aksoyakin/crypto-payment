package com.aksoyakin.cryptopayment.chainwatcher.adapter;

/**
 * The block a call is answered from. Confirmation is decided by {@code safe} and {@code finalized} rather than
 * by counting blocks (ADR 0004).
 */
public enum BlockTag {

    LATEST("latest"),
    SAFE("safe"),
    FINALIZED("finalized"),
    EARLIEST("earliest"),
    PENDING("pending");

    private final String wireName;

    BlockTag(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }
}
