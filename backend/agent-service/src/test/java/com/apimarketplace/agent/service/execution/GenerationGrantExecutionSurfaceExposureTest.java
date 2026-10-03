package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.agent.service.budget.BudgetResolver;
import com.apimarketplace.agent.service.budget.BudgetState;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.service.budget.ModelCostCalculator;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seam test for the {@code generation} / {@code imageGeneration} grants on the TWO
 * agent-service execution surfaces: the workflow {@code agent:} node (which reaches
 * {@link AgentRemoteExecutionService} over the wire) and a sub-agent invocation
 * ({@link SubAgentExecutionHandler}).
 *
 * <p><b>Why a seam test.</b> The chat surface had this leak closed, and both of these kept
 * the identical three-line shape: {@code enabledModules == null} / {@code toolsConfig == null}
 * ⇒ {@code coreToolsCache.getCoreTools()} unfiltered. So an agent row with NO toolsConfig
 * received the credit-spending {@code generation} tool and could spend 600 credits of the
 * customer's balance with no grant set anywhere. Asserting that a helper returns the right
 * SET would not have caught it: the defect was that the runtime asked a different question
 * than the helper answered. These tests therefore drive the REAL {@link CoreToolsCache}
 * (serving every core tool, exactly like a healthy install) through the REAL execution
 * services, and assert on {@link AgentLoopContext#tools()} - the list handed to the model -
 * or, on the bridge path, on the {@code enabledModules} the CLI rebuilds its MCP tool set from.
 */
@DisplayName("generation / imageGeneration grant on the workflow-node and sub-agent surfaces")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenerationGrantExecutionSurfaceExposureTest {

    /** Tool names (what the model sees). */
    private static final String GENERATION = "generation";

    /**
     * The retired legacy grant key. No tool answers to it any more; it is kept here
     * only so the tests below can prove that a persisted row still carrying it grants
     * NOTHING, rather than silently widening into {@link #GENERATION}.
     */
    private static final String RETIRED_IMAGE_GRANT = "imageGeneration";

    /** Every core tool a healthy install serves, so nothing is absent by accident. */
    private static final List<String> ALL_CORE_TOOL_NAMES = List.of(
            "catalog", "workflow", "table", "interface", "agent", "skill", "application",
            "web_search", GENERATION, "files", "wait");

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    /** The full per-family grant block, so only the opt-in toggles vary between cases. */
    private static final Map<String, Object> ALL_FAMILY_GRANTS = Map.of(
            "mode", "all",
            "workflowsGrant", "all", "tablesGrant", "all", "interfacesGrant", "all",
            "agentsGrant", "all", "applicationsGrant", "all");

    @Mock private RestTemplate toolRegistryRestTemplate;
    @Mock private AgentLoopService agentLoopService;

    /** REAL - the cache is where "no config ⇒ everything" used to be decided. */
    private CoreToolsCache coreToolsCache;

    @BeforeEach
    void setUpCache() {
        when(toolRegistryRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(allToolsResponse());
        coreToolsCache = new CoreToolsCache(toolRegistryRestTemplate, new ObjectMapper(),
                "http://orchestrator", "http://agent", "http://datasource", "http://interface",
                "http://catalog", true, true, null);
        coreToolsCache.refreshCoreTools();
        // Sanity: the cache really does serve the credit-spending tools, so a test that finds
        // them absent below proves FILTERING, not an empty cache.
        assertThat(coreToolsCache.getCoreTools()).extracting(ToolDefinition::name)
                .contains(GENERATION);
    }

    @SuppressWarnings("rawtypes")
    private static ResponseEntity<Map> allToolsResponse() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : ALL_CORE_TOOL_NAMES) {
            tools.add(Map.of("name", name, "description", name + " tool", "id", name + "-1"));
        }
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("tools", tools);
        envelope.put("count", tools.size());
        return new ResponseEntity<>(envelope, HttpStatus.OK);
    }

    private static AgentLoopResult okResult() {
        return AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
    }

    private static List<String> toolNames(AgentLoopContext context) {
        return context.tools() == null ? List.of()
                : context.tools().stream().map(ToolDefinition::name).toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Workflow `agent:` node - orchestrator AgentNode → AgentRemoteExecutionService
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("workflow agent: node (remote execution)")
    class WorkflowAgentNodeSurface {

        @Mock private RedisStreamingCallback redisStreamingCallback;
        @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
        @Mock private AgentActivityPublisher agentActivityPublisher;
        @Mock private GuardChainFactory guardChainFactory;
        @Mock private ClassifyService classifyService;
        @Mock private GuardrailService guardrailService;
        @Mock private BridgeLoopDispatcher bridgeDispatcher;
        @Mock private ModelCatalogService modelCatalogService;
        @Mock private ActiveStreamRegistry activeStreamRegistry;

        private AgentRemoteExecutionService service;

        @BeforeEach
        void setUp() {
            service = new AgentRemoteExecutionService(
                    agentLoopService, new ObjectMapper(), redisStreamingCallback,
                    conversationRedisStreamingCallback, coreToolsCache, agentActivityPublisher,
                    guardChainFactory, classifyService, guardrailService, bridgeDispatcher,
                    modelCatalogService, activeStreamRegistry);
            when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
            when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(i -> i.getArgument(0));
            when(modelCatalogService.resolveEffortWithDefault(any(), any(), any())).thenReturn(null);
            when(guardChainFactory.forAgentWithFallback(any(), any(), any(), any(), any(), any()))
                    .thenReturn(PreIterationGuard.ALWAYS_PROCEED);
        }

        /** Run one execution and return the context the loop (and so the model) received. */
        private AgentLoopContext execute(List<String> enabledModules) {
            ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(captor.capture(), any(StreamingCallback.class)))
                    .thenReturn(okResult());
            service.executeAgent(autoDiscoverRequest(enabledModules));
            return captor.getValue();
        }

        /** The module list the orchestrator sends for a given persisted toolsConfig. */
        private List<String> modulesFor(Map<String, Object> toolsConfig) {
            return List.copyOf(AgentModuleResolver.resolveEnabledModules(toolsConfig));
        }

        @Test
        @DisplayName("agent with NO toolsConfig → the tool list handed to the model has neither credit-spending tool")
        void noToolsConfigGrantsNeither() {
            AgentLoopContext context = execute(modulesFor(null));

            assertThat(toolNames(context)).doesNotContain(GENERATION);
            // ... while everything that is NOT opt-in still reaches the model, so this is a
            // scoped list and not an accidentally-empty one.
            assertThat(toolNames(context)).contains("catalog", "table", "workflow", "files");
        }

        @Test
        @DisplayName("enabledModules ABSENT on the wire (old producer / omitted async field) still withholds the credit-spending tools")
        void nullEnabledModulesFallsBackToTheNoConfigSetNotToEverything() {
            AgentLoopContext context = execute(null);

            // This is the exact pre-fix failure: null used to mean "unrestricted" and returned
            // the whole cache, so a workflow agent got `generation` with no grant anywhere.
            assertThat(toolNames(context)).doesNotContain(GENERATION);
            assertThat(toolNames(context)).contains("catalog", "table", "workflow");
        }

        @Test
        @DisplayName("agent WITH the generation grant ON → the model does receive the generation tool")
        void grantOnExposesGeneration() {
            Map<String, Object> toolsConfig = new HashMap<>(ALL_FAMILY_GRANTS);
            toolsConfig.put("generation", true);

            AgentLoopContext context = execute(modulesFor(toolsConfig));

            assertThat(toolNames(context)).contains(GENERATION);
        }

        @Test
        @DisplayName("a row still carrying the RETIRED imageGeneration grant gets no generation tool at all")
        void retiredImageGrantGrantsNothing() {
            Map<String, Object> toolsConfig = new HashMap<>(ALL_FAMILY_GRANTS);
            toolsConfig.put(RETIRED_IMAGE_GRANT, Map.of("enabled", true));

            AgentLoopContext context = execute(modulesFor(toolsConfig));

            // The retired grant is inert, not a fallback: it must not widen into the
            // generation tool, which also reaches per-second video models that spend an
            // order of magnitude more credits than the images it was granted for.
            assertThat(toolNames(context)).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("only the generation grant exposes the generation tool on this surface")
        void onlyTheGenerationGrantExposesIt() {
            Map<String, Object> generationOnly = new HashMap<>(ALL_FAMILY_GRANTS);
            generationOnly.put("generation", true);
            assertThat(toolNames(execute(modulesFor(generationOnly)))).contains(GENERATION);

            Map<String, Object> retiredOnly = new HashMap<>(ALL_FAMILY_GRANTS);
            retiredOnly.put(RETIRED_IMAGE_GRANT, true);
            assertThat(toolNames(execute(modulesFor(retiredOnly)))).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("an EMPTY module list still means zero tools (mode=off) - the null fallback must not swallow it")
        void emptyModulesStillMeansNoTools() {
            assertThat(toolNames(execute(List.of()))).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sub-agent invocation - SubAgentExecutionHandler
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sub-agent invocation")
    class SubAgentSurface {

        @Mock private AgentService agentService;
        @Mock private ConversationClient conversationServiceClient;
        @Mock private AgentObservabilityService observabilityService;
        @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
        @Mock private StringRedisTemplate redisTemplate;
        @SuppressWarnings("unchecked")
        @Mock private ValueOperations<String, String> valueOperations;
        @Mock private EventBus eventBus;
        @Mock private BudgetResolver budgetResolver;
        @Mock private BudgetReservationService budgetReservationService;
        @Mock private CreditConsumptionClient creditConsumptionClient;
        @Mock private AgentTaskService agentTaskService;
        @Mock private GuardChainFactory guardChainFactory;
        @Mock private AgentActivityPublisher agentActivityPublisher;

        private SubAgentExecutionHandler handler;

        @BeforeEach
        void setUp() {
            handler = new SubAgentExecutionHandler(
                    agentService, agentLoopService, coreToolsCache, conversationServiceClient,
                    observabilityService, conversationRedisStreamingCallback, redisTemplate,
                    eventBus, new ObjectMapper(), budgetResolver, budgetReservationService,
                    creditConsumptionClient, agentTaskService, guardChainFactory,
                    agentActivityPublisher, new AgentDefaultsConfig());

            when(guardChainFactory.resolveCalculator(any(), any())).thenReturn(
                    new ModelCostCalculator(new BigDecimal("0.001"), new BigDecimal("0.003"), BigDecimal.ZERO));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.increment(anyString())).thenReturn(1L);
            when(conversationServiceClient.getConversationMessages(any(), anyInt(), any())).thenReturn(List.of());
            when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
                    .thenReturn(BudgetState.disabled());
            when(creditConsumptionClient.fetchBalance(anyString())).thenReturn(new BigDecimal("999999"));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                    .thenReturn("conv-1");
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(mock(ConversationRedisStreamingCallback.ConversationCallback.class));
        }

        /** A direct-API (non-bridge) child agent whose persisted toolsConfig is {@code toolsConfig}. */
        private AgentEntity childAgent(Map<String, Object> toolsConfig) {
            AgentEntity entity = new AgentEntity();
            entity.setId(AGENT_ID);
            entity.setName("Child Agent");
            entity.setModelProvider("openai");
            entity.setModelName("gpt-4");
            entity.setTemperature(new BigDecimal("0.7"));
            entity.setMaxTokens(4096);
            entity.setIsActive(true);
            entity.setSystemPrompt("You are a helpful assistant.");
            entity.setToolsConfig(toolsConfig);
            return entity;
        }

        /** Invoke the child through the public agent(action='execute') tool and capture its context. */
        private AgentLoopContext invoke(Map<String, Object> toolsConfig) {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(childAgent(toolsConfig)));
            ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(captor.capture(), any(StreamingCallback.class)))
                    .thenReturn(okResult());

            Map<String, Object> credentials = new HashMap<>();
            credentials.put("__agent_depth__", 0);
            credentials.put("turnId", "turn-1");
            credentials.put("conversationId", "parent-conv-123");
            handler.execute(new ToolCall("tc-1", "agent", Map.of(
                    "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"), null),
                    TENANT_ID, credentials);
            return captor.getValue();
        }

        @Test
        @DisplayName("child agent with NO toolsConfig → the tool list handed to the model has neither credit-spending tool")
        void noToolsConfigGrantsNeither() {
            AgentLoopContext context = invoke(null);

            // Pre-fix, resolveTools short-circuited on a null toolsConfig and returned the whole
            // cache, so a config-less child could spend the customer's credits unprompted.
            assertThat(toolNames(context)).doesNotContain(GENERATION);
            assertThat(toolNames(context)).contains("catalog", "table", "workflow", "files");
        }

        @Test
        @DisplayName("child agent WITH the generation grant ON → the model does receive the generation tool")
        void grantOnExposesGeneration() {
            Map<String, Object> toolsConfig = new HashMap<>(ALL_FAMILY_GRANTS);
            toolsConfig.put("generation", true);

            assertThat(toolNames(invoke(toolsConfig))).contains(GENERATION);
        }

        @Test
        @DisplayName("a child agent still carrying the RETIRED imageGeneration grant gets no generation tool")
        void retiredImageGrantGrantsNothing() {
            Map<String, Object> toolsConfig = new HashMap<>(ALL_FAMILY_GRANTS);
            toolsConfig.put(RETIRED_IMAGE_GRANT, true);

            assertThat(toolNames(invoke(toolsConfig))).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("only the generation grant exposes the generation tool on this surface")
        void onlyTheGenerationGrantExposesIt() {
            Map<String, Object> generationOnly = new HashMap<>(ALL_FAMILY_GRANTS);
            generationOnly.put("generation", true);
            assertThat(toolNames(invoke(generationOnly))).contains(GENERATION);

            Map<String, Object> retiredOnly = new HashMap<>(ALL_FAMILY_GRANTS);
            retiredOnly.put(RETIRED_IMAGE_GRANT, true);
            assertThat(toolNames(invoke(retiredOnly))).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("grant explicitly OFF → the tool is withdrawn")
        void grantOffHidesGeneration() {
            Map<String, Object> toolsConfig = new HashMap<>(ALL_FAMILY_GRANTS);
            toolsConfig.put("generation", Map.of("enabled", false));

            assertThat(toolNames(invoke(toolsConfig))).doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("a config-less BRIDGE child forwards the no-config module set, so the CLI cannot rebuild the credit-spending tools")
        void bridgeChildWithoutToolsConfigForwardsScopedModules() {
            AgentEntity entity = childAgent(null);
            entity.setModelProvider("claude-code");
            entity.setModelName("claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));

            SubAgentBridgeClient bridgeClient = mock(SubAgentBridgeClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClient);
            when(bridgeClient.execute(any(AgentExecutionRequestDto.class), any())).thenReturn(
                    new com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto(
                            true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                            "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

            Map<String, Object> credentials = new HashMap<>();
            credentials.put("__agent_depth__", 0);
            credentials.put("turnId", "turn-1");
            credentials.put("conversationId", "parent-conv-123");
            handler.execute(new ToolCall("tc-1", "agent", Map.of(
                    "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"), null),
                    TENANT_ID, credentials);

            ArgumentCaptor<AgentExecutionRequestDto> captor =
                    ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(bridgeClient).execute(captor.capture(), any());
            // The bridge ignores the resolved tool list and rebuilds its own MCP tool set from
            // enabledModules, so a null here re-granted both credit-spending tools on the CLI path
            // even though the direct path (correctly) withheld them.
            assertThat(captor.getValue().enabledModules())
                    .as("a config-less bridge child must not be handed the credit-spending modules")
                    .isNotNull()
                    .doesNotContain(GENERATION)
                    .contains("catalog", "table", "workflow");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The shared contract both surfaces now read
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("shared no-config contract")
    class SharedContract {

        @Test
        @DisplayName("NO_CONFIG_MODULES is resolveEnabledModules(null) and excludes exactly the credit-spending opt-ins")
        void noConfigModulesExcludeTheOptIns() {
            assertThat(AgentModuleResolver.NO_CONFIG_MODULES)
                    .isEqualTo(AgentModuleResolver.resolveEnabledModules(null))
                    .doesNotContain(GENERATION)
                    .contains("catalog", "table", "interface", "agent", "skill", "workflow",
                            "application", "web_search", "files", "wait");
        }

        @Test
        @DisplayName("the cache's no-config tool names are derived from that set, so cache and resolver cannot drift")
        void cacheNoConfigNamesTrackTheResolver() {
            assertThat(CoreToolsCache.NO_CONFIG_CORE_TOOL_NAMES)
                    .isEqualTo(DefaultSystemPrompts.build(AgentModuleResolver.NO_CONFIG_MODULES, false).coreToolNames())
                    .doesNotContain(GENERATION);
        }

        @Test
        @DisplayName("getCoreTools(null) resolves to the no-config set, not to the full cache")
        void nullToolNameSetIsNotUnrestricted() {
            assertThat(coreToolsCache.getCoreTools(null)).extracting(ToolDefinition::name)
                    .doesNotContain(GENERATION)
                    .contains("catalog", "table");
        }
    }

    /** A workflow-node request the way AgentNode builds it: tools=null + autoDiscoverTools=true. */
    private static AgentExecutionRequestDto autoDiscoverRequest(List<String> enabledModules) {
        return new AgentExecutionRequestDto(
                "Do the work.", "You are a workflow agent.", "deepseek", "deepseek-chat",
                0.0, 320,
                null,  // tools - null so the auto-discover branch runs
                true,  // autoDiscoverTools
                10, 4, 150, null,
                TENANT_ID,
                null, null, null, Map.of(), null, null, null, null,
                "conversation-1", "conversation",
                null, null, null, null, null, null,
                "agent-1", 100.0, null, 0.0, null, null,
                UUID.randomUUID().toString(), "WORKFLOW", null,
                enabledModules);  // the field under test
    }
}
