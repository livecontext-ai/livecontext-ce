package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolAuthorizationPolicy - the hand-curated sensitive-action list")
class ToolAuthorizationPolicyTest {

    @ParameterizedTest(name = "requires({0}, {1}) == true")
    @CsvSource({
            "application,acquire",
            "application,execute",
            "agent,execute",
            "catalog,execute",
            "catalog,call",
            "workflow,execute",
            "workflow,continue_interface",   // advancing a paused interface mutates run state
            "workflow,resolve_approval",     // resolving a user approval mutates run state
    })
    @DisplayName("Listed (tool, action) pairs require authorization")
    void listedPairsRequireAuthorization(String tool, String action) {
        assertThat(ToolAuthorizationPolicy.requires(tool, action)).isTrue();
    }

    @ParameterizedTest(name = "requires({0}, {1}) == false")
    @CsvSource({
            "application,search",   // benign application action
            "agent,list",           // benign agent action
            "agent,get",
            "catalog,search",       // benign catalog action
            "files,list",           // files has no sensitive actions
            "web_search,search",
            "workflow,get_run",     // inspecting a run is read-only
            "workflow,modify",      // editing a draft does not advance a run
            "workflow,validate",
    })
    @DisplayName("Benign actions and non-sensitive tools do not require authorization")
    void benignPairsDoNotRequireAuthorization(String tool, String action) {
        assertThat(ToolAuthorizationPolicy.requires(tool, action)).isFalse();
    }

    @Test
    @DisplayName("Matching is case-insensitive on both tool and action")
    void matchingIsCaseInsensitive() {
        assertThat(ToolAuthorizationPolicy.requires("APPLICATION", "ACQUIRE")).isTrue();
        assertThat(ToolAuthorizationPolicy.requires("Catalog", "Call")).isTrue();
    }

    @Test
    @DisplayName("Null tool or action never requires authorization")
    void nullsAreSafe() {
        assertThat(ToolAuthorizationPolicy.requires(null, "acquire")).isFalse();
        assertThat(ToolAuthorizationPolicy.requires("application", null)).isFalse();
    }

    @Test
    @DisplayName("isSensitiveTool reflects whether a tool exposes any gated action")
    void isSensitiveTool() {
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("application")).isTrue();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("agent")).isTrue();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool("files")).isFalse();
        assertThat(ToolAuthorizationPolicy.isSensitiveTool(null)).isFalse();
    }

    @Test
    @DisplayName("ruleKey returns canonical lowercase tool:action, or null when not gated")
    void ruleKeyIsCanonical() {
        assertThat(ToolAuthorizationPolicy.ruleKey("Application", "Acquire")).isEqualTo("application:acquire");
        assertThat(ToolAuthorizationPolicy.ruleKey("catalog", "call")).isEqualTo("catalog:call");
        assertThat(ToolAuthorizationPolicy.ruleKey("agent", "list")).isNull();
    }

    @Test
    @DisplayName("Guard gates workflow continue_interface / resolve_approval from the action arg, not get_run")
    void guardGatesNewWorkflowSignalActions() {
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "resolve_approval", "run_id", "r")))
                .isEqualTo("workflow:resolve_approval");
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "continue_interface", "run_id", "r")))
                .isEqualTo("workflow:continue_interface");
        assertThat(ToolAuthorizationGuard.matchedRule("workflow",
                java.util.Map.of("action", "get_run", "run_id", "r")))
                .isNull();
    }

    @Test
    @DisplayName("All listed actions are lowercase non-blank - keeps the list clean for matching")
    void listedActionsAreCleanLowercase() {
        for (Set<String> actions : ToolAuthorizationPolicy.SENSITIVE_ACTIONS.values()) {
            for (String action : actions) {
                assertThat(action).isNotBlank();
                assertThat(action).isEqualTo(action.toLowerCase());
            }
        }
        for (String tool : ToolAuthorizationPolicy.SENSITIVE_ACTIONS.keySet()) {
            assertThat(tool).isEqualTo(tool.toLowerCase());
        }
    }
}
