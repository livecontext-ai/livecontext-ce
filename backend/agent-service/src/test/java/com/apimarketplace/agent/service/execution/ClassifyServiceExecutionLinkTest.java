package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the classify node ignoring model execution links.
 *
 * <p>The workflow classify node used to run its own {@code (provider, model)} pair
 * unconditionally: an admin who routed that pair to a CLI bridge still saw every
 * classification call the billed provider's own API key. In production that surfaced
 * as an upstream billing error ("credit balance too low") on a key the platform had
 * deliberately stopped using, while chat on the same model ran fine through the bridge.
 *
 * <p>Every test here fails on the pre-fix service, which never consulted the link.
 */
@DisplayName("ClassifyService - model execution links")
@ExtendWith(MockitoExtension.class)
class ClassifyServiceExecutionLinkTest {

    @Mock
    private AgentLoopService agentLoopService;

    @Mock
    private GuardChainFactory guardChainFactory;

    @Mock
    private BridgeLoopDispatcher bridgeDispatcher;

    @Mock
    private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    @Mock
    private ExecutionLinkRouter executionLinkRouter;

    /**
     * The decision engine, unstubbed on purpose: an execution link only ever moves a run
     * between chat-shaped targets, so every test here takes the LLM path and
     * {@code supports(...)} answers false.
     */
    @Mock
    private TypeSafeSystemOneClient typeSafeClient;

    private ClassifyService service;

    private static final List<ClassifyRequestDto.CategoryDto> CATEGORIES = List.of(
        new ClassifyRequestDto.CategoryDto("billing", "Billing-related issues"),
        new ClassifyRequestDto.CategoryDto("spam", "Unwanted messages")
    );

    private static final String JSON =
        "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"invoice\"}";

    private static final ModelExecutionLinkService.ExecutionRoute BRIDGE_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-8");
    private static final ModelExecutionLinkService.ExecutionRoute API_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("openrouter", "anthropic/claude-opus-4-8");

    @BeforeEach
    void setUp() {
        service = new ClassifyService(agentLoopService, guardChainFactory, new ObjectMapper(),
            bridgeDispatcher, modelCatalogService, executionLinkRouter, typeSafeClient);
        lenient().when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(PreIterationGuard.ALWAYS_PROCEED);
        lenient().when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
        lenient().when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        lenient().when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult());
        lenient().when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(loopResult());
    }

    @Test
    @DisplayName("the billed pair and the workflow surface are what the link is resolved on")
    void resolvesLinkOnBilledPairForTheWorkflowSurface() {
        service.execute(request());

        verify(executionLinkRouter).runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW");
    }

    @Test
    @DisplayName("regression V515: a DISABLED billed model is swapped for its replacement before the link and the guard see it")
    void disabledModelRunsOnReplacementThroughItsLink() {
        com.apimarketplace.agent.service.ModelReplacementResolver resolver =
            org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
        when(resolver.substituteIfDisabled("anthropic", "claude-opus-4-8")).thenReturn(java.util.Optional.of(
            new com.apimarketplace.agent.service.ModelReplacementResolver.Substitution(
                "anthropic", "claude-opus-4-9", "anthropic", "claude-opus-4-8", true)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelReplacementResolver", resolver);
        when(executionLinkRouter.runnableRoute("anthropic", "claude-opus-4-9", "WORKFLOW")).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);

        var response = service.execute(request());

        // Pre-fix the disabled pair itself reached the link lookup and the guard, and ran
        // on a model the admin had turned off. The replacement is now what is linked,
        // priced and executed (classification still succeeds).
        verify(executionLinkRouter).runnableRoute("anthropic", "claude-opus-4-9", "WORKFLOW");
        verify(executionLinkRouter, never()).runnableRoute(eq("anthropic"), eq("claude-opus-4-8"), any());
        verify(guardChainFactory).forAgent(any(), any(), eq("anthropic"), eq("claude-opus-4-9"));
        verify(bridgeDispatcher).execute(any(), eq(true));
        assertThat(response.success()).isTrue();
    }

    @Test
    @DisplayName("the key route is pinned once for the execution provider and rides on the loop context")
    void keyRouteIsPinnedOnTheLoopContext() {
        KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
        when(keyRouteResolver.resolve(any(), eq("anthropic"))).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);

        var response = service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
        // And the response carries it: the orchestrator bills this node from the response,
        // and an own-key turn is billed a flat fee, not the token rate.
        assertThat(response.keyRoute()).isEqualTo("OWN_KEY");
    }

    @Test
    @DisplayName("a bridge-linked classification is pinned PLATFORM without consulting the resolver (a bridge holds no API key)")
    void bridgeLinkPinsPlatform() {
        KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);

        var response = service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(bridgeDispatcher).execute(captor.capture(), eq(true));
        assertThat(captor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.PLATFORM);
        verify(keyRouteResolver, never()).resolve(any(), any());
        assertThat(response.keyRoute()).isEqualTo("PLATFORM");
    }

    @Test
    @DisplayName("regression: a bridge run that fails and falls back to the billed pair reports the route of the FALLBACK, not the bridge pin it started with")
    void fallbackRouteReplacesTheBridgePin() {
        KeyRouteResolver keyRouteResolver = org.mockito.Mockito.mock(KeyRouteResolver.class);
        when(keyRouteResolver.resolve(any(), eq("anthropic"))).thenReturn(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "keyRouteResolver", keyRouteResolver);
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
        when(bridgeDispatcher.execute(any(), eq(true)))
            .thenReturn(com.apimarketplace.agent.loop.AgentLoopResult.failure("bridge down", 10L, "claude-code"));

        var response = service.execute(request());

        // The bridge attempt was PLATFORM (no key); the billed pair ran on the user's key.
        // Billing the fallback as PLATFORM would charge the token rate for a turn the
        // user's provider already billed them for.
        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().keyRoute()).isEqualTo(com.apimarketplace.agent.domain.KeyRoute.OWN_KEY);
        assertThat(response.success()).isTrue();
        assertThat(response.keyRoute()).isEqualTo("OWN_KEY");
    }

    @Test
    @DisplayName("a link to a CLI bridge sends the classification through the bridge")
    void bridgeLinkRoutesThroughBridge() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(bridgeDispatcher).execute(captor.capture(), eq(true));
        verify(agentLoopService, never()).execute(any(), any());
        assertThat(captor.getValue().provider()).isEqualTo("claude-code");
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("a bridge-linked classification runs in restricted API mode")
    void bridgeLinkForcesRestrictedToolset() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(bridgeDispatcher).execute(captor.capture(), anyBoolean());
        // Without this the CLI would run a classification with its native file tools
        // and the project cwd, which a plain API call never has.
        assertThat(captor.getValue().credentials())
            .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
    }

    @Test
    @DisplayName("a link to another API provider moves the loop, not the billing")
    void apiLinkMovesTheLoopOnly() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(API_ROUTE);

        ClassifyResponseDto result = service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().provider()).isEqualTo("openrouter");
        assertThat(captor.getValue().model()).isEqualTo("anthropic/claude-opus-4-8");
        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("an API-targeted link leaves the toolset unrestricted")
    void apiLinkDoesNotRestrictToolset() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(API_ROUTE);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().credentials()).isNull();
    }

    @Test
    @DisplayName("the response keeps the billed identity so the ledger charges the chosen model")
    void responseKeepsBilledIdentityOnBridgeRun() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);

        ClassifyResponseDto result = service.execute(request());

        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("the budget guard prices the billed pair, never the execution target")
    void guardPricesTheBilledPair() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(API_ROUTE);

        service.execute(request());

        verify(guardChainFactory).forAgent(null, null, "anthropic", "claude-opus-4-8");
    }

    @Test
    @DisplayName("an UNLINKED bridge run is restricted too, so a classification never gets a CLI's tools or cwd")
    void unlinkedBridgeRunIsAlsoRestricted() {
        // The headline behaviour change: restriction follows the TRANSPORT, not the link.
        // Move this back under `route != null` and the node regains the CLI's native tools,
        // the real repo cwd and the repo/shell MCP tools - the exact escape this closes -
        // while every other test here stays green.
        when(bridgeDispatcher.shouldDispatch("anthropic")).thenReturn(true);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(bridgeDispatcher).execute(captor.capture(), eq(false));
        assertThat(captor.getValue().credentials())
            .containsEntry(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, Boolean.TRUE);
    }

    @Test
    @DisplayName("an UNLINKED bridge run still reports the model id the CLI returned")
    void unlinkedBridgeRunKeepsTheCliReportedModel() {
        // The relabel is gated on a link, exactly like the agent path. A node configured
        // directly on a CLI provider keeps the identity the bridge reported, which is what
        // it did before links reached this path.
        when(bridgeDispatcher.shouldDispatch("anthropic")).thenReturn(true);
        when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(loopResultEchoing("claude-code", "claude-opus-4-8-cli"));

        var result = service.execute(request());

        assertThat(result.model()).isEqualTo("claude-opus-4-8-cli");
    }

    @Test
    @DisplayName("a linked run the bridge guard refuses fails loudly instead of using the billed key")
    void bridgeDenialPropagates() {
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(BRIDGE_ROUTE);
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
        when(bridgeDispatcher.execute(any(), anyBoolean())).thenThrow(
            new com.apimarketplace.agent.bridge.BridgeAccessDeniedException("claude-code", "quota_exceeded", 0));

        // Falling back to the billed provider would spend the very key the admin linked
        // away from, so the node must surface the refusal.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.execute(request()))
            .isInstanceOf(com.apimarketplace.agent.bridge.BridgeAccessDeniedException.class);
        verify(agentLoopService, never()).execute(any(), any());
    }

    @Test
    @DisplayName("an unlinked pair runs exactly as before")
    void unlinkedPairIsUnchanged() {
        // An unlinked loop echoes the pair it was given, so this asserts no regression
        // for the overwhelmingly common case rather than the relabel.
        when(agentLoopService.execute(any(), isNull())).thenReturn(loopResultEchoing("anthropic", "claude-opus-4-8"));
        ClassifyResponseDto result = service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().provider()).isEqualTo("anthropic");
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-8");
        assertThat(captor.getValue().credentials()).isNull();
        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("with the link feature absent (self-hosted), the node runs the billed pair")
    void ceCompositionRunsTheBilledPair() {
        // The real router with no link store is the CE shape: the bean exists, the store
        // does not. Every other test here mocks the router, so this is the only place the
        // production composition is exercised.
        useRealRouter(null);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().provider()).isEqualTo("anthropic");
        assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("a bridge-targeted link with no bridge wired runs the billed pair rather than failing")
    void bridgeLinkWithoutBridgeFallsBackToTheBilledPair() {
        ModelExecutionLinkService store = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        when(store.resolve("anthropic", "claude-opus-4-8", "WORKFLOW"))
            .thenReturn(java.util.Optional.of(BRIDGE_ROUTE));
        when(bridgeDispatcher.isAvailable()).thenReturn(false);
        useRealRouter(store);

        service.execute(request());

        ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(agentLoopService).execute(captor.capture(), isNull());
        assertThat(captor.getValue().provider()).isEqualTo("anthropic");
        assertThat(captor.getValue().credentials()).isNull();
    }

    /** Rebuild the service on a REAL router, optionally backed by a link store. */
    private void useRealRouter(ModelExecutionLinkService store) {
        ExecutionLinkRouter realRouter = new ExecutionLinkRouter(bridgeDispatcher);
        org.springframework.test.util.ReflectionTestUtils.setField(realRouter, "executionLinkService", store);
        service = new ClassifyService(agentLoopService, guardChainFactory, new ObjectMapper(),
            bridgeDispatcher, modelCatalogService, realRouter, typeSafeClient);
    }

    private ClassifyRequestDto request() {
        return new ClassifyRequestDto("An invoice question", null, CATEGORIES,
            "anthropic", "claude-opus-4-8", null, null, null, null);
    }

    private AgentLoopResult loopResult() {
        return loopResultEchoing("claude-code", "claude-opus-4-8-cli");
    }

    /** A loop result reporting the identity the run actually executed on. */
    private AgentLoopResult loopResultEchoing(String provider, String model) {
        UsageInfo usage = UsageInfo.builder().promptTokens(80).completionTokens(20).totalTokens(100).build();
        CompletionResponse response = CompletionResponse.builder()
            .content(JSON).finishReason("stop").usage(usage).build();
        return AgentLoopResult.builder()
            .success(true)
            .content(JSON)
            .response(response)
            .usage(usage)
            .provider(provider)
            .model(model)
            .iterations(1)
            .durationMs(50)
            .stopReason(AgentStopReason.COMPLETED)
            .build();
    }
}
