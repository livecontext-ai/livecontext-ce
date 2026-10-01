package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The full chain from the catalog's HTTP answer to the node's retry wait: a mocked
 * {@link RestTemplate} returns the catalog's JSON envelope for a 429 with Retry-After, the REAL
 * {@link CatalogToolsGateway} deserializes it, extracts the status and flattens it, the step
 * failure is built the way the step node builds it, and the REAL {@link NodePolicyRunner} must
 * wait the provider's 7 s. Any link that drops {@code http_status} or
 * {@code metadata.retryAfterSeconds} fails this test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Catalog HTTP answer -> CatalogToolsGateway -> step failure -> node retry (chain)")
class CatalogGatewayRetryChainTest {

    private static final String ENVELOPE = """
            {"success":false,"error":"slow down",
             "metadata":{"status":429,"retryAfterSeconds":7,"httpStatus":{"code":429,"error":"slow down"}},
             "result":{"httpStatus":{"code":429}}}
            """;

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);
    }

    /** The catalog answers with its JSON envelope, bound into whatever type the gateway asked for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void catalogAnswers(String json) {
        ObjectMapper mapper = new ObjectMapper();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenAnswer(invocation -> ResponseEntity.ok(mapper.readValue(json, (Class) invocation.getArgument(3))));
    }

    /** The failure the step node returns for a failed gateway result: output + the first error message. */
    private static NodeExecutionResult stepFailure(ExecutionResult result) {
        Map<String, Object> output = new HashMap<>(result.output());
        String error = result.errors().isEmpty() ? "Tool execution failed" : result.errors().get(0).get("message");
        output.put("error", error);
        return NodeExecutionResult.failureWithOutput("mcp:post", error, output, 3);
    }

    @Test
    @DisplayName("a 429 with Retry-After 7 through the real gateway is waited out for 7000 ms and retried")
    void retryAfterSurvivesTheGateway() throws Exception {
        catalogAnswers(ENVELOPE);

        List<Long> sleeps = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        new NodePolicyRunner(sleeps::add).run(new NodePolicy(1, 1000L, false), "mcp:post", () -> {
            attempts.incrementAndGet();
            ExecutionResult result = gateway.executeTool(new ToolRef("x/post-update", 1), Map.of(), "tenant-1");
            assertThat(result.ok()).isFalse();
            return stepFailure(result);
        }, null);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(7_000L);
    }

    @Test
    @DisplayName("REGRESSION: the catalog's own refusals through the real gateway are attempted once, not retried")
    void catalogRefusalsThroughTheGatewayAreNotRetried() throws Exception {
        for (Object[] refusal : new Object[][] {
                {org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "{\"error\":\"INSUFFICIENT_CREDITS\",\"message\":\"Out of credits\"}"},
                {org.springframework.http.HttpStatus.NOT_FOUND, "{\"success\":false,\"error\":\"TOOL_NOT_FOUND\",\"message\":\"Tool not found: x/post-update\"}"},
                {org.springframework.http.HttpStatus.FORBIDDEN, "{\"error\":\"PLAN_UPGRADE_REQUIRED\",\"message\":\"Needs the Pro plan\"}"}}) {
            org.springframework.http.HttpStatus status = (org.springframework.http.HttpStatus) refusal[0];
            org.mockito.Mockito.reset(restTemplate);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(status, status.getReasonPhrase(),
                    org.springframework.http.HttpHeaders.EMPTY, ((String) refusal[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));

            List<Long> sleeps = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();
            NodeExecutionResult last = new NodePolicyRunner(sleeps::add).run(new NodePolicy(3, 1000L, false), "mcp:post", () -> {
                attempts.incrementAndGet();
                return stepFailure(gateway.executeTool(new ToolRef("x/post-update", 1), Map.of(), "tenant-1"));
            }, null);

            assertThat(attempts.get()).as(status.toString()).isEqualTo(1);
            assertThat(sleeps).as(status.toString()).isEmpty();
            assertThat(last.output()).as(status.toString()).doesNotContainKey("http_status");
        }
    }

    @Test
    @DisplayName("the gateway output carries the status and the Retry-After where the runner reads them")
    @SuppressWarnings("unchecked")
    void gatewayOutputShape() {
        catalogAnswers(ENVELOPE);

        ExecutionResult result = gateway.executeTool(new ToolRef("x/post-update", 1), Map.of(), "tenant-1");

        assertThat(result.output()).containsEntry("http_status", 429);
        assertThat((Map<String, Object>) result.output().get("metadata")).containsEntry("retryAfterSeconds", 7);
    }
}
