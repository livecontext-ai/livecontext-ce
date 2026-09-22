package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The classify node routing to a decision engine instead of an LLM.
 *
 * <p>The branch is what makes one node carry two engines. What these tests hold in place
 * is that choosing the decision engine changes the engine and NOTHING else: the same
 * budget gate runs, the same response shape comes back, and the LLM path is untouched
 * for every other provider.
 */
@DisplayName("ClassifyService - decision model dispatch")
@ExtendWith(MockitoExtension.class)
class ClassifyServiceDecisionModelTest {

    @Mock private AgentLoopService agentLoopService;
    @Mock private GuardChainFactory guardChainFactory;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;
    @Mock private ExecutionLinkRouter executionLinkRouter;
    @Mock private TypeSafeSystemOneClient typeSafeClient;

    private ClassifyService service;

    private static final List<ClassifyRequestDto.CategoryDto> CATEGORIES = List.of(
        new ClassifyRequestDto.CategoryDto("billing", "Billing issues"),
        new ClassifyRequestDto.CategoryDto("technical", "Bugs")
    );

    @BeforeEach
    void setUp() {
        service = new ClassifyService(agentLoopService, guardChainFactory, new ObjectMapper(),
            bridgeDispatcher, modelCatalogService, executionLinkRouter, typeSafeClient);
        lenient().when(modelCatalogService.resolveProvider(any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(guardChainFactory.forAgent(any(), any(), any(), any()))
            .thenReturn(PreIterationGuard.ALWAYS_PROCEED);
    }

    private ClassifyRequestDto request(String provider, String model) {
        return new ClassifyRequestDto("An invoice question", null, CATEGORIES, provider, model,
            null, null, "tenant-1", "agent-1");
    }

    private ClassifyResponseDto decided() {
        return new ClassifyResponseDto(true, "billing", 0.93, "billing 0.93, technical 0.07",
            null, 410, "typesafe", "jev-latest", 1150, 1150, 0, null, null, "instructions", null,
            Map.of("billing", 0.93, "technical", 0.07));
    }

    @Test
    @DisplayName("a decision provider is classified by the decision engine, never by the agent loop")
    void decisionProviderGoesToTheDecisionEngine() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(typeSafeClient.classify(any(), anyString(), anyLong())).thenReturn(decided());

        ClassifyResponseDto result = service.execute(request("typesafe", "jev-latest"));

        assertThat(result.success()).isTrue();
        assertThat(result.selectedCategory()).isEqualTo("billing");
        assertThat(result.probabilities()).containsEntry("billing", 0.93);
        verify(agentLoopService, never()).execute(any(), any());
        verify(bridgeDispatcher, never()).execute(any(), anyBoolean());
    }

    @Test
    @DisplayName("the decision branch is taken before the execution link is even looked up")
    void executionLinkIsNotConsultedForADecisionModel() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(typeSafeClient.classify(any(), anyString(), anyLong())).thenReturn(decided());

        service.execute(request("typesafe", "jev-latest"));

        // A link moves a run between targets that speak the same protocol. Resolving one
        // for a decision model could only ever produce a call its target cannot answer.
        verify(executionLinkRouter, never()).runnableRoute(any(), any(), any());
    }

    @Test
    @DisplayName("any other provider still takes the LLM path, with the decision engine untouched")
    void otherProvidersAreUnaffected() {
        when(typeSafeClient.serves("anthropic", "claude-opus-4-8")).thenReturn(false);
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        String json = "{\"selected_category\":\"billing\",\"confidence\":0.8}";
        when(agentLoopService.execute(any(), any())).thenReturn(
            com.apimarketplace.agent.loop.AgentLoopResult.builder()
                .success(true)
                .content(json)
                .provider("anthropic")
                .model("claude-opus-4-8")
                .iterations(1)
                .durationMs(50)
                .stopReason(AgentStopReason.COMPLETED)
                .build());

        service.execute(request("anthropic", "claude-opus-4-8"));

        verify(agentLoopService).execute(any(), any());
        verify(typeSafeClient, never()).classify(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("the budget guard runs on the decision path too, and a denial never reaches the API")
    void budgetDenialStopsTheCallBeforeItIsMade() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(
            ctx -> GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant",
                "Tenant is out of credits"));

        ClassifyResponseDto result = service.execute(request("typesafe", "jev-latest"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Tenant is out of credits");
        // The point of a gate: a workflow that runs this node thousands of times must not
        // reach the paid endpoint once the tenant is out of credits.
        verify(typeSafeClient, never()).classify(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("BUDGET: the guard is BUILT for the decision pair, not for the plan's empty provider")
    void guardIsBuiltForTheDecisionPair() {
        // A plan naming only the model leaves the catalogue unable to resolve a provider,
        // so the raw value is null. The guard's cost calculator is resolved from the pair
        // it is CONSTRUCTED with, so building it on null would price the run against a
        // model with no provider while the guard is then asked about the real one.
        when(typeSafeClient.serves(null, "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(typeSafeClient.classify(any(), anyString(), anyLong())).thenReturn(decided());

        service.execute(request(null, "jev-latest"));

        verify(guardChainFactory).forAgent("tenant-1", "agent-1", "typesafe", "jev-latest");
    }

    @Test
    @DisplayName("BUDGET: a chat pair still builds its guard on exactly what it was given")
    void guardForAChatPairIsUnchanged() {
        when(typeSafeClient.serves("anthropic", "claude-opus-4-8")).thenReturn(false);
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        when(agentLoopService.execute(any(), any())).thenReturn(
            com.apimarketplace.agent.loop.AgentLoopResult.builder()
                .success(true)
                .content("{\"selected_category\":\"billing\",\"confidence\":0.8}")
                .provider("anthropic").model("claude-opus-4-8")
                .iterations(1).durationMs(50).stopReason(AgentStopReason.COMPLETED)
                .build());

        service.execute(request("anthropic", "claude-opus-4-8"));

        verify(guardChainFactory).forAgent("tenant-1", "agent-1", "anthropic", "claude-opus-4-8");
    }

    @Test
    @DisplayName("a plan naming only the model still reaches the decision engine")
    void modelOnlyPlanReachesTheDecisionEngine() {
        when(typeSafeClient.serves(null, "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(typeSafeClient.classify(any(), anyString(), anyLong())).thenReturn(decided());

        ClassifyResponseDto result = service.execute(request(null, "jev-latest"));

        assertThat(result.success()).isTrue();
        // And it is dispatched under the canonical provider name, not under the null the
        // plan carried, so the ledger and the metrics both have something to key on.
        verify(typeSafeClient).classify(any(), org.mockito.ArgumentMatchers.eq("typesafe"), anyLong());
    }

    @Test
    @DisplayName("the guard is asked about the billed pair, as iteration 1 of a single-shot call")
    void guardIsAskedAboutTheBilledPair() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(true);
        when(typeSafeClient.classify(any(), anyString(), anyLong())).thenReturn(decided());
        PreIterationGuard guard = org.mockito.Mockito.mock(PreIterationGuard.class);
        when(guard.check(any())).thenReturn(GuardResult.allow());
        when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(guard);

        service.execute(request("typesafe", "jev-latest"));

        ArgumentCaptor<IterationContext> captor = ArgumentCaptor.forClass(IterationContext.class);
        verify(guard).check(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo("typesafe");
        assertThat(captor.getValue().model()).isEqualTo("jev-latest");
        assertThat(captor.getValue().upcomingIteration()).isEqualTo(1);
        assertThat(captor.getValue().iterationsCompleted()).isZero();
    }

    @Test
    @DisplayName("with no API key the node says so, and says what to do instead")
    void missingKeyIsExplained() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(false);

        ClassifyResponseDto result = service.execute(request("typesafe", "jev-latest"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
            .contains("No API key is configured")
            .contains("typesafe")
            // A dead end with no way out is worse than a failure: the same node runs on a
            // chat model, and the message is where the reader learns that.
            .contains("chat model");
        verify(typeSafeClient, never()).classify(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("an unconfigured key is reported without spending a budget check on it")
    void missingKeyShortCircuitsBeforeTheGuard() {
        when(typeSafeClient.serves("typesafe", "jev-latest")).thenReturn(true);
        when(typeSafeClient.isConfigured()).thenReturn(false);
        PreIterationGuard guard = org.mockito.Mockito.mock(PreIterationGuard.class);
        when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(guard);

        service.execute(request("typesafe", "jev-latest"));

        verify(guard, never()).check(any());
    }
}
