package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A JSON-RPC 2.0 transport over HTTP, which is all a node speaks.
 * <p>
 * Written here rather than taken from a client library: this is the one call path between the platform and a
 * third party it does not control, so its timeouts, its retries and the JSON it accepts are decisions this
 * project wants to own (ADR 0022).
 * <p>
 * More than one endpoint may be given for a chain. They are tried in order, and a provider that failed is
 * remembered only for the call that failed: the first endpoint is preferred again on the next call, so a brief
 * outage does not move the watcher to a slower provider for good.
 */
public final class JsonRpc {

    private static final Logger log = LoggerFactory.getLogger(JsonRpc.class);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Nodes answer with far more fields than the watcher reads, and they add new ones between releases.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<Endpoint> endpoints;
    private final int attemptsPerEndpoint;
    private final Duration backoff;
    private final AtomicLong nextId = new AtomicLong(1);

    JsonRpc(List<Endpoint> endpoints, int attemptsPerEndpoint, Duration backoff) {
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("a chain needs at least one node endpoint");
        }
        if (attemptsPerEndpoint < 1) {
            throw new IllegalArgumentException("an endpoint is tried at least once");
        }
        this.endpoints = List.copyOf(endpoints);
        this.attemptsPerEndpoint = attemptsPerEndpoint;
        this.backoff = backoff;
    }

    public static JsonRpc over(String url, Duration connectTimeout, Duration readTimeout) {
        return over(List.of(url), connectTimeout, readTimeout, 1, Duration.ZERO);
    }

    /**
     * @param urls                the chain's node endpoints, in order of preference
     * @param connectTimeout      how long to wait for a connection; a node that is down must not hold a thread
     * @param readTimeout         how long to wait for an answer, which bounds how far behind the watcher falls
     * @param attemptsPerEndpoint how often one endpoint is tried before the next one is used
     * @param backoff             how long to wait between attempts on the same endpoint
     */
    public static JsonRpc over(
            List<String> urls,
            Duration connectTimeout,
            Duration readTimeout,
            int attemptsPerEndpoint,
            Duration backoff) {
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);

        List<Endpoint> endpoints = urls.stream()
                .map(url -> new Endpoint(url, RestClient.builder()
                        .baseUrl(url)
                        .requestFactory(requests)
                        .messageConverters(converters -> {
                            converters.clear();
                            converters.add(new JacksonJsonHttpMessageConverter(MAPPER));
                        })
                        .build()))
                .toList();

        return new JsonRpc(endpoints, attemptsPerEndpoint, backoff);
    }

    /**
     * @param resultType the envelope's type, e.g. {@code new ParameterizedTypeReference<Result<String>>() {}}
     * @return the {@code result} member, which is null when the node has nothing to return (an unknown block,
     *         a receipt that does not exist yet)
     * @throws JsonRpcException     when a node answered and refused; this is never retried, because the answer
     *                              would not change
     * @throws NodeUnreachableException when no endpoint could be reached
     */
    <T> T call(String method, List<Object> params, ParameterizedTypeReference<Result<T>> resultType) {
        List<RuntimeException> failures = new ArrayList<>();

        for (Endpoint endpoint : endpoints) {
            for (int attempt = 1; attempt <= attemptsPerEndpoint; attempt++) {
                try {
                    return send(endpoint, method, params, resultType);
                } catch (RestClientException failure) {
                    failures.add(failure);
                    log.warn("{} failed on {} (attempt {} of {}): {}",
                            method, endpoint.url(), attempt, attemptsPerEndpoint, failure.getMessage());
                    if (attempt < attemptsPerEndpoint) {
                        pause();
                    }
                }
            }
        }

        NodeUnreachableException unreachable = new NodeUnreachableException(method, endpoints.size());
        failures.forEach(unreachable::addSuppressed);
        throw unreachable;
    }

    private <T> T send(
            Endpoint endpoint,
            String method,
            List<Object> params,
            ParameterizedTypeReference<Result<T>> resultType) {
        Call call = new Call("2.0", nextId.getAndIncrement(), method, params);
        Result<T> result = endpoint.http()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(call)
                .retrieve()
                .body(resultType);

        if (result == null) {
            throw new JsonRpcException(method, 0, "the node answered with an empty body");
        }
        if (result.error() != null) {
            throw new JsonRpcException(method, result.error().code(), result.error().message());
        }
        return result.result();
    }

    private void pause() {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry a node call", interrupted);
        }
    }

    record Endpoint(String url, RestClient http) {
    }

    record Call(String jsonrpc, long id, String method, List<Object> params) {
    }

    record Result<T>(T result, Error error) {
    }

    record Error(int code, String message) {
    }
}
