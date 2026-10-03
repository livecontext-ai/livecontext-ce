package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.common.web.GatewaySignatureV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CASA LC-013 / LC-035: agent-service signs its /api/agent-tools/execute calls (v1 + v2). */
@DisplayName("RemoteToolExecutionService signs remote tool calls (LC-013, LC-035)")
class RemoteToolExecutionServiceV2SigningTest {

    private static final String SECRET = "agent-secret";

    @Test
    @DisplayName("the execute call carries v1 + a v2 that verifies over the headers and URL sent")
    void executeCallIsSigned() {
        RemoteToolExecutionService service = new RemoteToolExecutionService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "gatewaySecretKey", SECRET);
        ReflectionTestUtils.setField(service, "datasourceUrl", "http://datasource.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        AtomicReference<URI> uri = new AtomicReference<>();
        server.expect(req -> {
            headers.set(req.getHeaders());
            uri.set(req.getURI());
        }).andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        service.executeTool(new ToolCall("c1", "table", Map.of("action", "list"), null),
                ToolDefinition.builder().name("table").build(), "42",
                Map.of("orgId", "org-1", "orgRole", "MEMBER"));

        HttpHeaders h = headers.get();
        assertThat(h.getFirst("X-Provider-ID")).isEqualTo(RemoteToolExecutionService.INTERNAL_PROVIDER_ID);
        assertThat(h.getFirst("X-Gateway-Secret")).startsWith("gw_");
        assertThat(GatewaySignatureV2.verify(SECRET, "POST", uri.get().getRawPath(), uri.get().getRawQuery(),
                h::get, h.getFirst(GatewaySignatureV2.HEADER), 60_000, System.currentTimeMillis())).isTrue();
    }
}
