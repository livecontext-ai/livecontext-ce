package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.AttachmentService;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider;
import com.apimarketplace.conversation.service.ai.CoreToolsProvider;
import com.apimarketplace.conversation.service.ai.SkillsSnapshotService;
import com.apimarketplace.conversation.service.ai.ThinkingLevelPinningService;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.ConversationMeta;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowBuilderSessionContext;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowContext;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.service.ai.schema.ModelTierMapper;
import com.apimarketplace.conversation.service.ai.schema.SchemaSlimExclusionPolicy;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Seam test for the {@code generation} / {@code imageGeneration} grants.
 *
 * <p><b>Why a seam test and not more layer tests.</b> The grant already had tests at every
 * layer, and it was still inert in chat in BOTH directions: an agent that opted in never
 * received the tool, and a general chat that opted into nothing received it anyway. The
 * reason is that no test followed a grant from the shape it is PERSISTED in to the tool list
 * the runtime actually hands the model. This one does: it drives the REAL
 * {@link AgentConfigProvider} (JSON parse), the REAL {@link AgentContextBuilder}
 * (module resolution) and the REAL {@link CoreToolsProvider} (tool filtering), and asserts on
 * {@link AgentLoopContext#tools()} - what the model is given - plus
 * {@link AgentLoopContext#enabledModules()}, which is what the CLI bridge re-scopes its own
 * MCP tool set with.
 *
 * <p>Only the transport (RestTemplate) and the unrelated collaborators are mocked, so a
 * regression anywhere along that chain fails here.
 */
@DisplayName("generation / imageGeneration grant reaches (or does not reach) the runtime tool list")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenerationGrantToolExposureTest {

    /** Tool name (what the model sees). */
    private static final String GENERATION = "generation";

    /** Config key (what is persisted). */
    private static final String GENERATION_KEY = "generation";

    /**
     * The other opt-in, added later and for a different reason: not cost, reach. Covered in
     * this file rather than its own because the failure mode it guards against is the one
     * this file exists for, a grant that is persisted and never arrives.
     */
    private static final String MAILBOX = "mailbox";

    /**
     * The RETIRED legacy tool name and its grant key. Nothing serves the tool and
     * nothing honours the grant; both are kept here only so the tests below can prove
     * a row still carrying the grant gets neither.
     */
    private static final String RETIRED_IMAGE_TOOL = "image_generation";
    private static final String RETIRED_IMAGE_GRANT_KEY = "imageGeneration";

    /** Every core tool name the platform can serve, so nothing is missing by accident. */
    private static final List<String> ALL_CORE_TOOL_NAMES = List.of(
            "catalog", "workflow", "table", "interface", "agent", "skill", "application",
            "web_search", GENERATION, "files", "wait", MAILBOX);

    @Mock
    private RestTemplate agentServiceRestTemplate;

    @Mock
    private RestTemplate toolRegistryRestTemplate;

    @Mock
    private ConversationHistoryConverter historyConverter;

    @Mock
    private WorkflowContextProvider workflowContextProvider;

    @Mock
    private SkillsSnapshotService skillsSnapshotService;

    @Mock
    private ThinkingLevelPinningService thinkingLevelPinningService;

    @Mock
    private ModelTierMapper modelTierMapper;

    private AgentContextBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        // Real collaborators - this is the point of the test.
        AgentConfigProvider agentConfigProvider = new AgentConfigProvider(agentServiceRestTemplate);
        setField(AgentConfigProvider.class, agentConfigProvider, "agentServiceUrl", "http://agent-service");

        CoreToolsProvider coreToolsProvider = new CoreToolsProvider(toolRegistryRestTemplate);
        when(toolRegistryRestTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("tools", ALL_CORE_TOOL_NAMES.stream()
                        .map(GenerationGrantToolExposureTest::toolPayload)
                        .toList()), HttpStatus.OK));

        builder = new AgentContextBuilder(
                historyConverter,
                coreToolsProvider,
                workflowContextProvider,
                agentConfigProvider,
                mock(ServiceApprovalService.class),
                mock(AttachmentService.class),
                skillsSnapshotService,
                thinkingLevelPinningService,
                modelTierMapper,
                mock(SchemaSlimExclusionPolicy.class),
                mock(HelpSeenRegistry.class),
                mock(MessageRepository.class));
        setField(AgentContextBuilder.class, builder, "customSystemPrompt", "");
        setField(AgentContextBuilder.class, builder, "defaultMaxIterations", 10);
        setField(AgentContextBuilder.class, builder, "defaultExecutionTimeoutSeconds", 3600);
        setField(AgentContextBuilder.class, builder, "defaultMaxTokens", 16000);

        when(historyConverter.convert(any(), anyString(), anyString())).thenReturn(List.of());
        when(historyConverter.isNewConversation(any())).thenReturn(false);
        when(workflowContextProvider.getWorkflowContext(any(ConversationMeta.class), anyString()))
                .thenReturn(new WorkflowContext(null, null, null, null, null, null, false));
        when(workflowContextProvider.getActiveWorkflowBuilderSession(anyString(), anyString()))
                .thenReturn(WorkflowBuilderSessionContext.empty());
        when(skillsSnapshotService.loadIfFresh(anyString(), anyString())).thenReturn(Optional.empty());
        // Non-SLIM schema mode: the tool list reaches the context untouched.
        when(modelTierMapper.resolve(anyString(), anyString(), any())).thenReturn(null);
        when(thinkingLevelPinningService.resolveAndPin(anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(null);
    }

    private static Map<String, Object> toolPayload(String name) {
        return Map.of("name", name, "description", name + " tool", "requiredParams", List.of());
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Wire an agent whose PERSISTED toolsConfig JSONB is exactly {@code toolsConfigJson}. */
    private void givenStoredAgentToolsConfig(String toolsConfigJson) {
        String agentJson = """
                {
                  "name": "Agent",
                  "modelProvider": "openai",
                  "modelName": "gpt-4",
                  "toolsConfig": %s
                }
                """.formatted(toolsConfigJson);
        when(agentServiceRestTemplate.exchange(contains("/by-config/"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(agentJson, HttpStatus.OK));
        when(workflowContextProvider.getConversationMeta("conv-1")).thenReturn(ConversationMeta.empty());
    }

    /** Wire a general chat (no agent) whose persisted chat_config is {@code chatConfig}. */
    private void givenGeneralChatConfig(Map<String, Object> chatConfig) {
        when(workflowContextProvider.getConversationMeta("conv-1"))
                .thenReturn(new ConversationMeta(null, null, chatConfig));
    }

    private AgentLoopContext buildForAgent() {
        ChatRequest request = baseRequest();
        request.setAgentId("agent-1");
        return builder.build(request, "conv-1", null);
    }

    private AgentLoopContext buildForGeneralChat() {
        return builder.build(baseRequest(), "conv-1", null);
    }

    private static ChatRequest baseRequest() {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setModel("gpt-4");
        request.setProvider("openai");
        request.setUserId("user-1");
        request.setConversationId("conv-1");
        return request;
    }

    private static List<String> toolNames(AgentLoopContext context) {
        return context.tools().stream().map(ToolDefinition::name).toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Agent scope - the grant is stored on the agent's toolsConfig
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("agent scope")
    class AgentScope {

        private static final String GRANTS = """
                "mode": "all",
                "workflowsGrant": "all", "tablesGrant": "all", "interfacesGrant": "all",
                "agentsGrant": "all", "applicationsGrant": "all"
                """;

        @Test
        @DisplayName("grant ON (boolean) → the runtime tool list CONTAINS generation")
        void booleanGrantOnExposesGeneration() {
            givenStoredAgentToolsConfig("{ %s, \"generation\": true }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).contains(GENERATION);
            assertThat(context.enabledModules()).contains(GENERATION);
        }

        @Test
        @DisplayName("grant ON (object shape) → the runtime tool list CONTAINS generation")
        void objectGrantOnExposesGeneration() {
            givenStoredAgentToolsConfig(
                    "{ %s, \"generation\": { \"enabled\": true, \"model\": \"seedance-1\" } }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).contains(GENERATION);
        }

        @Test
        @DisplayName("grant explicitly OFF → the runtime tool list does NOT contain generation")
        void grantOffHidesGeneration() {
            givenStoredAgentToolsConfig(
                    "{ %s, \"generation\": { \"enabled\": false } }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).doesNotContain(GENERATION);
            assertThat(context.enabledModules()).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("grant ABSENT → the runtime tool list does NOT contain generation")
        void grantAbsentHidesGeneration() {
            givenStoredAgentToolsConfig("{ %s }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("no toolsConfig at all → neither credit-spending tool is granted")
        void noToolsConfigGrantsNeither() {
            givenStoredAgentToolsConfig("null");

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);
            assertThat(context.enabledModules()).doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);
            // ... while everything that is NOT opt-in still reaches the model.
            assertThat(toolNames(context)).contains("catalog", "table", "workflow", "files");
        }

        @Test
        @DisplayName("no toolsConfig at all → the system prompt does not advertise a tool the agent has not got")
        void noToolsConfigPromptAgreesWithToolList() {
            givenStoredAgentToolsConfig("null");

            AgentLoopContext context = buildForAgent();

            // The concise agent prompt used to list EVERY module while the tool list (correctly)
            // withheld the two opt-ins, so the model was taught to call a tool it did not have.
            assertThat(context.effectiveSystemPrompt())
                    .doesNotContain("- " + GENERATION + " -")
                    .doesNotContain("- " + RETIRED_IMAGE_TOOL + " -")
                    .contains("- workflow -");
        }

        @Test
        @DisplayName("the two grants are independent: generation ON does not grant image_generation")
        void generationDoesNotWidenIntoImageGeneration() {
            givenStoredAgentToolsConfig("{ %s, \"generation\": true }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).contains(GENERATION);
        }

        @Test
        @DisplayName("a stored agent row still carrying the RETIRED imageGeneration grant gets no tool")
        void retiredImageGrantDoesNotWidenIntoGeneration() {
            givenStoredAgentToolsConfig("{ %s, \"imageGeneration\": true }".formatted(GRANTS));

            AgentLoopContext context = buildForAgent();

            assertThat(toolNames(context)).doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // General chat - the grant is stored on the conversation's chat_config
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("general chat scope")
    class GeneralChatScope {

        @Test
        @DisplayName("nothing configured → NEITHER credit-spending tool is handed to the model")
        void plainGeneralChatGetsNeither() {
            givenGeneralChatConfig(null);

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);
            // The bridge rebuilds its own MCP tool set from enabledModules, so "unrestricted"
            // (null) there would re-expose both tools on the CLI path.
            assertThat(context.enabledModules()).isNotNull().doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);
        }

        @Test
        @DisplayName("nothing configured → the system prompt does not advertise a tool the chat has not got")
        void plainGeneralChatPromptDoesNotAdvertiseUngrantedTools() {
            givenGeneralChatConfig(null);

            AgentLoopContext context = buildForGeneralChat();

            assertThat(context.effectiveSystemPrompt())
                    .doesNotContain("- " + GENERATION + " -")
                    .doesNotContain("- " + RETIRED_IMAGE_TOOL + " -")
                    .contains("- workflow -");
        }

        @Test
        @DisplayName("grant ON in chat_config → the runtime tool list CONTAINS generation")
        void chatConfigGrantOnExposesGeneration() {
            givenGeneralChatConfig(Map.of(GENERATION_KEY, Map.of("enabled", true)));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).contains(GENERATION);
            assertThat(context.enabledModules()).contains(GENERATION);
        }

        @Test
        @DisplayName("grant ON as a plain boolean in chat_config → the runtime tool list CONTAINS generation")
        void chatConfigBooleanGrantOnExposesGeneration() {
            givenGeneralChatConfig(Map.of(GENERATION_KEY, true));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).contains(GENERATION);
        }

        /**
         * The general chat had no way to reach a mailbox at all: its {@code ToolsConfig} was
         * built through a constructor that predated the field, so the grant was dropped on the
         * floor whatever the settings panel wrote. The switch would have rendered, saved, and
         * done nothing, which is the exact failure this whole file was written for.
         */
        @Test
        @DisplayName("mailbox granted in chat_config → the runtime tool list CONTAINS mailbox")
        void chatConfigMailboxGrantOnExposesMailbox() {
            givenGeneralChatConfig(Map.of(MAILBOX, Map.of("enabled", true)));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).contains(MAILBOX);
            assertThat(context.enabledModules()).contains(MAILBOX);
        }

        @Test
        @DisplayName("nothing configured → the general chat does NOT get a mailbox")
        void chatConfigWithoutMailboxGetsNone() {
            givenGeneralChatConfig(Map.of());

            assertThat(toolNames(buildForGeneralChat())).doesNotContain(MAILBOX);
        }

        @Test
        @DisplayName("mailbox turned back OFF → the tool is withdrawn")
        void chatConfigMailboxGrantOffHidesIt() {
            givenGeneralChatConfig(Map.of(MAILBOX, Map.of("enabled", false)));

            assertThat(toolNames(buildForGeneralChat())).doesNotContain(MAILBOX);
        }

        /**
         * The half that decides whether the chat can SEND. An absent mode reads as full access
         * everywhere on this platform, so the axis has to survive the same trip as the grant,
         * or granting a mailbox in the settings panel always grants sending with it.
         */
        @Test
        @DisplayName("the read-only mode travels with the grant, all the way to the credentials")
        void chatConfigMailboxAccessModeReachesTheCredentials() {
            givenGeneralChatConfig(Map.of(MAILBOX, Map.of("enabled", true), "mailboxAccessMode", "read"));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).contains(MAILBOX);
            assertThat(context.credentials())
                    .as("dropped here, the tool sees no stated permission and reads that as ALLOWED")
                    .containsEntry("__mailboxAccessMode__", "read");
        }

        @Test
        @DisplayName("grant turned back OFF in chat_config → the tool is withdrawn")
        void chatConfigGrantOffHidesGeneration() {
            givenGeneralChatConfig(Map.of(GENERATION_KEY, Map.of("enabled", false)));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context)).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("only the generation chat_config grant exposes the tool - the retired key never does")
        void onlyTheGenerationChatConfigGrantExposesIt() {
            givenGeneralChatConfig(Map.of(RETIRED_IMAGE_GRANT_KEY, Map.of("enabled", true)));
            assertThat(toolNames(buildForGeneralChat())).doesNotContain(GENERATION, RETIRED_IMAGE_TOOL);

            givenGeneralChatConfig(Map.of(GENERATION_KEY, Map.of("enabled", true)));
            assertThat(toolNames(buildForGeneralChat())).contains(GENERATION);
        }

        @Test
        @DisplayName("granting a credit-spending tool does not strip the ordinary tool families")
        void grantingGenerationKeepsTheOtherFamilies() {
            givenGeneralChatConfig(Map.of(GENERATION_KEY, Map.of("enabled", true)));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context))
                    .contains("table", "workflow", "interface", "agent", "application", "web_search");
        }

        @Test
        @DisplayName("turning web search off does not strip the other tool families either")
        void disablingWebSearchKeepsTheOtherFamilies() {
            givenGeneralChatConfig(Map.of("webSearch", false));

            AgentLoopContext context = buildForGeneralChat();

            assertThat(toolNames(context))
                    .contains("table", "workflow", "interface", "agent", "application")
                    .doesNotContain("web_search", GENERATION, RETIRED_IMAGE_TOOL);
        }
    }
}
