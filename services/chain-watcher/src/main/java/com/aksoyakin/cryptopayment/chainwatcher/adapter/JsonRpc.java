package com.aksoyakin.cryptopayment.chainwatcher.adapter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A JSON-RPC 2.0 transport over HTTP, which is all a node speaks.
 * <p>
 * Written here rather than taken from a client library: this is the one call path between the platform and a
 * third party it does not control, so its timeouts, its error handling and the JSON it accepts are decisions
 * this project wants to own (ADR 0022).
 */
public final class JsonRpc {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Nodes answer with far more fields than the watcher reads, and they add new ones between releases.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestClient http;
    private final AtomicLong nextId = new AtomicLong(1);

    JsonRpc(RestClient http) {
        this.http = http;
    }

    /**
     * @param url            the node's HTTP endpoint
     * @param connectTimeout how long to wait for the connection; a node that is down must not hold a thread
     * @param readTimeout    how long to wait for an answer, which bounds how far behind the watcher can fall
     */
    public static JsonRpc over(String url, Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);

        return new JsonRpc(RestClient.builder()
                .baseUrl(url)
                .requestFactory(requests)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new JacksonJsonHttpMessageConverter(MAPPER));
                })
                .build());
    }

    /**
     * @param resultType the envelope's type, e.g. {@code new ParameterizedTypeReference<Result<String>>() {}}
     * @return the {@code result} member, which is null when the node has nothing to return (an unknown block,
     *         a receipt that does not exist yet)
     */
    <T> T call(String method, List<Object> params, ParameterizedTypeReference<Result<T>> resultType) {
        Call call = new Call("2.0", nextId.getAndIncrement(), method, params);
        Result<T> result = http.post()
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

    record Call(String jsonrpc, long id, String method, List<Object> params) {
    }

    record Result<T>(T result, Error error) {
    }

    record Error(int code, String message) {
    }
}
