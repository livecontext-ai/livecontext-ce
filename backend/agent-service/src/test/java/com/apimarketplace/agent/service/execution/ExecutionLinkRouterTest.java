package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExecutionLinkRouter}, the single place that turns a billed
 * (provider, model) pair into a route that is actually runnable here.
 */
@DisplayName("ExecutionLinkRouter")
@ExtendWith(MockitoExtension.class)
class ExecutionLinkRouterTest {

    @Mock
    private ModelExecutionLinkService linkService;

    @Mock
    private BridgeLoopDispatcher bridgeDispatcher;

    private ExecutionLinkRouter router;

    private static final ModelExecutionLinkService.ExecutionRoute BRIDGE_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-8");
    private static final ModelExecutionLinkService.ExecutionRoute API_ROUTE =
        new ModelExecutionLinkService.ExecutionRoute("openrouter", "anthropic/claude-opus-4-8");

    @BeforeEach
    void setUp() {
        router = new ExecutionLinkRouter(bridgeDispatcher);
        ReflectionTestUtils.setField(router, "executionLinkService", linkService);
    }

    @Test
    @DisplayName("no route when the link feature is absent (CE monolith)")
    void noRouteWithoutLinkService() {
        ReflectionTestUtils.setField(router, "executionLinkService", null);

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
        verify(bridgeDispatcher, never()).isAvailable();
    }

    @Test
    @DisplayName("no route when the billed pair is not linked")
    void noRouteWhenPairUnlinked() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.empty());

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
    }

    @Test
    @DisplayName("a bridge-targeted link is returned when the bridge transport is wired")
    void bridgeRouteWithBridgeAvailable() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(BRIDGE_ROUTE));
        when(bridgeDispatcher.isAvailable()).thenReturn(true);

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isEqualTo(BRIDGE_ROUTE);
    }

    @Test
    @DisplayName("a bridge-targeted link is DROPPED when the bridge transport is not wired")
    void bridgeRouteDroppedWhenBridgeUnavailable() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(BRIDGE_ROUTE));
        when(bridgeDispatcher.isAvailable()).thenReturn(false);

        // Dropping it runs the billed pair on its own provider, which is the documented
        // fallback: a link must never turn into a silent bridge failure.
        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
    }

    @Test
    @DisplayName("an API-targeted link is returned even with no bridge wired")
    void apiRouteIgnoresBridgeAvailability() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(API_ROUTE));

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isEqualTo(API_ROUTE);
        verify(bridgeDispatcher, never()).isAvailable();
    }

    @Test
    @DisplayName("V515: a link whose TARGET is disabled with a replacement runs on that replacement")
    void disabledTargetWithReplacementIsFollowed() {
        com.apimarketplace.agent.service.ModelReplacementResolver resolver =
            org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
        ReflectionTestUtils.setField(router, "modelReplacementResolver", resolver);
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(BRIDGE_ROUTE));
        when(resolver.explicitReplacementIfDisabled("claude-code", "claude-opus-4-8")).thenReturn(Optional.of(
            new com.apimarketplace.agent.service.ModelReplacementResolver.Pair("claude-code", "claude-opus-4-9")));
        when(bridgeDispatcher.isAvailable()).thenReturn(true);

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW"))
            .isEqualTo(new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-9"));
    }

    @Test
    @DisplayName("V515: a link whose target has no replacement is returned unchanged")
    void targetWithoutReplacementUnchanged() {
        com.apimarketplace.agent.service.ModelReplacementResolver resolver =
            org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
        ReflectionTestUtils.setField(router, "modelReplacementResolver", resolver);
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(API_ROUTE));
        when(resolver.explicitReplacementIfDisabled("openrouter", "anthropic/claude-opus-4-8")).thenReturn(Optional.empty());

        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isEqualTo(API_ROUTE);
    }

    @Test
    @DisplayName("V515: a target replaced ONTO an unwired bridge is dropped like any bridge link")
    void replacedTargetStillPassesTheBridgeCheck() {
        com.apimarketplace.agent.service.ModelReplacementResolver resolver =
            org.mockito.Mockito.mock(com.apimarketplace.agent.service.ModelReplacementResolver.class);
        ReflectionTestUtils.setField(router, "modelReplacementResolver", resolver);
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(API_ROUTE));
        when(resolver.explicitReplacementIfDisabled("openrouter", "anthropic/claude-opus-4-8")).thenReturn(Optional.of(
            new com.apimarketplace.agent.service.ModelReplacementResolver.Pair("claude-code", "claude-opus-4-9")));
        when(bridgeDispatcher.isAvailable()).thenReturn(false);

        // The replacement is checked AFTER the swap: a target moved onto a CLI must not
        // become a silent bridge failure where the bridge is not wired.
        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
    }

    @Test
    @DisplayName("a link-store failure runs the billed pair instead of failing the run")
    void storeFailureFallsBackToTheBilledPair() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW"))
            .thenThrow(new IllegalStateException("connection pool exhausted"));

        // Before links reached them, these callers had no dependency on this store at all,
        // so a blip in it must not take a workflow node down.
        assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
    }

    @Test
    @DisplayName("an unwired bridge link is reported once per pair, not once per call")
    void droppedBridgeRouteIsReportedOncePerPair() {
        when(linkService.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).thenReturn(Optional.of(BRIDGE_ROUTE));
        when(bridgeDispatcher.isAvailable()).thenReturn(false);

        // A misconfigured link inside a split loop would otherwise write one warn per item.
        for (int i = 0; i < 5; i++) {
            assertThat(router.runnableRoute("anthropic", "claude-opus-4-8", "WORKFLOW")).isNull();
        }
        assertThat(ReflectionTestUtils.getField(router, "droppedBridgeRoutesLogged"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
            .containsExactly("anthropic/claude-opus-4-8");
    }

    @Test
    @DisplayName("the restricted-mode key is the one the agent path writes, so the bridge matches both")
    void restrictedToolsetKeyIsSharedWithTheAgentPath() {
        // The bridge server compares this string exactly. Two spellings would not fail a
        // build, they would silently leave a linked CLI run with its native tools.
        // Pinning the literal is the point: the bridge server (mcp/bridge/server.mjs) does a
        // strict === on this exact string, so a second spelling would compile, ship, and
        // quietly leave a linked CLI run with its native tools and the project cwd.
        assertThat(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY).isEqualTo("__restrictedToolset__");
    }

    @Test
    @DisplayName("the surface each caller reports is a real one: a typo would degrade to wildcard-only with no other failure")
    void callerActivitySourcesResolveToTheirSurface() {
        // These constants are the ONLY thing tying a caller to a scope, and
        // fromActivitySource swallows an unknown token and returns null - so a misspelling
        // silently stops every surface-scoped link from applying, with nothing else failing.
        assertThat(com.apimarketplace.agent.domain.ModelExecutionLinkScope
                .fromActivitySource(ClassifyService.ACTIVITY_SOURCE))
            .isEqualTo(com.apimarketplace.agent.domain.ModelExecutionLinkScope.WORKFLOW);
        assertThat(com.apimarketplace.agent.domain.ModelExecutionLinkScope
                .fromActivitySource(GuardrailService.ACTIVITY_SOURCE))
            .isEqualTo(com.apimarketplace.agent.domain.ModelExecutionLinkScope.WORKFLOW);
        // The sub-agent surface is deliberately not a scope: only an ALL link routes it.
        assertThat(com.apimarketplace.agent.domain.ModelExecutionLinkScope
                .fromActivitySource(SubAgentExecutionHandler.ACTIVITY_SOURCE))
            .isNull();
    }

    @Test
    @DisplayName("targetsBridge tells a CLI target apart from an API target")
    void targetsBridgeDiscriminates() {
        assertThat(ExecutionLinkRouter.targetsBridge(BRIDGE_ROUTE)).isTrue();
        assertThat(ExecutionLinkRouter.targetsBridge(API_ROUTE)).isFalse();
        assertThat(ExecutionLinkRouter.targetsBridge(null)).isFalse();
    }
}
