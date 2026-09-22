package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolAuthorizationGuard - pure decision: which calls require authorization")
class ToolAuthorizationGuardTest {

    @Test
    @DisplayName("Sensitive action returns its canonical rule key")
    void sensitiveActionReturnsRule() {
        assertThat(ToolAuthorizationGuard.matchedRule("application", Map.of("action", "acquire")))
                .isEqualTo("application:acquire");
        assertThat(ToolAuthorizationGuard.matchedRule("catalog", Map.of("action", "call")))
                .isEqualTo("catalog:call");
        assertThat(ToolAuthorizationGuard.requiresAuthorization("agent", Map.of("action", "execute")))
                .isTrue();
    }

    @Test
    @DisplayName("Benign action on a sensitive tool returns null (not gated)")
    void benignActionOnSensitiveToolIsNotGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("agent", Map.of("action", "list"))).isNull();
        assertThat(ToolAuthorizationGuard.matchedRule("application", Map.of("action", "search"))).isNull();
    }

    @Test
    @DisplayName("Non-sensitive tool is never gated, even with a sensitive-looking action")
    void nonSensitiveToolIsNeverGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("files", Map.of("action", "execute"))).isNull();
        assertThat(ToolAuthorizationGuard.matchedRule("web_search", Map.of("action", "search"))).isNull();
    }

    @Test
    @DisplayName("Fail-closed: sensitive tool with no resolvable action is gated with a wildcard rule")
    void sensitiveToolWithMissingActionFailsClosed() {
        assertThat(ToolAuthorizationGuard.matchedRule("application", null)).isEqualTo("application:*");
        assertThat(ToolAuthorizationGuard.matchedRule("agent", new HashMap<>())).isEqualTo("agent:*");
        Map<String, Object> blankAction = new HashMap<>();
        blankAction.put("action", "   ");
        assertThat(ToolAuthorizationGuard.matchedRule("catalog", blankAction)).isEqualTo("catalog:*");
    }

    @Test
    @DisplayName("Null tool is never gated")
    void nullToolIsNeverGated() {
        assertThat(ToolAuthorizationGuard.matchedRule(null, Map.of("action", "acquire"))).isNull();
    }

    @Test
    @DisplayName("Matching is case-insensitive on tool and action")
    void caseInsensitive() {
        assertThat(ToolAuthorizationGuard.matchedRule("APPLICATION", Map.of("action", "ACQUIRE")))
                .isEqualTo("application:acquire");
    }

    @Test
    @DisplayName("Non-string action value is coerced before matching")
    void nonStringActionIsCoerced() {
        Map<String, Object> args = new HashMap<>();
        args.put("action", new StringBuilder("execute"));
        assertThat(ToolAuthorizationGuard.matchedRule("agent", args)).isEqualTo("agent:execute");
    }

    @Test
    @DisplayName("Every rule in the policy is gated - adding a rule is one line, coverage auto-extends")
    void everyPolicyRuleIsGated() {
        ToolAuthorizationPolicy.SENSITIVE_ACTIONS.forEach((tool, actions) ->
                actions.forEach(action ->
                        assertThat(ToolAuthorizationGuard.matchedRule(tool, Map.of("action", action)))
                                .as("rule %s:%s must be gated", tool, action)
                                .isEqualTo(tool + ":" + action)));
    }

    @Test
    @DisplayName("A cron in the arguments gates an otherwise ordinary agent:create")
    void cronMakesAnOrdinaryCreateSensitive() {
        assertThat(ToolAuthorizationGuard.matchedRule("agent",
                Map.of("action", "create", "name", "Watcher", "schedule_cron", "0 9 * * *")))
                .isEqualTo("agent:schedule");
        assertThat(ToolAuthorizationGuard.requiresAuthorization("agent",
                Map.of("action", "create", "schedule_cron", "0 9 * * *"))).isTrue();
    }

    @Test
    @DisplayName("The same create without a cron is not gated at all")
    void createWithoutCronIsNotGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("agent",
                Map.of("action", "create", "name", "Watcher"))).isNull();
        assertThat(ToolAuthorizationGuard.requiresAuthorization("agent",
                Map.of("action", "create", "name", "Watcher"))).isFalse();
    }

    @Test
    @DisplayName("A stray cron does not relabel an action that is gated in its own right")
    void aStrayCronDoesNotRelabelAnAlreadyGatedAction() {
        // agent:execute is gated whatever the call carries, and it must keep its OWN key: a
        // card relabelled agent:schedule would stop matching a standing grant the user gave
        // on execute, and would describe the wrong thing on screen.
        // This does not pin the two registries' precedence - no pair is in both today, so
        // reading them in either order gives this same answer. It pins that the conditional
        // registry cannot hijack a rule that already matched.
        assertThat(ToolAuthorizationGuard.matchedRule("agent",
                Map.of("action", "execute", "agent_id", "a-1", "schedule_cron", "0 9 * * *")))
                .isEqualTo("agent:execute");
    }

    @Test
    @DisplayName("Pin and unpin are gated from the action argument")
    void pinAndUnpinAreGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                Map.of("action", "pin", "workflow_id", "w-1", "version", 12)))
                .isEqualTo("workflow:pin");
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                Map.of("action", "unpin", "workflow_id", "w-1")))
                .isEqualTo("workflow:unpin");
    }

    @Test
    @DisplayName("Fail-closed still applies to a call carrying a cron and no resolvable action")
    void failClosedSurvivesTheConditionalBranch() {
        // Widening the guard to a second registry is exactly the kind of change that drops a
        // pre-existing branch, so the wildcard is re-asserted on the shape most likely to hit
        // it. Note 'agent' is unconditionally sensitive too, so this does NOT exercise a
        // conditional-ONLY tool - there is none in the registry today, and inventing one here
        // would test a fixture rather than the policy.
        assertThat(ToolAuthorizationGuard.matchedRule("agent", Map.of("schedule_cron", "0 9 * * *")))
                .isEqualTo("agent:*");
    }

    @Test
    @DisplayName("A tool with no rules of either kind is never gated, cron or not")
    void toolWithNoRulesIsNeverGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("files",
                Map.of("action", "list", "schedule_cron", "0 9 * * *"))).isNull();
        assertThat(ToolAuthorizationGuard.matchedRule("files", Map.of())).isNull();
    }

    @Test
    @DisplayName("The nested params shape the agent help recommends is gated like the flat one")
    void nestedParamsShapeIsGated() {
        // The help's three scheduled-agent examples all write params={... schedule_cron ...},
        // so this is the shape that actually arrives, not an exotic one.
        Map<String, Object> nested = Map.of(
                "action", "create",
                "params", Map.of("name", "Daily Reporter", "schedule_cron", "0 9 * * *"));

        assertThat(ToolAuthorizationGuard.matchedRule("agent", nested)).isEqualTo("agent:schedule");
        assertThat(ToolAuthorizationGuard.requiresAuthorization("agent", nested)).isTrue();
    }

    @Test
    @DisplayName("A nested params object without a cron is still not gated")
    void nestedParamsWithoutCronIsNotGated() {
        assertThat(ToolAuthorizationGuard.matchedRule("agent",
                Map.of("action", "create", "params", Map.of("name", "Researcher")))).isNull();
    }
}
