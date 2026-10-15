package com.aksoyakin.cryptopayment.chainwatcher.reorg;

import java.math.BigInteger;
import java.util.Optional;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainBlock;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.EvmNode;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursor;

/**
 * Finds out whether the branch the watcher followed is still the chain's, and if it is not, where the two
 * agreed last.
 * <p>
 * The question is asked of the block the cursor sits on: if that number now has a different hash, everything
 * the watcher reported above the point where the hashes still match was reported about a branch that no longer
 * exists.
 */
public final class ReorgDetector {

    private final EvmNode node;
    private final BlockHistory history;

    public ReorgDetector(EvmNode node, BlockHistory history) {
        this.node = node;
        this.history = history;
    }

    /**
     * @return empty when the chain still contains the cursor's block
     * @throws DeepReorgException when the recorded history does not reach back far enough to find the fork, in
     *                            which case nothing can be retracted honestly and a person has to look
     */
    public Optional<Fork> detect(ChainCursor cursor) {
        Optional<ChainBlock> atCursor = node.blockAt(cursor.blockNumber());
        if (atCursor.isPresent() && atCursor.get().hash().equalsIgnoreCase(cursor.blockHash())) {
            return Optional.empty();
        }

        BigInteger oldestKnown = history.oldestKnownBlock(cursor.chain()).orElse(cursor.blockNumber());
        for (BigInteger number = cursor.blockNumber().subtract(BigInteger.ONE);
                number.compareTo(oldestKnown) >= 0;
                number = number.subtract(BigInteger.ONE)) {
            Optional<String> ours = history.hashAt(cursor.chain(), number);
            if (ours.isEmpty()) {
                continue;
            }
            Optional<ChainBlock> theirs = node.blockAt(number);
            if (theirs.isPresent() && theirs.get().hash().equalsIgnoreCase(ours.get())) {
                return Optional.of(new Fork(number, ours.get()));
            }
        }

        throw new DeepReorgException(cursor.chain(), cursor.blockNumber(), oldestKnown);
    }

    /** The last block the watcher and the chain agree on. Everything above it is withdrawn. */
    public record Fork(BigInteger blockNumber, String blockHash) {
    }
}
