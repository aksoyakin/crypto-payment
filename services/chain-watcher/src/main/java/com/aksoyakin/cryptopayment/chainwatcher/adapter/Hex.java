package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Ethereum's JSON-RPC carries every number as a hexadecimal string, so this is the only place where chain data
 * is still text.
 */
final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    static BigInteger toBigInteger(String quantity) {
        String digits = strip(quantity);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("not a quantity: " + quantity);
        }
        return new BigInteger(digits, 16);
    }

    static Instant toInstant(String secondsSinceEpoch) {
        return Instant.ofEpochSecond(toBigInteger(secondsSinceEpoch).longValueExact());
    }

    static String of(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("a quantity is never negative: " + value);
        }
        return "0x" + value.toString(16);
    }

    static String of(byte[] bytes) {
        StringBuilder hex = new StringBuilder(2 + bytes.length * 2);
        hex.append("0x");
        for (byte b : bytes) {
            hex.append(DIGITS[(b >> 4) & 0xf]).append(DIGITS[b & 0xf]);
        }
        return hex.toString();
    }

    private static String strip(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing quantity");
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }
}
