package com.apimarketplace.conversation.service.ai.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.3 - pin the security-sensitive schema-slim exclusion contract.
 *
 * <p>Covers:
 * <ul>
 *   <li>Whole-tool exclusion short-circuits on {@code alwaysFullTools}.</li>
 *   <li>Per-action exclusion uses a lowercased {@code "tool:action"} key.</li>
 *   <li>Positive-list regex fires on tool name OR action string.</li>
 *   <li>Fail-safe posture: malformed regex → every call reports excluded.</li>
 *   <li>Null/blank inputs behave without NPE and prefer the manual list.</li>
 * </ul>
 */
@DisplayName("SchemaSlimExclusionPolicy - always-FULL guard (Stage 4a.3)")
class SchemaSlimExclusionPolicyTest {

    private JitExclusionProperties properties;
    private SchemaSlimExclusionPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new JitExclusionProperties();
    }

    private SchemaSlimExclusionPolicy build() {
        SchemaSlimExclusionPolicy p = new SchemaSlimExclusionPolicy(properties);
        p.init();
        return p;
    }

    @Nested
    @DisplayName("whole-tool exclusion")
    class WholeToolExclusion {

        @Test
        @DisplayName("tool in alwaysFullTools → every action excluded")
        void alwaysFullToolMatches() {
            Set<String> tools = new HashSet<>();
            tools.add("request_credential");
            properties.setAlwaysFullTools(tools);
            policy = build();

            assertThat(policy.isExcluded("request_credential", null)).isTrue();
            assertThat(policy.isExcluded("request_credential", "any_action")).isTrue();
            assertThat(policy.isToolAlwaysFull("request_credential")).isTrue();
        }

        @Test
        @DisplayName("alwaysFullTools matching is exact-case")
        void alwaysFullToolsExactCase() {
            Set<String> tools = new HashSet<>();
            tools.add("request_credential");
            properties.setAlwaysFullTools(tools);
            // Default regex is disabled so case mismatch genuinely falls through.
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("Request_Credential", "foo")).isFalse();
        }
    }

    @Nested
    @DisplayName("per-action exclusion")
    class PerActionExclusion {

        @Test
        @DisplayName("tool:action key hits manual list → excluded")
        void toolActionKeyHits() {
            Set<String> actions = new HashSet<>();
            actions.add("agent:publish");
            actions.add("credential:rotate");
            properties.setAlwaysFullToolActions(actions);
            // Disable regex so we isolate the manual-list path.
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("agent", "publish")).isTrue();
            assertThat(policy.isExcluded("credential", "rotate")).isTrue();
        }

        @Test
        @DisplayName("tool:action key lookup is case-insensitive on both sides")
        void toolActionKeyCaseInsensitive() {
            Set<String> actions = new HashSet<>();
            actions.add("agent:publish");
            properties.setAlwaysFullToolActions(actions);
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("AGENT", "PUBLISH")).isTrue();
            assertThat(policy.isExcluded("Agent", "Publish")).isTrue();
        }

        @Test
        @DisplayName("unknown tool:action → not excluded (when regex disabled)")
        void unknownToolActionFalse() {
            Set<String> actions = new HashSet<>();
            actions.add("agent:publish");
            properties.setAlwaysFullToolActions(actions);
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("agent", "list")).isFalse();
            assertThat(policy.isExcluded("skill", "get")).isFalse();
        }

        @Test
        @DisplayName("null/blank action falls back to whole-tool check")
        void blankActionFallsBackToWholeTool() {
            Set<String> tools = new HashSet<>();
            tools.add("request_credential");
            properties.setAlwaysFullTools(tools);
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("request_credential", null)).isTrue();
            assertThat(policy.isExcluded("request_credential", "   ")).isTrue();
            assertThat(policy.isExcluded("agent", null)).isFalse();
            assertThat(policy.isExcluded("agent", "")).isFalse();
        }
    }

    @Nested
    @DisplayName("positive-list regex (default pattern - R25)")
    class DefaultRegex {

        @BeforeEach
        void useDefaultRegex() {
            policy = build();
        }

        @Test
        @DisplayName("action 'publish' matches → excluded")
        void publishActionMatches() {
            assertThat(policy.isExcluded("random_tool", "publish")).isTrue();
        }

        @Test
        @DisplayName("action 'rotate' matches → excluded")
        void rotateActionMatches() {
            assertThat(policy.isExcluded("random_tool", "rotate")).isTrue();
        }

        @Test
        @DisplayName("action 'approve' matches → excluded")
        void approveActionMatches() {
            assertThat(policy.isExcluded("task", "approve")).isTrue();
        }

        @Test
        @DisplayName("action 'charge' matches → excluded (catches future invoice:charge)")
        void chargeActionMatches() {
            assertThat(policy.isExcluded("invoice", "charge")).isTrue();
        }

        @Test
        @DisplayName("dash-separated tool name with 'credential' matches → excluded")
        void credentialToolMatches() {
            // Dash is not a word character, so \bcredential\b matches at the boundary.
            assertThat(policy.isExcluded("credential-store", "list")).isTrue();
            assertThat(policy.isToolAlwaysFull("credential-store")).isTrue();
        }

        @Test
        @DisplayName("dash-separated tool name with 'secret' matches → excluded")
        void secretToolMatches() {
            assertThat(policy.isExcluded("secret-manager", "get")).isTrue();
        }

        @Test
        @DisplayName("bare api_key / api-key tool names match → excluded")
        void apiKeyVariantsMatch() {
            // Bare tokens have string-boundary \b on each side, so both match.
            assertThat(policy.isExcluded("api_key", "list")).isTrue();
            assertThat(policy.isExcluded("api-key", "list")).isTrue();
        }

        @Test
        @DisplayName("underscore-embedded tool names are NOT caught by regex alone (documents \\b limitation)")
        void underscoreEmbeddedToolNamesNotCaughtByRegex() {
            // \b requires a transition between \w and \W. Underscore is \w, so
            // 'request_credential' has no \b around 'credential'. These names
            // must be pinned via alwaysFullTools instead of relying on regex.
            assertThat(policy.isExcluded("request_credential", "list")).isFalse();
            assertThat(policy.isExcluded("api_key_store", "list")).isFalse();
            assertThat(policy.isExcluded("secret_manager", "list")).isFalse();
        }

        @Test
        @DisplayName("underscore-embedded action names are NOT caught by regex alone - manual entries are required")
        void underscoreEmbeddedActionsNotCaughtByRegex() {
            // 'force_replace' contains 'force' but \bforce\b fails because '_' is \w.
            // Similarly 'rotate_key' hides 'rotate'. Using a benign tool name so the
            // tool-name regex branch stays silent and we isolate the action branch.
            assertThat(policy.isExcluded("creds", "force_replace")).isFalse();
            assertThat(policy.isExcluded("creds", "rotate_key")).isFalse();
        }

        @Test
        @DisplayName("delete_all / delete-all variants match → excluded")
        void deleteAllMatches() {
            assertThat(policy.isExcluded("tool", "delete_all")).isTrue();
            assertThat(policy.isExcluded("tool", "delete-all")).isTrue();
            assertThat(policy.isExcluded("tool", "deleteall")).isTrue();
        }

        @Test
        @DisplayName("benign read-only actions do NOT match")
        void benignActionsDoNotMatch() {
            assertThat(policy.isExcluded("agent", "list")).isFalse();
            assertThat(policy.isExcluded("workflow", "get")).isFalse();
            assertThat(policy.isExcluded("interface", "preview")).isFalse();
        }

        @Test
        @DisplayName("regex is case-insensitive")
        void regexCaseInsensitive() {
            assertThat(policy.isExcluded("agent", "PUBLISH")).isTrue();
            assertThat(policy.isExcluded("agent", "Rotate")).isTrue();
        }
    }

    @Nested
    @DisplayName("regex configuration edge cases")
    class RegexConfig {

        @Test
        @DisplayName("empty regex pattern → only manual list consulted")
        void emptyPatternDisablesRegex() {
            properties.setPositiveListPattern("");
            policy = build();

            // 'publish' would match the default regex, but regex is disabled.
            assertThat(policy.isExcluded("agent", "publish")).isFalse();
            assertThat(policy.isExcluded("random_tool", "rotate")).isFalse();
        }

        @Test
        @DisplayName("null regex pattern → only manual list consulted")
        void nullPatternDisablesRegex() {
            properties.setPositiveListPattern(null);
            policy = build();

            assertThat(policy.isExcluded("agent", "publish")).isFalse();
        }

        @Test
        @DisplayName("malformed regex → fail-safe: all calls report excluded")
        void malformedPatternFailsSafe() {
            properties.setPositiveListPattern("(?i)\\b(publish"); // unclosed group
            policy = build();

            assertThat(policy.isExcluded("any_tool", "any_action")).isTrue();
            assertThat(policy.isExcluded(null, null)).isTrue();
            assertThat(policy.isToolAlwaysFull("any_tool")).isTrue();
            assertThat(policy.isToolAlwaysFull(null)).isTrue();
        }

        @Test
        @DisplayName("custom regex overrides default and is applied")
        void customRegexApplied() {
            properties.setPositiveListPattern("(?i)custom_danger");
            policy = build();

            assertThat(policy.isExcluded("some_tool", "custom_danger_move")).isTrue();
            // Default keywords no longer match (we replaced the pattern).
            assertThat(policy.isExcluded("some_tool", "publish")).isFalse();
        }
    }

    @Nested
    @DisplayName("null / blank inputs")
    class NullBlank {

        @BeforeEach
        void useDefaultRegex() {
            policy = build();
        }

        @Test
        @DisplayName("null toolName → not excluded (nothing to look up)")
        void nullToolNameFalse() {
            assertThat(policy.isExcluded(null, "publish")).isFalse();
            assertThat(policy.isToolAlwaysFull(null)).isFalse();
        }

        @Test
        @DisplayName("null toolName with null action → not excluded")
        void nullBothFalse() {
            assertThat(policy.isExcluded(null, null)).isFalse();
        }

        @Test
        @DisplayName("null setter for alwaysFullTools → empty set (no NPE)")
        void nullAlwaysFullToolsSetter() {
            properties.setAlwaysFullTools(null);
            policy = build();

            assertThat(policy.isExcluded("agent", "list")).isFalse();
        }

        @Test
        @DisplayName("null setter for alwaysFullToolActions → empty set (no NPE)")
        void nullAlwaysFullToolActionsSetter() {
            properties.setAlwaysFullToolActions(null);
            policy = build();

            assertThat(policy.isExcluded("agent", "list")).isFalse();
        }
    }

    @Nested
    @DisplayName("resolution order")
    class ResolutionOrder {

        @Test
        @DisplayName("whole-tool exclusion wins over per-action miss")
        void wholeToolBeatsActionMiss() {
            Set<String> tools = new HashSet<>();
            tools.add("sensitive_tool");
            properties.setAlwaysFullTools(tools);
            properties.setPositiveListPattern("");
            policy = build();

            assertThat(policy.isExcluded("sensitive_tool", "read")).isTrue();
        }

        @Test
        @DisplayName("regex triggers even if manual lists are empty")
        void regexStandaloneTriggers() {
            properties.setAlwaysFullTools(new HashSet<>());
            properties.setAlwaysFullToolActions(new HashSet<>());
            policy = build();

            assertThat(policy.isExcluded("random", "publish")).isTrue();
        }
    }
}
