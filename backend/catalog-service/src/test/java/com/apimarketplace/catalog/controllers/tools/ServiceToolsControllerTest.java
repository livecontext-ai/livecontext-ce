package com.apimarketplace.catalog.controllers.tools;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.tools.ToolsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This endpoint is how the callers' tool caches learn what catalog-service owns.
 * A provider missing here is a tool that no agent can ever be handed, whatever its
 * toolsConfig says, so the controller must serve every {@code ToolsProvider} bean in
 * the service rather than one named one.
 */
@DisplayName("ServiceToolsController")
class ServiceToolsControllerTest {

    /** Minimal stand-in for a real provider: one tool, echoing what it was called with. */
    private static class StubProvider implements ToolsProvider {
        private final String toolName;
        private final ToolCategory category;
        String lastToolName;
        /** Captured so a test can assert what actually reached the tool, not what was sent. */
        ToolExecutionContext lastContext;

        StubProvider(String toolName, ToolCategory category) {
            this.toolName = toolName;
            this.category = category;
        }

        @Override
        public ToolCategory getCategory() {
            return category;
        }

        @Override
        public List<AgentToolDefinition> getTools() {
            return List.of(AgentToolDefinition.builder()
                    .name(toolName)
                    .description(toolName + " tool")
                    .category(category)
                    .parameters(List.of(ToolParameter.builder()
                            .name("action").type("string").description("action").required(true).build()))
                    .requiredParameters(List.of("action"))
                    .build());
        }

        @Override
        public ToolExecutionResult execute(String name, Map<String, Object> parameters,
                                            ToolExecutionContext context) {
            lastToolName = name;
            lastContext = context;
            return ToolExecutionResult.success(Map.of("ran", name));
        }
    }

    private static MockHttpServletRequest requestFor(String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", tenantId);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolNames(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("tools");
    }

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("lists the tools of EVERY provider, so generation is discoverable next to catalog")
        void listsEveryProvidersTools() {
            ServiceToolsController controller = new ServiceToolsController(List.of(
                    new StubProvider("catalog", ToolCategory.CATALOG),
                    new StubProvider("generation", ToolCategory.GENERATION)));

            var response = controller.listTools();

            assertThat(toolNames(response)).extracting(t -> t.get("name"))
                    .containsExactlyInAnyOrder("catalog", "generation");
            assertThat(response.getBody().get("count")).isEqualTo(2);
        }

        @Test
        @DisplayName("the MCP listing carries the same set, so an external client sees generation too")
        void mcpListingCarriesEveryTool() {
            ServiceToolsController controller = new ServiceToolsController(List.of(
                    new StubProvider("catalog", ToolCategory.CATALOG),
                    new StubProvider("generation", ToolCategory.GENERATION)));

            assertThat(toolNames(controller.listMcpTools())).extracting(t -> t.get("name"))
                    .containsExactlyInAnyOrder("catalog", "generation");
        }

        @Test
        @DisplayName("a provider gated off by configuration is simply not a bean, so it is not listed")
        void gatedOffProviderIsNotListed() {
            ServiceToolsController controller = new ServiceToolsController(
                    List.of(new StubProvider("catalog", ToolCategory.CATALOG)));

            assertThat(toolNames(controller.listTools())).extracting(t -> t.get("name"))
                    .containsExactly("catalog");
        }
    }

    @Nested
    @DisplayName("execution")
    class Execution {

        @Test
        @DisplayName("routes each call to the provider that owns the tool, not to the first one")
        void routesToTheOwningProvider() {
            StubProvider catalog = new StubProvider("catalog", ToolCategory.CATALOG);
            StubProvider generation = new StubProvider("generation", ToolCategory.GENERATION);
            ServiceToolsController controller = new ServiceToolsController(List.of(catalog, generation));

            var response = controller.executeTool(requestFor("tenant-1"),
                    Map.of("tool", "generation", "parameters", Map.of("action", "models")));

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody().get("tool")).isEqualTo("generation");
            assertThat(generation.lastToolName).isEqualTo("generation");
            assertThat(catalog.lastToolName).isNull();
        }

        @Test
        @DisplayName("the workflow run reaches the tool, or every generation from a workflow agent is "
                + "refused for want of a billing scope")
        void theWorkflowRunScopeReachesTheTool() {
            // This map is rebuilt key by key, so a key not copied here does not
            // exist by the time a tool reads it. An agent node inside a workflow
            // carries a run id and NO stream id, and those two are exactly what
            // the billing layer reads to build a scope. Drop the run id and the
            // scope has no kind, every billing branch is skipped, and a
            // generation is refused because it cannot be charged: the surface
            // fails 100%, in cloud, with the provider never called.
            StubProvider generation = new StubProvider("generation", ToolCategory.GENERATION);
            ServiceToolsController controller = new ServiceToolsController(List.of(generation));

            controller.executeTool(requestFor("tenant-1"), Map.of(
                    "tool", "generation",
                    "parameters", Map.of("action", "create"),
                    "agentId", "agent-1",
                    "workflowRunId", "run-42",
                    "workflowNodeId", "agent:make_clip",
                    "enabledModules", List.of("catalog", "workflow", "generation")));

            Map<String, Object> credentials = generation.lastContext.credentials();
            assertThat(credentials).containsEntry("__workflowRunId__", "run-42");
            // The consumer reads `__nodeId__`, not `__workflowNodeId__`: copying
            // it under the sender's name would look right and still be absent.
            assertThat(credentials).containsEntry("__nodeId__", "agent:make_clip");
            assertThat(credentials).containsEntry("__agentId__", "agent-1");
        }

        @Test
        @DisplayName("what the caller may DO travels with who it is, so a grant is enforceable here too")
        void theGrantReachesTheTool() {
            StubProvider generation = new StubProvider("generation", ToolCategory.GENERATION);
            ServiceToolsController controller = new ServiceToolsController(List.of(generation));

            controller.executeTool(requestFor("tenant-1"), Map.of(
                    "tool", "generation",
                    "parameters", Map.of("action", "create"),
                    "enabledModules", List.of("catalog", "generation")));

            assertThat(AgentModuleResolver.callerMayUse(
                    generation.lastContext.credentials(), "generation")).isTrue();
        }

        @Test
        @DisplayName("a call that states no run leaves the keys absent, so an ordinary chat call is untouched")
        void anAbsentRunStaysAbsent() {
            StubProvider generation = new StubProvider("generation", ToolCategory.GENERATION);
            ServiceToolsController controller = new ServiceToolsController(List.of(generation));

            controller.executeTool(requestFor("tenant-1"), Map.of(
                    "tool", "generation",
                    "parameters", Map.of("action", "models"),
                    "streamId", "stream-9"));

            Map<String, Object> credentials = generation.lastContext.credentials();
            assertThat(credentials).containsEntry("__streamId__", "stream-9");
            assertThat(credentials).doesNotContainKey("__workflowRunId__");
            assertThat(credentials).doesNotContainKey("__nodeId__");
        }

        @Test
        @DisplayName("a tool no provider owns is refused as not found instead of being sent to the wrong one")
        void unknownToolIsRefused() {
            StubProvider catalog = new StubProvider("catalog", ToolCategory.CATALOG);
            ServiceToolsController controller = new ServiceToolsController(List.of(catalog));

            var response = controller.executeTool(requestFor("tenant-1"),
                    Map.of("tool", "generation", "parameters", Map.of("action", "create")));

            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
            assertThat(response.getBody().get("success")).isEqualTo(false);
            assertThat(response.getBody().get("errorCode")).isEqualTo("TOOL_001");
            assertThat(catalog.lastToolName).isNull();
        }

        @Test
        @DisplayName("a missing tool name is refused before any provider is consulted")
        void missingToolNameIsRefused() {
            StubProvider catalog = new StubProvider("catalog", ToolCategory.CATALOG);
            ServiceToolsController controller = new ServiceToolsController(List.of(catalog));

            var response = controller.executeTool(requestFor("tenant-1"), Map.of("parameters", Map.of()));

            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
            assertThat(catalog.lastToolName).isNull();
        }
    }

    @Test
    @DisplayName("a body-supplied orgRole never reaches the execution context")
    void bodyOrgRoleIsIgnored() {
        // The body was a second channel for the privilege axis on a gateway-routed endpoint. The
        // gateway strips the caller's own identity HEADERS but not the body, so a user whose
        // gateway resolved no active org could name a workspace and assert OWNER in it. Every
        // internal caller sends the role as a header from the same source it filled the body with,
        // so nothing legitimate needs the body read. orgId is still accepted from the body, and is
        // asserted here so the two are not conflated by a later reader.
        StubProvider catalog = new StubProvider("catalog", ToolCategory.GENERATION);
        ServiceToolsController controller = new ServiceToolsController(List.of(catalog));

        controller.executeTool(requestFor("tenant-1"), Map.of(
                "tool", "catalog",
                "parameters", Map.of("action", "search"),
                "orgId", "victim-org",
                "orgRole", "OWNER"));

        assertThat(catalog.lastContext.orgRole())
                .as("a body-supplied role must never reach the execution context")
                .isNull();
        assertThat(catalog.lastContext.orgId()).isEqualTo("victim-org");
    }
}
