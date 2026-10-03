package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-029 regression suite: {@code web_search} was absent from the sensitive set, so the one
 * browser-agent session (an unbounded channel to a host the model chose) started with no user
 * approval. fetch is bounded by the agent mode and the restricted-scope allow-list instead.
 *
 * <p>Pre-fix, {@code SENSITIVE_ACTIONS} had no {@code web_search} key at all, so
 * {@code requires("web_search", "agent_browse")} was false and {@code matchedRule} returned null for
 * every call including one with no action: each assertion below inverts on that code.
 */
@DisplayName("LC-029 web_search authorization gate")
class ToolAuthorizationPolicyWebSearchEgressTest {

    @Test
    @DisplayName("The CE top-level agent_browse tool raises the same card")
    void ceAgentBrowseToolRequiresAuthorization() {
        assertThat(ToolAuthorizationGuard.matchedRule("agent_browse", Map.of("action", "agent_browse")))
                .isEqualTo("agent_browse:agent_browse");
        assertThat(ToolAuthorizationGuard.matchedRule("agent_browse", Map.of("action", "browse_status")))
                .isNull();
    }

    @ParameterizedTest(name = "web_search:{0} requires authorization")
    @ValueSource(strings = {"agent_browse"})
    @DisplayName("Actions that reach a model-chosen host require authorization")
    void modelChosenDestinationsRequireAuthorization(String action) {
        assertThat(ToolAuthorizationPolicy.requires("web_search", action)).isTrue();
        assertThat(ToolAuthorizationPolicy.ruleKey("web_search", action))
                .isEqualTo("web_search:" + action);
        assertThat(ToolAuthorizationGuard.matchedRule("web_search", Map.of("action", action)))
                .isEqualTo("web_search:" + action);
    }

    @ParameterizedTest(name = "web_search:{0} stays ungated")
    @ValueSource(strings = {
            // fixed destination: the platform's own search backend, not a host the model picks
            "search",
            // bounded by the read/write mode and the restricted-scope destination list instead
            // of a card on every page read (see ToolAuthorizationPolicy)
            "fetch",
            // address an already-authorized session by id and open no new destination
            "browse_status", "browse_intervene", "browse_abort", "browse_screenshot",
            // documentation
            "help", "help_models"
    })
    @DisplayName("Actions that open no new destination stay ungated")
    void actionsWithoutANewDestinationStayUngated(String action) {
        assertThat(ToolAuthorizationPolicy.requires("web_search", action)).isFalse();
        assertThat(ToolAuthorizationGuard.matchedRule("web_search", Map.of("action", action)))
                .isNull();
    }

    @Test
    @DisplayName("web_search is now a sensitive tool, so a call with no action fails closed")
    void missingActionFailsClosed() {
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("web_search")).isTrue();
        assertThat(ToolAuthorizationGuard.matchedRule("web_search", Map.of()))
                .isEqualTo("web_search:*");
        assertThat(ToolAuthorizationGuard.matchedRule("web_search", null))
                .isEqualTo("web_search:*");
    }

    @Test
    @DisplayName("Matching stays case-insensitive for the new pairs")
    void matchingIsCaseInsensitive() {
        assertThat(ToolAuthorizationPolicy.requires("WEB_SEARCH", "Agent_Browse")).isTrue();
        assertThat(ToolAuthorizationPolicy.requires("Web_Search", "AGENT_BROWSE")).isTrue();
    }
}
