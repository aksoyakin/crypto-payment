package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.util.List;

import com.aksoyakin.cryptopayment.chainwatcher.reorg.DeepReorgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every chain's follower, one after another, on one thread. A chain's work is a handful of calls, and
 * following them in turn keeps the order of writes within a chain obvious.
 * <p>
 * A cycle that throws is logged and the next chain is still tried: a node that is down or a chain that
 * reorganised deeper than the recorded history must not stop the other chains. Nothing is lost by stopping,
 * because nothing moves until the cursor does.
 */
@Component
@ConditionalOnProperty(name = "cryptopayment.watcher.scheduled", matchIfMissing = true)
public class WatcherSchedule {

    private static final Logger log = LoggerFactory.getLogger(WatcherSchedule.class);

    private final List<ChainFollower> followers;

    public WatcherSchedule(List<ChainFollower> followers) {
        this.followers = followers;
    }

    @Scheduled(fixedDelayString = "${cryptopayment.watcher.poll-interval:2s}")
    public void followEveryChain() {
        for (ChainFollower follower : followers) {
            try {
                follower.cycle();
            } catch (DeepReorgException alarm) {
                log.error("{} needs attention: {}", follower.chain(), alarm.getMessage());
            } catch (RuntimeException failure) {
                log.warn("{} made no progress this cycle: {}", follower.chain(), failure.getMessage());
            }
        }
    }
}
