package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.BlockTag;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainBlock;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainLog;
import com.aksoyakin.cryptopayment.chainwatcher.adapter.EvmNode;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursor;
import com.aksoyakin.cryptopayment.chainwatcher.cursor.ChainCursors;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ChainWriteLease;
import com.aksoyakin.cryptopayment.chainwatcher.publish.Observation;
import com.aksoyakin.cryptopayment.chainwatcher.publish.ObservationLog;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.BlockHistory;
import com.aksoyakin.cryptopayment.chainwatcher.reorg.ReorgDetector;
import com.aksoyakin.cryptopayment.chainwatcher.scan.WatchedTargets.WatchedAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Follows one chain: takes the right to write it, reads the next window of blocks, reports the transfers that
 * reached a watched address, and withdraws what a reorganisation removed.
 * <p>
 * One cycle writes at most one transaction, and that transaction contains everything the cycle decided: the
 * observations, the block hashes they were seen at, and the cursor. Either the api sees all of it or none of it
 * (ADR 0003).
 */
public final class ChainFollower {

    private static final Logger log = LoggerFactory.getLogger(ChainFollower.class);

    private final String chain;
    private final EvmNode node;
    private final ChainWriteLease lease;
    private final ChainCursors cursors;
    private final BlockHistory history;
    private final ObservationLog observations;
    private final ReorgDetector reorgs;
    private final WatchedTargets targets;
    private final TransactionTemplate transactions;
    private final int blockWindow;
    private final int blockHistoryDepth;

    public ChainFollower(
            String chain,
            EvmNode node,
            ChainWriteLease lease,
            ChainCursors cursors,
            BlockHistory history,
            ObservationLog observations,
            ReorgDetector reorgs,
            WatchedTargets targets,
            TransactionTemplate transactions,
            int blockWindow,
            int blockHistoryDepth) {
        this.chain = chain;
        this.node = node;
        this.lease = lease;
        this.cursors = cursors;
        this.history = history;
        this.observations = observations;
        this.reorgs = reorgs;
        this.targets = targets;
        this.transactions = transactions;
        this.blockWindow = blockWindow;
        this.blockHistoryDepth = blockHistoryDepth;
    }

    public String chain() {
        return chain;
    }

    /** One pass. Returns what it did, which is what the tests and the metrics ask about. */
    public Progress cycle() {
        if (!lease.acquired()) {
            return Progress.notWriting();
        }

        ChainCursor cursor = cursors.of(chain).orElseGet(this::startAtHead);

        Optional<ReorgDetector.Fork> fork = reorgs.detect(cursor);
        if (fork.isPresent()) {
            return withdrawAbove(cursor, fork.get());
        }

        BigInteger head = node.blockNumber();
        BigInteger from = cursor.blockNumber().add(BigInteger.ONE);
        if (from.compareTo(head) > 0) {
            return Progress.upToDate(cursor.blockNumber());
        }
        BigInteger to = head.min(from.add(BigInteger.valueOf(blockWindow - 1L)));

        return report(cursor, from, to, head);
    }

    private Progress report(ChainCursor cursor, BigInteger from, BigInteger to, BigInteger head) {
        List<ChainBlock> window = headersWorthKeeping(cursor, from, to, head);
        ChainBlock last = window.isEmpty()
                ? node.blockAt(to).orElseThrow(() -> missingBlock(to))
                : window.get(window.size() - 1);

        List<Observation> found = transfersIn(from, to);

        transactions.executeWithoutResult(status -> {
            long nextSeq = observations.append(chain, cursor.nextSeq(), found);
            window.forEach(block -> history.record(chain, block));
            history.record(chain, last);
            cursors.moveTo(chain, to, last.hash(), nextSeq);
            history.prune(chain, head.subtract(BigInteger.valueOf(blockHistoryDepth)).max(BigInteger.ZERO));
        });

        if (!found.isEmpty()) {
            log.info("{}: reported {} transfer(s) in blocks {}..{}", chain, found.size(), from, to);
        }
        return Progress.scanned(from, to, found.size());
    }

    /**
     * Withdraws every transfer reported above the fork and puts the cursor back there. The blocks above the
     * fork are forgotten, so the same numbers can be followed again on the branch that won.
     */
    private Progress withdrawAbove(ChainCursor cursor, ReorgDetector.Fork fork) {
        log.warn("{} reorganised: the chain and this watcher last agree at block {}; withdrawing what was "
                + "reported above it", chain, fork.blockNumber());

        List<Observation> retractions = observations.transfersToRetractAbove(chain, fork.blockNumber());

        transactions.executeWithoutResult(status -> {
            long nextSeq = observations.append(chain, cursor.nextSeq(), retractions);
            history.forgetAbove(chain, fork.blockNumber());
            cursors.moveTo(chain, fork.blockNumber(), fork.blockHash(), nextSeq);
        });

        return Progress.withdrawn(fork.blockNumber(), retractions.size());
    }

    /** The transfers in a block range that reached an address the api is watching. */
    private List<Observation> transfersIn(BigInteger from, BigInteger to) {
        List<WatchedAsset> tokens = targets.tokensOn(chain);
        Set<String> watched = targets.addressesOn(chain);
        if (tokens.isEmpty() || watched.isEmpty()) {
            // Asking for logs without naming a contract would read every log on the chain, and with nothing
            // to match them against there would be nothing to report anyway.
            return List.of();
        }

        Map<String, WatchedAsset> byContract = new HashMap<>();
        tokens.forEach(token -> byContract.put(token.contractAddress(), token));

        List<ChainLog> logs = node.logs(from, to, List.copyOf(byContract.keySet()), List.of(Erc20Transfers.TOPIC));
        List<Observation> found = new ArrayList<>();
        for (ChainLog entry : logs) {
            WatchedAsset asset = byContract.get(entry.address().toLowerCase());
            if (asset == null) {
                continue;
            }
            Erc20Transfers.decode(entry)
                    .filter(transfer -> watched.contains(transfer.to()))
                    .ifPresent(transfer -> found.add(new Observation(
                            Observation.Kind.TRANSFER_OBSERVED,
                            entry.transactionHash(),
                            entry.logIndex(),
                            entry.blockNumber(),
                            entry.blockHash(),
                            transfer.to(),
                            asset.assetId(),
                            transfer.value(),
                            Observation.Finality.SEEN)));
        }
        // The order the api applies them in: by block, then by position inside the block.
        found.sort((left, right) -> {
            int byBlock = left.blockNumber().compareTo(right.blockNumber());
            return byBlock != 0 ? byBlock : left.logIndex().compareTo(right.logIndex());
        });
        return found;
    }

    /**
     * Headers are fetched only for blocks a reorganisation could still touch. Catching up over a long gap does
     * not fetch thousands of headers: blocks that far behind the head cannot be reorganised away, and the
     * window's last block is kept in any case, because the cursor records its hash.
     */
    private List<ChainBlock> headersWorthKeeping(
            ChainCursor cursor, BigInteger from, BigInteger to, BigInteger head) {
        BigInteger horizon = head.subtract(BigInteger.valueOf(blockHistoryDepth - 1L)).max(from);
        List<ChainBlock> headers = new ArrayList<>();
        for (BigInteger number = horizon; number.compareTo(to) <= 0; number = number.add(BigInteger.ONE)) {
            BigInteger at = number;
            headers.add(node.blockAt(at).orElseThrow(() -> missingBlock(at)));
        }
        if (!headers.isEmpty() && headers.get(0).number().equals(from) && from.signum() > 0) {
            // Cheap insurance against a node answering from two different branches within one cycle.
            if (!headers.get(0).parentHash().equalsIgnoreCase(cursor.blockHash())) {
                throw new IllegalStateException(("%s: block %s does not follow the cursor's block; the node "
                        + "answered from more than one branch").formatted(chain, from));
            }
        }
        return headers;
    }

    private ChainCursor startAtHead() {
        ChainBlock head = node.blockAt(BlockTag.LATEST).orElseThrow(() -> missingBlock(null));
        log.info("{}: starting to follow at block {}", chain, head.number());
        return transactions.execute(status -> {
            // The block the cursor sits on is also the oldest hash a reorganisation can be measured against,
            // so it belongs in the history from the first cycle.
            history.record(chain, head);
            return cursors.start(chain, head.number(), head.hash());
        });
    }

    private IllegalStateException missingBlock(BigInteger number) {
        return new IllegalStateException("%s: block %s is not there, though the chain reported it"
                .formatted(chain, number == null ? "latest" : number));
    }

    /** What a cycle did. */
    public record Progress(State state, BigInteger fromBlock, BigInteger toBlock, int observations) {

        public enum State {
            /** Another instance holds the chain's write lock, which is the normal state for a second replica. */
            NOT_WRITING,
            /** The cursor is at the head of the chain. */
            UP_TO_DATE,
            /** A block range was read and whatever it contained was reported. */
            SCANNED,
            /** A reorganisation was found and what it removed was withdrawn. */
            WITHDRAWN
        }

        static Progress notWriting() {
            return new Progress(State.NOT_WRITING, null, null, 0);
        }

        static Progress upToDate(BigInteger at) {
            return new Progress(State.UP_TO_DATE, at, at, 0);
        }

        static Progress scanned(BigInteger from, BigInteger to, int observations) {
            return new Progress(State.SCANNED, from, to, observations);
        }

        static Progress withdrawn(BigInteger fork, int retractions) {
            return new Progress(State.WITHDRAWN, fork, fork, retractions);
        }
    }
}
