package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the {@link ModelExecutionLinkScope} routing contract: which app surfaces a
 * model execution link can be scoped to, and how an admin token / a runtime activity
 * source map onto a scope.
 *
 * <p><b>Why this test exists.</b> A link scoped to surface X only takes effect when a
 * real run is tagged with X as its {@code AgentExecutionRequestDto.source} at the
 * resolve chokepoint ({@code AgentRemoteExecutionService.resolveActivitySource}).
 * Two halves make that work, and each is guarded separately:
 * <ul>
 *   <li><b>Resolver side (here).</b> {@link #everyNonAllSurfaceIsResolvableFromItsOwnName()}
 *       pins that {@link ModelExecutionLinkScope#fromActivitySource(String)} keeps mapping
 *       every non-{@link ModelExecutionLinkScope#ALL} surface back to itself. Because the
 *       resolver is {@code valueOf(name)} plus two special branches, this specifically
 *       fails if a future change diverts a surface into the {@code null} / alias path
 *       (the silent "offer-but-never-routes" regression) - it does not, and cannot,
 *       prove the producer side.</li>
 *   <li><b>Producer side (elsewhere).</b> Each token below is emitted by a real dispatch
 *       path, covered by that path's own tests; {@link #fromActivitySourceMapsRealProducerTokens()}
 *       pins the exact spelling the resolver must keep accepting.</li>
 * </ul>
 *
 * <p>Each surface below has a real producer of its source token:
 * <ul>
 *   <li>WORKFLOW - {@code AgentNode} stamps {@code source="WORKFLOW"} on every
 *       workflow agent node run.</li>
 *   <li>CHAT - interactive general chat (the {@code CHAT}/{@code CONVERSATION}
 *       source).</li>
 *   <li>WEBHOOK - {@code AgentWebhookDispatchService} dispatches a standalone
 *       webhook agent with {@code source="WEBHOOK"}.</li>
 *   <li>WIDGET - {@code WidgetSessionService} dispatches an embedded widget agent
 *       with {@code source="WIDGET"}.</li>
 *   <li>SCHEDULE - {@code ScheduleExecutorService} runs a scheduled agent with
 *       {@code source="SCHEDULE"}.</li>
 *   <li>TASK / TASK_REVIEW - delegated-task + task-review runs.</li>
 * </ul>
 */
@DisplayName("ModelExecutionLinkScope - routing contract")
class ModelExecutionLinkScopeTest {

    // ── fromActivitySource: every offered surface routes ─────────────────────

    @ParameterizedTest
    @EnumSource(value = ModelExecutionLinkScope.class, names = "ALL", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every NON-ALL surface resolves from its own name (it is genuinely routable, so it is safe to offer in the picker)")
    void everyNonAllSurfaceIsResolvableFromItsOwnName(ModelExecutionLinkScope surface) {
        // A run tagged with the surface's own token must resolve back to that surface.
        // If this fails for some value, that surface never routes and must not be offered.
        assertThat(ModelExecutionLinkScope.fromActivitySource(surface.name())).isEqualTo(surface);
    }

    @Test
    @DisplayName("fromActivitySource maps each real runtime producer token to its surface scope")
    void fromActivitySourceMapsRealProducerTokens() {
        assertThat(ModelExecutionLinkScope.fromActivitySource("WORKFLOW")).isEqualTo(ModelExecutionLinkScope.WORKFLOW);
        assertThat(ModelExecutionLinkScope.fromActivitySource("CHAT")).isEqualTo(ModelExecutionLinkScope.CHAT);
        assertThat(ModelExecutionLinkScope.fromActivitySource("WEBHOOK")).isEqualTo(ModelExecutionLinkScope.WEBHOOK);
        assertThat(ModelExecutionLinkScope.fromActivitySource("WIDGET")).isEqualTo(ModelExecutionLinkScope.WIDGET);
        assertThat(ModelExecutionLinkScope.fromActivitySource("SCHEDULE")).isEqualTo(ModelExecutionLinkScope.SCHEDULE);
        assertThat(ModelExecutionLinkScope.fromActivitySource("TASK")).isEqualTo(ModelExecutionLinkScope.TASK);
        assertThat(ModelExecutionLinkScope.fromActivitySource("TASK_REVIEW")).isEqualTo(ModelExecutionLinkScope.TASK_REVIEW);
    }

    @Test
    @DisplayName("fromActivitySource treats CONVERSATION as an alias of CHAT")
    void fromActivitySourceAliasesConversationToChat() {
        assertThat(ModelExecutionLinkScope.fromActivitySource("CONVERSATION")).isEqualTo(ModelExecutionLinkScope.CHAT);
        assertThat(ModelExecutionLinkScope.fromActivitySource("conversation")).isEqualTo(ModelExecutionLinkScope.CHAT);
    }

    @Test
    @DisplayName("fromActivitySource is case-insensitive on the token")
    void fromActivitySourceIsCaseInsensitive() {
        assertThat(ModelExecutionLinkScope.fromActivitySource("webhook")).isEqualTo(ModelExecutionLinkScope.WEBHOOK);
        assertThat(ModelExecutionLinkScope.fromActivitySource(" Schedule ")).isEqualTo(ModelExecutionLinkScope.SCHEDULE);
    }

    @Test
    @DisplayName("fromActivitySource returns null for ALL, blank, null and an unknown surface (only an ALL link can then apply)")
    void fromActivitySourceReturnsNullForNonSurfaces() {
        assertThat(ModelExecutionLinkScope.fromActivitySource("ALL")).isNull();      // ALL is never an activity source
        assertThat(ModelExecutionLinkScope.fromActivitySource("all")).isNull();      // ... case-insensitively
        assertThat(ModelExecutionLinkScope.fromActivitySource(null)).isNull();
        assertThat(ModelExecutionLinkScope.fromActivitySource("   ")).isNull();
        assertThat(ModelExecutionLinkScope.fromActivitySource("SUB_AGENT")).isNull(); // guardrail/classify/sub-agent never reach the chokepoint
    }

    // ── parse: admin token handling ──────────────────────────────────────────

    @Test
    @DisplayName("parse maps a blank or null admin token to the ALL wildcard")
    void parseBlankDefaultsToAll() {
        assertThat(ModelExecutionLinkScope.parse(null)).isEqualTo(ModelExecutionLinkScope.ALL);
        assertThat(ModelExecutionLinkScope.parse("")).isEqualTo(ModelExecutionLinkScope.ALL);
        assertThat(ModelExecutionLinkScope.parse("  ")).isEqualTo(ModelExecutionLinkScope.ALL);
    }

    @ParameterizedTest
    @EnumSource(ModelExecutionLinkScope.class)
    @DisplayName("parse accepts every scope name, case-insensitively and trimmed")
    void parseAcceptsEveryScopeName(ModelExecutionLinkScope scope) {
        assertThat(ModelExecutionLinkScope.parse(scope.name())).isEqualTo(scope);
        assertThat(ModelExecutionLinkScope.parse("  " + scope.name().toLowerCase() + "  ")).isEqualTo(scope);
    }

    @Test
    @DisplayName("parse throws on an unknown token so the controller maps it to a 400")
    void parseThrowsOnUnknownToken() {
        assertThatThrownBy(() -> ModelExecutionLinkScope.parse("NOT_A_SURFACE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope must be one of");
    }

    @Test
    @DisplayName("parse does NOT honor the CONVERSATION alias (only the runtime resolver does) - it is not a real scope name, so it is a 400")
    void parseRejectsConversationAlias() {
        // Deliberate asymmetry: fromActivitySource("CONVERSATION") -> CHAT (a runtime
        // source alias), but CONVERSATION is not a ModelExecutionLinkScope an admin can
        // pick, so parse must reject it rather than silently coerce it to CHAT.
        assertThat(ModelExecutionLinkScope.fromActivitySource("CONVERSATION")).isEqualTo(ModelExecutionLinkScope.CHAT);
        assertThatThrownBy(() -> ModelExecutionLinkScope.parse("CONVERSATION"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope must be one of");
    }
}
