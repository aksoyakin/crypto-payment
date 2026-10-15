package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;

/**
 * An EVM node, in the terms the watcher uses: blocks, logs and receipts with numbers instead of hexadecimal
 * strings. Every method is one JSON-RPC call, and the hand-written mapping from the wire shape to these types
 * is right here, where it can be read (ADR 0021).
 */
public final class EvmNode {

    private static final ParameterizedTypeReference<JsonRpc.Result<String>> QUANTITY =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<JsonRpc.Result<BlockPayload>> BLOCK =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<JsonRpc.Result<List<LogPayload>>> LOGS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<JsonRpc.Result<ReceiptPayload>> RECEIPT =
            new ParameterizedTypeReference<>() {
            };

    private final JsonRpc rpc;

    public EvmNode(JsonRpc rpc) {
        this.rpc = rpc;
    }

    public static EvmNode at(String url, Duration connectTimeout, Duration readTimeout) {
        return new EvmNode(JsonRpc.over(url, connectTimeout, readTimeout));
    }

    public BigInteger chainId() {
        return quantity("eth_chainId", List.of());
    }

    public BigInteger blockNumber() {
        return quantity("eth_blockNumber", List.of());
    }

    public BigInteger balanceOf(String address, BlockTag at) {
        return quantity("eth_getBalance", List.of(address, at.wireName()));
    }

    /** The number of transactions sent from an address, which is the next nonce it may use. */
    public BigInteger transactionCount(String address, BlockTag at) {
        return quantity("eth_getTransactionCount", List.of(address, at.wireName()));
    }

    public BigInteger gasPrice() {
        return quantity("eth_gasPrice", List.of());
    }

    public Optional<ChainBlock> blockAt(BlockTag tag) {
        return block(tag.wireName());
    }

    public Optional<ChainBlock> blockAt(BigInteger number) {
        return block(Hex.of(number));
    }

    /**
     * Every matching log in a block range. Addresses to watch are not sent as a filter: the watcher asks for a
     * token contract's transfers and matches recipients in memory, so the call does not grow with the number of
     * deposit addresses (ADR 0004).
     *
     * @param contracts the contracts to read, or empty for every contract in the range
     * @param topics    the indexed event signature and arguments, or empty for all of them
     */
    public List<ChainLog> logs(BigInteger fromBlock, BigInteger toBlock, List<String> contracts, List<String> topics) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fromBlock", Hex.of(fromBlock));
        query.put("toBlock", Hex.of(toBlock));
        if (!contracts.isEmpty()) {
            query.put("address", contracts);
        }
        if (!topics.isEmpty()) {
            query.put("topics", topics);
        }

        List<LogPayload> logs = rpc.call("eth_getLogs", List.of(query), LOGS);
        return logs == null ? List.of() : logs.stream().map(EvmNode::toChainLog).toList();
    }

    /** Empty until the transaction is in a block. */
    public Optional<TransactionReceipt> receiptOf(String transactionHash) {
        ReceiptPayload payload = rpc.call("eth_getTransactionReceipt", List.of(transactionHash), RECEIPT);
        return Optional.ofNullable(payload).map(EvmNode::toReceipt);
    }

    /** @return the transaction's hash; the node has accepted it, not mined it. */
    public String sendRawTransaction(String signedTransaction) {
        return rpc.call("eth_sendRawTransaction", List.of(signedTransaction), QUANTITY);
    }

    private BigInteger quantity(String method, List<Object> params) {
        return Hex.toBigInteger(rpc.call(method, params, QUANTITY));
    }

    private Optional<ChainBlock> block(String blockParameter) {
        BlockPayload payload = rpc.call("eth_getBlockByNumber", List.of(blockParameter, false), BLOCK);
        return Optional.ofNullable(payload)
                .map(b -> new ChainBlock(
                        Hex.toBigInteger(b.number()),
                        b.hash(),
                        b.parentHash(),
                        Hex.toInstant(b.timestamp())));
    }

    private static ChainLog toChainLog(LogPayload log) {
        return new ChainLog(
                log.address(),
                log.topics() == null ? List.of() : log.topics(),
                log.data(),
                Hex.toBigInteger(log.blockNumber()),
                log.blockHash(),
                log.transactionHash(),
                Hex.toBigInteger(log.logIndex()),
                Boolean.TRUE.equals(log.removed()));
    }

    private static TransactionReceipt toReceipt(ReceiptPayload receipt) {
        return new TransactionReceipt(
                receipt.transactionHash(),
                Hex.toBigInteger(receipt.blockNumber()),
                receipt.blockHash(),
                Hex.toBigInteger(receipt.gasUsed()),
                // "0x1" is success. Pre-Byzantium blocks have no status field; no supported chain is that old.
                BigInteger.ONE.equals(Hex.toBigInteger(receipt.status())));
    }

    private record BlockPayload(String number, String hash, String parentHash, String timestamp) {
    }

    private record LogPayload(
            String address,
            List<String> topics,
            String data,
            String blockNumber,
            String blockHash,
            String transactionHash,
            String logIndex,
            Boolean removed) {
    }

    private record ReceiptPayload(
            String transactionHash,
            String blockNumber,
            String blockHash,
            String gasUsed,
            String status) {
    }
}
