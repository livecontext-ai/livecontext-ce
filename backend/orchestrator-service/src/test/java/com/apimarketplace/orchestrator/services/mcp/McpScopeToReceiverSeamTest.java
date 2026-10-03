package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.apimarketplace.orchestrator.controllers.agent.AgentToolsController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-055 (security audit 2026-08-13): the SEAM between the two halves of the fix.
 *
 * <p>The sending half derives {@code <category>AccessMode} from an API key's scopes and puts it in
 * the {@code /api/agent-tools/execute} body; the receiving half rebuilds a credentials map from
 * that body. Each half had its own test and the pair still did not hold, because the sender was
 * matching on the {@code AccessMode} suffix while every receiver copied a FIXED list of seven
 * category names. {@code catalogAccessMode} was therefore put on the wire and dropped on arrival,
 * and an absent mode means UNRESTRICTED to every tool module, so the restriction vanished at the
 * boundary while both halves' own tests stayed green.
 *
 * <p>This test drives the real {@link McpProtocolService}, the real {@link RemoteToolGateway} and a
 * real receiver ({@link AgentToolsController}, this service's own {@code /api/agent-tools/execute},
 * which shares the arrival rule with the four sibling receivers). It asserts on the HTTP body that
 * actually leaves and on the credentials map that actually reaches the tool, so no stub decides the
 * outcome.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MCP scope to receiver seam - a derived access mode survives the wire")
class McpScopeToReceiverSeamTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    @Mock private ObjectProvider<AggregatedToolCatalog> aggregatedCatalogProvider;
    @Mock private ObjectProvider<RemoteToolGateway> remoteToolGatewayProvider;
    @Mock private AggregatedToolCatalog aggregatedCatalog;
    @Mock private RestTemplate executionRestTemplate;

    private McpProtocolService service;
    private RestTemplate capturedRestTemplate;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        RemoteToolGateway gateway =
                new RemoteToolGateway(aggregatedCatalog, executionRestTemplate, new ObjectMapper());
        service = new McpProtocolService(registry, registrationService, new ObjectMapper(),
                aggregatedCatalogProvider, remoteToolGatewayProvider);
        capturedRestTemplate = executionRestTemplate;

        when(registry.hasTool("catalog")).thenReturn(false);
        when(aggregatedCatalogProvider.getIfAvailable()).thenReturn(aggregatedCatalog);
        when(aggregatedCatalog.knows("catalog")).thenReturn(true);
        when(aggregatedCatalog.serviceUrlFor("catalog")).thenReturn("http://catalog-service:8081");
        when(remoteToolGatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(executionRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(Map.of("success", true, "data", Map.of())));
    }

    /** The body the orchestrator actually POSTs for a key scoped to catalog reads only. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> bodySentForReadOnlyCatalogKey() throws Exception {
        service.callTool("catalog", Map.of("action", "search"), "tenant-1", "org-1", "MEMBER",
                Set.of("catalog.search"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(capturedRestTemplate).exchange(anyString(), eq(HttpMethod.POST), entity.capture(),
                eq(Map.class));
        return entity.getValue().getBody();
    }

    @Test
    @DisplayName("the derived catalogAccessMode is on the wire AND in the credentials the receiver hands the tool")
    void derivedCatalogModeSurvivesBothHalves() throws Exception {
        Map<String, Object> sentBody = bodySentForReadOnlyCatalogKey();

        assertThat(sentBody).containsEntry("catalogAccessMode", "read");

        AgentToolsController receiver = new AgentToolsController(registry, registrationService);
        when(registry.hasTool("catalog")).thenReturn(true);
        ArgumentCaptor<ToolsProvider.ToolExecutionContext> context =
                ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
        when(registrationService.executeTool(eq("catalog"), any(), context.capture()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "tenant-1");
        receiver.executeTool(httpRequest, sentBody);

        // Pre-fix this was absent, and absent means UNRESTRICTED to every tool module.
        assertThat(context.getValue().credentials()).containsEntry("catalogAccessMode", "read");
    }

    @Test
    @DisplayName("the wire body carries the modes at the root, never nested under parameters")
    void modesTravelAtTheBodyRootAndNotAsToolArguments() throws Exception {
        Map<String, Object> sentBody = bodySentForReadOnlyCatalogKey();

        assertThat(sentBody).containsKey("catalogAccessMode");
        assertThat((Map<String, Object>) sentBody.get("parameters"))
                .doesNotContainKey("catalogAccessMode");
    }
}
