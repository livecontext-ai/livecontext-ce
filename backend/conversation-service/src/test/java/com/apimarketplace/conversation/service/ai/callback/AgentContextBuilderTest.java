package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.SystemBlock;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.service.AttachmentService;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentConfig;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.ToolsConfig;
import com.apimarketplace.conversation.service.ai.CoreToolsProvider;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.ConversationMeta;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowBuilderSessionContext;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowContext;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentContextBuilder")
@ExtendWith(MockitoExtension.class)
class AgentContextBuilderTest {

    @Mock
    private ConversationHistoryConverter historyConverter;

    @Mock
    private CoreToolsProvider coreToolsProvider;

    @Mock
    private WorkflowContextProvider workflowContextProvider;

    @Mock
    private AgentConfigProvider agentConfigProvider;

    @Mock
    private ServiceApprovalService serviceApprovalService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private com.apimarketplace.conversation.service.ai.SkillsSnapshotService skillsSnapshotService;

    @Mock
    private com.apimarketplace.conversation.service.ai.ThinkingLevelPinningService thinkingLevelPinningService;

    @Mock
    private com.apimarketplace.conversation.service.ai.schema.ModelTierMapper modelTierMapper;

    @Mock
    private com.apimarketplace.conversation.service.ai.schema.SchemaSlimExclusionPolicy schemaSlimExclusionPolicy;

    @InjectMocks
    private AgentContextBuilder agentContextBuilder;

    private ChatRequest createRequest(String message, String model, String provider) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setModel(model);
        request.setProvider(provider);
        request.setUserId("user-1");
        request.setConversationId("conv-1");
        return request;
    }

    @BeforeEach
    void setUp() throws Exception {
        // Set default max iterations via reflection (it's a @Value field)
        Field maxIterationsField = AgentContextBuilder.class.getDeclaredField("defaultMaxIterations");
        maxIterationsField.setAccessible(true);
        maxIterationsField.setInt(agentContextBuilder, 10);

        Field customPromptField = AgentContextBuilder.class.getDeclaredField("customSystemPrompt");
        customPromptField.setAccessible(true);
        customPromptField.set(agentContextBuilder, "");

        Field timeoutField = AgentContextBuilder.class.getDeclaredField("defaultExecutionTimeoutSeconds");
        timeoutField.setAccessible(true);
        timeoutField.setInt(agentContextBuilder, 3600);

        Field maxTokensField = AgentContextBuilder.class.getDeclaredField("defaultMaxTokens");
        maxTokensField.setAccessible(true);
        maxTokensField.setInt(agentContextBuilder, 16000);
    }

    @Nested
    @DisplayName("build - basic")
    class BuildBasic {

        @Test
        @DisplayName("should build context for new conversation")
        void shouldBuildForNewConversation() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.provider()).isEqualTo("openai");
            assertThat(context.model()).isEqualTo("gpt-4");
            assertThat(context.userPrompt()).isEqualTo("Hello");
            assertThat(context.tenantId()).isEqualTo("user-1");
            assertThat(context.maxIterations()).isEqualTo(10);
            // Empty core-tools list triggers the degraded fallback: allow RRF
            // discovery so the agent isn't stranded with zero tools.
            assertThat(context.autoDiscoverTools()).isTrue();
        }

        @Test
        @DisplayName("non-empty core-tools list disables RRF discovery - meta-tools cover the catalog")
        void chatWithMetaToolsDoesNotAutoDiscover() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            ToolDefinition metaTool = ToolDefinition.builder()
                    .id("catalog").name("catalog").description("catalog meta-tool")
                    .build();

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of(metaTool));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // Chat must NOT trigger AgentLoopService RRF discovery whenever
            // meta-tools are present - discovered tools would bypass SchemaSlimmer
            // and arrive FULL, leaking ~3-4 KB of tool JSON per turn.
            assertThat(context.autoDiscoverTools()).isFalse();
            assertThat(context.tools()).hasSize(1);
        }

        @Test
        @DisplayName("should build context for follow-up message")
        void shouldBuildForFollowUp() {
            ChatRequest request = createRequest("How are you?", "gpt-4", "openai");
            Message prevMsg = Message.builder().role(Message.Role.USER).content("Hello").build();

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of(prevMsg));
            when(historyConverter.isNewConversation(any())).thenReturn(false);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(false)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.conversationHistory()).hasSize(1);
            assertThat(context.systemPrompt()).contains("CONTINUATION");
        }
    }

    @Nested
    @DisplayName("build - agent config override")
    class BuildAgentConfigOverride {

        @Test
        @DisplayName("should override model and provider from agent config")
        void shouldOverrideModelAndProvider() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");

            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "My Agent", "Custom prompt", "anthropic", "claude-3",
                    0.5, 4000, 15, null, null, null,
                    null, null, null);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            // Agent without toolsConfig uses getCoreTools(Set, boolean) via buildAgentDefault().
            // Agent-bound conversations never request set_conversation_title, so boolean is false.
            when(coreToolsProvider.getCoreTools(anySet(), eq(false))).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.provider()).isEqualTo("anthropic");
            assertThat(context.model()).isEqualTo("claude-3");
            assertThat(context.temperature()).isEqualTo(0.5);
            assertThat(context.maxTokens()).isEqualTo(4000);
            assertThat(context.maxIterations()).isEqualTo(15);
            assertThat(context.systemPrompt()).contains("Custom prompt");
        }

        @Test
        @DisplayName("resolves agent config with organization context for org-scoped chat")
        void resolvesAgentConfigWithOrganizationContext() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");
            request.setOrgId("org-42");
            request.setOrgRole("MEMBER");

            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "My Agent", "Custom prompt", "anthropic", "claude-3",
                    0.5, 4000, 2, null, null, null,
                    null, null, null);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", "org-42", "MEMBER")).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), eq(false))).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.maxIterations()).isEqualTo(2);
            verify(agentConfigProvider).getAgentConfig("agent-1", "user-1", "org-42", "MEMBER");
            verify(agentConfigProvider, never()).getAgentConfig("agent-1", "user-1");
        }

        @Test
        @DisplayName("should not override when agent has no model")
        void shouldNotOverrideWhenAgentHasNoModel() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");

            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "My Agent", null, null, null,
                    null, null, null, null, null, null,
                    null, null, null);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            // Agent without toolsConfig uses getCoreTools(Set, boolean) via buildAgentDefault().
            // Agent-bound conversations never request set_conversation_title, so boolean is false.
            when(coreToolsProvider.getCoreTools(anySet(), eq(false))).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.provider()).isEqualTo("openai");
            assertThat(context.model()).isEqualTo("gpt-4");
        }
    }

    @Nested
    @DisplayName("build - reasoning effort precedence")
    class BuildReasoningEffort {

        private void stubCommon(AgentConfig agentConfig) {
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), eq(false))).thenReturn(List.of());
        }

        // 15-arg AgentConfig: trailing field is reasoningEffort. Bridge model so the
        // control/precedence path is exercised (provider claude-code, model claude-opus-4-7).
        private AgentConfig agentConfigWithEffort(String effort) {
            return new AgentConfig("agent-1", "Agent", null, "claude-code", "claude-opus-4-7",
                    null, null, null, null, null, null, null, null, null, effort);
        }

        @Test
        @DisplayName("per-conversation request override wins over the agent setting")
        void requestOverrideWins() {
            ChatRequest request = createRequest("Hi", "claude-opus-4-7", "claude-code");
            request.setAgentId("agent-1");
            request.setReasoningEffort("low");
            stubCommon(agentConfigWithEffort("high"));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.reasoningEffort()).isEqualTo("low");
        }

        @Test
        @DisplayName("agent setting applies when there is no request override")
        void agentSettingWhenNoOverride() {
            ChatRequest request = createRequest("Hi", "claude-opus-4-7", "claude-code");
            request.setAgentId("agent-1");
            stubCommon(agentConfigWithEffort("high"));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.reasoningEffort()).isEqualTo("high");
        }

        @Test
        @DisplayName("per-model default applies when request and agent are both blank")
        void modelDefaultWhenRequestAndAgentBlank() {
            ChatRequest request = createRequest("Hi", "claude-opus-4-7", "claude-code");
            request.setAgentId("agent-1");
            stubCommon(agentConfigWithEffort(null));
            when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                    new com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel(
                            "claude-code", "claude-opus-4-7", "top", 1, "medium")));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.reasoningEffort()).isEqualTo("medium");
        }

        @Test
        @DisplayName("null when no override, no agent setting, and no model default")
        void nullWhenAllAbsent() {
            ChatRequest request = createRequest("Hi", "claude-opus-4-7", "claude-code");
            request.setAgentId("agent-1");
            stubCommon(agentConfigWithEffort(null));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.reasoningEffort()).isNull();
        }
    }

    @Nested
    @DisplayName("build - tools config restrictions")
    class BuildToolsConfigRestrictions {

        @Test
        @DisplayName("should filter workflow tools when workflows=none")
        void shouldFilterWorkflowToolsWhenNone() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");

            ToolsConfig tc = new ToolsConfig("all", List.of(), List.of(), null, null, null, null, null, null, null, null, null, null, null);
            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "Agent", null, null, null,
                    null, null, null, tc, null, null,
                    null, null, null);

            // Create tools list including workflow tools
            ToolDefinition workflowTool = ToolDefinition.builder()
                    .name("workflow").description("Workflow tool").build();
            ToolDefinition searchTool = ToolDefinition.builder()
                    .name("catalog").description("Catalog tool").build();

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            // With toolsConfig present, getCoreTools(Set<String>, boolean) is called with module-filtered names
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean())).thenReturn(List.of(searchTool));
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // workflow tool is excluded because workflows=none (empty list in toolsConfig)
            assertThat(context.tools()).hasSize(1);
            assertThat(context.tools().get(0).name()).isEqualTo("catalog");
        }

        @Test
        @DisplayName("should add tool restrictions to credentials when mode=none")
        void shouldAddToolRestrictionsToCredentials() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");

            ToolsConfig tc = new ToolsConfig("none", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "Agent", null, null, null,
                    null, null, null, tc, null, null,
                    null, null, null);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            // With toolsConfig present, getCoreTools(Set<String>, boolean) is called
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean())).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.credentials()).containsKey("__allowedToolIds__");
            assertThat(context.systemPrompt()).contains("TOOL RESTRICTIONS");
            // The canonical enabled-module set MUST be carried on the context so the BRIDGE
            // dispatch (ConversationAgentService → CLI) scopes the core tool SCHEMAS too.
            // mode=none drops catalog/MCP but keeps internal tools. Guards AgentContextBuilder's
            // .enabledModules wiring - without it the bridge (claude-code/codex/gemini-cli) would
            // advertise EVERY core schema regardless of mode (the over-billing class the
            // async-queue path shipped with, caught only by e2e). 3 prod paths depend on this:
            // general-chat-bridge, task/reviewer-bridge, schedule/webhook-sync-bridge.
            assertThat(context.enabledModules())
                .as("mode=none must scope bridge tool schemas: internal kept, catalog dropped")
                .contains("table")
                .doesNotContain("catalog");
        }

        @Test
        @DisplayName("mode=off carries an EMPTY enabledModules on the context - a tool-less chat agent advertises ZERO tool schemas")
        void modeOffCarriesEmptyEnabledModules() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");

            ToolsConfig tc = new ToolsConfig("off", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "Agent", null, null, null,
                    null, null, null, tc, null, null,
                    null, null, null);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean())).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // mode=off → the canonical resolver returns an EMPTY module set, so the bridge dispatch
            // (ConversationAgentService → CLI) scopes the core tool schemas to nothing. A
            // reasoning-only chat agent thus advertises zero tool schemas. A broken .enabledModules
            // wiring (null) would NPE here, so this also guards the producer leg for mode=off.
            assertThat(context.enabledModules())
                .as("mode=off must carry an empty enabled-module set (zero tool schemas)")
                .isEmpty();
        }

        // ── grant-driven restrictions, R/W decoupled ───────────────────────────────
        // For every internal resource family the per-family GRANT sentinel
        // (<family>Grant ∈ {none|all|custom}) is the SINGLE authority for the
        // restriction block this builder emits:
        //   none (or absent) → "[X RESTRICTIONS] You do NOT have access"
        //   all              → no "do NOT have access" block (unrestricted)
        //   custom           → "[X RESTRICTIONS] ... limited set of X only"
        // The per-resource R/W access mode (<family>AccessMode ∈ {read|write}) is a
        // SEPARATE axis enforced at the tool layer (ToolAccessControl.checkWriteAccess);
        // it MUST NOT change any of these three prompt outcomes. The 5 per-family tests
        // below assert the full {none, all, custom} × {read, write} matrix per family,
        // superseding the old "write overrides an empty list" coupling that this change
        // removed.

        /** Builds the system prompt for an agent whose only config is {@code tc}. */
        private String systemPromptFor(ToolsConfig tc) {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");
            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "Agent", null, null, null,
                    null, null, null, tc, null, null,
                    null, null, null);
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean())).thenReturn(List.of());
            return agentContextBuilder.build(request, "conv-1", null).systemPrompt();
        }

        /** Builds the runtime credentials map for an agent whose only config is {@code tc}. */
        private Map<String, Object> credentialsFor(ToolsConfig tc) {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-1");
            AgentConfig agentConfig = new AgentConfig(
                    "agent-1", "Agent", null, null, null,
                    null, null, null, tc, null, null,
                    null, null, null);
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(agentConfigProvider.getAgentConfig("agent-1", "user-1", null)).thenReturn(agentConfig);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean())).thenReturn(List.of());
            return agentContextBuilder.build(request, "conv-1", null).credentials();
        }

        @Test
        @DisplayName("emits __fileAccessMode__ into credentials when the agent's fileAccessMode is read (chat-path read-only file enforcement)")
        void emitsFileAccessModeCredential() {
            // fileAccessMode is the LAST canonical component; set it to 'read'.
            ToolsConfig tc = new ToolsConfig("all", List.of(), null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, "read");
            assertThat(credentialsFor(tc)).containsEntry("__fileAccessMode__", "read");
        }

        @Test
        @DisplayName("omits __fileAccessMode__ when the agent has no fileAccessMode (default write - no credential, full access)")
        void omitsFileAccessModeWhenAbsent() {
            ToolsConfig tc = new ToolsConfig("all", List.of(), null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
            assertThat(credentialsFor(tc)).doesNotContainKey("__fileAccessMode__");
        }

        @Test
        @DisplayName("emits __<family>AccessMode__ for EVERY grant family's read mode (chat-path per-resource read/write enforcement)")
        void emitsAccessModeForEachGrantFamily() {
            // Each grant family's read mode must reach credentials under its namespaced key so the
            // tool modules' checkWriteAccess gate fires on the chat path (the hop is hardcoded by
            // name per family - a refactor could silently drop one; this pins all five).
            for (String[] fam : List.of(
                    new String[]{"tables", "__tableAccessMode__"},
                    new String[]{"workflows", "__workflowAccessMode__"},
                    new String[]{"interfaces", "__interfaceAccessMode__"},
                    new String[]{"agents", "__agentAccessMode__"},
                    new String[]{"applications", "__applicationAccessMode__"})) {
                assertThat(credentialsFor(singleFamily(fam[0], "all", "read")))
                        .as("family %s must emit %s=read", fam[0], fam[1])
                        .containsEntry(fam[1], "read");
            }
        }

        /**
         * Build a ToolsConfig that scopes exactly ONE resource family via the 20-arg
         * canonical ctor: {@code grant} drives the named {@code family} (with a single-id
         * custom payload when {@code grant=="custom"}), and {@code accessMode} sets only
         * that family's R/W access mode. Every other family is left with a {@code null}
         * grant (⇒ "none"/no-block in the prompt unless explicitly named) so each test
         * asserts in isolation. {@code mode="all"} keeps the external-tool block silent.
         */
        private ToolsConfig singleFamily(String family, String grant, String accessMode) {
            List<String> customPayload = "custom".equals(grant) ? List.of(family + "-1") : List.of();
            List<String> workflows = "workflows".equals(family) ? customPayload : List.of();
            List<String> applications = "applications".equals(family) ? customPayload : List.of();
            List<String> tables = "tables".equals(family) ? customPayload : List.of();
            List<String> interfaces = "interfaces".equals(family) ? customPayload : List.of();
            List<String> agents = "agents".equals(family) ? customPayload : List.of();
            return new ToolsConfig(
                    "all", List.of(), workflows, applications, tables, interfaces, agents, null,
                    "tables".equals(family) ? accessMode : null,        // tableAccessMode
                    "workflows".equals(family) ? accessMode : null,     // workflowAccessMode
                    "interfaces".equals(family) ? accessMode : null,    // interfaceAccessMode
                    "agents".equals(family) ? accessMode : null,        // agentAccessMode
                    "applications".equals(family) ? accessMode : null,  // applicationAccessMode
                    null,                                               // skillAccessMode
                    null,                                               // files
                    "workflows".equals(family) ? grant : null,          // workflowsGrant
                    "tables".equals(family) ? grant : null,             // tablesGrant
                    "interfaces".equals(family) ? grant : null,         // interfacesGrant
                    "agents".equals(family) ? grant : null,             // agentsGrant
                    "applications".equals(family) ? grant : null,       // applicationsGrant
                    null);                                              // fileAccessMode
        }

        // ── workflows - full {none, all, custom} × R/W-irrelevance matrix ──────────
        @Test
        @DisplayName("workflows: grant=none → 'do NOT have access'; grant=all → none; grant=custom → 'limited set'; R/W never changes the outcome")
        void workflowsGrantMatrixDecoupledFromAccessMode() {
            for (String mode : List.of("read", "write")) {
                assertThat(systemPromptFor(singleFamily("workflows", "none", mode)))
                        .as("workflows none/%s", mode)
                        .contains("[WORKFLOW RESTRICTIONS] You do NOT have access to workflows");
                assertThat(systemPromptFor(singleFamily("workflows", "all", mode)))
                        .as("workflows all/%s", mode)
                        .doesNotContain("[WORKFLOW RESTRICTIONS] You do NOT have access")
                        .doesNotContain("Do not attempt to use workflow tools");
                assertThat(systemPromptFor(singleFamily("workflows", "custom", mode)))
                        .as("workflows custom/%s", mode)
                        .contains("[WORKFLOW RESTRICTIONS] You have access to a limited set of workflows only")
                        .doesNotContain("You do NOT have access to workflows");
            }
        }

        // ── tables - full {none, all, custom} × R/W-irrelevance matrix ─────────────
        @Test
        @DisplayName("tables: grant=none → 'do NOT have access'; grant=all → none; grant=custom → 'limited set'; R/W never changes the outcome")
        void tablesGrantMatrixDecoupledFromAccessMode() {
            for (String mode : List.of("read", "write")) {
                assertThat(systemPromptFor(singleFamily("tables", "none", mode)))
                        .as("tables none/%s", mode)
                        .contains("[TABLE RESTRICTIONS] You do NOT have access to tables");
                assertThat(systemPromptFor(singleFamily("tables", "all", mode)))
                        .as("tables all/%s", mode)
                        .doesNotContain("[TABLE RESTRICTIONS] You do NOT have access");
                assertThat(systemPromptFor(singleFamily("tables", "custom", mode)))
                        .as("tables custom/%s", mode)
                        .contains("[TABLE RESTRICTIONS] You have access to a limited set of tables only")
                        .doesNotContain("You do NOT have access to tables");
            }
        }

        // ── interfaces - full {none, all, custom} × R/W-irrelevance matrix ─────────
        @Test
        @DisplayName("interfaces: grant=none → 'do NOT have access'; grant=all → none; grant=custom → 'limited set'; R/W never changes the outcome")
        void interfacesGrantMatrixDecoupledFromAccessMode() {
            for (String mode : List.of("read", "write")) {
                assertThat(systemPromptFor(singleFamily("interfaces", "none", mode)))
                        .as("interfaces none/%s", mode)
                        .contains("[INTERFACE RESTRICTIONS] You do NOT have access to interfaces");
                assertThat(systemPromptFor(singleFamily("interfaces", "all", mode)))
                        .as("interfaces all/%s", mode)
                        .doesNotContain("[INTERFACE RESTRICTIONS] You do NOT have access");
                assertThat(systemPromptFor(singleFamily("interfaces", "custom", mode)))
                        .as("interfaces custom/%s", mode)
                        .contains("[INTERFACE RESTRICTIONS] You have access to a limited set of interfaces only")
                        .doesNotContain("You do NOT have access to interfaces");
            }
        }

        // ── agents - full {none, all, custom} × R/W-irrelevance matrix ─────────────
        @Test
        @DisplayName("agents: grant=none → 'do NOT have access'; grant=all → none; grant=custom → 'limited set'; R/W never changes the outcome")
        void agentsGrantMatrixDecoupledFromAccessMode() {
            for (String mode : List.of("read", "write")) {
                assertThat(systemPromptFor(singleFamily("agents", "none", mode)))
                        .as("agents none/%s", mode)
                        .contains("[AGENT RESTRICTIONS] You do NOT have access to sub-agents");
                assertThat(systemPromptFor(singleFamily("agents", "all", mode)))
                        .as("agents all/%s", mode)
                        .doesNotContain("[AGENT RESTRICTIONS] You do NOT have access");
                assertThat(systemPromptFor(singleFamily("agents", "custom", mode)))
                        .as("agents custom/%s", mode)
                        .contains("[AGENT RESTRICTIONS] You have access to a limited set of sub-agents only")
                        .doesNotContain("You do NOT have access to sub-agents");
            }
        }

        // ── applications - full {none, all, custom} × R/W-irrelevance matrix ───────
        @Test
        @DisplayName("applications: grant=none → 'do NOT have access'; grant=all → none; grant=custom → 'limited set'; R/W never changes the outcome")
        void applicationsGrantMatrixDecoupledFromAccessMode() {
            for (String mode : List.of("read", "write")) {
                assertThat(systemPromptFor(singleFamily("applications", "none", mode)))
                        .as("applications none/%s", mode)
                        .contains("[APPLICATION RESTRICTIONS] You do NOT have access to marketplace applications");
                assertThat(systemPromptFor(singleFamily("applications", "all", mode)))
                        .as("applications all/%s", mode)
                        .doesNotContain("[APPLICATION RESTRICTIONS] You do NOT have access");
                assertThat(systemPromptFor(singleFamily("applications", "custom", mode)))
                        .as("applications custom/%s", mode)
                        .contains("[APPLICATION RESTRICTIONS] You have access to a limited set of marketplace applications only")
                        .doesNotContain("You do NOT have access to marketplace applications");
            }
        }

        @Test
        @DisplayName("absent workflows grant → still emits 'do NOT have access' (absent ⇒ none, deny-by-default)")
        void shouldStillBlockWorkflowsWhenNoWriteAccess() {
            // No workflowsGrant at all (legacy/un-backfilled row) → authoritative "none".
            ToolsConfig tc = new ToolsConfig("all", List.of(), List.of(), null, null, null, null, null,
                    null, null, null, null, null, null);

            String prompt = systemPromptFor(tc);

            assertThat(prompt).contains("[WORKFLOW RESTRICTIONS] You do NOT have access to workflows");
        }

        @Test
        @DisplayName("grant=custom → 'limited set' note regardless of access mode (R/W is a separate axis)")
        void shouldStillEmitLimitedSetForCustomListEvenWithWrite() {
            // workflowsGrant=custom (payload ["wf-1"]) + write → the limited-set note applies;
            // the write access mode does NOT change the grant.
            ToolsConfig tc = new ToolsConfig("all", List.of(), List.of("wf-1"), null, null, null, null, null,
                    null, "write", null, null, null, null, null, "custom", null, null, null, null, null);

            String prompt = systemPromptFor(tc);

            assertThat(prompt).contains("[WORKFLOW RESTRICTIONS] You have access to a limited set of workflows only");
            assertThat(prompt).doesNotContain("You do NOT have access to workflows");
        }

        @Test
        @DisplayName("App Factory durable config (grant=all on builder families) → no 'do NOT have access' block anywhere; tables custom → limited-set")
        void appFactoryGrantAllEmitsNoBuilderRestrictions() {
            // Durable post-migration App Factory: workflows/interfaces/applications/agents grant=all,
            // tables grant=custom (["93"]). The R/W access modes are a SEPARATE axis (here write) and
            // do NOT affect whether the agent "has access".
            ToolsConfig tc = new ToolsConfig(
                    "all", List.of(),
                    List.of(),            // workflows (payload empty; grant=all drives)
                    List.of(),            // applications
                    List.of("93"),        // tables custom payload
                    List.of(),            // interfaces
                    List.of(),            // agents
                    Boolean.TRUE,         // webSearch
                    "write",              // tableAccessMode
                    "write",              // workflowAccessMode
                    "write",              // interfaceAccessMode
                    null,                 // agentAccessMode
                    null,                 // applicationAccessMode
                    null,                 // skillAccessMode
                    null,                 // files
                    "all",                // workflowsGrant
                    "custom",             // tablesGrant
                    "all",                // interfacesGrant
                    "all",                // agentsGrant
                    "all", null);               // applicationsGrant

            String prompt = systemPromptFor(tc);

            // grant=all on every builder family → no "do NOT have access" anywhere.
            assertThat(prompt).doesNotContain("[WORKFLOW RESTRICTIONS] You do NOT have access");
            assertThat(prompt).doesNotContain("[INTERFACE RESTRICTIONS] You do NOT have access");
            assertThat(prompt).doesNotContain("[APPLICATION RESTRICTIONS] You do NOT have access");
            assertThat(prompt).doesNotContain("[AGENT RESTRICTIONS] You do NOT have access");
            // Custom table list keeps its limited-set note.
            assertThat(prompt).contains("[TABLE RESTRICTIONS] You have access to a limited set of tables only");
        }

        @Test
        @DisplayName("grant=none still emits 'do NOT have access' even with write mode (R/W decoupled from grant)")
        void grantNoneEmitsRestrictionEvenWithWrite() {
            // Decouple proof: write is about read-vs-write WITHIN granted access; it never
            // turns a 'none' grant into access. (Supersedes the old write-overrides-empty-list coupling.)
            ToolsConfig tc = new ToolsConfig("all", List.of(), List.of(), null, null, null, null, null,
                    null, "write", null, null, null, null, null, "none", null, null, null, null, null);

            String prompt = systemPromptFor(tc);

            assertThat(prompt).contains("[WORKFLOW RESTRICTIONS] You do NOT have access to workflows");
        }

        // ── credential-emit symmetry: grant=all OMITS the allow-list key ───────────
        // Mirrors AgentNodeAccessModeCredentialsTest + SubAgentExecutionHandlerTest
        // which assert doesNotContainKey("allowedWorkflowIds") for grant=all on their
        // (non-namespaced) paths. The conversation path emits the INTERNAL-namespaced
        // key (__allowedWorkflowIds__); ABSENT = unrestricted, [] = deny, [ids] = scoped.
        // grant=all ⇒ unrestricted ⇒ the key must be OMITTED (never []).

        @Test
        @DisplayName("grant=all on workflows → NO __allowedWorkflowIds__ credential (omission = unrestricted)")
        void grantAllWorkflowsOmitsAllowedWorkflowIdsCredential() {
            // workflowsGrant=all with an empty list placeholder. isWorkflowsAll true,
            // None/Custom false → applyToolsConfigCredentials writes nothing for workflows.
            Map<String, Object> creds = credentialsFor(singleFamily("workflows", "all", "write"));

            assertThat(creds).doesNotContainKey("__allowedWorkflowIds__");
        }

        @Test
        @DisplayName("grant=none on workflows → __allowedWorkflowIds__ = [] (explicit deny, NOT omitted)")
        void grantNoneWorkflowsEmitsEmptyAllowedWorkflowIdsCredential() {
            // Contrast with grant=all: 'none' is an explicit deny, so the key is present and [].
            Map<String, Object> creds = credentialsFor(singleFamily("workflows", "none", "write"));

            assertThat(creds).containsEntry("__allowedWorkflowIds__", List.of());
        }

        @Test
        @DisplayName("grant=custom on workflows → __allowedWorkflowIds__ = the custom payload")
        void grantCustomWorkflowsEmitsListAllowedWorkflowIdsCredential() {
            // 'custom' emits the family's id list (the "custom" payload) as the allow-list.
            Map<String, Object> creds = credentialsFor(singleFamily("workflows", "custom", "write"));

            assertThat(creds).containsEntry("__allowedWorkflowIds__", List.of("workflows-1"));
        }

        @Test
        @DisplayName("an UNKNOWN grant ('bogus') → __allowed<Family>Ids__ = [] (deny) on EVERY family - must NOT fail OPEN")
        void unknownGrantEmitsDenyCredentialForEveryFamily() {
            // Fail-open regression: pre-fix isXNone('bogus')=false (none-of-the-three) →
            // applyToolsConfigCredentials emitted NEITHER [] NOR a list → the __allowed<Family>Ids__
            // key was OMITTED → ToolAccessControl read absent == null == UNRESTRICTED. Post-fix
            // isXNone('bogus')=true → []. Proven for all 5 families, not just workflows.
            Map<String, String> familyToCredKey = Map.of(
                    "workflows", "__allowedWorkflowIds__",
                    "tables", "__allowedTableIds__",
                    "interfaces", "__allowedInterfaceIds__",
                    "agents", "__allowedAgentIds__",
                    "applications", "__allowedApplicationIds__");
            familyToCredKey.forEach((family, credKey) -> {
                Map<String, Object> creds = credentialsFor(singleFamily(family, "bogus", "write"));
                assertThat(creds)
                        .as("family %s with grant=bogus", family)
                        .containsEntry(credKey, List.of());
            });
        }

        @Test
        @DisplayName("a junk grant with a NON-EMPTY list still emits [] (the stale list must NOT leak through)")
        void unknownGrantDoesNotLeakStaleList() {
            // workflowsGrant='bogus' behind a non-empty ['wf-leak'] list. The read side resolves
            // none (deny) via isXNone and emits [] - it never trusts the list behind a junk grant.
            ToolsConfig tc = new ToolsConfig("all", List.of(), List.of("wf-leak"), null, null, null, null, null,
                    null, "write", null, null, null, null, null, "bogus", null, null, null, null, null);

            Map<String, Object> creds = credentialsFor(tc);

            assertThat(creds).containsEntry("__allowedWorkflowIds__", List.of());
        }
    }

    @Nested
    @DisplayName("build - approved services")
    class BuildApprovedServices {

        @Test
        @DisplayName("should return empty approved services (no longer fetched from DB)")
        void shouldIncludeApprovedServices() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // Approved services are no longer fetched from DB - always empty
            assertThat(context.approvedServices()).isEmpty();
        }
    }

    @Nested
    @DisplayName("build - workflow context")
    class BuildWorkflowContext {

        @Test
        @DisplayName("should use workflow-specific prompt when conversation has workflow")
        void shouldUseWorkflowPrompt() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext("wf-1", "My Workflow", "ACTIVE", "diagram", "ds-1", "last run ok", false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            // isNewConversation but workflow is present => exclude set_conversation_title
            when(coreToolsProvider.getCoreTools(false)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.credentials()).containsEntry("__viewingWorkflowId__", "wf-1");
            assertThat(context.credentials()).containsEntry("__viewingWorkflowName__", "My Workflow");
        }
    }

    @Nested
    @DisplayName("build - credentials")
    class BuildCredentials {

        @Test
        @DisplayName("should include conversationId and turnId in credentials")
        void shouldIncludeCredentials() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.credentials()).containsEntry("conversationId", "conv-1");
            assertThat(context.credentials()).containsKey("turnId");
        }
    }

    @Nested
    @DisplayName("build - attachments")
    class BuildAttachments {

        @Test
        @DisplayName("should load attachments when present in request")
        void shouldLoadAttachments() {
            ChatRequest request = createRequest("See attached", "gpt-4", "openai");
            request.setOrgId("org-1");
            var ref = new com.apimarketplace.conversation.dto.AttachmentRef("storage-1", "IMAGE", "photo.jpg", "image/jpeg");
            request.setAttachments(List.of(ref));

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()
            when(attachmentService.loadAttachments(any(), eq("user-1"), eq("org-1"))).thenReturn(List.of());

            agentContextBuilder.build(request, "conv-1", null);

            verify(attachmentService).loadAttachments(any(), eq("user-1"), eq("org-1"));
        }

        @Test
        @DisplayName("should not load attachments when none in request")
        void shouldNotLoadWhenNoAttachments() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            // serviceApprovalService no longer called - approvedServices hardcoded to Set.of()

            agentContextBuilder.build(request, "conv-1", null);

            verify(attachmentService, never()).loadAttachments(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("build - chat config (per-conversation general chat)")
    class BuildChatConfig {

        @Test
        @DisplayName("should apply chatConfig temperature, maxTokens, maxIterations")
        void shouldApplyChatConfigValues() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            Map<String, Object> chatConfig = Map.of(
                "temperature", 0.3,
                "maxTokens", 2000,
                "maxIterations", 25
            );
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.temperature()).isEqualTo(0.3);
            assertThat(context.maxTokens()).isEqualTo(2000);
            assertThat(context.maxIterations()).isEqualTo(25);
        }

        @Test
        @DisplayName("should clamp out-of-range chatConfig values")
        void shouldClampOutOfRangeValues() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            Map<String, Object> chatConfig = Map.of(
                "temperature", 5.0,
                "maxTokens", -10,
                "maxIterations", 99999,
                "executionTimeout", 999999
            );
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.temperature()).isEqualTo(2.0);  // clamped from 5.0
            assertThat(context.maxTokens()).isEqualTo(1);       // clamped from -10
            assertThat(context.maxIterations()).isEqualTo(1000);  // clamped from 99999
            assertThat(context.executionTimeout()).isEqualTo(7200); // clamped from 999999
        }

        @Test
        @DisplayName("a valid chatConfig inactivityTimeout is carried as the __inactivityTimeoutSeconds__ credential")
        void inactivityTimeoutCarriedAsCredential() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            ConversationMeta meta = new ConversationMeta(null, null, Map.of("inactivityTimeout", 90));

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.credentials())
                .as("a valid chatConfig inactivityTimeout must ride on the credentials for the watchdog")
                .containsEntry("__inactivityTimeoutSeconds__", 90);
        }

        @Test
        @DisplayName("an out-of-range chatConfig inactivityTimeout (1-9) is OMITTED so the 5-min default applies")
        void inactivityTimeoutOutOfRangeOmitted() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            ConversationMeta meta = new ConversationMeta(null, null, Map.of("inactivityTimeout", 5));

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.credentials()).doesNotContainKey("__inactivityTimeoutSeconds__");
        }

        /** Build a chat context with the given chatConfig.inactivityTimeout and return its credentials. */
        private Map<String, Object> credentialsForInactivity(Object inactivityValue) {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            ConversationMeta meta = new ConversationMeta(null, null, Map.of("inactivityTimeout", inactivityValue));
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            return agentContextBuilder.build(request, "conv-1", null).credentials();
        }

        @Test
        @DisplayName("inactivityTimeout=0 (disabled) is carried VERBATIM, distinct from omission (which means default)")
        void inactivityTimeoutZeroCarriedVerbatim() {
            assertThat(credentialsForInactivity(0))
                .as("0 = disabled is a distinct semantic from omission (5-min default) and must reach the watchdog verbatim")
                .containsEntry("__inactivityTimeoutSeconds__", 0);
        }

        @Test
        @DisplayName("inactivityTimeout=10 (lower bound, inclusive) is carried")
        void inactivityTimeoutLowerBoundary10Carried() {
            assertThat(credentialsForInactivity(10)).containsEntry("__inactivityTimeoutSeconds__", 10);
        }

        @Test
        @DisplayName("inactivityTimeout=7200 (upper bound, inclusive) is carried")
        void inactivityTimeoutUpperBoundary7200Carried() {
            assertThat(credentialsForInactivity(7200)).containsEntry("__inactivityTimeoutSeconds__", 7200);
        }

        @Test
        @DisplayName("inactivityTimeout=7201 (above the ceiling) is OMITTED so the 5-min default applies")
        void inactivityTimeout7201Omitted() {
            assertThat(credentialsForInactivity(7201)).doesNotContainKey("__inactivityTimeoutSeconds__");
        }

        @Test
        @DisplayName("clamps the default output budget to a low-cap model's catalog ceiling")
        void clampsDefaultMaxTokensToLowModelCeiling() {
            // General chat (no agent, no per-conversation maxTokens) → platform default
            // 16000, but DeepSeek-chat caps output at 8192. Sending 16000 would 400.
            ChatRequest request = createRequest("Hello", "deepseek-chat", "deepseek");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                    new com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel(
                            "deepseek", "deepseek-chat", "mid", 1, null, 8192)));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.maxTokens()).isEqualTo(8192); // 16000 → capped to DeepSeek-chat's 8192
        }

        @Test
        @DisplayName("keeps the full default output budget for a high-cap model")
        void keepsDefaultMaxTokensForHighCapModel() {
            ChatRequest request = createRequest("Hello", "claude-opus-4-8", "anthropic");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());
            when(agentConfigProvider.getAvailableModels()).thenReturn(List.of(
                    new com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel(
                            "anthropic", "claude-opus-4-8", "top", 1, null, 128000)));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.maxTokens()).isEqualTo(16000); // 16000 < 128000 cap → unchanged
        }

        @Test
        @DisplayName("should sanitize unknown toolsMode to 'all'")
        void shouldSanitizeUnknownToolsMode() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            Map<String, Object> chatConfig = Map.of("toolsMode", "hacked");
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // "hacked" toolsMode sanitized to "all" → uses general chat prompt (full default)
            assertThat(context.maxIterations()).isEqualTo(10); // default, since chatConfig had no maxIterations
            assertThat(context.systemPrompt()).isNotNull();
        }

        @Test
        @DisplayName("should truncate systemPrompt beyond 10000 chars")
        void shouldTruncateSystemPrompt() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            String longPrompt = "x".repeat(15000);
            Map<String, Object> chatConfig = Map.of("systemPrompt", longPrompt);
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // systemPrompt is appended to default prompt, but the custom part is truncated to 10000
            assertThat(context.systemPrompt()).doesNotContain("x".repeat(10001));
        }

        @Test
        @DisplayName("should use general chat prompt when chatConfig has no tool restrictions")
        void shouldUseGeneralChatPromptWithoutRestrictions() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");

            // toolsMode=all, webSearch=true (no restrictions)
            Map<String, Object> chatConfig = Map.of("temperature", 0.5);
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // Should use full default prompt (general chat), not concise agent prompt
            assertThat(context.temperature()).isEqualTo(0.5);
            assertThat(context.systemPrompt()).isNotNull();
        }

        @Test
        @DisplayName("turnLimits.loop* propagate into AgentLoopContext")
        void turnLimitsLoopPropagateToLoopContext() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            Map<String, Object> chatConfig = Map.of(
                "turnLimits", Map.of(
                    "loopIdenticalStop", 7,
                    "loopConsecutiveStop", 21
                )
            );
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.loopIdenticalStop()).isEqualTo(7);
            assertThat(context.loopConsecutiveStop()).isEqualTo(21);
        }

        @Test
        @DisplayName("turnLimits unified per-resource cap injected as __chatMaxPerResourcePerTurn__ credential")
        void turnLimitsPerTurnCapsInjectedAsCredentials() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            Map<String, Object> chatConfig = Map.of(
                "turnLimits", Map.of(
                    "maxPerResourcePerTurn", 4
                )
            );
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            Map<String, Object> creds = context.credentials();
            assertThat(creds).containsEntry(
                com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 4);
        }

        @Test
        @DisplayName("turnLimits absent → no override, no credential key added")
        void turnLimitsAbsentProducesNoOverride() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            Map<String, Object> chatConfig = Map.of("temperature", 0.5);
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.loopIdenticalStop()).isNull();
            assertThat(context.loopConsecutiveStop()).isNull();
            Map<String, Object> creds = context.credentials();
            assertThat(creds).doesNotContainKey(
                com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN);
        }

        @Test
        @DisplayName("turnLimits with non-positive values are ignored (no override)")
        void turnLimitsNonPositiveValuesIgnored() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            // buildChatConfig.readPositiveInt drops values ≤ 0 - validation at the write
            // path blocks them reaching DB, this covers read-path robustness.
            java.util.Map<String, Object> turnLimits = new java.util.HashMap<>();
            turnLimits.put("maxPerResourcePerTurn", 0);
            turnLimits.put("loopIdenticalStop", -5);
            Map<String, Object> chatConfig = Map.of("turnLimits", turnLimits);
            ConversationMeta meta = new ConversationMeta(null, null, chatConfig);

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(true)).thenReturn(List.of());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.loopIdenticalStop()).isNull();
            assertThat(context.credentials()).doesNotContainKey(
                com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN);
        }
    }

    @Nested
    @DisplayName("Stage 1a.1 - layered systemBlocks")
    class LayeredSystemBlocks {

        private ChatRequest generalChatRequest(boolean newConv) {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(newConv);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anyBoolean())).thenReturn(List.of());
            return request;
        }

        @Test
        @DisplayName("emits a 10-slot list with cache breakpoints on [0] and [3] only")
        void blockListShapeHasTenSlotsWithBreakpointsAtZeroAndThree() {
            ChatRequest request = generalChatRequest(true);

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            List<SystemBlock> blocks = context.systemBlocks();
            assertThat(blocks).hasSize(10);
            for (int i = 0; i < blocks.size(); i++) {
                boolean expectedBreakpoint = (i == 0 || i == 3);
                assertThat(blocks.get(i).cacheBreakpoint())
                    .as("block[%d] breakpoint flag", i)
                    .isEqualTo(expectedBreakpoint);
            }
        }

        @Test
        @DisplayName("block [0] carries the static base prompt (no conversation instructions appended)")
        void baseBlockDoesNotIncludeConversationInstructions() {
            ChatRequest request = generalChatRequest(true);

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            String block0 = context.systemBlocks().get(0).text();
            assertThat(block0).isNotBlank();
            // NEW_CONVERSATION_INSTRUCTION and FOLLOW_UP_INSTRUCTION now live
            // in block [9] so the stable prefix in [0] stays cacheable.
            assertThat(block0).doesNotContain("[START - DO THIS IMMEDIATELY]");
            assertThat(block0).doesNotContain("[CONTINUATION]");
        }

        @Test
        @DisplayName("block [9] carries NEW_CONVERSATION_INSTRUCTION on a new general-chat turn")
        void instructionBlockCarriesNewConversationOnFirstTurn() {
            ChatRequest request = generalChatRequest(true);

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.systemBlocks().get(9).text())
                .contains("[START - DO THIS IMMEDIATELY]")
                .contains("set_conversation_title");
        }

        @Test
        @DisplayName("block [9] carries FOLLOW_UP_INSTRUCTION on subsequent turns")
        void instructionBlockCarriesFollowUpOnSubsequentTurns() {
            ChatRequest request = generalChatRequest(false);

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.systemBlocks().get(9).text())
                .contains("[CONTINUATION]");
        }

        @Test
        @DisplayName("effective systemPrompt concatenates non-blank blocks with blank-line separators")
        void legacyStringIsConcatenationOfNonBlankBlocks() {
            ChatRequest request = generalChatRequest(true);

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            String expected = context.systemBlocks().stream()
                .filter(b -> !b.isBlank())
                .map(SystemBlock::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
            assertThat(context.systemPrompt()).isEqualTo(expected);
        }

        @Test
        @DisplayName("agent-bound new conversation: block [9] falls through to FOLLOW_UP, set_conversation_title excluded")
        void agentBoundConversationSkipsTitleDirective() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            request.setAgentId("agent-42");

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1"))
                .thenReturn(new ConversationMeta(null, "agent-42", null));
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                .thenReturn(WorkflowBuilderSessionContext.empty());
            // Agent path takes the Set-based overload; the key assertion is the boolean arg
            // (false = no title tool). Return a non-null list so the builder doesn't fall back.
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean()))
                .thenReturn(List.of(com.apimarketplace.agent.domain.ToolDefinition.builder().name("agent").build()));
            when(agentConfigProvider.getAgentConfig("agent-42", "user-1", null))
                .thenReturn(new AgentConfig("agent-42", "My Agent", null, null, null,
                    null, null, null, null, null, null, null, null, null));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // Directive in block [9] must be the neutral FOLLOW_UP, not the title-setting NEW one.
            String block9 = context.systemBlocks().get(9).text();
            assertThat(block9).contains("[CONTINUATION]");
            assertThat(block9).doesNotContain("[START - DO THIS IMMEDIATELY]");
            assertThat(block9).doesNotContain("set_conversation_title");
            // Pin the CoreToolsProvider contract: title-tool flag propagated as false.
            verify(coreToolsProvider).getCoreTools(anySet(), eq(false));
            verify(coreToolsProvider, never()).getCoreTools(anySet(), eq(true));
        }

        @Test
        @DisplayName("agentId from conversation entity (not request) also suppresses title directive")
        void agentIdFromConversationEntityAlsoSuppresses() {
            ChatRequest request = createRequest("Hello", "gpt-4", "openai");
            // agentId absent on the request - resolved via ConversationMeta instead.

            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(true);
            when(workflowContextProvider.getConversationMeta("conv-1"))
                .thenReturn(new ConversationMeta(null, "agent-99", null));
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(anySet(), anyBoolean()))
                .thenReturn(List.of(com.apimarketplace.agent.domain.ToolDefinition.builder().name("agent").build()));
            when(agentConfigProvider.getAgentConfig("agent-99", "user-1", null))
                .thenReturn(new AgentConfig("agent-99", "Other Agent", null, null, null,
                    null, null, null, null, null, null, null, null, null));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.systemBlocks().get(9).text())
                .contains("[CONTINUATION]")
                .doesNotContain("set_conversation_title");
            verify(coreToolsProvider).getCoreTools(anySet(), eq(false));
            verify(coreToolsProvider, never()).getCoreTools(anySet(), eq(true));
        }
    }

    @Nested
    @DisplayName("build - COLD compaction summary (block 6)")
    class ColdSummaryWiring {

        private void stubCommon(ConversationMeta meta) {
            when(historyConverter.convert(any(), eq("conv-1"), eq("user-1"))).thenReturn(List.of());
            when(historyConverter.isNewConversation(any())).thenReturn(false);
            when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(meta);
            when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), eq("user-1")))
                    .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
            when(workflowContextProvider.getActiveWorkflowBuilderSession("user-1", "conv-1"))
                    .thenReturn(WorkflowBuilderSessionContext.empty());
            when(coreToolsProvider.getCoreTools(false)).thenReturn(List.of());
        }

        @Test
        @DisplayName("a populated summary_cold is rendered into block 6 and the flattened systemPrompt (shared by both chat paths)")
        void coldSummaryInjectedIntoBlock6() {
            ChatRequest request = createRequest("continue", "gpt-4", "openai");
            Map<String, Object> cold = Map.of(
                    "user_intents", List.of("build an Airbnb search app"),
                    "ids_resolved", Map.of("workflow_id", "wf-abc"),
                    "turns_covered", List.of(1, 2, 3));
            stubCommon(new ConversationMeta(null, null, null, cold));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            // Direct-API path reads systemBlocks; block 6 carries the COLD summary.
            String block6 = context.systemBlocks().get(6).text();
            assertThat(block6).contains("[DURABLE CONTEXT - distilled summary of earlier turns]");
            assertThat(block6).contains("build an Airbnb search app");
            assertThat(block6).contains("workflow_id: wf-abc");
            // Bridge/CLI path reads the flattened systemPrompt; same content must be present.
            assertThat(context.systemPrompt()).contains("[DURABLE CONTEXT - distilled summary of earlier turns]");
            assertThat(context.systemPrompt()).contains("build an Airbnb search app");
        }

        @Test
        @DisplayName("a null summary_cold leaves block 6 empty (no-op for short conversations)")
        void nullColdSummaryLeavesBlock6Empty() {
            ChatRequest request = createRequest("continue", "gpt-4", "openai");
            stubCommon(ConversationMeta.empty());

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.systemBlocks().get(6).text()).isEmpty();
            assertThat(context.systemPrompt()).doesNotContain("[DURABLE CONTEXT");
        }

        @Test
        @DisplayName("a malformed summary_cold leaves block 6 empty and never breaks the turn")
        void malformedColdSummaryLeavesBlock6Empty() {
            ChatRequest request = createRequest("continue", "gpt-4", "openai");
            // Metadata only, no usable content sections -> renderer returns "".
            Map<String, Object> cold = Map.of(
                    "generated_at", "2026-06-09T12:00:00Z",
                    "model", "deepseek-chat");
            stubCommon(new ConversationMeta(null, null, null, cold));

            AgentLoopContext context = agentContextBuilder.build(request, "conv-1", null);

            assertThat(context.systemBlocks().get(6).text()).isEmpty();
            assertThat(context.systemPrompt()).doesNotContain("[DURABLE CONTEXT");
        }
    }

    @Nested
    @DisplayName("ensureTaskDelegationModules - F19: a delegated task/review keeps the 'agent' module")
    class TaskDelegationModules {

        @Test
        @DisplayName("TASK source whose modules omit 'agent' -> 'agent' force-included (task_complete reachable)")
        void taskSourceForceIncludesAgentModule() {
            Set<String> out = AgentContextBuilder.ensureTaskDelegationModules(
                new java.util.LinkedHashSet<>(Set.of("web_search")), "TASK");
            assertThat(out).contains("agent", "web_search");
        }

        @Test
        @DisplayName("TASK_REVIEW source also force-includes 'agent' (reviewer needs task_approve/task_reject_review)")
        void taskReviewSourceForceIncludesAgentModule() {
            Set<String> out = AgentContextBuilder.ensureTaskDelegationModules(
                new java.util.LinkedHashSet<>(Set.of("web_search")), "TASK_REVIEW");
            assertThat(out).contains("agent");
        }

        @Test
        @DisplayName("'agent' already present -> unchanged (same reference, no duplicate)")
        void agentAlreadyPresentIsNoOp() {
            Set<String> in = new java.util.LinkedHashSet<>(Set.of("agent", "web_search"));
            assertThat(AgentContextBuilder.ensureTaskDelegationModules(in, "TASK")).isSameAs(in);
        }

        @Test
        @DisplayName("non-delegated source (CHAT) -> unchanged (same reference)")
        void nonDelegatedSourceIsNoOp() {
            Set<String> in = new java.util.LinkedHashSet<>(Set.of("web_search"));
            assertThat(AgentContextBuilder.ensureTaskDelegationModules(in, "CHAT")).isSameAs(in);
        }

        @Test
        @DisplayName("null modules -> null (unrestricted; the agent already has every tool)")
        void nullModulesIsNoOp() {
            assertThat(AgentContextBuilder.ensureTaskDelegationModules(null, "TASK")).isNull();
        }
    }

}
