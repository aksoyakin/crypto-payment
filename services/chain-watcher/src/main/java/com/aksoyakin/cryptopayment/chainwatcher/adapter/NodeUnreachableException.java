package com.aksoyakin.cryptopayment.chainwatcher.adapter;

/**
 * No endpoint for the chain could be reached. Unlike a refusal from a node, this is worth trying again later,
 * so the watcher leaves its cursor where it is and waits for the next cycle.
 */
public class NodeUnreachableException extends RuntimeException {

    public NodeUnreachableException(String method, int endpoints) {
        super("%s reached none of the %d configured node endpoints".formatted(method, endpoints));
    }
}
