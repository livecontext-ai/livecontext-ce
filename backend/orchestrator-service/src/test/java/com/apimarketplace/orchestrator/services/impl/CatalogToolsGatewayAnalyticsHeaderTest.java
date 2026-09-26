package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The product-analytics attribution headers the gateway forwards to the catalog,
 * and the one it must NOT light up for that purpose: X-Lc-Billing-Step-Id shapes
 * the billing ledger key, so attribution travels in its own headers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsGateway - analytics attribution headers")
class CatalogToolsGatewayAnalyticsHeaderTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(Class.class)))
                .thenReturn(ResponseEntity.ok(null));
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<Map<String, Object>> outbound() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("__workflowId__ / __analyticsNodeId__ become X-Lc-Workflow-Id / X-Lc-Node-Id, and the billing step header stays absent")
    void forwardsAttributionHeadersOnly() {
        Map<String, Object> ids = new HashMap<>();
        ids.put("__workflowRunId__", "run_1");
        ids.put("__workflowId__", "wf-uuid");
        ids.put("__analyticsNodeId__", "mcp:slack/send_message");

        gateway.executeTool(new ToolRef("slack/send-message", 1), Map.of("text", "hi"), "42", ids);

        var headers = outbound().getHeaders();
        assertThat(headers.getFirst("X-Lc-Workflow-Id")).isEqualTo("wf-uuid");
        assertThat(headers.getFirst("X-Lc-Node-Id")).isEqualTo("mcp:slack/send_message");
        assertThat(headers.getFirst("X-Lc-Billing-Scope-Kind")).isEqualTo("RUN");
        assertThat(headers.getFirst("X-Lc-Billing-Scope-Id")).isEqualTo("run_1");
        assertThat(headers.getFirst("X-Lc-Billing-Step-Id"))
                .as("attribution must never light the billing step key")
                .isNull();
    }

    @Test
    @DisplayName("without the markers no attribution header is sent (agent / test callers unchanged)")
    void noMarkersNoHeaders() {
        gateway.executeTool(new ToolRef("slack/send-message", 1), Map.of(), "42", Map.of("__streamId__", "s1"));

        var headers = outbound().getHeaders();
        assertThat(headers.getFirst("X-Lc-Workflow-Id")).isNull();
        assertThat(headers.getFirst("X-Lc-Node-Id")).isNull();
        assertThat(headers.getFirst("X-Lc-Billing-Scope-Kind")).isEqualTo("STREAM");
    }

    @Test
    @DisplayName("a step's __stepOutput__ marker becomes X-Lc-Step-Output, so the catalog keeps its text whole")
    void forwardsStepOutputMarker() {
        Map<String, Object> ids = new HashMap<>();
        ids.put("__workflowRunId__", "run_1");
        ids.put(CatalogToolsGateway.STEP_OUTPUT_MARKER, Boolean.TRUE);

        gateway.executeTool(new ToolRef("gmail/get-message", 1), Map.of(), "42", ids);

        assertThat(outbound().getHeaders().getFirst("X-Lc-Step-Output")).isEqualTo("true");
    }

    @Test
    @DisplayName("without the marker (an agent inside a run) X-Lc-Step-Output is not sent")
    void noStepOutputHeaderWithoutMarker() {
        gateway.executeTool(new ToolRef("gmail/get-message", 1), Map.of(), "42", Map.of("__workflowRunId__", "run_1"));

        assertThat(outbound().getHeaders().getFirst("X-Lc-Step-Output")).isNull();
    }
}
