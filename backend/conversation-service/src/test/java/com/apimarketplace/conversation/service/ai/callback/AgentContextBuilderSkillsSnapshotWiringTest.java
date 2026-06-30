package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.AttachmentService;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentConfig;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentSkillsSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.FolderSummary;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.SkillSummary;
import com.apimarketplace.conversation.service.ai.CoreToolsProvider;
import com.apimarketplace.conversation.service.ai.SkillsSnapshotService;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.ConversationMeta;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowBuilderSessionContext;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowContext;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 1a.2 - pin the skills-snapshot wiring inside {@code AgentContextBuilder}.
 * Two behaviors are locked:
 * <ul>
 *   <li><b>Cache hit</b> - a fresh snapshot is returned; the HTTP fetch
 *   ({@code getAgentSkillsSummary}) is skipped entirely and the cached
 *   bytes are spliced into the system prompt verbatim.</li>
 *   <li><b>Cache miss</b> - the HTTP fetch runs, the rendered block is
 *   persisted via {@code save} with the expected derivation key.</li>
 * </ul>
 */
@DisplayName("AgentContextBuilder - skills snapshot wiring (Stage 1a.2)")
@ExtendWith(MockitoExtension.class)
class AgentContextBuilderSkillsSnapshotWiringTest {

    @Mock private ConversationHistoryConverter historyConverter;
    @Mock private CoreToolsProvider coreToolsProvider;
    @Mock private WorkflowContextProvider workflowContextProvider;
    @Mock private AgentConfigProvider agentConfigProvider;
    @Mock private ServiceApprovalService serviceApprovalService;
    @Mock private AttachmentService attachmentService;
    @Mock private SkillsSnapshotService skillsSnapshotService;
    @Mock private com.apimarketplace.conversation.service.ai.ThinkingLevelPinningService thinkingLevelPinningService;
    @Mock private com.apimarketplace.conversation.service.ai.schema.ModelTierMapper modelTierMapper;
    @Mock private com.apimarketplace.conversation.service.ai.schema.SchemaSlimExclusionPolicy schemaSlimExclusionPolicy;

    @InjectMocks
    private AgentContextBuilder agentContextBuilder;

    @BeforeEach
    void setDefaults() throws Exception {
        Field maxIterationsField = AgentContextBuilder.class.getDeclaredField("defaultMaxIterations");
        maxIterationsField.setAccessible(true);
        maxIterationsField.setInt(agentContextBuilder, 10);

        Field customPromptField = AgentContextBuilder.class.getDeclaredField("customSystemPrompt");
        customPromptField.setAccessible(true);
        customPromptField.set(agentContextBuilder, "");

        Field timeoutField = AgentContextBuilder.class.getDeclaredField("defaultExecutionTimeoutSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setInt(agentContextBuilder, 3600);
    }

    private ChatRequest createAgentRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");
        request.setAgentId("agent-1");
        return request;
    }

    private void wireDefaultMocks() {
        when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.<Message>of());
        when(historyConverter.isNewConversation(any())).thenReturn(true);
        when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
        when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
        when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                .thenReturn(WorkflowBuilderSessionContext.empty());
        lenient().when(coreToolsProvider.getCoreTools(anySet(), eq(true))).thenReturn(List.of());
    }

    @Test
    @DisplayName("cache hit: skills snapshot returned from DB, HTTP fetch not called")
    void cacheHitSkipsHttpFetch() {
        ChatRequest request = createAgentRequest();
        AgentConfig agentConfig = new AgentConfig(
                "agent-1", "Agent", null, null, null,
                null, null, null, null, null, null,
                null, null, null);

        when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
        wireDefaultMocks();

        String cachedBlock = "[SKILLS]\n- cached-skill [sk-1]";
        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString()))
                .thenReturn(Optional.of(cachedBlock));

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains(cachedBlock);
        verify(agentConfigProvider, never()).getAgentSkillsSummary(anyString(), anyString());
        verify(skillsSnapshotService, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("cache miss: HTTP fetch runs, rendered block is saved with derivation key")
    void cacheMissFetchesAndSaves() {
        ChatRequest request = createAgentRequest();
        AgentConfig agentConfig = new AgentConfig(
                "agent-1", "Agent", null, null, null,
                null, null, null, null, null, null,
                null, null, null);

        when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
        wireDefaultMocks();

        // Cache miss - service returns empty.
        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString())).thenReturn(Optional.empty());

        AgentSkillsSummary summary = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-1", "Alpha", "desc", null, true, false)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getAgentSkillsSummary("agent-1", "user-1")).thenReturn(summary);

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains("[SKILLS]").contains("Alpha").contains("[sk-1]");
        String expectedKey = SkillsSnapshotService.deriveKey("agent-1", List.of());
        verify(skillsSnapshotService).save(eq("conv-1"), eq(expectedKey),
                org.mockito.ArgumentMatchers.contains("[SKILLS]"));
    }

    @Test
    @DisplayName("dual-path turn: agent-skills and request-skills both cache-miss, each saves under its own key without clobber")
    void dualPathSavesBothKeysIndependently() {
        ChatRequest request = createAgentRequest();
        request.setDefaultSkillIds(List.of("sk-user-1", "sk-user-2"));
        AgentConfig agentConfig = new AgentConfig(
                "agent-1", "Agent", null, null, null,
                null, null, null, null, null, null,
                null, null, null);

        when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
        wireDefaultMocks();

        // Both paths miss → both render → both save.
        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString())).thenReturn(Optional.empty());

        AgentSkillsSummary agentSummary = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-agent-1", "AgentSkill", "desc", null, true, false)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getAgentSkillsSummary("agent-1", "user-1")).thenReturn(agentSummary);

        AgentSkillsSummary userSummary = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-user-1", "UserSkill", "desc", null, true, false)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getSkillsSummaryByIds(List.of("sk-user-1", "sk-user-2"), "user-1"))
                .thenReturn(userSummary);

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        // Both blocks must be present in the system prompt.
        assertThat(ctx.systemPrompt()).contains("AgentSkill").contains("UserSkill");

        // Each path saves under its own derivation key - the keyed merge in the service
        // preserves both entries (no cross-path clobber).
        String agentKey = SkillsSnapshotService.deriveKey("agent-1", List.of());
        String requestKey = SkillsSnapshotService.deriveKey(null, List.of("sk-user-1", "sk-user-2"));
        verify(skillsSnapshotService).save(eq("conv-1"), eq(agentKey),
                org.mockito.ArgumentMatchers.contains("AgentSkill"));
        verify(skillsSnapshotService).save(eq("conv-1"), eq(requestKey),
                org.mockito.ArgumentMatchers.contains("UserSkill"));
    }

    @Test
    @DisplayName("V276 fallback cache hit: snapshot found under the sentinel key, HTTP fetch is skipped")
    void v276FallbackCacheHitSkipsHttpFetch() {
        // Pin the perf contract - the V276 fallback path uses the same
        // snapshot mechanism as the agent-skills and user-skills branches.
        // Without this, every turn would pay a roundtrip to agent-service.
        ChatRequest request = new ChatRequest();
        request.setMessage("Hi");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");

        wireDefaultMocks();
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());

        String cachedBlock = "[SKILLS]\n- cached-effective-default";
        String expectedKey = SkillsSnapshotService.deriveKey("__v276_default_active__", List.of());
        when(skillsSnapshotService.loadIfFresh("conv-1", expectedKey)).thenReturn(Optional.of(cachedBlock));

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains(cachedBlock);
        verify(agentConfigProvider, never()).getDefaultActiveSkillsSummary(anyString());
        verify(skillsSnapshotService, never()).save(eq("conv-1"), eq(expectedKey), anyString());
    }

    @Test
    @DisplayName("V275/V276 fallback: general chat with no defaultSkillIds and no agent calls getDefaultActiveSkillsSummary and injects the resolved set")
    void v276FallbackFiresWhenNoDefaultSkillIdsAndNoAgent() {
        // General chat - no agentId, no defaultSkillIds. This is the path the
        // legacy localStorage seed used to handle; V276 moved the resolution
        // to the backend.
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");

        wireDefaultMocks();
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());

        // Cache miss path - service returns empty for the V276 sentinel key.
        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString())).thenReturn(Optional.empty());

        AgentSkillsSummary effectiveDefaults = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-def-1", "PersistedDefault", "desc", null, true, true)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getDefaultActiveSkillsSummary("user-1")).thenReturn(effectiveDefaults);

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt())
                .as("PersistedDefault must appear in the system prompt because the V276 fallback fired")
                .contains("PersistedDefault");
        verify(agentConfigProvider).getDefaultActiveSkillsSummary("user-1");
        verify(agentConfigProvider, never()).getAgentSkillsSummary(anyString(), anyString());
        verify(agentConfigProvider, never()).getSkillsSummaryByIds(any(), anyString());
        // On cache miss, the rendered block IS persisted under the sentinel key
        // so subsequent turns hit the cached path.
        String expectedKey = SkillsSnapshotService.deriveKey("__v276_default_active__", List.of());
        verify(skillsSnapshotService).save(eq("conv-1"), eq(expectedKey),
                org.mockito.ArgumentMatchers.contains("PersistedDefault"));
    }

    @Test
    @DisplayName("V276 fallback DOES NOT fire when client passes defaultSkillIds (per-conv override wins)")
    void v276FallbackSuppressedByPerConvOverride() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");
        // Per-conversation explicit list - should be used verbatim, V276 must
        // stay out of the way.
        request.setDefaultSkillIds(List.of("sk-user-explicit"));

        wireDefaultMocks();
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());

        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString())).thenReturn(Optional.empty());
        AgentSkillsSummary explicit = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-user-explicit", "ExplicitSkill", "desc", null, true, false)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getSkillsSummaryByIds(List.of("sk-user-explicit"), "user-1"))
                .thenReturn(explicit);

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains("ExplicitSkill");
        // The fallback method must NOT be reached when the client opted in
        // with an explicit list - preserves the "reactivate just for this
        // conversation" UX without double-injecting.
        verify(agentConfigProvider, never()).getDefaultActiveSkillsSummary(anyString());
    }

    @Test
    @DisplayName("regression: persisted conversation chatConfig.defaultSkillIds drives skills when request omits defaultSkillIds")
    void persistedConversationDefaultSkillIdsDrivePromptWhenRequestOmitsField() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");

        wireDefaultMocks();
        when(workflowContextProvider.getConversationMeta("conv-1"))
                .thenReturn(new ConversationMeta(null, null, java.util.Map.of(
                        "defaultSkillIds", List.of("sk-db-explicit"))));
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());
        when(skillsSnapshotService.loadIfFresh(eq("conv-1"), anyString())).thenReturn(Optional.empty());
        AgentSkillsSummary explicit = new AgentSkillsSummary(
                List.of(new SkillSummary("sk-db-explicit", "DbPersistedSkill", "desc", null, true, false)),
                List.<FolderSummary>of());
        when(agentConfigProvider.getSkillsSummaryByIds(List.of("sk-db-explicit"), "user-1"))
                .thenReturn(explicit);

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains("DbPersistedSkill");
        verify(agentConfigProvider).getSkillsSummaryByIds(List.of("sk-db-explicit"), "user-1");
        verify(agentConfigProvider, never()).getDefaultActiveSkillsSummary(anyString());
    }

    @Test
    @DisplayName("regression: persisted empty chatConfig.defaultSkillIds suppresses default-active fallback")
    void persistedEmptyDefaultSkillIdsSuppressFallback() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");

        wireDefaultMocks();
        when(workflowContextProvider.getConversationMeta("conv-1"))
                .thenReturn(new ConversationMeta(null, null, java.util.Map.of(
                        "defaultSkillIds", List.of())));
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).doesNotContain("[SKILLS]");
        verify(agentConfigProvider, never()).getSkillsSummaryByIds(any(), anyString());
        verify(agentConfigProvider, never()).getDefaultActiveSkillsSummary(anyString());
    }

    @Test
    @DisplayName("request-skills cache hit: HTTP fetch not called for user skill ids")
    void requestSkillsCacheHitSkipsHttpFetch() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");
        request.setDefaultSkillIds(List.of("sk-user-1", "sk-user-2"));

        wireDefaultMocks();
        when(coreToolsProvider.getCoreTools(eq(true))).thenReturn(List.of());

        String cachedBlock = "[SKILLS]\n- cached-user-skill";
        String expectedKey = SkillsSnapshotService.deriveKey(null, List.of("sk-user-1", "sk-user-2"));
        when(skillsSnapshotService.loadIfFresh("conv-1", expectedKey)).thenReturn(Optional.of(cachedBlock));

        AgentLoopContext ctx = agentContextBuilder.build(request, "conv-1", null);

        assertThat(ctx.systemPrompt()).contains(cachedBlock);
        verify(agentConfigProvider, never()).getSkillsSummaryByIds(any(), anyString());
    }
}
