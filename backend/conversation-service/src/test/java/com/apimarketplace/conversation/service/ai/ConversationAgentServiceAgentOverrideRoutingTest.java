package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.conversation.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the production bug observed in conversation
 * {@code dfadb561-953b-42d1-b57e-31144a9dc84c} (May 2026): the user picked
 * {@code (claude-code, claude-opus-4-6)} in the chat header, the attached agent
 * "Competitive Intelligence Analyst" was configured for {@code (deepseek,
 * deepseek-chat)}, and the chat routed to the Claude Code bridge instead of
 * DeepSeek direct. The bridge spawned {@code claude --model deepseek-chat} and
 * the CLI's "There's an issue with the selected model (deepseek-chat). … Run
 * --model to pick a different model." stdout line was relayed as assistant
 * content.
 *
 * <p>Root cause (commit {@code ab065339a}, 2026-03-30 "multi-CLI bridge"): the
 * {@link ConversationAgentService#executeStreaming} method read
 * {@code request.getProvider()} directly to decide bridge-vs-remote - BEFORE
 * {@link AgentContextBuilder#build} had a chance to apply the agent's
 * configured model. The centralized agent-override existed
 * ({@code AgentContextBuilder.build} line ~354), the streaming path just
 * looked at the wrong fields. {@link ConversationAgentService#executeSync}
 * already did it correctly (decision based on {@code dto.provider()} after
 * the build).
 *
 * <p>Fix: build context first, decide bridge from {@code dto.provider() /
 * dto.model()} - same order as {@code executeSync}.
 */
@DisplayName("ConversationAgentService - agent-override drives bridge routing")
class ConversationAgentServiceAgentOverrideRoutingTest {

    private AgentContextBuilder contextBuilder;
    private AgentClient agentClient;
    private BridgeClient bridgeClient;
    private BridgeAccessEnforcer bridgeAccessEnforcer;
    private CreditConsumptionClient creditClient;
    private StreamingOutput streamOutput;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        contextBuilder = mock(AgentContextBuilder.class);
        agentClient = mock(AgentClient.class);
        bridgeClient = mock(BridgeClient.class);
        bridgeAccessEnforcer = mock(BridgeAccessEnforcer.class);
        creditClient = mock(CreditConsumptionClient.class);
        streamOutput = mock(StreamingOutput.class);

        when(streamOutput.getCurrentStreamId()).thenReturn("stream-1");
        // fetchCreditBudget calls creditClient.fetchBalance(userId); a non-null
        // balance lets the dto carry tenantBalance. The exact value doesn't
        // matter for routing - we just want to keep the path realistic.
        when(creditClient.fetchBalance(anyString())).thenReturn(new java.math.BigDecimal("100"));

        service = new ConversationAgentService(
            contextBuilder,
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            creditClient,
            mock(MessageService.class),
            mock(PendingActionService.class),
            mock(ToolResultService.class),
            new ObjectMapper(),
            agentClient,
            mock(StreamStateService.class),
            mock(EventBus.class),
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );

        // bridgeEnabled, bridgeClient, bridgeAccessEnforcer are @Autowired field-
        // injected in production - set them via reflection so we can exercise
        // both bridge and remote branches without booting a Spring context.
        setField(service, "bridgeEnabled", Boolean.TRUE);
        setField(service, "bridgeClient", bridgeClient);
        setField(service, "bridgeAccessEnforcer", bridgeAccessEnforcer);
    }

    @Test
    @DisplayName("Pre-fix repro: chat-header=(claude-code, claude-opus-4-6) + agent=(deepseek, deepseek-chat) → routes REMOTE, not bridge")
    void agentOverrideToDeepSeekRoutesRemoteNotBridge() {
        // Frontend chat header had (claude-code, claude-opus-4-6) selected.
        // This is what reaches the request - the user's pick is not wrong, it's
        // just not the source of truth when an agent is attached.
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setModel("claude-opus-4-6");
        request.setProvider("claude-code");
        request.setAgentId("agent-deepseek");
        request.setMessage("hello");

        // AgentContextBuilder applies the agent override and returns a context
        // whose (provider, model) is the agent's, not the chat header's. The
        // production log at line ~354 of AgentContextBuilder logs this as
        // "Using agent model: deepseek / deepseek-chat (overriding request:
        // claude-code / claude-opus-4-6)" - we simulate the post-override
        // tuple by returning a context with deepseek directly.
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .enabledModules(java.util.List.of("table", "agent"))
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        // Mock a successful remote response so the after-dispatch logic doesn't NPE.
        AgentExecutionResponseDto remoteResponse = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null, 50L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(remoteResponse);

        service.executeStreaming(request, streamOutput, "conv-1");

        // Capture the DTO that was sent to agent-service: it MUST carry the
        // agent-resolved (provider, model), not the chat-header pick.
        ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor =
            ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(dtoCaptor.capture());
        AgentExecutionRequestDto sent = dtoCaptor.getValue();
        assertThat(sent.provider()).isEqualTo("deepseek");
        assertThat(sent.model()).isEqualTo("deepseek-chat");
        // The bridge dispatch ignores the explicit tool list and rebuilds its MCP tool set from
        // enabledModules; the DTO MUST carry the context's set (a null is the async-bug shape).
        assertThat(sent.enabledModules())
            .as("bridge DTO must carry the context's enabledModules so the CLI scopes tool schemas")
            .containsExactlyInAnyOrder("table", "agent");

        // The bridge client must NEVER be invoked - this is the regression
        // guard. Pre-fix, bridgeClient.executeViaBridge was called with
        // model=deepseek-chat, the Claude CLI emitted its "model not found"
        // line, and that string ended up as the assistant message in
        // conversation dfadb561.
        verify(bridgeClient, never()).executeViaBridge(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Positive: chat-header=(claude-code, claude-opus-4-7) with no agent override → routes BRIDGE")
    void unattachedClaudeCodeRequestRoutesToBridge() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setModel("claude-opus-4-7");
        request.setProvider("claude-code");
        request.setMessage("hello");

        // No agent attached - AgentContextBuilder returns a context whose
        // (provider, model) is the chat-header pick unchanged.
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("claude-code")
            .model("claude-opus-4-7")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .enabledModules(java.util.List.of("table", "agent"))
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null, 50L, "claude-code", "claude-opus-4-7",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

        service.executeStreaming(request, streamOutput, "conv-1");

        verify(bridgeClient).executeViaBridge(any(AgentExecutionRequestDto.class));
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("executeSync symmetry: webhook/schedule with agent override drives bridge decision the same way")
    void executeSyncRespectsAgentOverrideForBridgeDecision() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setModel("claude-opus-4-6");
        request.setProvider("claude-code");
        request.setAgentId("agent-deepseek");
        request.setSource("webhook");
        request.setMessage("hello");

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-chat")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .enabledModules(java.util.List.of("table", "agent"))
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto response = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null, 50L, "deepseek", "deepseek-chat",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(response);

        Map<String, Object> result = service.executeSync(request, "conv-1");

        assertThat(result.get("success")).isEqualTo(true);
        verify(agentClient).executeAgent(any(AgentExecutionRequestDto.class));
        verify(bridgeClient, never()).executeViaBridge(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Inverse: chat-header=(deepseek, deepseek-chat) + agent=(claude-code, claude-opus-4-7) → routes BRIDGE (agent override wins both ways)")
    void agentOverrideToClaudeCodeRoutesBridge() {
        ChatRequest request = new ChatRequest();
        request.setUserId("user-1");
        request.setModel("deepseek-chat");
        request.setProvider("deepseek");
        request.setAgentId("agent-claude");
        request.setMessage("hello");

        // Agent attached and configured for claude-code; AgentContextBuilder
        // overrides the chat-header deepseek pick.
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("claude-code")
            .model("claude-opus-4-7")
            .userPrompt("hello")
            .systemPrompt("you are helpful")
            .conversationHistory(Collections.emptyList())
            .tools(Collections.emptyList())
            .enabledModules(java.util.List.of("table", "agent"))
            .tenantId("user-1")
            .build();
        when(contextBuilder.build(any(ChatRequest.class), anyString(), anyString(), any())).thenReturn(context);

        AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
            true, "ok", "ok", Collections.emptyList(), 1,
            Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
            null, 50L, "claude-code", "claude-opus-4-7",
            Collections.emptyList(), "end_turn",
            Map.of(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            null, null, null
        );
        when(bridgeClient.executeViaBridge(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

        service.executeStreaming(request, streamOutput, "conv-1");

        ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor =
            ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeClient).executeViaBridge(dtoCaptor.capture());
        AgentExecutionRequestDto sent = dtoCaptor.getValue();
        assertThat(sent.provider()).isEqualTo("claude-code");
        assertThat(sent.model()).isEqualTo("claude-opus-4-7");
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
        // Bridge subscription gate must be enforced against the RESOLVED
        // provider (claude-code), not the chat-header request provider
        // (deepseek). Otherwise we'd skip the gate entirely (deepseek isn't
        // a bridge slug) and let unsubscribed users hit the Claude CLI.
        verify(bridgeAccessEnforcer).enforce(eq("user-1"), any(), eq("claude-code"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ConversationAgentService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
