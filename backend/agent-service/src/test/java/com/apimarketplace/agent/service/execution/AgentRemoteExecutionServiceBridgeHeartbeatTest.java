package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: a BRIDGE conversation run must hold a {@code stream:hb:{streamId}}
 * liveness heartbeat for the duration of the dispatch.
 *
 * <p>The bug: the bridge publishes its streaming events to Redis itself, so the
 * worker never built a {@link ConversationRedisStreamingCallback} - the only
 * component that registers the stream in {@link ActiveStreamRegistry}. The
 * conversation {@code Stream} DB row's {@code updated_at} is frozen at creation
 * (nothing saves the JPA row mid-run), and conversation-service's absolute-timeout
 * reaper spares only live-heartbeat streams - so a healthy bridge chat run was
 * interrupted at the TTL (~10 min in cloud) even while actively streaming, with a
 * generic timeout instead of the run's own inactivity/execution semantics.
 */
@DisplayName("AgentRemoteExecutionService - bridge run stream heartbeat (StreamTTL reap regression)")
@ExtendWith(MockitoExtension.class)
class AgentRemoteExecutionServiceBridgeHeartbeatTest {

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
    @Mock private ActiveStreamRegistry activeStreamRegistry;

    private AgentRemoteExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentRemoteExecutionService(
            agentLoopService, new ObjectMapper(), redisStreamingCallback,
            conversationRedisStreamingCallback, coreToolsCache, agentActivityPublisher,
            guardChainFactory, classifyService, guardrailService, bridgeDispatcher, modelCatalogService,
            activeStreamRegistry);
        // Route to the bridge and keep provider/effort resolution a pass-through.
        lenient().when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        lenient().when(modelCatalogService.resolveProvider(any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelCatalogService.resolveEffortWithDefault(any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("conversation-format bridge run registers the stream heartbeat BEFORE dispatch and unregisters after")
    void conversationBridgeRunIsHeartbeatProtectedForTheWholeDispatch() {
        when(bridgeDispatcher.dispatchRaw(any(), any())).thenReturn(response());

        service.executeAgent(bridgeRequest("conversation", "stream-42"), null);

        InOrder inOrder = inOrder(activeStreamRegistry, bridgeDispatcher);
        inOrder.verify(activeStreamRegistry).register(eq("stream-42"), any(Runnable.class));
        inOrder.verify(bridgeDispatcher).dispatchRaw(any(), any());
        inOrder.verify(activeStreamRegistry).unregister("stream-42");
    }

    @Test
    @DisplayName("the heartbeat is released even when the bridge dispatch throws (no leaked registration)")
    void heartbeatReleasedWhenDispatchThrows() {
        when(bridgeDispatcher.dispatchRaw(any(), any())).thenThrow(new RuntimeException("bridge down"));

        AgentExecutionResponseDto result = service.executeAgent(bridgeRequest("conversation", "stream-err"), null);

        assertThat(result.success()).isFalse();
        verify(activeStreamRegistry).register(eq("stream-err"), any(Runnable.class));
        verify(activeStreamRegistry).unregister("stream-err");
    }

    @Test
    @DisplayName("the heartbeat is released when the bridge guard denies the run (typed rethrow path)")
    void heartbeatReleasedOnBridgeAccessDenied() {
        when(bridgeDispatcher.dispatchRaw(any(), any()))
            .thenThrow(new BridgeAccessDeniedException("claude-code", "POLICY_DISABLED"));

        assertThatThrownBy(() -> service.executeAgent(bridgeRequest("conversation", "stream-denied"), null))
            .isInstanceOf(BridgeAccessDeniedException.class);

        verify(activeStreamRegistry).register(eq("stream-denied"), any(Runnable.class));
        verify(activeStreamRegistry).unregister("stream-denied");
    }

    @Test
    @DisplayName("workflow-format bridge run does NOT register: its streamChannelId is a run channel, not a conversation Stream row")
    void workflowFormatBridgeRunIsNotRegistered() {
        when(bridgeDispatcher.dispatchRaw(any(), any())).thenReturn(response());

        service.executeAgent(bridgeRequest("workflow", "run-7"), null);

        verify(activeStreamRegistry, never()).register(anyString(), any(Runnable.class));
        verify(activeStreamRegistry, never()).unregister(anyString());
    }

    @Test
    @DisplayName("conversation format without a streamChannelId does NOT register (nothing to protect)")
    void conversationFormatWithoutChannelIsNotRegistered() {
        when(bridgeDispatcher.dispatchRaw(any(), any())).thenReturn(response());

        service.executeAgent(bridgeRequest("conversation", null), null);

        verify(activeStreamRegistry, never()).register(anyString(), any(Runnable.class));
    }

    @Test
    @DisplayName("the shutdown-drain handle is inert (logs only): the worker cannot rescue a bridge run's partials itself")
    void drainHandleDoesNotThrow() {
        when(bridgeDispatcher.dispatchRaw(any(), any())).thenReturn(response());
        ArgumentCaptor<Runnable> handle = ArgumentCaptor.forClass(Runnable.class);

        service.executeAgent(bridgeRequest("conversation", "stream-drain"), null);

        verify(activeStreamRegistry).register(eq("stream-drain"), handle.capture());
        // Must never throw: interruptAll isolates handles, but an inert handle is the contract here.
        handle.getValue().run();
    }

    private AgentExecutionResponseDto response() {
        return new AgentExecutionResponseDto(
            true, "done", null, List.of(), 1, Map.of(), null, 5L,
            "claude-code", "claude-opus-4-6", List.of(), "COMPLETED",
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    /** Minimal bridge-bound request mirroring how the chat path builds the DTO. */
    private AgentExecutionRequestDto bridgeRequest(String streamingFormat, String streamChannelId) {
        return new AgentExecutionRequestDto(
            "Answer.",
            "You are an agent.",
            "claude-code",
            "claude-opus-4-6",
            0.0,
            320,
            List.of(),
            false,
            10,
            4,
            150,
            null,
            "tenant-1",
            null,             // runId
            null,             // nodeId
            null,             // variables
            Map.of(),         // credentials
            null,             // maxCreditBudget
            streamChannelId,
            null,             // itemIndex
            null,             // loopIteration
            "conversation-1",
            streamingFormat,
            null,             // parentConversationId
            null,             // subAgentName
            null,             // subAgentAvatarUrl
            null,             // subAgentId
            null,             // workflowRunId
            null,             // attachments
            "agent-1",
            100.0,            // tenantBalance
            null,             // pricingRates
            0.0,              // creditsConsumedSoFar
            null,             // loopIdenticalStop
            null,             // loopConsecutiveStop
            UUID.randomUUID().toString(),
            null,             // source
            null,             // reasoningEffort
            null              // enabledModules
        );
    }
}
