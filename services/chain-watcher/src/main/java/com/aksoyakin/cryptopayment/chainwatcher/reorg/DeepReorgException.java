package com.aksoyakin.cryptopayment.chainwatcher.reorg;

import java.math.BigInteger;

/**
 * A reorganisation deeper than the recorded block history. The watcher cannot say which of its reports are
 * still true, so it stops moving the cursor for that chain instead of guessing: on a chain with finality this
 * should not happen, and if it does, it is an alarm rather than an event.
 */
public class DeepReorgException extends RuntimeException {

    public DeepReorgException(String chain, BigInteger cursorBlock, BigInteger oldestKnownBlock) {
        super(("%s reorganised below the recorded history: the cursor sat on block %s and the oldest block known "
                + "is %s, so the fork point cannot be found").formatted(chain, cursorBlock, oldestKnownBlock));
    }
}
