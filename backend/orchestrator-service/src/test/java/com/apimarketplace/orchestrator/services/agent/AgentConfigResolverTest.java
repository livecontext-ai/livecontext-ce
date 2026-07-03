package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the contract that {@link AgentConfigResolver#resolve} returns BOTH the merged
 * {@link Agent} record AND the {@link AgentRuntimeOverrides} sidecar from a single
 * agent-service fetch. Regression for the bug where AgentNode was making 3 separate
 * {@code resolveAgentConfig} HTTP calls per execution (executionTimeout +
 * loopIdenticalStop + loopConsecutiveStop), one of which was the resolver's own
 * internal call.
 */
class AgentConfigResolverTest {

    private AgentClient agentClient;
    private AgentConfigResolver resolver;

    @BeforeEach
    void setUp() {
        agentClient = mock(AgentClient.class);
        resolver = new AgentConfigResolver(agentClient);
    }

    @Test
    @DisplayName("resolve() returns AgentRuntimeOverrides built from the same DTO fetch (no extra HTTP calls)")
    void resolveReturnsOverridesFromSingleFetch() {
        UUID entityId = UUID.randomUUID();
        Agent planAgent = new Agent(
            "a1", "agent", "My Agent", entityId.toString(), null,
            null, null, null, "user prompt",
            null, null, null, null, null, null, null,
            null, null, null, null, null);

        AgentDto dto = new AgentDto();
        dto.setId(entityId);
        dto.setName("Resolved Agent");
        dto.setModelProvider("openai");
        dto.setModelName("gpt-4");
        dto.setExecutionTimeout(120);
        dto.setInactivityTimeout(300);
        dto.setLoopIdenticalStop(7);
        dto.setLoopConsecutiveStop(11);
        when(agentClient.resolveAgentConfig(entityId, "tenant-1", null)).thenReturn(dto);

        AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1");

        assertThat(result.agent().provider()).isEqualTo("openai");
        assertThat(result.agent().model()).isEqualTo("gpt-4");
        assertThat(result.overrides().executionTimeout()).isEqualTo(120);
        // V372 - the per-agent inactivity window must thread from the DTO into the runtime
        // overrides on the SAME single fetch (this is the workflow-agent producer path that
        // AgentNode then carries as __inactivityTimeoutSeconds__).
        assertThat(result.overrides().inactivityTimeout()).isEqualTo(300);
        assertThat(result.overrides().loopIdenticalStop()).isEqualTo(7);
        assertThat(result.overrides().loopConsecutiveStop()).isEqualTo(11);

        // Critical regression assertion - only ONE HTTP fetch must happen.
        verify(agentClient, times(1)).resolveAgentConfig(entityId, "tenant-1", null);
    }

    @Test
    @DisplayName("resolve() returns EMPTY overrides when planAgent has no agentConfigId (no HTTP fetch)")
    void resolveSkipsFetchForInlineConfig() {
        Agent inline = new Agent(
            "a1", "agent", "Inline", null, null,
            "openai", "gpt-4", "sys", "user",
            0.5, 1024, 5, 3, null, null, null,
            null, null, null, null, null);

        AgentConfigResolver.ResolveResult result = resolver.resolve(inline, "tenant-1");

        assertThat(result.agent()).isSameAs(inline);
        assertThat(result.overrides()).isSameAs(AgentRuntimeOverrides.EMPTY);
        verify(agentClient, never()).resolveAgentConfig(any(UUID.class), any(), any());
    }

    @Test
    @DisplayName("resolve() returns EMPTY overrides when entity not found (soft-fail to plan agent)")
    void resolveSoftFailsWhenEntityMissing() {
        UUID entityId = UUID.randomUUID();
        Agent planAgent = new Agent(
            "a1", "agent", "Missing", entityId.toString(), null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

        when(agentClient.resolveAgentConfig(entityId, "tenant-1", null)).thenReturn(null);

        AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1");

        assertThat(result.agent()).isSameAs(planAgent);
        assertThat(result.overrides()).isSameAs(AgentRuntimeOverrides.EMPTY);
    }

    @Test
    @DisplayName("resolve() returns EMPTY overrides when DTO fields are null (no fabricated defaults)")
    void resolveCarriesNullsThrough() {
        UUID entityId = UUID.randomUUID();
        Agent planAgent = new Agent(
            "a1", "agent", "Sparse", entityId.toString(), null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

        AgentDto dto = new AgentDto();
        dto.setId(entityId);
        dto.setName("Sparse");
        // executionTimeout / loopIdenticalStop / loopConsecutiveStop intentionally null
        when(agentClient.resolveAgentConfig(entityId, "tenant-1", null)).thenReturn(dto);

        AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1");

        assertThat(result.overrides().executionTimeout()).isNull();
        assertThat(result.overrides().inactivityTimeout()).isNull();
        assertThat(result.overrides().loopIdenticalStop()).isNull();
        assertThat(result.overrides().loopConsecutiveStop()).isNull();
    }

    @Test
    @DisplayName("resolve() threads inactivityTimeout=0 (disabled) VERBATIM - 0 must never be dropped as falsy")
    void resolveThreadsZeroInactivityVerbatim() {
        UUID entityId = UUID.randomUUID();
        Agent planAgent = new Agent(
            "a1", "agent", "Disabled Watchdog", entityId.toString(), null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

        AgentDto dto = new AgentDto();
        dto.setId(entityId);
        dto.setName("Disabled Watchdog");
        dto.setInactivityTimeout(0);
        when(agentClient.resolveAgentConfig(entityId, "tenant-1", null)).thenReturn(dto);

        AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1");

        // 0 is the "watchdog disabled" sentinel: if the resolver coerced it to null the
        // downstream loop would silently fall back to the 5-min default.
        assertThat(result.overrides().inactivityTimeout()).isZero();
    }

    @Test
    @DisplayName("resolve() threads the contract boundary windows 10 and 7200 unchanged (no clamping)")
    void resolveThreadsBoundaryWindowsUnchanged() {
        for (int boundary : new int[] {10, 7200}) {
            UUID entityId = UUID.randomUUID();
            Agent planAgent = new Agent(
                "a1", "agent", "Boundary", entityId.toString(), null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

            AgentDto dto = new AgentDto();
            dto.setId(entityId);
            dto.setName("Boundary");
            dto.setInactivityTimeout(boundary);
            when(agentClient.resolveAgentConfig(entityId, "tenant-1", null)).thenReturn(dto);

            AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1");

            assertThat(result.overrides().inactivityTimeout())
                .as("boundary window %s must thread unchanged", boundary)
                .isEqualTo(boundary);
        }
    }

    @Test
    @DisplayName("resolve() forwards organization scope for entity-backed workflow agents")
    void resolveForwardsOrganizationScope() {
        UUID entityId = UUID.randomUUID();
        Agent planAgent = new Agent(
            "a1", "agent", "Org Agent", entityId.toString(), null,
            null, null, null, "user prompt",
            null, null, null, null, null, null, null,
            null, null, null, null, null);

        AgentDto dto = new AgentDto();
        dto.setId(entityId);
        dto.setName("Resolved Org Agent");
        dto.setModelProvider("deepseek");
        dto.setModelName("deepseek-chat");
        when(agentClient.resolveAgentConfig(entityId, "tenant-1", "org-1")).thenReturn(dto);

        AgentConfigResolver.ResolveResult result = resolver.resolve(planAgent, "tenant-1", "org-1");

        assertThat(result.agent().provider()).isEqualTo("deepseek");
        assertThat(result.agent().model()).isEqualTo("deepseek-chat");
        verify(agentClient, times(1)).resolveAgentConfig(entityId, "tenant-1", "org-1");
    }
}
