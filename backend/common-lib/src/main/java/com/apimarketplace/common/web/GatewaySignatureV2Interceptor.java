package com.apimarketplace.common.web;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Adds the {@link GatewaySignatureV2} header to an outgoing {@code RestTemplate} call at send
 * time, when the method, the final expanded URI and every header are known.
 *
 * <p>It only acts on a request the caller has ALREADY stamped with the v1 gateway headers
 * ({@link InternalGatewaySigner#stamp}): no {@code X-Gateway-Timestamp} means the caller did not
 * mean to sign this call (a third-party URL on a shared client, for one), and it must not leak a
 * gateway signature there. A blank secret is likewise a no-op, matching {@code stamp}.
 */
public final class GatewaySignatureV2Interceptor implements ClientHttpRequestInterceptor {

    private final Supplier<String> secret;

    public GatewaySignatureV2Interceptor(Supplier<String> secret) {
        this.secret = secret;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        InternalGatewaySigner.stampV2(request.getHeaders(), request.getMethod().name(),
                request.getURI(), secret.get());
        return execution.execute(request, body);
    }
}
