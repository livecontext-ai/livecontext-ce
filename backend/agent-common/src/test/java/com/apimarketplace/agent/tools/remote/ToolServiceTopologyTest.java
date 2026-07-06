package com.apimarketplace.agent.tools.remote;

import com.apimarketplace.agent.tools.remote.ToolServiceTopology.ServiceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single source of truth for name to owning-service routing, shared by
 * conversation-service's ToolServiceRouter and agent-service's RemoteToolExecutionService.
 */
@DisplayName("ToolServiceTopology")
class ToolServiceTopologyTest {

    @Test
    @DisplayName("agent and skill are owned by agent-service")
    void agentAndSkillOwnedByAgentService() {
        assertThat(ToolServiceTopology.serviceFor("agent")).isEqualTo(ServiceKey.AGENT);
        assertThat(ToolServiceTopology.serviceFor("skill")).isEqualTo(ServiceKey.AGENT);
    }

    @Test
    @DisplayName("table / interface / catalog map to their dedicated services")
    void dedicatedServices() {
        assertThat(ToolServiceTopology.serviceFor("table")).isEqualTo(ServiceKey.DATASOURCE);
        assertThat(ToolServiceTopology.serviceFor("interface")).isEqualTo(ServiceKey.INTERFACE);
        assertThat(ToolServiceTopology.serviceFor("catalog")).isEqualTo(ServiceKey.CATALOG);
    }

    @Test
    @DisplayName("any other tool defaults to orchestrator")
    void defaultsToOrchestrator() {
        assertThat(ToolServiceTopology.serviceFor("workflow")).isEqualTo(ServiceKey.ORCHESTRATOR);
        assertThat(ToolServiceTopology.serviceFor("web_search")).isEqualTo(ServiceKey.ORCHESTRATOR);
        assertThat(ToolServiceTopology.serviceFor("application")).isEqualTo(ServiceKey.ORCHESTRATOR);
    }

    @Test
    @DisplayName("a null tool name is treated as orchestrator, not an error")
    void nullIsOrchestrator() {
        assertThat(ToolServiceTopology.serviceFor(null)).isEqualTo(ServiceKey.ORCHESTRATOR);
    }

    @Test
    @DisplayName("hasDedicatedService is true only for non-orchestrator tools")
    void hasDedicatedService() {
        assertThat(ToolServiceTopology.hasDedicatedService("table")).isTrue();
        assertThat(ToolServiceTopology.hasDedicatedService("agent")).isTrue();
        assertThat(ToolServiceTopology.hasDedicatedService("workflow")).isFalse();
        assertThat(ToolServiceTopology.hasDedicatedService(null)).isFalse();
    }
}
