package com.aksoyakin.cryptopayment.chainwatcher.adapter;

/** A node that answered, and refused. Distinct from a transport failure, which arrives as an IO exception. */
public class JsonRpcException extends RuntimeException {

    private final int code;

    public JsonRpcException(String method, int code, String message) {
        super("%s failed: %s (code %d)".formatted(method, message, code));
        this.code = code;
    }

    public int code() {
        return code;
    }
}
