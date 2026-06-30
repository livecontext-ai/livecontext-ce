package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Complements {@code AgentRemoteExecutionServiceTest} (which covers the type=agent path) with the
 * remaining {@link AgentRemoteExecutionService#executeByType} dispatch branches: the unknown-type
 * rejection and the classify/guardrail routing + role forwarding. The classify/guardrail cases use
 * a throw-sentinel so routing is proven without constructing the full response records.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRemoteExecutionService - executeByType classify/guardrail/unknown dispatch")
class AgentRemoteExecutionServiceDispatchTest {

    @Mock private AgentLoopService agentLoopService;
    @Mock private RedisStreamingCallback redisStreamingCallback;
    @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    @Mock private CoreToolsCache coreToolsCache;
    @Mock private AgentActivityPublisher agentActivityPublisher;
    @Mock private GuardChainFactory guardChainFactory;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    private AgentRemoteExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentRemoteExecutionService(
            agentLoopService, new ObjectMapper(), redisStreamingCallback,
            conversationRedisStreamingCallback, coreToolsCache, agentActivityPublisher,
            guardChainFactory, classifyService, guardrailService, bridgeDispatcher, modelCatalogService);
    }

    @Test
    @DisplayName("an unknown agent type is rejected with IllegalArgumentException")
    void unknownAgentTypeRejected() {
        assertThatThrownBy(() -> service.executeByType("totally-unknown-type", "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown agent type: totally-unknown-type");
    }

    @Test
    @DisplayName("type=classify routes to ClassifyService and forwards the caller roles")
    void classifyRoutesToClassifyService() {
        when(classifyService.execute(any(), eq("ADMIN")))
                .thenThrow(new IllegalStateException("classify-routed"));

        assertThatThrownBy(() -> service.executeByType(
                AgentExecutionTask.TYPE_CLASSIFY, "{\"content\":\"hello\"}", "ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("classify-routed");

        verify(classifyService).execute(any(), eq("ADMIN"));
        verify(guardrailService, never()).execute(any(), any());
    }

    @Test
    @DisplayName("type=guardrail routes to GuardrailService and forwards the caller roles")
    void guardrailRoutesToGuardrailService() {
        when(guardrailService.execute(any(), eq("ADMIN")))
                .thenThrow(new IllegalStateException("guardrail-routed"));

        assertThatThrownBy(() -> service.executeByType(
                AgentExecutionTask.TYPE_GUARDRAIL, "{\"content\":\"hello\"}", "ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("guardrail-routed");

        verify(guardrailService).execute(any(), eq("ADMIN"));
        verify(classifyService, never()).execute(any(), any());
    }
}
