package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-state contract: the compaction orchestrator must fire for ANY {@link Conversation},
 * regardless of which surface owns it. A conversation with {@code agentId=null} (pure chat),
 * {@code agentId=X} (standalone agent or sub-agent), or {@code agentId=X + workflowId=Y}
 * (workflow AgentNode turn) all go through the exact same pipeline with the same gate,
 * same envelope shape, same persistence column.
 *
 * <p>Locks in the invariant that after centralisation, no surface-specific branching exists
 * inside the orchestrator: it is driven purely by conversationId + the messages attached
 * to that row.</p>
 */
@DisplayName("Compaction - agnostic to source surface (chat / agent / workflow-agent / sub-agent)")
class CompactionAgnosticSurfaceTest {

    private static final String CONV_CHAT = "conv-chat";
    private static final String CONV_AGENT = "conv-standalone-agent";
    private static final String CONV_WORKFLOW = "conv-workflow-agent";
    private static final String CONV_SUBAGENT = "conv-sub-agent";
    private static final String TENANT = "tenant-42";

    private MessageRepository messageRepo;
    private ConversationRepository conversationRepo;
    private ColdSummarizerService summarizer;
    private HttpLlmJsonInvoker httpInvoker;
    private CompactionDefaultsConfig config;
    private StreamPubSubService streamPubSub;
    private AgentConfigProvider agentConfigProvider;
    private SimpleMeterRegistry meterRegistry;

    private ChatCompactionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        messageRepo = mock(MessageRepository.class);
        conversationRepo = mock(ConversationRepository.class);
        summarizer = mock(ColdSummarizerService.class);
        httpInvoker = mock(HttpLlmJsonInvoker.class);
        streamPubSub = mock(StreamPubSubService.class);
        when(streamPubSub.publishCompactionDone(anyString(), anyString(), anyInt(), anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());
        config = new CompactionDefaultsConfig();
        config.setEnabled(true);
        config.setHotWarmTurnWindow(5);
        config.setCadenceTurns(3);
        agentConfigProvider = mock(AgentConfigProvider.class);
        when(agentConfigProvider.getCompactionOverride(anyString(), any()))
                .thenReturn(AgentConfigProvider.CompactionOverride.NONE);
        meterRegistry = new SimpleMeterRegistry();
        orchestrator = new ChatCompactionOrchestrator(
                messageRepo, conversationRepo, summarizer, httpInvoker, config,
                streamPubSub, agentConfigProvider, meterRegistry);

        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.SkippedGate());
    }

    @Test
    @DisplayName("Pure chat conversation (agentId=null, workflowId=null) triggers the summariser")
    void chatSurface_firesSamePipeline() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_CHAT))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV_CHAT))
                .thenReturn(Optional.of(conversationWith(null, null, null)));

        orchestrator.afterTurn(CONV_CHAT, "anthropic", "claude-sonnet-4-6", TENANT, null);

        SummarizeRequest req = captureRequest();
        assertThat(req.conversationId()).isEqualTo(CONV_CHAT);
        assertThat(req.coldTurns()).hasSize(3);
    }

    @Test
    @DisplayName("Standalone agent conversation (agentId set, workflowId=null) triggers the same pipeline")
    void standaloneAgentSurface_firesSamePipeline() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_AGENT))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV_AGENT))
                .thenReturn(Optional.of(conversationWith("agent-42", null, null)));

        orchestrator.afterTurn(CONV_AGENT, "anthropic", "claude-sonnet-4-6", TENANT, null);

        SummarizeRequest req = captureRequest();
        assertThat(req.conversationId()).isEqualTo(CONV_AGENT);
        assertThat(req.coldTurns()).hasSize(3);
    }

    @Test
    @DisplayName("Workflow AgentNode conversation (agentId set, workflowId set) triggers the same pipeline")
    void workflowAgentSurface_firesSamePipeline() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_WORKFLOW))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV_WORKFLOW))
                .thenReturn(Optional.of(conversationWith("agent-42", "wf-run-1", null)));

        orchestrator.afterTurn(CONV_WORKFLOW, "anthropic", "claude-sonnet-4-6", TENANT, null);

        SummarizeRequest req = captureRequest();
        assertThat(req.conversationId()).isEqualTo(CONV_WORKFLOW);
        assertThat(req.coldTurns()).hasSize(3);
    }

    @Test
    @DisplayName("Sub-agent conversation (parentConversationId set) triggers the same pipeline - independent compaction state")
    void subAgentSurface_firesSamePipeline_independentState() {
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV_SUBAGENT))
                .thenReturn(messages(8));
        when(conversationRepo.findById(CONV_SUBAGENT))
                .thenReturn(Optional.of(conversationWith("sub-agent-77", null, "conv-parent")));

        orchestrator.afterTurn(CONV_SUBAGENT, "anthropic", "claude-sonnet-4-6", TENANT, null);

        SummarizeRequest req = captureRequest();
        assertThat(req.conversationId()).isEqualTo(CONV_SUBAGENT);
        assertThat(req.coldTurns()).hasSize(3);
    }

    @Test
    @DisplayName("Envelope shape is identical across surfaces - no surface label leaks into SummarizeRequest")
    void envelopeShape_isIdenticalAcrossSurfaces() {
        List<SummarizeRequest> captured = new ArrayList<>();

        for (String convId : List.of(CONV_CHAT, CONV_AGENT, CONV_WORKFLOW, CONV_SUBAGENT)) {
            when(messageRepo.findByConversationIdOrderByCreatedAtAsc(convId))
                    .thenReturn(messages(8));
            when(conversationRepo.findById(convId))
                    .thenReturn(Optional.of(conversationWith(
                            convId.equals(CONV_CHAT) ? null : "agent-x",
                            convId.equals(CONV_WORKFLOW) ? "wf-1" : null,
                            convId.equals(CONV_SUBAGENT) ? "parent" : null)));
        }

        orchestrator.afterTurn(CONV_CHAT, "anthropic", "claude-sonnet-4-6", TENANT, null);
        orchestrator.afterTurn(CONV_AGENT, "anthropic", "claude-sonnet-4-6", TENANT, null);
        orchestrator.afterTurn(CONV_WORKFLOW, "anthropic", "claude-sonnet-4-6", TENANT, null);
        orchestrator.afterTurn(CONV_SUBAGENT, "anthropic", "claude-sonnet-4-6", TENANT, null);

        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer, org.mockito.Mockito.times(4))
                .summarize(captor.capture(), any(LlmJsonInvoker.class));
        captured.addAll(captor.getAllValues());

        assertThat(captured).allSatisfy(r -> {
            assertThat(r.coldTurns()).hasSize(3);
            assertThat(r.modelColdCapTokens()).isEqualTo(4000);
            assertThat(r.providerName()).isEqualTo("anthropic");
            assertThat(r.modelName()).isEqualTo("claude-haiku-4-5");
            assertThat(r.cadenceTurns()).isEqualTo(3);
            assertThat(r.turnsCovered()).containsExactly(0, 1, 2);
        });
    }

    private SummarizeRequest captureRequest() {
        ArgumentCaptor<SummarizeRequest> captor = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(captor.capture(), any(LlmJsonInvoker.class));
        return captor.getValue();
    }

    private static List<Message> messages(int n) {
        List<Message> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Message m = new Message();
            m.setRole(Message.MessageRole.USER);
            m.setContent("msg-" + i);
            m.setCreatedAt(LocalDateTime.now());
            out.add(m);
        }
        return out;
    }

    private static Conversation conversationWith(String agentId, String workflowId, String parentConversationId) {
        Conversation c = new Conversation();
        c.setAgentId(agentId);
        c.setWorkflowId(workflowId);
        c.setParentConversationId(parentConversationId);
        c.setSummaryCold(null);
        return c;
    }
}
