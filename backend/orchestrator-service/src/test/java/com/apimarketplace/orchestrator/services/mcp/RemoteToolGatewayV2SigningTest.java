package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CASA LC-013 / LC-035: the MCP aggregation gateway signs its sibling execute calls. */
@DisplayName("RemoteToolGateway signs sibling execute calls (LC-013, LC-035)")
class RemoteToolGatewayV2SigningTest {

    private static final String SECRET = "orch-secret";

    @Test
    @DisplayName("with a secret: v1 + v2 over user, org and role; without: unsigned as before")
    void executeCallIsSigned() {
        AggregatedToolCatalog catalog = mock(AggregatedToolCatalog.class);
        when(catalog.serviceUrlFor("table")).thenReturn("http://ds:8088");
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        AtomicReference<URI> uri = new AtomicReference<>();
        server.expect(req -> {
            headers.set(req.getHeaders());
            uri.set(req.getURI());
        }).andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        new RemoteToolGateway(catalog, rt, new ObjectMapper(), SECRET)
                .execute("table", Map.of("action", "list"), "42", "org-1", "MEMBER");

        HttpHeaders h = headers.get();
        assertThat(h.getFirst("X-Gateway-Secret")).startsWith("gw_");
        assertThat(h.getFirst("X-Organization-Role")).isEqualTo("MEMBER");
        assertThat(GatewaySignatureV2.verify(SECRET, "POST", uri.get().getRawPath(), uri.get().getRawQuery(),
                h::get, h.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis())).isTrue();

        RestTemplate plain = new RestTemplate();
        MockRestServiceServer plainServer = MockRestServiceServer.bindTo(plain).build();
        plainServer.expect(req -> headers.set(req.getHeaders()))
                .andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));
        new RemoteToolGateway(catalog, plain, new ObjectMapper())
                .execute("table", Map.of("action", "list"), "42", "org-1", "MEMBER");
        assertThat(headers.get().getFirst("X-Gateway-Secret")).isNull();
        assertThat(headers.get().getFirst(GatewaySignatureV2.HEADER)).isNull();
    }
}
