package com.apimarketplace.credential.client;

import com.apimarketplace.common.web.GatewaySignatureV2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CASA LC-035 / LC-008: CredentialClient sends v1 + v2, bound to the X-User-ID it sends. */
@DisplayName("CredentialClient adds the v2 signature (LC-035)")
class CredentialClientV2SigningTest {

    @Test
    @DisplayName("/all is signed: v2 verifies over the exact URL (query included) and X-User-ID")
    void allIsSigned() {
        CredentialClient client = new CredentialClient("http://auth:8083", "cred-secret");
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate")).build();
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        AtomicReference<URI> uri = new AtomicReference<>();
        server.expect(req -> {
            headers.set(req.getHeaders());
            uri.set(req.getURI());
        }).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.getAllCredentials("42");

        HttpHeaders h = headers.get();
        assertThat(h.getFirst("X-User-ID")).isEqualTo("42");
        assertThat(uri.get().getRawQuery()).isEqualTo("userId=42");
        assertThat(GatewaySignatureV2.verify("cred-secret", "GET", uri.get().getRawPath(), uri.get().getRawQuery(),
                h::get, h.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis())).isTrue();
        // The userId in the query is inside v2: editing it (the LC-008 attack) breaks the signature.
        assertThat(GatewaySignatureV2.verify("cred-secret", "GET", uri.get().getRawPath(), "userId=7",
                h::get, h.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis())).isFalse();
    }
}
