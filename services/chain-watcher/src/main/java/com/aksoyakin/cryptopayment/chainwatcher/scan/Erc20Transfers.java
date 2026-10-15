package com.aksoyakin.cryptopayment.chainwatcher.scan;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import com.aksoyakin.cryptopayment.chainwatcher.adapter.ChainLog;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;

/**
 * The one event the watcher reads: {@code Transfer(address indexed from, address indexed to, uint256 value)}.
 * <p>
 * The topic is derived from the event's signature rather than written down as a hash, so a typo cannot make the
 * watcher silently see nothing.
 */
public final class Erc20Transfers {

    private static final Event TRANSFER = new Event("Transfer", List.of(
            TypeReference.create(Address.class, true),
            TypeReference.create(Address.class, true),
            TypeReference.create(Uint256.class)));

    /** The first topic of every ERC-20 transfer log. */
    public static final String TOPIC = EventEncoder.encode(TRANSFER).toLowerCase();

    private Erc20Transfers() {
    }

    /**
     * @return the transfer this log carries, or empty when the log is not a complete ERC-20 transfer: a token
     *         that emits a different event, or one whose transfer leaves out an indexed argument.
     */
    public static Optional<Erc20Transfer> decode(ChainLog log) {
        List<String> topics = log.topics();
        if (topics.size() != 3 || !TOPIC.equalsIgnoreCase(topics.get(0))) {
            return Optional.empty();
        }
        return Optional.of(new Erc20Transfer(
                address(topics.get(1)),
                address(topics.get(2)),
                new BigInteger(strip(log.data()).isEmpty() ? "0" : strip(log.data()), 16)));
    }

    /** An address topic is a 32-byte word; the address is its last 20 bytes. */
    private static String address(String topic) {
        String word = strip(topic);
        return "0x" + word.substring(Math.max(0, word.length() - 40)).toLowerCase();
    }

    private static String strip(String hex) {
        if (hex == null) {
            return "";
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    /** Amounts stay in the token's smallest unit; the asset they belong to is decided by the contract. */
    public record Erc20Transfer(String from, String to, BigInteger value) {
    }
}
