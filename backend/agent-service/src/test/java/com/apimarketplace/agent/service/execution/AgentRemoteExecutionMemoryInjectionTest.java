package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural proof that the memory block is appended at the choke point, rather
 * than a source scan proving the method name appears in the file.
 *
 * <p>This is THE claim the whole feature rests on: every direct-API execution in
 * the product (chat, workflow agent nodes, task and schedule runs, direct and
 * bridge transports alike) reaches
 * {@link AgentRemoteExecutionService#executeAgent}, and the append happens there,
 * before the bridge split. If it did not, agents would run amnesiac while every
 * unit test and every run stayed green, which is exactly how the skills tree came
 * to be missing on three of the four execution paths.
 *
 * <p>The test drives the real method and only asserts on the interaction it cares
 * about, so it does not depend on the execution completing: the append is the
 * first thing that happens after provider normalisation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AgentRemoteExecutionService - long-term memory injection")
class AgentRemoteExecutionMemoryInjectionTest {

    @Mock private com.apimarketplace.agent.loop.AgentLoopService agentLoopService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private RedisStreamingCallback redisStreamingCallback;
    @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    @Mock private CoreToolsCache coreToolsCache;
    @Mock private AgentActivityPublisher agentActivityPublisher;
    @Mock private com.apimarketplace.agent.service.budget.GuardChainFactory guardChainFactory;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    @Mock private MemoryPromptSection memoryPromptSection;

    @InjectMocks private AgentRemoteExecutionService service;

    private static final String ORG = "org-alpha";
    private static final String BLOCK = "<recalled-memory>\n## Index\n- [project] a: b\n</recalled-memory>";

    private void wireMemorySection() throws Exception {
        // Field-injected on purpose (see the field's javadoc: widening the
        // constructor would break a dozen positional test constructors), so the
        // test wires it the same way Spring does.
        Field field = AgentRemoteExecutionService.class.getDeclaredField("memoryPromptSection");
        field.setAccessible(true);
        field.set(service, memoryPromptSection);
    }

    /** Minimal request; only systemPrompt and agentEntityId matter to what is asserted here. */
    private AgentExecutionRequestDto request(String agentEntityId) {
        return new AgentExecutionRequestDto(
            "do the thing",
            "You are a helpful assistant.",
            "anthropic",
            "claude-sonnet-5",
            0.0,
            256,
            java.util.List.of(),
            false,
            10,
            4,
            150,
            null,             // conversationHistory
            "42",             // tenantId
            null,             // runId
            null,             // nodeId
            null,             // variables
            java.util.Map.of(),
            null,             // maxCreditBudget
            null,             // streamChannelId
            null,             // itemIndex
            null,             // loopIteration
            null,             // conversationId
            null,             // streamingFormat
            null,             // parentConversationId
            null,             // subAgentName
            null,             // subAgentAvatarUrl
            null,             // subAgentId
            null,             // workflowRunId
            null,             // attachments
            agentEntityId,
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

    /**
     * Run far enough to exercise the append, tolerating the downstream failure that
     * mocks make inevitable.
     *
     * <p>It swallows only what a mocked dependency legitimately throws. A
     * {@link NullPointerException} is rethrown: the first version caught every
     * {@code RuntimeException}, which meant the "survives an absent memory section"
     * test passed even when the injection path NPE'd, i.e. it could not fail for
     * the reason it existed.
     */
    private void runIgnoringDownstreamFailure(AgentExecutionRequestDto dto) {
        try {
            service.executeAgent(dto, null);
        } catch (NullPointerException npe) {
            throw new AssertionError("the memory injection path threw a NullPointerException", npe);
        } catch (RuntimeException expected) {
            // The run cannot complete against mocks, and it does not need to: the
            // append happens before anything downstream is reached.
        }
    }

    @Test
    @DisplayName("appends the workspace's memory block to the system prompt before the run is dispatched")
    void appendsTheBlockToTheSystemPrompt() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), anyString())).thenReturn("anthropic");
        when(memoryPromptSection.appendTo(anyString(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0) + "\n\n" + BLOCK);

        UUID agentId = UUID.randomUUID();
        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(agentId.toString())));

        // The block is built for THIS workspace and THIS agent. The org is read from
        // the thread binding the controller established, so memory resolves in the
        // same workspace as the rest of the run rather than in a second one carried
        // in the payload; the agent id scopes the read so a sibling agent's private
        // entries never reach this run.
        verify(memoryPromptSection).appendTo("You are a helpful assistant.", ORG, agentId);
    }

    @Test
    @DisplayName("passes a null agent id for a chat with no agent bound, so no private memory leaks into it")
    void chatWithNoAgentGetsWorkspaceScopeOnly() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), any())).thenReturn("anthropic");
        when(memoryPromptSection.appendTo(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(null)));

        verify(memoryPromptSection).appendTo("You are a helpful assistant.", ORG, null);
    }

    @Test
    @DisplayName("treats an unparseable agent id as no agent rather than failing the run")
    void malformedAgentIdDegradesToWorkspaceScope() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), any())).thenReturn("anthropic");
        when(memoryPromptSection.appendTo(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request("not-a-uuid")));

        verify(memoryPromptSection).appendTo("You are a helpful assistant.", ORG, null);
    }

    @Test
    @DisplayName("runs unchanged when no memory section is wired, so an install without it is not broken")
    void survivesAnAbsentMemorySection() {
        // The field is optional (a test or a deployment may never wire it), so the
        // guarded call must not NPE on every execution. runIgnoringDownstreamFailure
        // turns an NPE into an assertion failure, which is what makes this test
        // capable of failing at all: asserting `true` at the end proved nothing.
        when(modelCatalogService.resolveProvider(anyString(), any())).thenReturn("anthropic");

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(null)));

        // And nothing was appended, because there was nothing to append with.
        verify(memoryPromptSection, never()).appendTo(anyString(), any(), any());
    }
    @Test
    @DisplayName("the ENRICHED prompt is the one the run is given, not merely a prompt the renderer was shown")
    void theEnrichedPromptIsWhatRuns() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), anyString())).thenReturn("anthropic");
        when(memoryPromptSection.appendTo(anyString(), any(), any()))
            .thenReturn("PROMPT-WITH-MEMORY-BLOCK");

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(null)));

        // Every other test here verifies that appendTo was CALLED. Calling it and
        // throwing the result away satisfies all of them, and satisfies the archunit
        // scan too, while leaving every agent on this path amnesiac. This is the site
        // that covers chat on a direct-API model, workflow agent nodes, tasks and
        // schedules, so it is the one where that mistake would cost the most.
        org.mockito.ArgumentCaptor<com.apimarketplace.agent.loop.AgentLoopContext> captor =
            org.mockito.ArgumentCaptor.forClass(com.apimarketplace.agent.loop.AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), any());
        assertThat(captor.getValue().systemPrompt()).isEqualTo("PROMPT-WITH-MEMORY-BLOCK");
    }
    @Test
    @DisplayName("the bridge transport is handed the ENRICHED prompt too, not the original")
    void theBridgeBranchAlsoRunsTheEnrichedPrompt() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), any())).thenReturn("claude-code");
        when(memoryPromptSection.appendTo(anyString(), any(), any()))
            .thenReturn("PROMPT-WITH-MEMORY-BLOCK");
        // The other side of the split at the top of executeAgent: a CLI provider goes
        // out over the bridge instead of through the local loop.
        when(bridgeDispatcher.shouldDispatch(anyString())).thenReturn(true);

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(null)));

        // The append happens BEFORE the split, so both transports ought to carry it -
        // but "ought to" is exactly how the skills tree ended up present on one path
        // out of four. This is the path every claude-code and codex agent takes.
        org.mockito.ArgumentCaptor<AgentExecutionRequestDto> captor =
            org.mockito.ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeDispatcher).dispatchRaw(captor.capture(), any(), anyBoolean());
        assertThat(captor.getValue().systemPrompt()).isEqualTo("PROMPT-WITH-MEMORY-BLOCK");
    }

    @Test
    @DisplayName("runs WITHOUT memory when the enrichment throws, instead of failing the whole execution")
    void aFailedEnrichmentDoesNotFailTheRun() throws Exception {
        wireMemorySection();
        when(modelCatalogService.resolveProvider(anyString(), any())).thenReturn("anthropic");
        when(memoryPromptSection.appendTo(anyString(), any(), any()))
            .thenThrow(new IllegalStateException("memory backend is down"));

        TenantResolver.runWithOrgScope(ORG, () -> runIgnoringDownstreamFailure(request(null)));

        // Memory is an enrichment. An agent running without it is degraded; an
        // agent that 500s because a lookup misbehaved is broken, and this is the
        // site every direct-API execution in the product goes through - chat on a
        // direct model, workflow agent nodes, tasks and schedules. The run must
        // reach the loop, carrying the prompt it started with.
        org.mockito.ArgumentCaptor<com.apimarketplace.agent.loop.AgentLoopContext> captor =
            org.mockito.ArgumentCaptor.forClass(com.apimarketplace.agent.loop.AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), any());
        assertThat(captor.getValue().systemPrompt()).isEqualTo("You are a helpful assistant.");
    }
}
