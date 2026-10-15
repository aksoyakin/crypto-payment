package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;

import static org.awaitility.Awaitility.await;

/**
 * A chain the tests own: Anvil in a container, mining on demand, plus the few things a test needs to do to it —
 * fund an account, deploy the test token, send a transfer, and reorganise.
 * <p>
 * It lives in the adapter's package because the node's administrative methods ({@code anvil_*}) are sent over
 * the transport's own envelope, which is not part of what this project exposes to the rest of the service.
 * <p>
 * Blocks are mined only when a test asks (no {@code --block-time}), so a test never races the chain. Keys are
 * generated here, so none is written down anywhere.
 */
public final class LocalChain implements AutoCloseable {

    public static final BigInteger CHAIN_ID = BigInteger.valueOf(31337);

    private static final String IMAGE = "ghcr.io/foundry-rs/foundry:v1.8.4";
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger CALL_GAS = BigInteger.valueOf(200_000);
    private static final BigInteger DEPLOY_GAS = BigInteger.valueOf(1_500_000);
    private static final ParameterizedTypeReference<JsonRpc.Result<Object>> ANYTHING =
            new ParameterizedTypeReference<>() {
            };

    private final GenericContainer<?> anvil;
    private final JsonRpc rpc;
    private final EvmNode node;

    @SuppressWarnings("resource") // closed in close()
    public LocalChain() {
        anvil = new GenericContainer<>(IMAGE)
                // The image's entrypoint is "/bin/sh -c", so the whole command has to arrive as one argument.
                // Passed as a plain string it would be split on spaces, and anvil would start with its
                // defaults: bound to localhost, where no mapped port can reach it.
                .withCommand(new String[] {"anvil --host 0.0.0.0 --port 8545 --chain-id %s".formatted(CHAIN_ID)})
                .withExposedPorts(8545)
                .waitingFor(Wait.forListeningPort());
        anvil.start();
        rpc = JsonRpc.over(url(), Duration.ofSeconds(2), Duration.ofSeconds(10));
        node = new EvmNode(rpc);
    }

    public String url() {
        return "http://%s:%d".formatted(anvil.getHost(), anvil.getMappedPort(8545));
    }

    public EvmNode node() {
        return node;
    }

    /** A new account with ether to spend. Anvil's own funded accounts come from a mnemonic everyone knows. */
    public Credentials fundedAccount() {
        Credentials account = newAccount();
        call("anvil_setBalance", List.of(account.getAddress(), "0x" + BigInteger.TEN.pow(20).toString(16)));
        return account;
    }

    public static Credentials newAccount() {
        try {
            return Credentials.create(Keys.createEcKeyPair());
        } catch (Exception impossible) {
            throw new IllegalStateException("generating a key pair failed", impossible);
        }
    }

    /** @return the token's address, with the whole supply held by the deployer */
    public String deployTestToken(Credentials deployer, BigInteger supply) {
        String creationCode = testTokenCreationCode()
                + FunctionEncoder.encodeConstructor(List.of(new Uint256(supply)));
        TransactionReceipt receipt = send(deployer, null, creationCode, DEPLOY_GAS);
        return receipt.deployedContract().orElseThrow(() -> new IllegalStateException("the token did not deploy"));
    }

    public TransactionReceipt transferToken(Credentials from, String token, String to, BigInteger amount) {
        String data = FunctionEncoder.encode(new Function(
                "transfer",
                List.of(new Address(to), new Uint256(amount)),
                List.of()));
        return send(from, token, data, CALL_GAS);
    }

    /** Mines one block, which is when anything sent before it actually happens. */
    public void mine() {
        call("anvil_mine", List.of("0x1"));
    }

    /**
     * Rewinds the given number of blocks and mines replacements, which is how a reorganisation is produced
     * deliberately rather than waited for (ADR 0004).
     */
    public void reorganise(int depth) {
        call("anvil_reorg", List.of(depth, List.of()));
    }

    public <T> void call(String method, List<Object> params) {
        rpc.call(method, params, ANYTHING);
    }

    private TransactionReceipt send(Credentials from, String to, String data, BigInteger gasLimit) {
        RawTransaction transaction = RawTransaction.createTransaction(
                node.transactionCount(from.getAddress(), BlockTag.PENDING),
                GAS_PRICE,
                gasLimit,
                to,
                BigInteger.ZERO,
                data);
        String hash = node.sendRawTransaction(
                hex(TransactionEncoder.signMessage(transaction, CHAIN_ID.longValueExact(), from)));
        mine();

        TransactionReceipt receipt = await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> node.receiptOf(hash).orElse(null), Objects::nonNull);
        if (!receipt.successful()) {
            throw new IllegalStateException("the chain rejected the transaction " + hash);
        }
        return receipt;
    }

    /** The creation bytecode of {@code contracts/TestToken.sol}; see the README next to it. */
    private static String testTokenCreationCode() {
        try (var bytecode = LocalChain.class.getResourceAsStream("/contracts/TestToken.bin")) {
            if (bytecode == null) {
                throw new IllegalStateException("contracts/TestToken.bin is missing from the test resources");
            }
            return new String(bytecode.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read the test token's bytecode", failure);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(2 + bytes.length * 2);
        out.append("0x");
        for (byte b : bytes) {
            out.append("%02x".formatted(b));
        }
        return out.toString();
    }

    @Override
    public void close() {
        anvil.stop();
    }
}
