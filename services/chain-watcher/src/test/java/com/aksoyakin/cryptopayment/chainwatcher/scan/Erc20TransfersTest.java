package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.math.BigInteger;
import java.util.List;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainLog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a transfer out of a log, including the shapes that are not one. */
class Erc20TransfersTest {

    private static final String FROM = "0x000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TO = "0x00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** The signature is hashed from the event, not copied from somewhere, so this pins what it comes to. */
    @Test
    void knowsTheTransferTopic() {
        assertThat(Erc20Transfers.TOPIC)
                .isEqualTo("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    }

    @Test
    void readsTheRecipientAndTheAmount() {
        ChainLog log = logWith(List.of(Erc20Transfers.TOPIC, FROM, TO),
                "0x0000000000000000000000000000000000000000000000000000000005f5e100");

        Erc20Transfers.Erc20Transfer transfer = Erc20Transfers.decode(log).orElseThrow();

        assertThat(transfer.from()).isEqualTo("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        assertThat(transfer.to()).isEqualTo("0x70997970c51812dc3a010c7d01b50e0d17dc79c8");
        assertThat(transfer.value()).isEqualTo(BigInteger.valueOf(100_000_000L));
    }

    /** A uint256 larger than a long is ordinary for a token with eighteen decimals. */
    @Test
    void readsAnAmountLargerThanALong() {
        ChainLog log = logWith(List.of(Erc20Transfers.TOPIC, FROM, TO),
                "0x" + "f".repeat(64));

        assertThat(Erc20Transfers.decode(log).orElseThrow().value())
                .isEqualTo(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));
    }

    @Test
    void ignoresAnotherEvent() {
        ChainLog log = logWith(List.of("0x" + "1".repeat(64), FROM, TO), "0x01");

        assertThat(Erc20Transfers.decode(log)).isEmpty();
    }

    /** A token that leaves an argument unindexed does not produce the three topics a transfer has. */
    @Test
    void ignoresATransferShapedDifferently() {
        ChainLog log = logWith(List.of(Erc20Transfers.TOPIC, FROM), "0x01");

        assertThat(Erc20Transfers.decode(log)).isEmpty();
    }

    private static ChainLog logWith(List<String> topics, String data) {
        return new ChainLog(
                "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                topics,
                data,
                BigInteger.ONE,
                "0xblock",
                "0xtx",
                BigInteger.ZERO,
                false);
    }
}
