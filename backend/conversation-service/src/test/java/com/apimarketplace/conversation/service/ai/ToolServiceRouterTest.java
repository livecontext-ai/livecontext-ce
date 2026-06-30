package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolServiceRouter")
class ToolServiceRouterTest {

    private ToolServiceRouter router;

    private static final String ORCHESTRATOR_URL = "http://localhost:8099";
    private static final String AGENT_URL = "http://localhost:8090";
    private static final String DATASOURCE_URL = "http://localhost:8088";
    private static final String INTERFACE_URL = "http://localhost:8089";
    private static final String CATALOG_URL = "http://localhost:8081";

    @BeforeEach
    void setUp() {
        router = new ToolServiceRouter(ORCHESTRATOR_URL, AGENT_URL, DATASOURCE_URL, INTERFACE_URL, CATALOG_URL);
    }

    // ==================== getServiceUrl ====================

    @Test
    @DisplayName("Should route 'agent' to agent-service")
    void shouldRouteAgentToAgentService() {
        assertThat(router.getServiceUrl("agent")).isEqualTo(AGENT_URL);
    }

    @Test
    @DisplayName("Should route 'skill' to agent-service")
    void shouldRouteSkillToAgentService() {
        assertThat(router.getServiceUrl("skill")).isEqualTo(AGENT_URL);
    }

    @Test
    @DisplayName("Should route 'table' to datasource-service")
    void shouldRouteTableToDatasourceService() {
        assertThat(router.getServiceUrl("table")).isEqualTo(DATASOURCE_URL);
    }

    @Test
    @DisplayName("Should route 'interface' to interface-service")
    void shouldRouteInterfaceToInterfaceService() {
        assertThat(router.getServiceUrl("interface")).isEqualTo(INTERFACE_URL);
    }

    @Test
    @DisplayName("Should route 'workflow' to orchestrator")
    void shouldRouteWorkflowToOrchestrator() {
        assertThat(router.getServiceUrl("workflow")).isEqualTo(ORCHESTRATOR_URL);
    }

    @Test
    @DisplayName("Should route 'catalog' to catalog-service")
    void shouldRouteCatalogToCatalogService() {
        assertThat(router.getServiceUrl("catalog")).isEqualTo(CATALOG_URL);
    }

    @Test
    @DisplayName("Should route 'application' to orchestrator")
    void shouldRouteApplicationToOrchestrator() {
        assertThat(router.getServiceUrl("application")).isEqualTo(ORCHESTRATOR_URL);
    }

    @Test
    @DisplayName("Should route 'web_search' to orchestrator")
    void shouldRouteWebSearchToOrchestrator() {
        assertThat(router.getServiceUrl("web_search")).isEqualTo(ORCHESTRATOR_URL);
    }

    @Test
    @DisplayName("Should route unknown tools to orchestrator")
    void shouldRouteUnknownToOrchestrator() {
        assertThat(router.getServiceUrl("unknown_tool")).isEqualTo(ORCHESTRATOR_URL);
    }

    // ==================== getExecuteUrl ====================

    @Test
    @DisplayName("Should return full execute URL for agent tool")
    void shouldReturnFullExecuteUrlForAgent() {
        assertThat(router.getExecuteUrl("agent")).isEqualTo(AGENT_URL + "/api/agent-tools/execute");
    }

    @Test
    @DisplayName("Should return full execute URL for table tool")
    void shouldReturnFullExecuteUrlForTable() {
        assertThat(router.getExecuteUrl("table")).isEqualTo(DATASOURCE_URL + "/api/agent-tools/execute");
    }

    @Test
    @DisplayName("Should return full execute URL for interface tool")
    void shouldReturnFullExecuteUrlForInterface() {
        assertThat(router.getExecuteUrl("interface")).isEqualTo(INTERFACE_URL + "/api/agent-tools/execute");
    }

    @Test
    @DisplayName("Should return full execute URL for workflow tool (orchestrator)")
    void shouldReturnFullExecuteUrlForWorkflow() {
        assertThat(router.getExecuteUrl("workflow")).isEqualTo(ORCHESTRATOR_URL + "/api/agent-tools/execute");
    }

    // ==================== getServiceName ====================

    @Test
    @DisplayName("Should return correct service names")
    void shouldReturnCorrectServiceNames() {
        assertThat(router.getServiceName("agent")).isEqualTo("agent-service");
        assertThat(router.getServiceName("skill")).isEqualTo("agent-service");
        assertThat(router.getServiceName("table")).isEqualTo("datasource-service");
        assertThat(router.getServiceName("interface")).isEqualTo("interface-service");
        assertThat(router.getServiceName("workflow")).isEqualTo("orchestrator-service");
        assertThat(router.getServiceName("catalog")).isEqualTo("catalog-service");
    }
}
