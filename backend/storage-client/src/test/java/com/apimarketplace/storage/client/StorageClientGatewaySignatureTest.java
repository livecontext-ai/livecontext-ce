package com.apimarketplace.storage.client;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.common.web.InternalGatewaySigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-038: storage-service can only gate {@code /api/internal/storage/*} with HMAC once every
 * caller signs. This pins that {@link StorageClient} sends a v1 signature over the X-User-ID it
 * sends, and a v2 signature over the final method + URI, both verifiable with the shared secret.
 */
@DisplayName("StorageClient signs its internal storage calls (LC-038)")
class StorageClientGatewaySignatureTest {

    private static final String SECRET = "test-gateway-hmac-key-for-unit-tests";

    /** Last interceptor: records the request exactly as it would be sent, answers 200 with one byte. */
    private static void capture(RestTemplate rt, AtomicReference<HttpHeaders> headers, AtomicReference<URI> uri,
                                AtomicReference<String> verb) {
        rt.getInterceptors().add((request, body, execution) -> {
            headers.set(request.getHeaders());
            uri.set(request.getURI());
            verb.set(request.getMethod().name());
            return new org.springframework.http.client.ClientHttpResponse() {
                @Override public org.springframework.http.HttpStatusCode getStatusCode() { return org.springframework.http.HttpStatus.OK; }
                @Override public String getStatusText() { return "OK"; }
                @Override public void close() { }
                @Override public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(new byte[] {1}); }
                @Override public HttpHeaders getHeaders() {
                    HttpHeaders out = new HttpHeaders();
                    out.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
                    return out;
                }
            };
        });
    }

    @Test
    @DisplayName("download carries v1 + v2 signatures that verify against the request actually sent")
    void downloadIsSigned() {
        RestTemplate restTemplate = new RestTemplate();
        AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
        AtomicReference<URI> sentUri = new AtomicReference<>();
        AtomicReference<String> verb = new AtomicReference<>();
        StorageClient client = new StorageClient(restTemplate, "http://storage:8093", SECRET);
        capture(restTemplate, sentHeaders, sentUri, verb);

        client.download("42", "42/wf/run/a b.png");
        assertThat(verb.get()).isEqualTo(HttpMethod.GET.name());

        HttpHeaders h = sentHeaders.get();
        assertThat(h.getFirst("X-User-ID")).isEqualTo("42");
        assertThat(h.getFirst("X-Provider-ID")).isEqualTo(StorageClient.INTERNAL_PROVIDER_ID);
        String ts = h.getFirst("X-Gateway-Timestamp");
        assertThat(h.getFirst("X-Gateway-Secret"))
                .isEqualTo(InternalGatewaySigner.sign(StorageClient.INTERNAL_PROVIDER_ID, "42", null, ts, SECRET));
        assertThat(h.getFirst(GatewaySignatureV2.HEADER))
                .isEqualTo(GatewaySignatureV2.sign(SECRET, "GET", sentUri.get(), h::get, ts));
        assertThat(sentUri.get().getRawPath()).isEqualTo("/api/internal/storage/download");
    }

    @Test
    @DisplayName("without a secret (dev / CE) the call goes out unsigned, as before")
    void noSecretNoSignature() {
        RestTemplate restTemplate = new RestTemplate();
        AtomicReference<HttpHeaders> sentHeaders = new AtomicReference<>();
        StorageClient client = new StorageClient(restTemplate, "http://storage:8093");
        capture(restTemplate, sentHeaders, new AtomicReference<>(), new AtomicReference<>());

        client.download("42", "42/k");

        assertThat(sentHeaders.get().getFirst("X-Gateway-Secret")).isNull();
        assertThat(sentHeaders.get().getFirst(GatewaySignatureV2.HEADER)).isNull();
    }
}
