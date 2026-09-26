package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.agent.service.budget.BudgetResolver;
import com.apimarketplace.agent.service.budget.BudgetState;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.service.budget.InsufficientBudgetException;
import com.apimarketplace.agent.service.budget.ModelCostCalculator;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ValueOperations;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SubAgentExecutionHandler")
@ExtendWith(MockitoExtension.class)
class SubAgentExecutionHandlerTest {

    @Mock private AgentService agentService;
    @Mock private AgentLoopService agentLoopService;
    @Mock private CoreToolsCache coreToolsCache;
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

    private ObjectMapper objectMapper;
    private AgentDefaultsConfig agentDefaults;
    private SubAgentExecutionHandler handler;

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final String ORG_ROLE = "MEMBER";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        agentDefaults = new AgentDefaultsConfig();
        handler = new SubAgentExecutionHandler(
            agentService, agentLoopService, coreToolsCache,
            conversationServiceClient, observabilityService,
            conversationRedisStreamingCallback, redisTemplate, eventBus, objectMapper,
            budgetResolver, budgetReservationService, creditConsumptionClient, agentTaskService,
            guardChainFactory, agentActivityPublisher, agentDefaults);

        // GuardChainFactory.resolveCalculator returns a zero-cost calculator by default in tests
        lenient().when(guardChainFactory.resolveCalculator(any(), any()))
            .thenReturn(new ModelCostCalculator(new BigDecimal("0.001"), new BigDecimal("0.003"), BigDecimal.ZERO));

        // Tool resolution defaults (both overloads → empty). A null toolsConfig uses the no-arg
        // overload; a non-null toolsConfig now routes through the canonical FILTERED
        // getCoreTools(Set) overload (AgentModuleResolver → coreToolNames). Tests that only
        // exercise credential forwarding don't care about the returned tools - lenient defaults
        // keep them from NPE'ing and avoid UnnecessaryStubbing churn. Per-test stubs override.
        lenient().when(coreToolsCache.getCoreTools()).thenReturn(List.of());
        lenient().when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());

        // Stub Redis for rate limiting (returns 1L → first call, under limit)
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);

        // Default: memory is enabled so getConversationMessages is always called
        lenient().when(conversationServiceClient.getConversationMessages(any(), anyInt(), any()))
            .thenReturn(List.of());
        // Default: budget guards never deny - disabled state and large tenant balance
        lenient().when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
            .thenReturn(BudgetState.disabled());
        lenient().when(creditConsumptionClient.fetchLlmSpendableBalance(anyString(), any(), any()))
            .thenReturn(new BigDecimal("999999"));
    }

    private ToolCall createToolCall(Map<String, Object> args) {
        return new ToolCall("tc-1", "agent", args, null);
    }

    private AgentEntity createAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(AGENT_ID);
        entity.setName("Test Agent");
        entity.setModelProvider("openai");
        entity.setModelName("gpt-4");
        entity.setTemperature(new BigDecimal("0.7"));
        entity.setMaxTokens(4096);
        entity.setIsActive(true);
        entity.setSystemPrompt("You are a helpful assistant.");
        return entity;
    }

    private Map<String, Object> defaultCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agent_depth__", 0);
        creds.put("turnId", "turn-1");
        creds.put("conversationId", "parent-conv-123");
        return creds;
    }

    @Nested
    @DisplayName("Parameter Validation")
    class ParameterValidationTests {

        @Test
        @DisplayName("should fail when agent_id is missing")
        void shouldFailMissingAgentId() {
            ToolCall toolCall = createToolCall(Map.of("action", "execute", "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
        }

        @Test
        @DisplayName("should fail when agent_id is not a valid UUID")
        void shouldFailInvalidUuid() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", "not-a-uuid", "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
        }

        @Test
        @DisplayName("should fail when prompt is missing")
        void shouldFailMissingPrompt() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString()));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'prompt' is required");
        }

        @Test
        @DisplayName("should fail when prompt is blank")
        void shouldFailBlankPrompt() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "  "));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'prompt' is required");
        }
    }

    @Nested
    @DisplayName("Access Control")
    class AccessControlTests {

        @Test
        @DisplayName("should allow when no allowedAgentIds restriction")
        void shouldAllowNoRestriction() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("a sub-agent inherits the parent's key-route pin from the credentials and never re-resolves it")
        void subAgentInheritsParentKeyRoute() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4"));
            Map<String, Object> creds = defaultCredentials();
            // The parent was pinned PLATFORM (e.g. a lookup failure or a plan rule): the child
            // must not run on the user's key just because the tenant happens to hold one.
            creds.put(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "PLATFORM");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(contextCaptor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.PLATFORM);
            // The row is billed under the route the child actually ran on.
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getKeyRoute()).isEqualTo("PLATFORM");
        }

        @Test
        @DisplayName("same provider as the parent: the child inherits OWN_KEY verbatim and re-stamps it for its own children")
        void subAgentInheritsOwnKeyOnSameProvider() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            KeyRouteResolver keyRouteResolver = mock(KeyRouteResolver.class);
            ReflectionTestUtils.setField(handler, "keyRouteResolver", keyRouteResolver);
            Map<String, Object> creds = defaultCredentials();
            com.apimarketplace.agent.domain.KeyRoute.stamp(creds, com.apimarketplace.agent.domain.KeyRoute.OWN_KEY, "openai");

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            AgentLoopContext child = contextCaptor.getValue();
            assertThat(child.keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            // Grandchildren must inherit THIS child's pin: both keys are stamped on its credentials.
            assertThat(child.credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");
            verify(keyRouteResolver, never()).resolve(any(), any());
        }

        /**
         * Spawning a child was the way around a read-only restriction. ToolAccessControl
         * reads an ABSENT access mode as FULL access, so a child that states no mode ran
         * unrestricted no matter what its parent was allowed: a read-only mail agent could
         * send by delegating. Inheriting the parent's mode closes that, and it is the only
         * narrowing in this cascade.
         */
        @Test
        @DisplayName("a sub-agent inherits the parent's read-only mode instead of defaulting to full access")
        void subAgentInheritsTheParentAccessMode() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            Map<String, Object> creds = defaultCredentials();
            creds.put("__mailboxAccessMode__", "read");

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(contextCaptor.getValue().credentials())
                    .containsEntry("__mailboxAccessMode__", "read");
        }

        /**
         * BOTH spellings, because the producers disagree: a conversation writes the
         * namespaced form, while a sub-agent's or a bridge agent's own config writes the
         * plain one. Forwarding a single spelling would carry a restriction that came from
         * chat and drop one that came from the agent's own row, which is the deeper nesting
         * and the likelier to matter.
         */
        @Test
        @DisplayName("the plain spelling is inherited too, which is the one an agent's own config writes")
        void subAgentInheritsThePlainSpellingToo() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            Map<String, Object> creds = defaultCredentials();
            creds.put("mailboxAccessMode", "read");
            creds.put("memoryAccessMode", "read");

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(contextCaptor.getValue().credentials())
                    .containsEntry("mailboxAccessMode", "read")
                    .containsEntry("memoryAccessMode", "read");
        }

        /**
         * The module list is deliberately NOT inherited: the block that resolves the child's
         * own modules overwrites anything forwarded, and its own comment says inheriting them
         * would be wrong in both directions. Pinned so a future reader does not "fix" the
         * asymmetry by adding a forward that can never be observed.
         */
        @Test
        @DisplayName("the module list is NOT inherited: the child resolves its own")
        void subAgentDoesNotInheritTheModuleList() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            Map<String, Object> creds = defaultCredentials();
            creds.put(com.apimarketplace.agent.config.AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                    java.util.List.of("mailbox", "table"));

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            Object inherited = contextCaptor.getValue().credentials()
                    .get(com.apimarketplace.agent.config.AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY);
            assertThat(inherited)
                    .as("the child resolves its own modules, so the parent list must not survive")
                    .isNotEqualTo(java.util.List.of("mailbox", "table"));
        }

        @Test
        @DisplayName("regression: a parent on its OWN key of another provider does not pin the child to a key it may not have; the child resolves for its own provider")
        void subAgentOnAnotherProviderResolvesItsOwnRoute() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            KeyRouteResolver keyRouteResolver = mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT_ID, "openai")).thenReturn(com.apimarketplace.agent.domain.KeyRoute.PLATFORM);
            ReflectionTestUtils.setField(handler, "keyRouteResolver", keyRouteResolver);
            Map<String, Object> creds = defaultCredentials();
            // Parent ran on its own ANTHROPIC key; this child's entity runs on OPENAI.
            com.apimarketplace.agent.domain.KeyRoute.stamp(creds, com.apimarketplace.agent.domain.KeyRoute.OWN_KEY, "anthropic");

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            // Before the fix the child inherited OWN_KEY and failed closed: "no usable openai key".
            assertThat(result.success()).isTrue();
            assertThat(contextCaptor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.PLATFORM);
            assertThat(contextCaptor.getValue().credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");
            verify(keyRouteResolver).resolve(TENANT_ID, "openai");
        }

        @Test
        @DisplayName("a PLATFORM parent on another provider does not pin the child: 'no usable anthropic key' says nothing about openai")
        void subAgentOnAnotherProviderResolvesPastAPlatformParent() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            KeyRouteResolver keyRouteResolver = mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT_ID, "openai")).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            ReflectionTestUtils.setField(handler, "keyRouteResolver", keyRouteResolver);
            Map<String, Object> creds = defaultCredentials();
            com.apimarketplace.agent.domain.KeyRoute.stamp(creds, com.apimarketplace.agent.domain.KeyRoute.PLATFORM, "anthropic");

            handler.execute(toolCall(), TENANT_ID, creds);

            // The tenant holds an openai key: the child runs on it, not on the platform key the
            // parent was pinned to for lack of an anthropic one.
            assertThat(contextCaptor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            verify(keyRouteResolver).resolve(TENANT_ID, "openai");
        }

        @Test
        @DisplayName("an unpinned parent (a chat that ran on a CLI bridge builds no context and stamps nothing): its direct-API child takes its own pin")
        void unpinnedParentChildResolvesItsOwnRoute() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            KeyRouteResolver keyRouteResolver = mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT_ID, "openai")).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            ReflectionTestUtils.setField(handler, "keyRouteResolver", keyRouteResolver);

            handler.execute(toolCall(), TENANT_ID, defaultCredentials());

            // The one production shape with no parent stamp; without this the child ran unpinned
            // (user-first, no fail-closed) while every other path in the platform was pinned.
            assertThat(contextCaptor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            assertThat(contextCaptor.getValue().credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");
            verify(keyRouteResolver).resolve(TENANT_ID, "openai");
        }

        @Test
        @DisplayName("without a resolver (unit-test wiring) an unpinned parent's child stays unpinned")
        void unpinnedParentWithoutResolverStaysUnpinned() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();

            handler.execute(toolCall(), TENANT_ID, defaultCredentials());

            assertThat(contextCaptor.getValue().keyRoute()).isNull();
            assertThat(contextCaptor.getValue().credentials())
                .doesNotContainKey(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY);
        }

        @Test
        @DisplayName("without a resolver, a cross-provider OWN_KEY child runs unpinned but the parent's stamp is left for its own children")
        void crossProviderWithoutResolverKeepsParentStamp() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            Map<String, Object> creds = defaultCredentials();
            com.apimarketplace.agent.domain.KeyRoute.stamp(creds, com.apimarketplace.agent.domain.KeyRoute.OWN_KEY, "anthropic");

            handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(contextCaptor.getValue().keyRoute()).isNull();
            // A null decision must not erase what the grandparent stamped: a grandchild back on
            // anthropic still inherits OWN_KEY.
            assertThat(contextCaptor.getValue().credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "anthropic");
        }

        /**
         * The other direction, and the one that keeps the narrowing RECOVERABLE. The forward
         * runs first, then the child's own config is applied over it, so a child whose row
         * states a mode keeps that mode. Without this, a parent narrowed once would pin every
         * descendant for ever and the only fix would be editing the parent.
         *
         * <p>A read-only parent cannot CREATE such a child, since the escalation guard refuses
         * exactly that call. A PERSON can, in the UI, and then this parent may spawn it: that
         * is the person's decision standing, not a hole.
         */
        @Test
        @DisplayName("a child that states its own mode keeps it, so the narrowing stays recoverable")
        void aChildsOwnModeOverridesTheInheritedOne() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of("mode", "all", "mailboxAccessMode", "write"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4"));
            Map<String, Object> creds = defaultCredentials();
            creds.put("__mailboxAccessMode__", "read");

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(contextCaptor.getValue().credentials())
                    .as("ToolAccessControl reads the plain key first, so the child's own row decides")
                    .containsEntry("mailboxAccessMode", "write");
        }

        /**
         * Every family, not only the two a reader happens to look at. The forward loops over
         * the shared key list, so a category dropped from that list would vanish here silently.
         */
        @Test
        @DisplayName("every access-mode family is inherited, not just the mailbox")
        void everyAccessModeFamilyIsInherited() {
            ArgumentCaptor<AgentLoopContext> contextCaptor = stubSuccessfulExecution();
            Map<String, Object> creds = defaultCredentials();
            // Every family EXCEPT agent: spawning a sub-agent is itself an agent WRITE, so
            // narrowing that one refuses the call under test rather than exercising the
            // forward. That refusal is correct, and it is covered by the access-mode tests
            // on the tool itself.
            for (String key : com.apimarketplace.agent.config.ToolAccessControl.ACCESS_MODE_KEYS) {
                if (!"agentAccessMode".equals(key)) creds.put("__" + key + "__", "read");
            }

            ToolResult result = handler.execute(toolCall(), TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            for (String key : com.apimarketplace.agent.config.ToolAccessControl.ACCESS_MODE_KEYS) {
                if ("agentAccessMode".equals(key)) continue;
                assertThat(contextCaptor.getValue().credentials())
                        .as("family %s lost its restriction on the way to the child", key)
                        .containsEntry("__" + key + "__", "read");
            }
        }

        private ArgumentCaptor<AgentLoopContext> stubSuccessfulExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4"));
            return contextCaptor;
        }

        private ToolCall toolCall() {
            return createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
        }

        @Test
        @DisplayName("should reject when agent not in allowedAgentIds list")
        void shouldRejectNotInAllowedList() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("rejects sub-agent execute when namespaced allowedAgentIds excludes the child")
        void rejectsSubAgentExecuteWhenNamespacedAllowedListExcludesChild() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__allowedAgentIds__", List.of("other-agent-id"));
            creds.put("agentAccessMode", "write");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not in your approved agent list");
            verify(agentService, never()).getAgent(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("rejects sub-agent execute when namespaced agent access mode is read-only")
        void rejectsSubAgentExecuteWhenAgentAccessModeIsReadOnly() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));
            creds.put("__agentAccessMode__", "read");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only").contains("execute");
            verify(agentService, never()).getAgent(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("should allow when agent in allowedAgentIds list")
        void shouldAllowWhenInList() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("org-scoped sub-agent execute loads the child from the active organization workspace")
        void orgScopedSubAgentExecuteLoadsChildFromActiveOrganizationWorkspace() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__orgId__", "org-123");
            creds.put("__orgRole__", "MEMBER");
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            lenient().when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", "MEMBER")).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-org-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            verify(agentService).getAgent(AGENT_ID, TENANT_ID, "org-123", "MEMBER");
            verify(agentService, never()).getAgent(AGENT_ID, TENANT_ID);
        }
    }

    @Nested
    @DisplayName("Agent Loading")
    class AgentLoadingTests {

        @Test
        @DisplayName("should fail when agent not found")
        void shouldFailAgentNotFound() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Agent not found");
        }

        @Test
        @DisplayName("should fail when agent is inactive")
        void shouldFailInactiveAgent() {
            AgentEntity entity = createAgent();
            entity.setIsActive(false);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("inactive");
        }
    }

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("should execute sub-agent successfully and return result")
        void shouldExecuteSuccessfully() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Sub-agent completed the task"),
                List.of(), 2, null, 500, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Analyse data"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");
            assertThat(result.content()).contains("Sub-agent completed the task");
            assertThat(result.content()).contains("Test Agent");
        }

        @Test
        @DisplayName("should pass context + prompt as full prompt")
        void shouldCombineContextAndPrompt() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Summarize this", "context", "Important data here"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify the user prompt passed to saveMessage contains context + prompt
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("user"), contentCaptor.capture(), isNull(), eq(TENANT_ID), any());

            String savedPrompt = contentCaptor.getValue();
            assertThat(savedPrompt).contains("Context:");
            assertThat(savedPrompt).contains("Important data here");
            assertThat(savedPrompt).contains("Task:");
            assertThat(savedPrompt).contains("Summarize this");
        }

        @Test
        @DisplayName("should increment depth in sub-agent credentials")
        void shouldIncrementDepth() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__agent_depth__", 2);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, creds);

            // Verify depth was incremented in the context passed to agentLoopService
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("__agent_depth__")).isEqualTo(3);
        }

        @Test
        @DisplayName("stamps a non-blank executionId on the sub-agent context so CE relay billing aggregates it")
        void stampsExecutionIdOnSubAgentContext() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Without the stamp the sub-agent would silently fall back to per-call CE relay billing.
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));
            assertThat(contextCaptor.getValue().executionId()).isNotBlank();
        }

        @Test
        @DisplayName("should forward parent restrictions to sub-agent")
        void shouldForwardRestrictions() {
            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", null)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__allowedWorkflowIds__", List.of("wf-1", "wf-2"));
            creds.put("__allowedFileIds__", List.of("file-1"));
            creds.put("__orgId__", "org-123");
            creds.put("__workflowRunId__", "run-456");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, creds);

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("__allowedWorkflowIds__")).isEqualTo(List.of("wf-1", "wf-2"));
            assertThat(subCreds.get("__orgId__")).isEqualTo("org-123");
            assertThat(subCreds.get("__workflowRunId__")).isEqualTo("run-456");
            // The parent's file allow-list must BIND the config-less child - assert the
            // RESOLVED scope (the value FilesToolsProvider actually reads), not just the
            // forwarded namespaced key. Pre-fix the child also got a plain allowedFileIds=[]
            // that shadowed the forward (plain wins) and resolved to "unrestricted" → escape.
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isEqualTo(List.of("file-1"));
        }

        @Test
        @DisplayName("org credential scopes sub-agent conversation and observability")
        void orgCredentialScopesConversationAndObservability() {
            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", null)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__orgId__", "org-123");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello", "memory", false));
            handler.execute(toolCall, TENANT_ID, creds);

            verify(conversationServiceClient).findOrCreateAgentConversation(
                AGENT_ID.toString(), TENANT_ID, entity.getName(), "org-123");
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("user"), eq("hello"), isNull(), eq(TENANT_ID), any(), eq("org-123"));
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("assistant"), eq("OK"), isNull(), eq(TENANT_ID), any(), eq("org-123"));

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getOrganizationId()).isEqualTo("org-123");
            assertThat(obsCaptor.getValue().getSource()).isEqualTo("SUB_AGENT");
            // No resolver wired: whether a swap happened is unknown, so nothing is claimed.
            assertThat(obsCaptor.getValue().getModelReplaced()).isNull();
            assertThat(obsCaptor.getValue().getReplacedModel()).isNull();
        }

        @Test
        @DisplayName("an ENABLED model the resolver left alone is recorded as not replaced (resolution ran)")
        void enabledModelIsRecordedAsNotReplaced() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            com.apimarketplace.agent.service.ModelReplacementResolver resolver =
                mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
            when(resolver.substituteIfDisabled(entity.getModelProvider(), entity.getModelName()))
                .thenReturn(Optional.empty());
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "modelReplacementResolver", resolver);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class))).thenReturn(
                AgentLoopResult.success(CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));

            handler.execute(createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello", "memory", false)),
                TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obs =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obs.capture());
            assertThat(obs.getValue().getModelReplaced()).isFalse();
            assertThat(obs.getValue().getReplacedModel()).isNull();
        }

        @Test
        @DisplayName("regression V515: a sub-agent whose model is DISABLED runs on the replacement and is billed as it; the entity is never rewritten")
        void disabledModelRunsOnReplacement() {
            AgentEntity entity = createAgent();
            String storedProvider = entity.getModelProvider();
            String storedModel = entity.getModelName();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            com.apimarketplace.agent.service.ModelReplacementResolver resolver =
                mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
            when(resolver.substituteIfDisabled(storedProvider, storedModel)).thenReturn(Optional.of(
                new com.apimarketplace.agent.service.ModelReplacementResolver.Substitution(
                    "deepseek", "deepseek-chat", storedProvider, storedModel, false)));
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "modelReplacementResolver", resolver);
            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "deepseek", "deepseek-chat");
            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class))).thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello", "memory", false));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Pre-fix the child ran (and was billed) on the disabled pair stored on the entity.
            assertThat(ctx.getValue().provider()).isEqualTo("deepseek");
            assertThat(ctx.getValue().model()).isEqualTo("deepseek-chat");
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obs =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obs.capture());
            assertThat(obs.getValue().getProvider()).isEqualTo("deepseek");
            assertThat(obs.getValue().getModel()).isEqualTo("deepseek-chat");
            // The swap reaches analytics: which disabled model the run was configured with.
            assertThat(obs.getValue().getModelReplaced()).isTrue();
            assertThat(obs.getValue().getReplacedModel()).isEqualTo(storedModel);
            // A run-time swap only: the agent's stored configuration is left as the admin found it.
            assertThat(entity.getModelProvider()).isEqualTo(storedProvider);
            assertThat(entity.getModelName()).isEqualTo(storedModel);
        }

        @Test
        @DisplayName("Sub-agent cascade: absent internal-list key in child config writes [] cred (regression)")
        void subAgentCascadeSetsEmptyCredsWhenChildHasAbsentKey() {
            // Pre-fix: passAllowedIds only put a credential when value was a List;
            // absent keys silently fell through, downstream tool modules saw null and
            // treated as "no restriction" → child sub-agent had unrestricted access
            // to every workflow/table/interface/agent/application in the tenant.
            //
            // Post-fix: passAllowedIds writes List.of() for absent keys, and the
            // parent applyToolsConfigCredentials uses an empty map when the whole
            // toolsConfig is null. Ensures legacy agents (pre-V163) cannot escalate
            // through the sub-agent execution path.
            AgentEntity entity = createAgent();
            // Child has only `mode` and `tools` - none of the 5 internal lists
            entity.setToolsConfig(Map.of("mode", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("allowedWorkflowIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedTableIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedInterfaceIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedAgentIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedApplicationIds")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("Sub-agent cascade forwards child access modes into runtime credentials")
        void subAgentCascadeForwardsAccessModes() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "tables", List.of("table-1"),
                "agents", List.of(AGENT_ID.toString()),
                "tableAccessMode", "read",
                "workflowAccessMode", "read",
                "interfaceAccessMode", "write",
                "agentAccessMode", "read",
                "applicationAccessMode", "write",
                "skillAccessMode", "read",
                "fileAccessMode", "read",
                "memoryAccessMode", "read"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("table-1"));
            assertThat(subCreds).containsEntry("allowedAgentIds", List.of(AGENT_ID.toString()));
            assertThat(subCreds).containsEntry("tableAccessMode", "read");
            assertThat(subCreds).containsEntry("workflowAccessMode", "read");
            assertThat(subCreds).containsEntry("interfaceAccessMode", "write");
            assertThat(subCreds).containsEntry("agentAccessMode", "read");
            assertThat(subCreds).containsEntry("applicationAccessMode", "write");
            assertThat(subCreds).containsEntry("skillAccessMode", "read");
            assertThat(subCreds).containsEntry("fileAccessMode", "read");
            // Memory rides the same cascade. A child that inherits every other mode
            // but not this one falls back to the permissive default on the one axis
            // whose writes are durable and outlive the conversation that made them.
            assertThat(subCreds).containsEntry("memoryAccessMode", "read");
        }

        @Test
        @DisplayName("Sub-agent cascade: an UNKNOWN grant ('bogus') → [] deny-by-default, never the stale list (must NOT fail OPEN)")
        void subAgentCascadeUnknownGrantFailsClosed() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "workflows", List.of("wf-stale"), // a stale list behind a junk grant
                "workflowsGrant", "bogus"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // Deny: [] (present, not omitted → not unrestricted), and NOT the stale ['wf-stale'].
            assertThat(subCreds).containsEntry("allowedWorkflowIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent cascade stringifies a NUMERIC table allow-list (MCP stores tables:[209] as Integer)")
        void subAgentCascadeStringifiesNumericTableAllowlist() {
            // Regression: an agent created via MCP stores its table allow-list with the
            // native JSON type, so `tables:[209]` is a List<Integer>. Tool modules compare
            // with `.contains(String.valueOf(id))`, and a List<Integer> never contains a
            // String → silent "This table is not in your approved table list." The cascade
            // must normalize every element to String before forwarding it.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "none",
                "tables", List.of(209, 42)
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // Stringified, not the raw List<Integer>.
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("209", "42"));
            // And the canonical read path resolves the same strings the tool modules compare against.
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table")).isEqualTo(List.of("209", "42"));
        }

        @Test
        @DisplayName("Sub-agent cascade forwards child file allow-list (toolsConfig.files) into runtime credentials")
        void subAgentCascadeForwardsChildFileScope() {
            // A scoped sub-agent must carry its own file allow-list like tables/workflows;
            // FilesToolsProvider reads allowedFileIds to scope list/get/view to those ids.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "none",
                "files", List.of("file-1", "file-2")
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds).containsEntry("allowedFileIds", List.of("file-1", "file-2"));
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isEqualTo(List.of("file-1", "file-2"));
        }

        @Test
        @DisplayName("Sub-agent file scope is opt-in: a config-less child of an UNSCOPED parent stays unrestricted")
        void subAgentFileScopeOptInWhenNeitherParentNorChildScopes() {
            // Files are opt-in everywhere: no parent forward + no child files list = full org
            // access (null resolved scope). The child must NOT get allowedFileIds=[] (which for
            // the 5 internal resources means deny-all) - that asymmetry is the whole point.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of("mode", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            // Parent credentials carry NO __allowedFileIds__ (unscoped parent).
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // No empty allowedFileIds written (unlike the 5 internal resources, which ARE []).
            assertThat(subCreds).doesNotContainKey("allowedFileIds");
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isNull();
            // Contrast: an internal resource with no child config IS locked to [].
            assertThat(subCreds).containsEntry("allowedTableIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent grant='all' + empty list → allowed<Family>Ids OMITTED (unrestricted)")
        void subAgentCascadeGrantAllOmitsCredential() {
            // The bug: a grant:'all' + empty-list child was BLOCKED on every family when run
            // as a sub-agent, because passAllowedIds wrote allowed<Family>Ids=[] (deny-all)
            // ignoring the grant. With the omission fix, grant='all' OMITS the credential
            // → ToolAccessControl.getAllowedIds returns null = unrestricted access.
            AgentEntity entity = createAgent();
            // workflows + tables granted 'all' with EMPTY lists (V163 self-describing shape).
            entity.setToolsConfig(Map.of(
                "mode", "all",
                "workflows", List.of(),
                "workflowsGrant", "all",
                "tables", List.of(),
                "tablesGrant", "all"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // grant='all' families: credential OMITTED → unrestricted.
            assertThat(subCreds).doesNotContainKey("allowedWorkflowIds");
            assertThat(subCreds).doesNotContainKey("allowedTableIds");
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "workflow")).isNull();
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table")).isNull();
            // Ungranted families with no list still get []  (deny-all default).
            assertThat(subCreds).containsEntry("allowedInterfaceIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent grant='none' → [], grant='custom'+list → the list")
        void subAgentCascadeGrantNoneAndCustom() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "all",
                // workflows: none → []
                "workflows", List.of(),
                "workflowsGrant", "none",
                // tables: custom with a list → that list
                "tables", List.of("table-1", "table-2"),
                "tablesGrant", "custom"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // grant='none' → deny-all empty list (present, not omitted).
            assertThat(subCreds).containsEntry("allowedWorkflowIds", List.of());
            // grant='custom' → exactly the configured list.
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("table-1", "table-2"));
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table"))
                .containsExactly("table-1", "table-2");
        }

        @Test
        @DisplayName("should save assistant response after execution")
        void shouldSaveAssistantResponse() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Final response"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify assistant message saved
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("assistant"), eq("Final response"), any(), eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("should record observability after execution")
        void shouldRecordObservability() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(observabilityService).recordFromRequest(any());
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimitTests {

        @Test
        @DisplayName("should allow up to 5 executions per turn (unified maxPerResourcePerTurn default)")
        void shouldAllowUpTo5() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("turnId", "same-turn");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));

            // Stub Redis increment to return sequential counts (1..6)
            when(valueOperations.increment(anyString()))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

            // First 5 should succeed
            for (int i = 0; i < 5; i++) {
                ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
                assertThat(result.success()).isTrue();
            }

            // 6th should fail
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("LIMIT REACHED");
        }

        @Test
        @DisplayName("should proceed when Redis rate limit throws (graceful degradation)")
        void shouldProceedWhenRedisRateLimitFails() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            // Redis throws on increment → graceful degradation, execution should proceed
            when(valueOperations.increment(anyString()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Connection refused"));

            Map<String, Object> creds = defaultCredentials();
            creds.put("turnId", "some-turn");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));

            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("Response Truncation")
    class TruncationTests {

        @Test
        @DisplayName("should truncate long responses and save full content")
        void shouldTruncateLongResponse() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Create a response longer than 50000 chars
            String longContent = "x".repeat(60000);
            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text(longContent), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            when(conversationServiceClient.saveToolResult(
                any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
                .thenReturn("result-id");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("truncated");
            assertThat(result.content()).contains("full_response_tool_call_id");

            // Verify full content was saved
            verify(conversationServiceClient).saveToolResult(
                eq("parent-conv-123"), eq(TENANT_ID),
                startsWith("agent_execute:"), any(),
                eq(true), anyLong(), eq(longContent), isNull());
        }
    }

    @Nested
    @DisplayName("Tool Resolution")
    class ToolResolutionTests {

        @Test
        @DisplayName("toolsConfig=null resolves through the canonical NO-CONFIG module set (never the unfiltered cache) so the credit-spending tools stay out")
        void shouldUseNoConfigModuleSetWhenConfigNull() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(null);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(
                ToolDefinition.builder().name("agent").build(),
                ToolDefinition.builder().name("workflow").build()
            ));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().tools()).hasSize(2);
            // The FILTERED overload is used with the no-config module set: pre-fix this branch
            // short-circuited on the null and returned the whole cache, handing a config-less
            // child the credit-spending generation / image_generation tools.
            verify(coreToolsCache).getCoreTools(argThat(set ->
                !set.contains("generation") && !set.contains("image_generation")
                    && set.contains("catalog") && set.contains("table")));
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("mode='none' scopes to internal modules (catalog blocked) - canonical resolution, parity with chat & workflow (NOT zero tools)")
        void modeNoneScopesToInternalModulesBlockingCatalog() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of("mode", "none"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(
                ToolDefinition.builder().name("table").build()));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Canonical AgentModuleResolver semantics: mode=none blocks only MCP/catalog tools;
            // internal tools (table, agent, …) stay enabled. The sub-agent path now resolves via
            // AgentModuleResolver → coreToolNames exactly like chat & workflow - it USED to return
            // List.of() (zero tools), diverging from the other two paths. So we assert the FILTERED
            // overload is used with catalog dropped but internal tools present - never the no-arg one.
            verify(coreToolsCache).getCoreTools(argThat(set ->
                !set.contains("catalog") && set.contains("table")));
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("mode='custom' scopes by per-family grants (canonical) - granted families enabled, ungranted blocked; raw 'tools' list is no longer the gate")
        void modeCustomScopesByFamilyGrants() {
            AgentEntity entity = createAgent();
            // Canonical custom: the per-family <family>Grant fields decide access (the raw `tools`
            // list is only the "custom" payload, never the gate). tables+agents granted ⇒ enabled;
            // catalog/skill/files are always-on; interfaces/workflows/applications absent ⇒ blocked.
            entity.setToolsConfig(Map.of("mode", "custom", "tablesGrant", "all", "agentsGrant", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(
                ToolDefinition.builder().name("table").build(),
                ToolDefinition.builder().name("agent").build()
            ));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(coreToolsCache).getCoreTools(argThat(set ->
                set.contains("table") && set.contains("agent")          // granted families
                && !set.contains("workflow") && !set.contains("interface"))); // ungranted ⇒ blocked
            verify(coreToolsCache, never()).getCoreTools();
        }
    }

    @Nested
    @DisplayName("Memory")
    class MemoryTests {

        private void setupAgentExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);
        }

        @Test
        @DisplayName("should load conversation history by default (memory=true)")
        void shouldLoadHistoryByDefault() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(
                    Map.of("role", "user", "content", "Previous question"),
                    Map.of("role", "assistant", "content", "Previous answer")
                ));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify conversation history was loaded
            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);

            // Verify history was passed to agent loop
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            List<Message> history = contextCaptor.getValue().conversationHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).role()).isEqualTo(Message.Role.USER);
            assertThat(history.get(0).content()).isEqualTo("Previous question");
            assertThat(history.get(1).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(history.get(1).content()).isEqualTo("Previous answer");
        }

        @Test
        @DisplayName("should skip history when memory=false")
        void shouldSkipHistoryWhenMemoryFalse() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "New task", "memory", false));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify conversation history was NOT loaded
            verify(conversationServiceClient, never()).getConversationMessages(any(), anyInt(), any());

            // Verify empty history passed to agent loop
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should pass memory=true explicitly")
        void shouldLoadHistoryWhenMemoryTrue() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("role", "user", "content", "Hello")));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "New task", "memory", true));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);
        }

        @Test
        @DisplayName("should handle empty conversation gracefully")
        void shouldHandleEmptyConversation() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "First task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should skip system and tool messages from history")
        void shouldFilterSystemAndToolMessages() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(
                    Map.of("role", "system", "content", "System prompt"),
                    Map.of("role", "user", "content", "Question"),
                    Map.of("role", "assistant", "content", "Answer"),
                    Map.of("role", "tool", "content", "Tool result")
                ));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Next"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            List<Message> history = contextCaptor.getValue().conversationHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).content()).isEqualTo("Question");
            assertThat(history.get(1).content()).isEqualTo("Answer");
        }

        @Test
        @DisplayName("should treat non-boolean memory param as default (true)")
        void shouldTreatStringMemoryAsDefault() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("role", "user", "content", "Old msg")));

            // Pass memory as string "false" - not a Boolean, so defaults to true
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Task", "memory", "false"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // String "false" is not Boolean → defaults to true → history loaded
            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);
        }

        @Test
        @DisplayName("should handle getConversationMessages exception gracefully")
        void shouldHandleMessageLoadException() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Task"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Should still succeed with empty history
            assertThat(result.success()).isTrue();

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));
            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should record memoryEnabled in observability")
        void shouldRecordMemoryInObservability() {
            setupAgentExecution();

            // Execute with memory=false
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Task", "memory", false));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getMemoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("should record memoryEnabled=true in observability by default")
        void shouldRecordMemoryTrueByDefault() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("should prepend SYSTEM and USER messages to observability conversation")
        void shouldPrependSystemAndUserMessages() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Summarize this dataset"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var req = obsCaptor.getValue();
            // System prompt now includes the full modular prompt (tool docs, core rules)
            // with the custom entity prompt appended at the end
            assertThat(req.getSystemPrompt()).contains("You are a helpful assistant.");
            assertThat(req.getSystemPrompt()).contains("# Available Tools");
            // Messages list starts with SYSTEM + USER (normally missing from AgentLoopResult
            // .conversationHistory() which excludes the initial prompts).
            assertThat(req.getMessages()).isNotNull();
            assertThat(req.getMessages()).hasSizeGreaterThanOrEqualTo(2);
            var sys = req.getMessages().get(0);
            assertThat(sys.getRole()).isEqualTo("SYSTEM");
            assertThat(sys.getContent()).contains("You are a helpful assistant.");
            assertThat(sys.getContent()).contains("# Available Tools");
            var usr = req.getMessages().get(1);
            assertThat(usr.getRole()).isEqualTo("USER");
            assertThat(usr.getContent()).isEqualTo("Summarize this dataset");
        }

        @Test
        @DisplayName("should include optional context in USER message when provided")
        void shouldIncludeContextInUserMessage() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Write a report",
                "context", "Q1 sales figures"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var req = obsCaptor.getValue();
            var usr = req.getMessages().get(1);
            assertThat(usr.getRole()).isEqualTo("USER");
            assertThat(usr.getContent()).contains("Q1 sales figures").contains("Write a report");
        }
    }

    // ==========================================================================
    // Cascade reservation (§4.4 AGENT_BUDGET_HIERARCHY.md)
    // ==========================================================================

    @Nested
    @DisplayName("Cascade Reservation")
    class CascadeReservationTests {

        private final UUID PARENT_AGENT_ID = UUID.randomUUID();

        private Map<String, Object> parentCredentials() {
            // __agentId__ is the reserved key SubAgentExecutionHandler reads to derive
            // callerAgentEntityId - without it, the handler treats the call as a root
            // invocation and the cascade path is skipped entirely.
            Map<String, Object> creds = defaultCredentials();
            creds.put("__agentId__", PARENT_AGENT_ID.toString());
            return creds;
        }

        private void setupAgentForSuccessfulExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);
        }

        @Test
        @DisplayName("should reserve chain with parent id when spawned from another agent")
        void reservesChainOnSuccessfulSpawn() {
            setupAgentForSuccessfulExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isTrue();
            // Chain is nearest-first: [parent] because parent has no upstream __callerChain__.
            ArgumentCaptor<List<UUID>> chainCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(budgetReservationService).tryReserveChain(chainCaptor.capture(), amountCaptor.capture());
            assertThat(chainCaptor.getValue()).containsExactly(PARENT_AGENT_ID);
            assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("10"));
        }

        @Test
        @DisplayName("should return BUDGET_EXHAUSTED failure when ancestor lacks free budget")
        void insufficientBudgetExceptionShortCircuitsSpawn() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("conv-sub");

            // tryReserveChain rejects spawn: one ancestor ran out of room. The whole
            // cascade transaction is rolled back by BudgetReservationService internally,
            // so the handler is NOT expected to call settle/refund - there was nothing
            // successfully held to release.
            doThrow(new InsufficientBudgetException(PARENT_AGENT_ID, new BigDecimal("50")))
                .when(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 50));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("ancestor agent " + PARENT_AGENT_ID);
            assertThat(result.error()).contains("parent_reservation");
            assertThat(result.error()).contains("BUDGET_EXHAUSTED");

            // Spawn must short-circuit BEFORE any LLM work, but still emit the
            // execution failure to the live fleet stream, conversation, and metrics.
            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
            verify(agentActivityPublisher).publishExecutionStarted(
                eq(AGENT_ID.toString()), anyString(), eq("gpt-4"), eq("SUB_AGENT"), isNull());
            verify(agentActivityPublisher).publishExecutionCompleted(
                eq(AGENT_ID.toString()), anyString(), eq("FAILED"), eq(0), eq(0), anyLong(), isNull());
            verify(conversationServiceClient).saveMessage(
                eq("conv-sub"), eq("assistant"), contains("BUDGET_EXHAUSTED"),
                isNull(), eq(TENANT_ID), any());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            var obs = obsCaptor.getValue();
            assertThat(obs.getStatus()).isEqualTo("FAILED");
            assertThat(obs.getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
            assertThat(obs.getBudgetScope()).isEqualTo("parent_reservation");
            assertThat(obs.getCallerChain()).isNull();
            assertThat(obs.getMessages())
                .anySatisfy(message -> assertThat(message.getContent()).contains("BUDGET_EXHAUSTED"));
        }

        @Test
        @DisplayName("should refund reservation with ZERO actual when spawn fails after reserve")
        void refundsReservationOnEarlyFailureAfterReserve() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("conv-sub");            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Reservation succeeds (default mock behaviour - no throw), so reservationHeld=true.
            // Then agentLoopService blows up BEFORE recordObservability runs → the outer catch
            // must release the chain (full refund: actual=0) before the exception propagates.
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenThrow(new RuntimeException("LLM call exploded"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 25));

            try {
                handler.execute(toolCall, TENANT_ID, parentCredentials());
            } catch (Throwable t) {
                // Expected - the handler rethrows after refunding.
                assertThat(t).hasMessageContaining("LLM call exploded");
            }

            // Verify the reservation was actually taken. Use compareTo-based matchers -
            // the handler goes through toBigDecimal(25) which yields scale=1 ("25.0"), so
            // strict .equals() on a scale-0 constant would fail.
            verify(budgetReservationService).tryReserveChain(
                anyList(),
                argThat(v -> v != null && v.compareTo(new BigDecimal("25")) == 0));
            // Verify the refund path ran with actual=ZERO (full refund).
            ArgumentCaptor<List<UUID>> chainCaptor = ArgumentCaptor.forClass(List.class);
            verify(budgetReservationService).settleReservationChain(
                chainCaptor.capture(),
                argThat(v -> v != null && v.compareTo(new BigDecimal("25")) == 0),
                argThat(v -> v != null && v.compareTo(BigDecimal.ZERO) == 0));
            assertThat(chainCaptor.getValue()).containsExactly(PARENT_AGENT_ID);
            // observability was never reached → its settle hook did not fire, so this is the
            // only settle call - no double refund.
            verify(observabilityService, never()).recordFromRequest(any());
        }

        @Test
        @DisplayName("should NOT refund when spawn succeeds (ownership transferred to observability)")
        void doesNotDoubleRefundOnNormalCompletion() {
            setupAgentForSuccessfulExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isTrue();
            // Reservation was taken …
            verify(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));
            // … ownership transferred to observability - settle is NOT called from the handler
            // (the dual-refund guard: reservationHeld=false before recordObservability runs).
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
            // …but the chain + amount DID travel in the observability request so the downstream
            // settle hook can refund once.
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getCallerChain()).containsExactly(PARENT_AGENT_ID);
            assertThat(obsCaptor.getValue().getReservedAmount()).isEqualByComparingTo(new BigDecimal("10"));
        }

        @Test
        @DisplayName("should skip reservation entirely when invoked from root (no parent id)")
        void skipsReservationOnRootInvocation() {
            setupAgentForSuccessfulExecution();

            // Default credentials have no __agentId__ → callerAgentEntityId is null →
            // the handler does not even compute a chain and tryReserveChain is never called.
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(budgetReservationService, never()).tryReserveChain(anyList(), any());
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
        }
    }

    @Nested
    @DisplayName("Bridge Routing")
    class BridgeRoutingTests {

        @Mock private SubAgentBridgeClient bridgeClientMock;

        @BeforeEach
        void injectBridge() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
        }

        private AgentEntity createBridgeAgent(String provider, String model) {
            AgentEntity entity = createAgent();
            entity.setModelProvider(provider);
            entity.setModelName(model);
            return entity;
        }

        @Test
        @DisplayName("should route to bridge when provider is claude-code")
        void shouldRouteToBridgeForClaudeCode() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge response", "Bridge response", List.of(), 2,
                Map.of("promptTokens", 100, "completionTokens", 50), null, 5000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");
            assertThat(result.content()).contains("Bridge response");

            // Bridge client was called, NOT agentLoopService
            verify(bridgeClientMock).execute(any(AgentExecutionRequestDto.class));
            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
        }

        @Test
        @DisplayName("regression: a bridge child is recorded PLATFORM (a CLI holds no API key), never unpinned - the row used to read the route off the credentials, where a bridge child stamps nothing")
        void bridgeChildIsRecordedPlatform() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            stubConversationPlumbing();
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class)))
                .thenReturn(okBridgeResponse("claude-code", "claude-sonnet-4-6"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            // An unpinned row is billed the platform token rate by default, which happens to be
            // right for a bridge; the point is that the row SAYS so, like every other execution.
            assertThat(obsCaptor.getValue().getKeyRoute()).isEqualTo("PLATFORM");
        }

        private AgentExecutionResponseDto okBridgeResponse(String provider, String model) {
            return new AgentExecutionResponseDto(
                true, "Bridge response", "Bridge response", List.of(), 2,
                Map.of("promptTokens", 100, "completionTokens", 50), null, 5000L,
                provider, model, List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        }

        private void stubConversationPlumbing() {
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(ConversationRedisStreamingCallback.ConversationCallback.class));
        }

        @Test
        @DisplayName("a sub-agent that NAMES the CLI itself is refused by the access policy, and never reaches the bridge")
        void directBridgeSubAgentIsGatedByTheAccessPolicy() {
            // This branch had no access check at all - a non-admin's sub-agent COMPLETED twice on
            // claude-code and billed 2617 credits to the shared subscription, while the same
            // agent's scheduled fires were refused. Same agent, same user, opposite outcomes,
            // decided by which path dispatched it.
            AgentEntity entity = createBridgeAgent("claude-code", "claude-fable-5");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            stubConversationPlumbing();
            com.apimarketplace.agent.bridge.BridgeAccessGuard guard =
                mock(com.apimarketplace.agent.bridge.BridgeAccessGuard.class);
            org.mockito.Mockito.doThrow(new com.apimarketplace.agent.bridge.BridgeAccessDeniedException(
                    "claude-code", com.apimarketplace.agent.bridge.BridgeAccessDecision.REASON_NOT_ADMIN, null))
                .when(guard).enforce(any(), any(), eq("claude-code"), org.mockito.ArgumentMatchers.anyBoolean());
            handler.setBridgeAccessGuard(guard);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            verify(bridgeClientMock, never()).execute(any(AgentExecutionRequestDto.class));
        }

        @Test
        @DisplayName("a sub-agent ROUTED to the CLI by an execution link skips the access policy and runs there, restricted")
        void linkedBridgeSubAgentSkipsTheAccessPolicy() {
            // Production's Agenda Scout after its migration: stored on anthropic, sent to
            // claude-code by link 7, owned by a non-admin. The guard answers "may this user SELECT
            // this CLI"; a routed run is not a selection, so it must not even be asked.
            AgentEntity entity = createBridgeAgent("anthropic", "claude-fable-5");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            stubConversationPlumbing();
            ExecutionLinkRouter router = mock(ExecutionLinkRouter.class);
            when(router.runnableRoute(eq("anthropic"), eq("claude-fable-5"), any()))
                .thenReturn(new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-fable-5"));
            ReflectionTestUtils.setField(handler, "executionLinkRouter", router);
            com.apimarketplace.agent.bridge.BridgeAccessGuard guard =
                mock(com.apimarketplace.agent.bridge.BridgeAccessGuard.class);
            handler.setBridgeAccessGuard(guard);
            ArgumentCaptor<AgentExecutionRequestDto> sent = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(sent.capture())).thenReturn(okBridgeResponse("claude-code", "claude-fable-5"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "scan the agenda"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(guard, never()).enforce(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
            // And it is the SESSION the user gets, nothing more: the restricted marker travels with
            // the request, so the CLI runs with an empty cwd and none of its native tools.
            assertThat(sent.getValue().credentials())
                .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
        }

        @Test
        @DisplayName("bridge sub-agent request carries enabledModules scoped by the child's toolsConfig - mode=none drops catalog (guards the bridge over-billing on the sub-agent path)")
        void bridgeRequestScopesEnabledModulesFromChildToolsConfig() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            entity.setToolsConfig(Map.of("mode", "none")); // catalog/MCP blocked, internal kept
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(bridgeClientMock).execute(captor.capture());
            // The bridge ignores the explicit toolMaps and rebuilds its MCP tool set from
            // enabledModules; a null here (the original async-bug shape) would advertise every
            // core schema. mode=none ⇒ internal modules kept, catalog dropped.
            assertThat(captor.getValue().enabledModules())
                .as("bridge sub-agent must forward the mode-scoped module set (catalog dropped) to the CLI")
                .contains("table")
                .doesNotContain("catalog");
        }

        @Test
        @DisplayName("should route to bridge when provider is codex")
        void shouldRouteToBridgeForCodex() {
            AgentEntity entity = createBridgeAgent("codex", "codex-mini-latest");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Codex done", "Codex done", List.of(), 1,
                Map.of(), null, 2000L, "codex", "codex-mini-latest",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any(AgentExecutionRequestDto.class));
            verify(agentLoopService, never()).execute(any(), any(StreamingCallback.class));
        }

        @Test
        @DisplayName("should route to bridge when provider is gemini-cli")
        void shouldRouteToBridgeForGeminiCli() {
            AgentEntity entity = createBridgeAgent("gemini-cli", "gemini-2.5-pro");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Gemini done", "Gemini done", List.of(), 1,
                Map.of(), null, 3000L, "gemini-cli", "gemini-2.5-pro",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should route to bridge when provider is mistral-vibe")
        void shouldRouteToBridgeForMistralVibe() {
            AgentEntity entity = createBridgeAgent("mistral-vibe", "mistral-large");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Mistral done", "Mistral done", List.of(), 1,
                Map.of(), null, 1500L, "mistral-vibe", "mistral-large",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should NOT route to bridge for API providers (openai, anthropic, etc.)")
        void shouldNotRouteToBridgeForApiProviders() {
            AgentEntity entity = createAgent(); // openai/gpt-4 by default
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();

            // AgentLoopService was called, NOT bridge
            verify(agentLoopService).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            verify(bridgeClientMock, never()).execute(any());
        }

        @Test
        @DisplayName("should handle bridge returning null (failure)")
        void shouldHandleBridgeNullResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge returns null (connection failure, timeout, etc.)
            when(bridgeClientMock.execute(any())).thenReturn(null);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // The wrapper always succeeds, inner status in content indicates failure
            assertThat(result.content()).contains("FAILED");
            assertThat(result.content()).contains("no response from bridge server");
        }

        @Test
        @DisplayName("should handle bridge returning error response")
        void shouldHandleBridgeErrorResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto errorResponse = new AgentExecutionResponseDto(
                false, null, null, List.of(), 0, Map.of(),
                "CLI agent crashed", 1000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(errorResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.content()).contains("FAILED");
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should pass streaming context in bridge request")
        void shouldPassStreamingContextInBridgeRequest() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.provider()).isEqualTo("claude-code");
            assertThat(captured.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(captured.prompt()).isEqualTo("Do something");
            assertThat(captured.conversationId()).isEqualTo("conv-1");
            assertThat(captured.streamingFormat()).isEqualTo("conversation");
            assertThat(captured.parentConversationId()).isEqualTo("parent-conv-123");
            assertThat(captured.subAgentName()).isEqualTo("Test Agent");
            assertThat(captured.tenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.streamChannelId()).isNotNull();
        }

        @Test
        @DisplayName("should still record observability for bridge execution")
        void shouldRecordObservabilityForBridge() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 3, Map.of(), null, 5000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Observability should have been recorded
            verify(observabilityService).recordFromRequest(any());
        }

        @Test
        @DisplayName("should save assistant response to conversation for bridge execution")
        void shouldSaveConversationForBridge() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge result", "Bridge result", List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // User prompt saved + assistant response saved
            verify(conversationServiceClient, times(2))
                .saveMessage(eq("conv-1"), anyString(), anyString(), any(), eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("should work with cascade budget reservation via bridge")
        void shouldWorkWithCascadeReservationViaBridge() {
            UUID parentAgentId = UUID.randomUUID();

            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge done", "Bridge done", List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            // Parent credentials with __agentId__
            Map<String, Object> creds = defaultCredentials();
            creds.put("__agentId__", parentAgentId.toString());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");

            // Budget reservation was still taken via bridge path
            verify(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));
            // Bridge was used, not agentLoopService
            verify(bridgeClientMock).execute(any());
            verify(agentLoopService, never()).execute(any(), any(StreamingCallback.class));
            // Observability recorded with chain
            verify(observabilityService).recordFromRequest(any());
        }

        @Test
        @DisplayName("should truncate long bridge responses")
        void shouldTruncateLongBridgeResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Build a response longer than MAX_RESPONSE_LENGTH (50000 chars)
            String longContent = "X".repeat(60000);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, longContent, longContent, List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            // Allow saving full content tool result
            when(conversationServiceClient.saveToolResult(any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
                .thenReturn("tr-123");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            // Content in the result is truncated
            assertThat(result.content()).contains("truncated");
            assertThat(result.content()).contains("full_response_tool_call_id");
        }

        @Test
        @DisplayName("should pass agent budget to bridge request when budget is enabled")
        void shouldPassBudgetToBridgeRequest() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            entity.setCreditBudget(new BigDecimal("50"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            // Budget enabled: 50 total, 30 consumed
            BudgetState enabledBudget = new BudgetState(
                new BigDecimal("50"), new BigDecimal("30"), BigDecimal.ZERO, false);
            when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
                .thenReturn(enabledBudget);

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.maxCreditBudget()).isEqualTo(50.0);
            assertThat(captured.creditsConsumedSoFar()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("should NOT pass budget to bridge request when budget is disabled")
        void shouldNotPassBudgetWhenDisabled() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            // Budget disabled (default in setUp)

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.maxCreditBudget()).isNull();
            assertThat(captured.creditsConsumedSoFar()).isNull();
        }

        @Test
        @DisplayName("should propagate budgetScope from bridge response to observability")
        void shouldPropagateBudgetScopeToObservability() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge response with BUDGET_EXHAUSTED and budgetScope="agent"
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Partial result", "Partial result", List.of(), 1,
                Map.of("promptTokens", 100, "completionTokens", 50),
                null, 3000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "BUDGET_EXHAUSTED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "agent");
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var obs = obsCaptor.getValue();
            assertThat(obs.getBudgetScope()).isEqualTo("agent");
            assertThat(obs.getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
        }

        @Test
        @DisplayName("should map bridge response usage info correctly")
        void shouldMapBridgeResponseUsageInfo() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge response with detailed usage
            Map<String, Object> usage = Map.of(
                "promptTokens", 500, "completionTokens", 200, "totalTokens", 700,
                "cacheCreationInputTokens", 50, "cacheReadInputTokens", 30);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 2, usage,
                null, 3000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(),
                List.of(1500L, 1500L), List.of("tool_use", "end_turn"),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            // Verify observability request has the usage data
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var obs = obsCaptor.getValue();
            assertThat(obs.getPromptTokens()).isEqualTo(500);
            assertThat(obs.getCompletionTokens()).isEqualTo(200);
            assertThat(obs.getTotalTokens()).isEqualTo(700);
            assertThat(obs.getIterationCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("SubAgentBridgeClient.isBridgeProvider")
    class BridgeProviderDetectionTests {

        @Test
        @DisplayName("should detect all bridge providers")
        void shouldDetectBridgeProviders() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("claude-code")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("codex")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("gemini-cli")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("mistral-vibe")).isTrue();
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("Claude-Code")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("CLAUDE-CODE")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("CODEX")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("Gemini-CLI")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("MISTRAL-VIBE")).isTrue();
        }

        @Test
        @DisplayName("should reject API providers")
        void shouldRejectApiProviders() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("openai")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("anthropic")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("google")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("mistral")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("deepseek")).isFalse();
        }

        @Test
        @DisplayName("should reject null and blank")
        void shouldRejectNullAndBlank() {
            assertThat(SubAgentBridgeClient.isBridgeProvider(null)).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("  ")).isFalse();
        }
    }

    // ==========================================================================
    // V100: per-agent unified maxPerResourcePerTurn override resolution
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        private AgentEntity mockCaller(UUID id) {
            AgentEntity e = new AgentEntity();
            e.setId(id);
            e.setName("Caller");
            return e;
        }

        @Test
        @DisplayName("Returns YAML default (5) when credentials is null")
        void fallsBackWhenCredentialsNull() {
            assertThat(handler.resolveMaxPerResourcePerTurn(null)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns YAML default when no __agentId__ in credentials")
        void fallsBackWhenNoAgentId() {
            assertThat(handler.resolveMaxPerResourcePerTurn(Map.of("turnId", "turn-1")))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Returns per-agent override when entity has non-null positive value")
        void usesPerAgentOverride() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockCaller(callerId);
            caller.setMaxPerResourcePerTurn(7);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(7);
        }

        @Test
        @DisplayName("Falls back to default when entity override is null")
        void fallsBackWhenOverrideNull() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockCaller(callerId);
            caller.setMaxPerResourcePerTurn(null);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default when agent not found")
        void fallsBackWhenAgentMissing() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenReturn(Optional.empty());

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default on lookup exception (soft-fail)")
        void softFailsOnException() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenThrow(new RuntimeException("DB down"));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back on malformed __agentId__ string")
        void fallsBackOnMalformedUuid() {
            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", "not-a-uuid")))
                .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("max_tokens / timeout clamping at the sub-agent build site")
    class BuildSiteClampWiring {

        /**
         * Runs a happy-path sub-agent execution and returns the AgentLoopContext handed to the loop.
         * {@code entityMaxTokens} may be null to exercise the platform-default fallback;
         * {@code timeoutParam} (the tool-call "timeout" arg) may be null to use the default.
         */
        private AgentLoopContext runAndCaptureContext(Integer entityMaxTokens, Integer timeoutParam) {
            AgentEntity entity = createAgent();
            entity.setMaxTokens(entityMaxTokens);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-sub");            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("done"), List.of(), 1, null, 100, "openai", "gpt-4"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "execute");
            args.put("agent_id", AGENT_ID.toString());
            args.put("prompt", "go");
            if (timeoutParam != null) {
                args.put("timeout", timeoutParam);
            }
            handler.execute(createToolCall(args), TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            return ctx.getValue();
        }

        @Test
        @DisplayName("falls back to the safe 8192 floor when the catalog has no cap (modelCatalog absent)")
        void clampsToSafeFloorWhenCatalogMissing() {
            // modelCatalog is not injected here → resolveMaxOutputTokens is not consulted,
            // so the unknown-cap floor applies. A high entity budget must NOT reach the LLM.
            AgentLoopContext context = runAndCaptureContext(16000, null);
            assertThat(context.maxTokens()).isEqualTo(8192); // 16000 → floor (would have 400'd a low-cap model)
        }

        @Test
        @DisplayName("clamps the configured budget to the model's real catalog ceiling")
        void clampsToModelCeilingFromCatalog() {
            com.apimarketplace.agent.service.ModelCatalogService modelCatalog =
                mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            when(modelCatalog.resolveMaxOutputTokens(anyString(), anyString())).thenReturn(4096);
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "modelCatalog", modelCatalog);

            AgentLoopContext context = runAndCaptureContext(16000, null);
            assertThat(context.maxTokens()).isEqualTo(4096); // capped to the catalog ceiling, not the 8192 floor
        }

        @Test
        @DisplayName("null entity budget uses the platform default (16000), then clamps to the floor")
        void nullEntityBudgetUsesPlatformDefaultThenFloor() {
            // Pins the null-fallback: pre-fix this used a hardcoded 4096 (→ 4096); now it uses
            // agentDefaults.getMaxTokens()=16000, clamped to the 8192 floor (no catalog).
            AgentLoopContext context = runAndCaptureContext(null, null);
            assertThat(context.maxTokens()).isEqualTo(8192);
        }

        @Test
        @DisplayName("a sub-agent timeout above the old 3600 cap now survives up to 7200s")
        void timeoutSurvivesUpToRaisedCap() {
            // Pre-raise this was clamped to 3600; the cap is now 7200.
            AgentLoopContext context = runAndCaptureContext(4096, 7200);
            assertThat(context.executionTimeout()).isEqualTo(7200);
        }

        @Test
        @DisplayName("a sub-agent timeout above 7200 is still clamped to the 7200 ceiling")
        void timeoutClampedToRaisedCap() {
            AgentLoopContext context = runAndCaptureContext(4096, 9000);
            assertThat(context.executionTimeout()).isEqualTo(7200);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Model execution links
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Model execution links")
    class ExecutionLinks {

        private final com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute apiRoute =
            new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("openrouter", "openai/gpt-4o");
        private final com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute bridgeRoute =
            new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-8");

        @Mock private SubAgentBridgeClient bridgeClientMock;

        private ExecutionLinkRouter router;

        @BeforeEach
        void wireRouter() {
            router = mock(ExecutionLinkRouter.class);
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "executionLinkRouter", router);
        }

        @Test
        @DisplayName("a linked sub-agent runs the loop on the EXECUTION pair")
        void linkedSubAgentRunsOnExecutionPair() {
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(apiRoute);

            AgentLoopContext context = runLinkedAndCaptureContext();

            assertThat(context.provider()).isEqualTo("openrouter");
            assertThat(context.model()).isEqualTo("openai/gpt-4o");
        }

        @Test
        @DisplayName("an unlinked sub-agent keeps its own pair")
        void unlinkedSubAgentIsUnchanged() {
            when(router.runnableRoute(any(), any(), any())).thenReturn(null);

            AgentLoopContext context = runLinkedAndCaptureContext();

            assertThat(context.provider()).isEqualTo("openai");
            assertThat(context.model()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("a bridge-linked sub-agent dispatches to the bridge in restricted API mode")
        void bridgeLinkedSubAgentDispatchesRestricted() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                    "claude-code", "claude-opus-4-8", List.of(), "COMPLETED",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentExecutionRequestDto> dispatched =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(bridgeClientMock).execute(dispatched.capture());
            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            assertThat(dispatched.getValue().provider()).isEqualTo("claude-code");
            assertThat(dispatched.getValue().model()).isEqualTo("claude-opus-4-8");
            // Without the marker the CLI would run a linked model with its native file
            // tools and the project cwd, which the billed API model never has.
            assertThat(dispatched.getValue().credentials())
                .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
        }

        @Test
        @DisplayName("a linked sub-agent is recorded on its billed pair even though the loop reports the execution one")
        void linkedSubAgentRecordsTheBilledPair() {
            when(router.runnableRoute(any(), any(), any())).thenReturn(apiRoute);
            // The loop echoes what it actually ran on, which is what production does. If the
            // recording ever read the RESULT instead of the agent entity, the ledger would
            // charge the execution pair and this assertion would catch it.
            runLinkedAndCaptureContext(AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openrouter", "openai/gpt-4o"));

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> recorded =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(recorded.capture());
            // The ledger is fed from this row: a link moves execution, never the price.
            assertThat(recorded.getValue().getProvider()).isEqualTo("openai");
            assertThat(recorded.getValue().getModel()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("an API-targeted link leaves the shared credentials map free of the restricted marker")
        void apiLinkedSubAgentIsNotRestricted() {
            when(router.runnableRoute(any(), any(), any())).thenReturn(apiRoute);

            AgentLoopContext context = runLinkedAndCaptureContext();

            // subCredentials is shared with observability and task resolution, so a stray
            // marker would travel further than the one run that set it.
            assertThat(context.credentials())
                .doesNotContainKey(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY);
        }

        @Test
        @DisplayName("an UNLINKED bridge sub-agent keeps its tools: the restriction is for linked runs only")
        void unlinkedBridgeSubAgentIsNotRestricted() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute(any(), any(), any())).thenReturn(null);
            AgentEntity entity = createAgent();
            entity.setModelProvider("claude-code");
            entity.setModelName("claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                    "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentExecutionRequestDto> dispatched =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(bridgeClientMock).execute(dispatched.capture());
            // A sub-agent chosen on a CLI provider is a real agent with a real toolset,
            // unlike the single-shot classify and guardrail judges, so nothing is removed.
            assertThat(dispatched.getValue().credentials())
                .doesNotContainKey(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY);
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: the bridge hop is transparent for the key route - the retry on the billed pair inherits the parent's OWN_KEY, never a bridge pin")
        void bridgeLinkedSubAgentFallbackInheritsTheParentsRoute() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            KeyRouteResolver keyRouteResolver = mock(KeyRouteResolver.class);
            ReflectionTestUtils.setField(handler, "keyRouteResolver", keyRouteResolver);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            ArgumentCaptor<AgentExecutionRequestDto> bridgeRequest = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(bridgeRequest.capture())).thenReturn(
                new AgentExecutionResponseDto(false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10L,
                    "claude-code", "claude-opus-4-8", List.of(), "ERROR",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));
            // The parent ran on its OWN openai key; this child is billed on openai, linked to claude-code.
            Map<String, Object> creds = defaultCredentials();
            com.apimarketplace.agent.domain.KeyRoute.stamp(creds, com.apimarketplace.agent.domain.KeyRoute.OWN_KEY, "openai");

            handler.execute(createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello")), TENANT_ID, creds);

            // The regression this guards: the bridge attempt used to re-stamp (PLATFORM, claude-code)
            // in place, and the retry on openai then inherited PLATFORM for a user on their own key.
            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            assertThat(ctx.getValue().provider()).isEqualTo("openai");
            assertThat(ctx.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            assertThat(ctx.getValue().credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");
            // The bridge attempt itself carried the parent's stamp untouched (no bridge pin written),
            // and no lookup was made for a CLI that holds no API key.
            assertThat(bridgeRequest.getValue().credentials())
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.CREDENTIAL_KEY, "OWN_KEY")
                .containsEntry(com.apimarketplace.agent.domain.KeyRoute.PROVIDER_CREDENTIAL_KEY, "openai");
            verify(keyRouteResolver, never()).resolve(any(), any());
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: a bridge-linked sub-agent that fails BEFORE producing output silently retries on the billed pair's direct API")
        void bridgeLinkedSubAgentFallsBackToDirectApiOnEmptyFailure() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            // Distinct catalog resolutions for the EXECUTION pair (claude-code/claude-opus-4-8, a
            // large-context model) vs the BILLED pair (openai/gpt-4, a much smaller one) - if the
            // fallback context reused the execution pair's resolved limits (the bug this test
            // guards against), these assertions below would see the execution values instead.
            var catalog = mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            ReflectionTestUtils.setField(handler, "modelCatalog", catalog);
            when(catalog.resolveMaxOutputTokens("claude-code", "claude-opus-4-8")).thenReturn(64000);
            when(catalog.resolveContextWindow("claude-code", "claude-opus-4-8")).thenReturn(200000);
            when(catalog.resolveEffortWithDefault(any(), org.mockito.ArgumentMatchers.eq("claude-code"),
                org.mockito.ArgumentMatchers.eq("claude-opus-4-8"))).thenReturn("xhigh");
            when(catalog.resolveMaxOutputTokens("openai", "gpt-4")).thenReturn(4096);
            when(catalog.resolveContextWindow("openai", "gpt-4")).thenReturn(8192);
            when(catalog.resolveEffortWithDefault(any(), org.mockito.ArgumentMatchers.eq("openai"),
                org.mockito.ArgumentMatchers.eq("gpt-4"))).thenReturn("medium");
            // The bridge fails BEFORE producing anything visible: no content, no tool results.
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10L,
                    "claude-code", "claude-opus-4-8", List.of(), "ERROR",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            // The fallback ran on the BILLED pair, never on claude-code.
            assertThat(ctx.getValue().provider()).isEqualTo("openai");
            assertThat(ctx.getValue().model()).isEqualTo("gpt-4");
            // No restricted-toolset marker travels into a direct-API call.
            assertThat(ctx.getValue().credentials())
                .doesNotContainKey(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY);
            // maxTokens/contextWindow/reasoningEffort must be RE-resolved against the billed pair,
            // not left stale from the (discarded) execution-pair context: a stale 64000-token cap
            // sent to a 4096-cap model would 400 the very call this fallback exists to rescue.
            assertThat(ctx.getValue().maxTokens()).isEqualTo(4096);
            assertThat(ctx.getValue().contextWindow()).isEqualTo(8192);
            assertThat(ctx.getValue().reasoningEffort()).isEqualTo("medium");
            // Invisible to the caller: the failed bridge attempt never surfaces - the inner
            // status (embedded in content, since the wrapper ToolResult always succeeds)
            // reads COMPLETED, from the fallback's own successful direct-API run.
            assertThat(result.content()).contains("COMPLETED");
            // Fleet activity: exactly one started/completed pair for this logical execution -
            // both calls sit outside the bridge/direct branch (published once regardless of
            // which branch resolves `result`), so no duplicate/blip from the aborted attempt.
            verify(agentActivityPublisher, org.mockito.Mockito.times(1)).publishExecutionStarted(
                any(), any(), any(), any(), any());
            verify(agentActivityPublisher, org.mockito.Mockito.times(1)).publishExecutionCompleted(
                any(), any(), org.mockito.ArgumentMatchers.eq("COMPLETED"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), any());
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: when the direct-API retry ALSO fails, the error surfaces normally on the billed pair (a single retry, never a loop)")
        void bridgeLinkedSubAgentFallbackAlsoFailsSurfacesError() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            // The bridge fails BEFORE producing anything visible: no content, no tool results.
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10L,
                    "claude-code", "claude-opus-4-8", List.of(), "ERROR",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
            // The fallback's own direct-API attempt ALSO fails.
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.failure("upstream 500", 10, "openai", AgentStopReason.ERROR));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Exactly one bridge attempt and one fallback attempt - never a second bridge try,
            // never a second fallback try (no loop).
            verify(bridgeClientMock, org.mockito.Mockito.times(1)).execute(any(AgentExecutionRequestDto.class));
            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService, org.mockito.Mockito.times(1)).execute(ctx.capture(), any(StreamingCallback.class));
            // The fallback ran on the BILLED pair, never on claude-code, even on a double failure.
            assertThat(ctx.getValue().provider()).isEqualTo("openai");
            assertThat(ctx.getValue().model()).isEqualTo("gpt-4");
            // The wrapper tool call always succeeds; the double failure is reported in the
            // content's embedded status (same contract as shouldHandleBridgeErrorResponse).
            assertThat(result.content()).contains("FAILED");
        }

        @Test
        @DisplayName("A bridge-linked sub-agent that already produced visible content before failing does NOT fall back")
        void bridgeLinkedSubAgentWithVisibleContentDoesNotFallBack() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            // The CLI streamed a partial answer before crashing - it already reached the user.
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(false, "Partial answer", "Partial answer", List.of(), 0, Map.of(),
                    "CLI crashed mid-stream", 10L, "claude-code", "claude-opus-4-8", List.of(), "ERROR",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            // The wrapper tool call always succeeds; the sub-agent's own failure is reported
            // in the content's embedded status (same contract as shouldHandleBridgeErrorResponse).
            assertThat(result.content()).contains("FAILED");
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: records a Prometheus fallback counter for operators")
        void bridgeLinkedSubAgentFallbackRecordsPrometheusMetric() {
            com.apimarketplace.agent.metrics.AgentPrometheusMetrics metrics =
                mock(com.apimarketplace.agent.metrics.AgentPrometheusMetrics.class);
            ReflectionTestUtils.setField(handler, "prometheusMetrics", metrics);
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
            when(router.runnableRoute("openai", "gpt-4", "SUB_AGENT")).thenReturn(bridgeRoute);
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(
                new AgentExecutionResponseDto(false, null, null, List.of(), 0, Map.of(), "CLI crashed", 10L,
                    "claude-code", "claude-opus-4-8", List.of(), "ERROR",
                    Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(metrics).recordExecutionLinkFallback("openai", "gpt-4", "claude-code");
        }

        @Test
        @DisplayName("a linked sub-agent clamps output tokens and reasoning effort against the EXECUTION model")
        void linkedSubAgentResolvesModelLimitsOnTheExecutionPair() {
            when(router.runnableRoute(any(), any(), any())).thenReturn(apiRoute);
            var catalog = mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            ReflectionTestUtils.setField(handler, "modelCatalog", catalog);
            when(catalog.resolveMaxOutputTokens("openrouter", "openai/gpt-4o")).thenReturn(4096);
            when(catalog.resolveEffortWithDefault(any(), org.mockito.ArgumentMatchers.eq("openrouter"),
                org.mockito.ArgumentMatchers.eq("openai/gpt-4o"))).thenReturn("high");

            AgentLoopContext context = runLinkedAndCaptureContext();

            // Both describe the model that actually runs the turn, so they follow the
            // execution pair - the same rule the agent path applies.
            assertThat(context.maxTokens()).isEqualTo(4096);
            assertThat(context.reasoningEffort()).isEqualTo("high");
        }

        @Test
        @DisplayName("the sub-agent surface matches no scope, so only an ALL link can apply")
        void resolvesWithTheSubAgentSource() {
            when(router.runnableRoute(any(), any(), any())).thenReturn(null);

            runLinkedAndCaptureContext();

            verify(router).runnableRoute("openai", "gpt-4", "SUB_AGENT");
        }

        private AgentLoopContext runLinkedAndCaptureContext() {
            return runLinkedAndCaptureContext(AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));
        }

        private AgentLoopContext runLinkedAndCaptureContext(AgentLoopResult loopResult) {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(captor.capture(), any(StreamingCallback.class));
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("long-term memory injection")
    class LongTermMemoryInjection {

        private void setupAgentExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);
        }

        @Test
        @DisplayName("the block the renderer produced really reaches the sub-agent's prompt")
        void theBlockReachesTheLoop() {
            setupAgentExecution();
            var section = mock(com.apimarketplace.agent.memory.MemoryPromptSection.class);
            // Returns something that could only have come from the renderer, and
            // returns it as the WHOLE prompt, so an implementation that called
            // appendTo and threw the result away fails here. A source scan for the
            // call site cannot tell those two apart.
            when(section.appendTo(anyString(), any(), any())).thenReturn("PROMPT-WITH-MEMORY-BLOCK");
            ReflectionTestUtils.setField(handler, "memoryPromptSection", section);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));
            assertThat(contextCaptor.getValue().systemPrompt()).isEqualTo("PROMPT-WITH-MEMORY-BLOCK");
        }

        @Test
        @DisplayName("scopes the lookup to the sub-agent itself, so it cannot read a sibling's private entries")
        void scopesTheLookupToTheSubAgent() {
            setupAgentExecution();
            var section = mock(com.apimarketplace.agent.memory.MemoryPromptSection.class);
            when(section.appendTo(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
            ReflectionTestUtils.setField(handler, "memoryPromptSection", section);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Passing the PARENT's id here, or null, would hand the sub-agent either
            // another agent's private memory or none of its own. Neither is visible
            // in the rendered prompt, so it has to be asserted on the argument.
            ArgumentCaptor<java.util.UUID> agentCaptor = ArgumentCaptor.forClass(java.util.UUID.class);
            verify(section).appendTo(anyString(), any(), agentCaptor.capture());
            assertThat(agentCaptor.getValue()).isEqualTo(AGENT_ID);
        }

        @Test
        @DisplayName("runs WITHOUT memory when the enrichment throws, rather than losing the whole delegation")
        void aFailedEnrichmentDoesNotFailTheDelegation() {
            setupAgentExecution();
            var section = mock(com.apimarketplace.agent.memory.MemoryPromptSection.class);
            when(section.appendTo(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("memory backend is down"));
            ReflectionTestUtils.setField(handler, "memoryPromptSection", section);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // "Not wired" was already covered; this is the case where it IS wired and
            // misbehaves, which is the one that happens in production. A delegation that
            // dies here loses the sub-agent's entire run and hands the parent an error
            // it can do nothing about, over an enrichment.
            verify(agentLoopService).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
        }

                @Test
        @DisplayName("runs normally when memory is not wired at all, since it is an enrichment and not a dependency")
        void runsWithoutTheRenderer() {
            setupAgentExecution();
            ReflectionTestUtils.setField(handler, "memoryPromptSection", null);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(agentLoopService).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
        }
    }

    /**
     * A delegated sub-agent's observability row is stamped with the AGENT ENTITY's
     * provider - the billed pair - while a model execution link can have run it on a CLI
     * bridge. The Claude Code bridge folds the cache into its prompt total and the
     * Anthropic API does not, so carrying the bridge's numbers under the billed label
     * charges the cached tokens twice: once at full input rate inside the prompt, then
     * again on their own discounted line. Observed in production at 3.80x the cost of the
     * identical run on the directly-selected bridge.
     */
    @Nested
    @DisplayName("Bridge response conversion re-expresses usage in the BILLED convention")
    class BridgeUsageConvention {

        private AgentExecutionResponseDto bridgeResponse(java.util.Map<String, Object> usage) {
            return new AgentExecutionResponseDto(
                true, "answer", "answer", java.util.List.of(), 1, usage, null, 10,
                "claude-code", "claude-fable-5", java.util.List.of(), "COMPLETED",
                java.util.Map.of(), java.util.List.of(usage), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
        }

        /** Shape of a real production row: 6 plain input tokens under a 98,319 total. */
        private java.util.Map<String, Object> inclusiveUsage() {
            java.util.Map<String, Object> usage = new java.util.HashMap<>();
            usage.put("promptTokens", 98_319);
            usage.put("completionTokens", 1_915);
            usage.put("totalTokens", 100_234);
            usage.put("cacheCreationInputTokens", 18_945);
            usage.put("cacheReadInputTokens", 79_368);
            return usage;
        }

        @Test
        @DisplayName("a linked run billed as anthropic reports PLAIN input, so the cache is billed once on its own line")
        void linkedRunConvertsToTheBilledConvention() {
            AgentLoopResult result = handler.convertBridgeResponse(
                bridgeResponse(inclusiveUsage()), "anthropic", "claude-fable-5", "claude-code");

            assertThat(result.usage().promptTokens()).isEqualTo(6);
            assertThat(result.usage().totalTokens()).isEqualTo(1_921);
            // The cache counters ARE the billable cache - only their double-count inside
            // the prompt total was wrong, so they must survive untouched.
            assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(18_945);
            assertThat(result.usage().cacheReadInputTokens()).isEqualTo(79_368);
            assertThat(result.usage().completionTokens()).isEqualTo(1_915);
        }

        @Test
        @DisplayName("a FAILED linked run converts too - a killed sub-agent must be billed for what it spent, in the billed convention")
        void failedLinkedRunConvertsToTheBilledConvention() {
            // Two ways to get this wrong, and this branch had one of each in turn. Dropping
            // the usage bills a killed turn nothing (what the branch did before). Carrying
            // it raw bills 98,319 prompt tokens where 6 are owed, roughly 4x - which is the
            // worse mistake, since it lands on a customer's invoice.
            java.util.Map<String, Object> usage = inclusiveUsage();
            AgentExecutionResponseDto stopped = new AgentExecutionResponseDto(
                false, null, null, java.util.List.of(), 1, usage, null, 10,
                "claude-code", "claude-fable-5", java.util.List.of(),
                AgentStopReason.STOPPED_BY_USER.name(),
                java.util.Map.of(), java.util.List.of(usage), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);

            AgentLoopResult result = handler.convertBridgeResponse(
                stopped, "anthropic", "claude-fable-5", "claude-code");

            assertThat(result.success()).isFalse();
            assertThat(result.usage()).isNotNull();
            assertThat(result.usage().promptTokens()).isEqualTo(6);
            assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(18_945);
            assertThat(result.usage().cacheReadInputTokens()).isEqualTo(79_368);
            // The per-iteration rows the dashboard reads follow the same convention.
            assertThat(result.usagePerIteration()).hasSize(1);
            assertThat(result.usagePerIteration().get(0).promptTokens()).isEqualTo(6);
        }

        @Test
        @DisplayName("per-iteration usage converts too, so the observability rows agree with the ledger")
        void perIterationUsageConvertsAsWell() {
            AgentLoopResult result = handler.convertBridgeResponse(
                bridgeResponse(inclusiveUsage()), "anthropic", "claude-fable-5", "claude-code");

            assertThat(result.usagePerIteration()).hasSize(1);
            assertThat(result.usagePerIteration().get(0).promptTokens()).isEqualTo(6);
        }

        @Test
        @DisplayName("an UNLINKED bridge run is untouched - billed as claude-code, whose convention the numbers already are")
        void unlinkedBridgeRunIsUnchanged() {
            AgentLoopResult result = handler.convertBridgeResponse(
                bridgeResponse(inclusiveUsage()), "claude-code", "claude-fable-5", "claude-code");

            assertThat(result.usage().promptTokens()).isEqualTo(98_319);
            assertThat(result.usage().cacheReadInputTokens()).isEqualTo(79_368);
        }

        @Test
        @DisplayName("a link onto a NON-Anthropic bridge converts nothing: codex already reports a cached subset, like the API it wraps")
        void codexLinkNeedsNoConversion() {
            java.util.Map<String, Object> usage = new java.util.HashMap<>();
            usage.put("promptTokens", 50_000);
            usage.put("completionTokens", 500);
            usage.put("totalTokens", 50_500);
            usage.put("cachedTokens", 40_000);
            AgentExecutionResponseDto response = new AgentExecutionResponseDto(
                true, "answer", "answer", java.util.List.of(), 1, usage, null, 10,
                "codex", "gpt-5.4", java.util.List.of(), "COMPLETED",
                java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);

            AgentLoopResult result = handler.convertBridgeResponse(
                response, "openai", "gpt-5.4", "codex");

            assertThat(result.usage().promptTokens()).isEqualTo(50_000);
            assertThat(result.usage().cachedTokens()).isEqualTo(40_000);
        }

        @Test
        @DisplayName("a failed bridge response reports no usage at all and must not blow up on the conversion")
        void failedResponseHasNoUsageToConvert() {
            AgentExecutionResponseDto failure = new AgentExecutionResponseDto(
                false, null, null, null, 0, null, "bridge died", 5,
                "claude-code", "claude-fable-5", null, "ERROR",
                null, null, null, null, null, null, null);

            AgentLoopResult result = handler.convertBridgeResponse(
                failure, "anthropic", "claude-fable-5", "claude-code");

            assertThat(result.success()).isFalse();
            assertThat(result.usage()).isNull();
        }
    }
}
