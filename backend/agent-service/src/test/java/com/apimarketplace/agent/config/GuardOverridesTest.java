package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the shared guard-override utility used by both the agent-scope write path
 * ({@code AgentService.validateGuardOverrides}) and the conversation-scope write path
 * ({@code ConversationCommandService.validateChatConfig}).
 *
 * <p>Pinning these rules here ensures both write paths stay in lockstep.
 */
@DisplayName("GuardOverrides")
class GuardOverridesTest {

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("null or empty map is accepted (no-op)")
        void nullOrEmptyMapIsAccepted() {
            assertThatCode(() -> GuardOverrides.validate(null)).doesNotThrowAnyException();
            assertThatCode(() -> GuardOverrides.validate(Map.of())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null value for a known key is allowed (= reset to YAML default)")
        void nullValueForKnownKeyIsAllowed() {
            Map<String, Integer> overrides = new HashMap<>();
            overrides.put(GuardOverrides.MAX_PER_RESOURCE_PER_TURN, null);
            overrides.put(GuardOverrides.LOOP_IDENTICAL_STOP, null);
            assertThatCode(() -> GuardOverrides.validate(overrides)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts in-range values for every known key")
        void acceptsInRangeValues() {
            Map<String, Integer> overrides = Map.of(
                GuardOverrides.MAX_PER_RESOURCE_PER_TURN, 5,
                GuardOverrides.LOOP_IDENTICAL_STOP, 15,
                GuardOverrides.LOOP_CONSECUTIVE_STOP, 40
            );
            assertThatCode(() -> GuardOverrides.validate(overrides)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects unknown key")
        void rejectsUnknownKey() {
            Map<String, Integer> overrides = Map.of("maxWidgetsPerTurn", 3);
            assertThatThrownBy(() -> GuardOverrides.validate(overrides))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxWidgetsPerTurn");
        }

        @Test
        @DisplayName("rejects maxAgentsPerTurn even if well-formed - key no longer exists")
        void rejectsRetiredKey() {
            Map<String, Integer> overrides = Map.of("maxAgentsPerTurn", 5);
            assertThatThrownBy(() -> GuardOverrides.validate(overrides))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAgentsPerTurn");
        }

        @Test
        @DisplayName("rejects maxPerResourcePerTurn <= 0")
        void rejectsMaxPerResourceZero() {
            assertThatThrownBy(() -> GuardOverrides.validate(Map.of(GuardOverrides.MAX_PER_RESOURCE_PER_TURN, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPerResourcePerTurn");
        }

        @Test
        @DisplayName("rejects loopIdenticalStop < 2")
        void rejectsLoopIdenticalBelow2() {
            assertThatThrownBy(() -> GuardOverrides.validate(Map.of(GuardOverrides.LOOP_IDENTICAL_STOP, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopIdenticalStop");
        }

        @Test
        @DisplayName("rejects loopConsecutiveStop < 4")
        void rejectsLoopConsecutiveBelow4() {
            assertThatThrownBy(() -> GuardOverrides.validate(Map.of(GuardOverrides.LOOP_CONSECUTIVE_STOP, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopConsecutiveStop");
        }

        @Test
        @DisplayName("loopIdenticalStop=2 is the minimum accepted value")
        void loopIdenticalStopMinimumBoundary() {
            assertThatCode(() -> GuardOverrides.validate(Map.of(GuardOverrides.LOOP_IDENTICAL_STOP, 2)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("loopConsecutiveStop=4 is the minimum accepted value")
        void loopConsecutiveStopMinimumBoundary() {
            assertThatCode(() -> GuardOverrides.validate(Map.of(GuardOverrides.LOOP_CONSECUTIVE_STOP, 4)))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("caller-agent override wins over credential and YAML default")
        void callerAgentOverrideWins() {
            Map<String, Object> creds = Map.of(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 99);
            int resolved = GuardOverrides.resolve(7, creds, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 5);
            assertThat(resolved).isEqualTo(7);
        }

        @Test
        @DisplayName("credential is used when caller-agent override is null")
        void credentialUsedWhenAgentOverrideNull() {
            Map<String, Object> creds = Map.of(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 12);
            int resolved = GuardOverrides.resolve(null, creds, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 5);
            assertThat(resolved).isEqualTo(12);
        }

        @Test
        @DisplayName("YAML default is returned when neither override is present")
        void yamlDefaultWhenNothingPresent() {
            int resolved = GuardOverrides.resolve(null, Map.of(), GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 5);
            assertThat(resolved).isEqualTo(5);
        }

        @Test
        @DisplayName("YAML default is returned when credentials map is null")
        void yamlDefaultWhenCredentialsNull() {
            int resolved = GuardOverrides.resolve(null, null, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 5);
            assertThat(resolved).isEqualTo(5);
        }

        @Test
        @DisplayName("non-positive caller-agent override falls through to credential")
        void nonPositiveAgentOverrideFallsThrough() {
            Map<String, Object> creds = Map.of(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 8);
            int resolved = GuardOverrides.resolve(0, creds, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 10);
            assertThat(resolved).isEqualTo(8);
        }

        @Test
        @DisplayName("non-positive credential falls through to YAML default")
        void nonPositiveCredentialFallsThrough() {
            Map<String, Object> creds = Map.of(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 0);
            int resolved = GuardOverrides.resolve(null, creds, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 3);
            assertThat(resolved).isEqualTo(3);
        }

        @Test
        @DisplayName("non-numeric credential value falls through to YAML default")
        void nonNumericCredentialFallsThrough() {
            Map<String, Object> creds = Map.of(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, "bogus");
            int resolved = GuardOverrides.resolve(null, creds, GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, 5);
            assertThat(resolved).isEqualTo(5);
        }
    }
}
