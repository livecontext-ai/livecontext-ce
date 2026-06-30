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
}
