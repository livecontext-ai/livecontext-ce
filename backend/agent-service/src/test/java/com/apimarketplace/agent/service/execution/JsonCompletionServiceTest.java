package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.metrics.AgentPrometheusMetrics;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression tests for COLD-summary compaction refusing a CLI-bridge execution link.
 *
 * <p>Production links the compaction default {@code anthropic/claude-haiku-4-5} to
 * {@code claude-code}. The json-completion path used to throw
 * {@code BRIDGE_EXECUTION_NOT_RELAYABLE} on that link, so the linked chat was never
 * compacted and re-sent its whole history (120k+ prompt tokens) on every turn. The bridge
 * tests here fail on the pre-fix path, which had no bridge branch at all.
 */
@DisplayName("JsonCompletionService")
@ExtendWith(MockitoExtension.class)
class JsonCompletionServiceTest {

    private static final String BILLED_PROVIDER = "anthropic";
    private static final String BILLED_MODEL = "claude-haiku-4-5";
    private static final String SYSTEM = "Answer with one JSON object.";
    private static final String USER = "Summarise the cold turns.";
    private static final String TENANT = "121";
    private static final String JSON = "{\"decisions\":[\"keep the link\"],\"open_questions\":[]}";

    private static final ModelExecutionLinkService.ExecutionRoute BRIDGE_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-haiku-4-5");
    private static final ModelExecutionLinkService.ExecutionRoute API_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("openrouter", "anthropic/claude-haiku");

    /** What the invoker returns now that it reports what the call consumed. */
    private static ProviderLlmJsonInvoker.InvocationResult result(String content) {
        return new ProviderLlmJsonInvoker.InvocationResult(content, null);
    }

    @Mock private ProviderLlmJsonInvoker jsonInvoker;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private ExecutionLinkRouter executionLinkRouter;

    private JsonCompletionService service;

    @BeforeEach
    void setUp() {
        service = new JsonCompletionService(jsonInvoker, bridgeDispatcher, executionLinkRouter);
        lenient().when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
        lenient().when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
    }

    private static JsonCompletionRequestDto request() {
        return new JsonCompletionRequestDto(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT);
    }

    private static AgentLoopResult ok(String content) {
        return AgentLoopResult.builder().success(true).content(content)
            .provider("claude-code").model("claude-haiku-4-5").build();
    }

    private static AgentLoopResult failed(String error) {
        return AgentLoopResult.builder().success(false).error(error).build();
    }

    private void linkToBridge() {
        when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
    }

    private AgentLoopContext bridgeContextSent(boolean routed) {
        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(bridgeDispatcher).execute(captor.capture(), eq(routed));
        return captor.getValue();
    }

    @Nested
    @DisplayName("direct API path, unchanged")
    class DirectApi {

        @Test
        @DisplayName("with a key-route resolver wired, the pin for the EXECUTION provider rides on the invoker call")
        void keyRoutePinRidesOnTheInvokerCall() {
            KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT, BILLED_PROVIDER)).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT,
                    com.apimarketplace.agent.domain.KeyRoute.OWN_KEY)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            verify(jsonInvoker, never()).invokeWithUsage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("no link: the requested pair runs on the invoker and the bridge is never consulted")
        void noLinkRunsRequestedPair() {
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            // A bare completion belongs to no surface: only an ALL link may apply.
            verify(executionLinkRouter).runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null);
            verify(bridgeDispatcher, never()).execute(any(), anyBoolean());
        }

        @Test
        @DisplayName("regression V515: a DISABLED requested model runs on its replacement, which is what the link is resolved for")
        void disabledModelRunsOnReplacement() {
            com.apimarketplace.agent.service.ModelReplacementResolver resolver =
                org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
            when(resolver.substituteIfDisabled(BILLED_PROVIDER, BILLED_MODEL)).thenReturn(java.util.Optional.of(
                new com.apimarketplace.agent.service.ModelReplacementResolver.Substitution(
                    "deepseek", "deepseek-chat", BILLED_PROVIDER, BILLED_MODEL, false)));
            ReflectionTestUtils.setField(service, "modelReplacementResolver", resolver);
            when(jsonInvoker.invokeWithUsage("deepseek", "deepseek-chat", SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            // Pre-fix the disabled pair itself was sent to the provider (and failed once retired).
            verify(executionLinkRouter).runnableRoute("deepseek", "deepseek-chat", null);
            verify(jsonInvoker, never()).invokeWithUsage(eq(BILLED_PROVIDER), eq(BILLED_MODEL), any(), any(), any());
        }

        @Test
        @DisplayName("an API-target link swaps the execution pair on the invoker")
        void apiLinkSwapsPair() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(API_ROUTE);
            when(jsonInvoker.invokeWithUsage("openrouter", "anthropic/claude-haiku", SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            verify(bridgeDispatcher, never()).execute(any(), anyBoolean());
        }

        @Test
        @DisplayName("a blank pair skips link resolution and is handed to the invoker, whose error it is")
        void blankPairGoesStraightToTheInvoker() {
            JsonCompletionRequestDto blank = new JsonCompletionRequestDto("", BILLED_MODEL, SYSTEM, USER, null);
            when(jsonInvoker.invoke("", BILLED_MODEL, SYSTEM, USER, null))
                .thenThrow(new IllegalStateException("provider"));

            assertThatThrownBy(() -> service.complete(blank, null)).isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(executionLinkRouter);
        }

        @Test
        @DisplayName("a blank MODEL is the other half of the same guard")
        void blankModelGoesStraightToTheInvoker() {
            JsonCompletionRequestDto blank = new JsonCompletionRequestDto(BILLED_PROVIDER, " ", SYSTEM, USER, null);
            when(jsonInvoker.invoke(BILLED_PROVIDER, " ", SYSTEM, USER, null))
                .thenThrow(new IllegalStateException("model"));

            assertThatThrownBy(() -> service.complete(blank, null)).isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(executionLinkRouter, bridgeDispatcher);
        }

        @Test
        @DisplayName("a null provider never reaches the router or the bridge either")
        void nullProviderGoesStraightToTheInvoker() {
            JsonCompletionRequestDto blank = new JsonCompletionRequestDto(null, BILLED_MODEL, SYSTEM, USER, null);
            when(jsonInvoker.invoke(null, BILLED_MODEL, SYSTEM, USER, null))
                .thenThrow(new NullPointerException("provider"));

            assertThatThrownBy(() -> service.complete(blank, null)).isInstanceOf(NullPointerException.class);

            verifyNoInteractions(executionLinkRouter, bridgeDispatcher);
        }

        @Test
        @DisplayName("the invoker's own failure propagates untouched")
        void invokerFailurePropagates() {
            when(jsonInvoker.invokeWithUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("returned empty content"));

            assertThatThrownBy(() -> service.complete(request(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
        }

        @Test
        @DisplayName("an API answer with a preamble is reduced to its JSON object, same rule as the bridge")
        void apiAnswerIsReducedToItsObject() {
            when(jsonInvoker.invokeWithUsage(any(), any(), any(), any(), any()))
                .thenReturn(result("Here is the summary:\n" + JSON + "\nLet me know if you need more."));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }
    }

    @Nested
    @DisplayName("a link to a CLI bridge serves the completion as a restricted session")
    class LinkedBridge {

        @Test
        @DisplayName("the completion runs on the bridge as a ROUTED run, and the invoker is never called")
        void bridgeLinkRunsOnTheBridge() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(ok(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            // routed=true: the link sent the run here, nobody chose the CLI, so the selection
            // policy must not judge the (usually absent) roles of an async compaction.
            AgentLoopContext context = bridgeContextSent(true);
            assertThat(context.provider()).isEqualTo("claude-code");
            assertThat(context.model()).isEqualTo("claude-haiku-4-5");
            verifyNoInteractions(jsonInvoker);
        }

        @Test
        @DisplayName("the session is single-shot, tool-less, restricted, bounded in time, and tagged as a completion")
        void bridgeSessionShape() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(ok(JSON));

            service.complete(request(), "USER");

            AgentLoopContext context = bridgeContextSent(true);
            assertThat(context.systemPrompt()).isEqualTo(SYSTEM);
            assertThat(context.userPrompt()).isEqualTo(USER);
            assertThat(context.tenantId()).isEqualTo(TENANT);
            // A bridge holds no API key: pinned to the platform route.
            assertThat(context.keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.PLATFORM);
            assertThat(context.userRoles()).isEqualTo("USER");
            assertThat(context.maxIterations()).isEqualTo(1);
            assertThat(context.tools()).isNull();
            assertThat(context.autoDiscoverTools()).isFalse();
            // Without the marker the CLI keeps the repo cwd and its repo/shell tools: a
            // summariser handed a source checkout and a shell, which a plain API never has.
            assertThat(context.credentials())
                .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
            // The caller stops reading at 90 s: a CLI still working after that answers nobody,
            // and a linked fallback that starts after the cap must still fit inside that budget.
            assertThat(context.executionTimeout()).isEqualTo(60);
            assertThat(context.executionTimeout()).isLessThanOrEqualTo(90 - 30);
            // Same sampling as the direct path (ProviderLlmJsonInvoker): a summary, not prose.
            assertThat(context.temperature()).isEqualTo(0.2);
            assertThat(context.purpose()).isEqualTo(CallPurpose.JSON_COMPLETION);
        }

        @Test
        @DisplayName("a chatty CLI answer (preamble + fenced JSON) is reduced to the JSON object")
        void chattyBridgeAnswerIsReduced() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), anyBoolean()))
                .thenReturn(ok("Sure! Here is the envelope:\n```json\n" + JSON + "\n```\nDone."));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }

        @Test
        @DisplayName("bridge failure on a linked run: retried invisibly on the billed pair's own API")
        void bridgeFailureFallsBackToBilledPair() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(failed("stream timeout"));
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }

        @Test
        @DisplayName("a linked bridge run that succeeds with no content also falls back")
        void emptyBridgeContentFallsBack() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(ok("  "));
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }

        @Test
        @DisplayName("the fallback is counted on the shared execution-link counter when metrics are wired")
        void fallbackIsCounted() {
            AgentPrometheusMetrics metrics = org.mockito.Mockito.mock(AgentPrometheusMetrics.class);
            ReflectionTestUtils.setField(service, "prometheusMetrics", metrics);
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(failed("bridge down"));
            when(jsonInvoker.invokeWithUsage(any(), any(), any(), any(), any())).thenReturn(result(JSON));

            service.complete(request(), null);

            verify(metrics).recordExecutionLinkFallback(BILLED_PROVIDER, BILLED_MODEL, "claude-code");
        }

        @Test
        @DisplayName("without a metrics sink the fallback still runs (no NPE on the optional bean)")
        void fallbackWithoutMetrics() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(failed("bridge down"));
            when(jsonInvoker.invokeWithUsage(any(), any(), any(), any(), any())).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }

        @Test
        @DisplayName("the fallback's own failure propagates: no third attempt, no silent empty answer")
        void fallbackFailurePropagates() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(failed("bridge down"));
            when(jsonInvoker.invokeWithUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("returned empty content"));

            assertThatThrownBy(() -> service.complete(request(), null))
                .isInstanceOf(IllegalStateException.class);
            verify(bridgeDispatcher).execute(any(), anyBoolean());
        }

        @Test
        @DisplayName("a link the router already dropped is not second-guessed: no bridge, the billed pair on its API")
        void droppedLinkIsNotSecondGuessed() {
            // The router answers null for an unwired bridge and the service must not consult the
            // dispatcher on its own: here the dispatcher WOULD accept the CLI, and still nothing
            // may reach it, because "is this link runnable" is the router's decision alone.
            lenient().when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT)).thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            verify(bridgeDispatcher, never()).execute(any(), anyBoolean());
            verify(bridgeDispatcher, never()).shouldDispatch("claude-code");
        }
    }

    @Nested
    @DisplayName("a bridge the caller NAMED is a choice, gated like everywhere else")
    class ChosenBridge {

        private JsonCompletionRequestDto chosen() {
            return new JsonCompletionRequestDto("claude-code", "claude-haiku-4-5", SYSTEM, USER, TENANT);
        }

        @Test
        @DisplayName("runs on the bridge as a CHOSEN run (routed=false) with the caller's roles")
        void chosenBridgeIsNotRouted() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(false))).thenReturn(ok(JSON));

            assertThat(service.complete(chosen(), "USER,ADMIN")).isEqualTo(JSON);

            AgentLoopContext context = bridgeContextSent(false);
            assertThat(context.userRoles()).isEqualTo("USER,ADMIN");
            assertThat(context.credentials())
                .as("restricted even when chosen: a summariser never needs the checkout")
                .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
            verifyNoInteractions(jsonInvoker);
        }

        @Test
        @DisplayName("an access denial propagates as-is and is never retried on an API")
        void accessDenialPropagates() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(false))).thenThrow(
                new BridgeAccessDeniedException("claude-code", BridgeAccessDecision.REASON_NOT_ADMIN, null));

            assertThatThrownBy(() -> service.complete(chosen(), "USER"))
                .isInstanceOf(BridgeAccessDeniedException.class);

            verifyNoInteractions(jsonInvoker);
        }

        @Test
        @DisplayName("a chosen bridge that fails is an error, not a fallback: there is no billed API to fall back to")
        void chosenBridgeFailureIsAnError() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(false))).thenReturn(failed("bridge down"));

            assertThatThrownBy(() -> service.complete(chosen(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bridge down");

            verifyNoInteractions(jsonInvoker);
        }

        @Test
        @DisplayName("the roles are read from the request, not invented: absent roles travel as null")
        void absentRolesTravelAsNull() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(false))).thenReturn(ok(JSON));

            service.complete(chosen(), null);

            assertThat(bridgeContextSent(false).userRoles()).isNull();
            verify(executionLinkRouter).runnableRoute(eq("claude-code"), eq("claude-haiku-4-5"), isNull());
        }
    }

    /**
     * The COLD summary is an LLM call the tenant makes, and until now it was the one the
     * platform paid for and never charged: the invoker discarded the usage, so no
     * observability row and no ledger row could be written, while the
     * {@code COMPACTION_SUMMARY} source type sat allow-listed in billing waiting for one.
     * Nine summaries ran in production and billed zero.
     */
    @Nested
    @DisplayName("Billing the COLD summary")
    class Billing {

        private final com.apimarketplace.agent.domain.UsageInfo directUsage =
            com.apimarketplace.agent.domain.UsageInfo.builder()
                .promptTokens(4_000).completionTokens(300).totalTokens(4_300)
                .cacheCreationInputTokens(1_000).cacheReadInputTokens(9_000).build();

        private com.apimarketplace.agent.service.AgentObservabilityService observability;

        @org.junit.jupiter.api.BeforeEach
        void wireObservability() {
            observability = org.mockito.Mockito.mock(
                com.apimarketplace.agent.service.AgentObservabilityService.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                service, "observabilityService", observability);
        }

        private com.apimarketplace.agent.client.dto.AgentObservabilityRequest captured() {
            var captor = org.mockito.ArgumentCaptor.forClass(
                com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observability).recordFromRequest(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("the key route the run was pinned to reaches the debit: OWN_KEY on a direct run on the user's key")
        void ownKeyRouteReachesTheDebit() {
            KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT, BILLED_PROVIDER)).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT,
                    com.apimarketplace.agent.domain.KeyRoute.OWN_KEY))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            service.complete(request(), null);

            assertThat(captured().getKeyRoute()).isEqualTo("OWN_KEY");
        }

        @Test
        @DisplayName("a bridge run is billed on the platform route (a CLI holds no API key)")
        void bridgeRunIsPlatformRoute() {
            linkToBridge();
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(
                AgentLoopResult.builder().success(true).content(JSON).usage(directUsage).build());

            service.complete(request(), null);

            assertThat(captured().getKeyRoute()).isEqualTo("PLATFORM");
        }

        @Test
        @DisplayName("a direct run is charged as COMPACTION_SUMMARY, with every token class it consumed")
        void directRunIsBilled() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            service.complete(request(), null);

            var req = captured();
            // "compaction_summary" is what resolveSourceType turns into COMPACTION_SUMMARY,
            // which is what keeps this cost out of primary agent spend in the ledger.
            assertThat(req.getAgentType()).isEqualTo("compaction_summary");
            assertThat(req.getTenantId()).isEqualTo(TENANT);
            assertThat(req.getProvider()).isEqualTo(BILLED_PROVIDER);
            assertThat(req.getModel()).isEqualTo(BILLED_MODEL);
            assertThat(req.getPromptTokens()).isEqualTo(4_000L);
            assertThat(req.getCompletionTokens()).isEqualTo(300L);
            // The cache counters are the whole reason this is worth billing correctly.
            assertThat(req.getCacheCreationTokens()).isEqualTo(1_000L);
            assertThat(req.getCacheReadTokens()).isEqualTo(9_000L);
        }

        @Test
        @DisplayName("each summary gets its OWN ledger id, because credit_ledger.source_id is unique GLOBALLY and not per tenant")
        void everySummaryGetsItsOwnLedgerId() {
            // A compaction has no execution row to borrow an id from, so the nodeId IS the
            // ledger's source_id. A constant one would let the first summary in a workspace
            // through and make every later one a duplicate key - silently unbilled, which
            // is the defect this whole change exists to remove.
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            service.complete(request(), null);
            service.complete(request(), null);

            var captor = org.mockito.ArgumentCaptor.forClass(
                com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observability, org.mockito.Mockito.times(2)).recordFromRequest(captor.capture());

            String first = captor.getAllValues().get(0).getNodeId();
            String second = captor.getAllValues().get(1).getNodeId();
            assertThat(first).startsWith("compaction:" + TENANT + ":");
            assertThat(second).startsWith("compaction:" + TENANT + ":");
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a run moved onto a bridge by a link is charged on the BILLED pair, with the counts re-expressed in its convention")
        void linkedBridgeRunIsBilledOnTheBilledPair() {
            // Same trap as every other surface: the bridge folds the cache into its prompt
            // total and the Anthropic API counts it beside. Billed verbatim, the cache would
            // be charged once inside the prompt and again on its own line.
            var bridgeUsage = com.apimarketplace.agent.domain.UsageInfo.builder()
                .promptTokens(4_000 + 1_000 + 9_000).completionTokens(300).totalTokens(14_300)
                .cacheCreationInputTokens(1_000).cacheReadInputTokens(9_000).build();
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(BRIDGE_ROUTE);
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(
                AgentLoopResult.builder().success(true).content(JSON).usage(bridgeUsage).build());

            service.complete(request(), null);

            var req = captured();
            assertThat(req.getProvider()).isEqualTo(BILLED_PROVIDER);
            // 14,000 stripped back to the 4,000 of plain input.
            assertThat(req.getPromptTokens()).isEqualTo(4_000L);
            assertThat(req.getCacheReadTokens()).isEqualTo(9_000L);
        }

        @Test
        @DisplayName("a subset-billed pair gets the cache under cachedTokens, the only field its family reads")
        void subsetBilledPairGetsTheCacheUnderTheRightName() {
            // The other half of the conversion, and the half that once turned a 58%
            // over-bill into an 86% UNDER-bill: the OpenAI family discounts cachedTokens
            // and ignores cacheReadTokens, so stripping the cache out of the prompt for an
            // openai-billed run charged it nowhere. bill() copies all four counters; this
            // is what proves the copy keeps the one that matters.
            var bridgeUsage = com.apimarketplace.agent.domain.UsageInfo.builder()
                .promptTokens(4_000 + 1_000 + 9_000).completionTokens(300).totalTokens(14_300)
                .cacheCreationInputTokens(1_000).cacheReadInputTokens(9_000).build();
            var openAiRequest = new JsonCompletionRequestDto("openai", "gpt-5.4", SYSTEM, USER, TENANT);
            when(executionLinkRouter.runnableRoute("openai", "gpt-5.4", null)).thenReturn(
                new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-haiku-4-5"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(
                AgentLoopResult.builder().success(true).content(JSON).usage(bridgeUsage).build());

            service.complete(openAiRequest, null);

            var req = captured();
            // Nothing stripped: the cached part stays inside the prompt, which is what a
            // subset family discounts against.
            assertThat(req.getPromptTokens()).isEqualTo(14_000L);
            assertThat(req.getCachedTokens()).isEqualTo(9_000L);
            // And NOT also under the Anthropic name, or Google would count it twice.
            assertThat(req.getCacheReadTokens()).isZero();
        }

        @Test
        @DisplayName("the bridge-failure fallback on the billed pair is pinned too: the one billed transport must not escape the pin")
        void fallbackOnTheBilledPairIsPinned() {
            KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
            when(keyRouteResolver.resolve(TENANT, BILLED_PROVIDER)).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
            ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(BRIDGE_ROUTE);
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(
                AgentLoopResult.builder().success(false).error("bridge died").build());
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT,
                    com.apimarketplace.agent.domain.KeyRoute.OWN_KEY))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            // Resolved for the BILLED provider, the one whose key serves the retry.
            verify(keyRouteResolver).resolve(TENANT, BILLED_PROVIDER);
            verify(jsonInvoker, never()).invokeWithUsage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("after a failed linked bridge, only the run that answered is charged - the discarded attempt is not")
        void fallbackBillsOnceOnTheBilledPair() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(BRIDGE_ROUTE);
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), eq(true))).thenReturn(
                AgentLoopResult.builder().success(false).error("bridge died").build());
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            service.complete(request(), null);

            // Exactly one row: the tokens the bridge burned before dying were the bridge's,
            // and a run that produced nothing is not a thing to charge for.
            verify(observability, org.mockito.Mockito.times(1)).recordFromRequest(any());
            assertThat(captured().getPromptTokens()).isEqualTo(4_000L);
        }

        @Test
        @DisplayName("a provider that reports no counts is not billed, and the summary is still returned")
        void noUsageMeansNoChargeButStillASummary() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT))
                .thenReturn(result(JSON));

            assertThat(service.complete(request(), null)).isEqualTo(JSON);

            verify(observability, org.mockito.Mockito.never()).recordFromRequest(any());
        }

        @Test
        @DisplayName("a billing failure never costs the caller its summary - the work is already done and paid for")
        void billingFailureDoesNotLoseTheSummary() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, TENANT))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));
            org.mockito.Mockito.doThrow(new IllegalStateException("ledger down"))
                .when(observability).recordFromRequest(any());

            assertThat(service.complete(request(), null)).isEqualTo(JSON);
        }

        @Test
        @DisplayName("a call with no tenant is not billed - there is no wallet to charge")
        void noTenantMeansNoCharge() {
            when(executionLinkRouter.runnableRoute(BILLED_PROVIDER, BILLED_MODEL, null)).thenReturn(null);
            when(jsonInvoker.invokeWithUsage(BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, null))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON, directUsage));

            service.complete(new JsonCompletionRequestDto(
                BILLED_PROVIDER, BILLED_MODEL, SYSTEM, USER, null), null);

            verify(observability, org.mockito.Mockito.never()).recordFromRequest(any());
        }
    }

    @Nested
    @DisplayName("model replacement reaches the observability record")
    class ModelReplacementStamp {

        private com.apimarketplace.agent.service.AgentObservabilityService observability;

        @BeforeEach
        void wire() {
            observability = org.mockito.Mockito.mock(com.apimarketplace.agent.service.AgentObservabilityService.class);
            ReflectionTestUtils.setField(service, "observabilityService", observability);
        }

        private com.apimarketplace.agent.client.dto.AgentObservabilityRequest recorded() {
            var captor = ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observability).recordFromRequest(captor.capture());
            return captor.getValue();
        }

        private void usage(String provider, String model) {
            when(jsonInvoker.invokeWithUsage(provider, model, SYSTEM, USER, TENANT))
                .thenReturn(new ProviderLlmJsonInvoker.InvocationResult(JSON,
                    com.apimarketplace.agent.domain.UsageInfo.builder()
                        .promptTokens(10).completionTokens(5).totalTokens(15).build()));
        }

        @Test
        @DisplayName("a disabled model swapped for its replacement is recorded as replaced, naming the disabled model")
        void swapped() {
            com.apimarketplace.agent.service.ModelReplacementResolver resolver =
                org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
            when(resolver.substituteIfDisabled(BILLED_PROVIDER, BILLED_MODEL)).thenReturn(java.util.Optional.of(
                new com.apimarketplace.agent.service.ModelReplacementResolver.Substitution(
                    "deepseek", "deepseek-chat", BILLED_PROVIDER, BILLED_MODEL, true)));
            ReflectionTestUtils.setField(service, "modelReplacementResolver", resolver);
            usage("deepseek", "deepseek-chat");

            service.complete(request(), null);

            assertThat(recorded().getModelReplaced()).isTrue();
            assertThat(recorded().getReplacedModel()).isEqualTo(BILLED_MODEL);
        }

        @Test
        @DisplayName("an enabled model is recorded as not replaced; without a resolver nothing is claimed")
        void notSwappedOrUnknown() {
            com.apimarketplace.agent.service.ModelReplacementResolver resolver =
                org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
            when(resolver.substituteIfDisabled(BILLED_PROVIDER, BILLED_MODEL)).thenReturn(java.util.Optional.empty());
            ReflectionTestUtils.setField(service, "modelReplacementResolver", resolver);
            usage(BILLED_PROVIDER, BILLED_MODEL);

            service.complete(request(), null);
            assertThat(recorded().getModelReplaced()).isFalse();
            assertThat(recorded().getReplacedModel()).isNull();

            org.mockito.Mockito.clearInvocations(observability);
            ReflectionTestUtils.setField(service, "modelReplacementResolver", null);
            service.complete(request(), null);
            assertThat(recorded().getModelReplaced()).isNull();
        }
    }
}
