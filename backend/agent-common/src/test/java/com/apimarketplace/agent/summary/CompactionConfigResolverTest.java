package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CompactionConfigResolver} - the three-tier precedence
 * (conversation > agent > YAML) for the compaction enable flag + cadence.
 */
class CompactionConfigResolverTest {

    @Nested
    @DisplayName("enabled precedence")
    class EnabledPrecedence {

        @Test
        @DisplayName("conversation override wins over agent and YAML")
        void conversationEnabledWins() {
            // conversation=true beats agent=false and yaml=false
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(true, null, false, null, false, 5);
            assertThat(e.enabled()).isTrue();

            // conversation=false beats agent=true and yaml=true
            CompactionConfigResolver.Effective e2 =
                    CompactionConfigResolver.resolve(false, null, true, null, true, 5);
            assertThat(e2.enabled()).isFalse();
        }

        @Test
        @DisplayName("agent override wins when conversation is unset")
        void agentEnabledWinsWhenConversationNull() {
            // agent=true overrides a globally-disabled default
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, null, true, null, false, 5);
            assertThat(e.enabled()).isTrue();

            // agent=false overrides a globally-enabled default
            CompactionConfigResolver.Effective e2 =
                    CompactionConfigResolver.resolve(null, null, false, null, true, 5);
            assertThat(e2.enabled()).isFalse();
        }

        @Test
        @DisplayName("falls back to YAML default when both overrides are null")
        void yamlEnabledFallback() {
            assertThat(CompactionConfigResolver.resolve(null, null, null, null, true, 5).enabled()).isTrue();
            assertThat(CompactionConfigResolver.resolve(null, null, null, null, false, 5).enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("afterTurns precedence")
    class AfterTurnsPrecedence {

        @Test
        @DisplayName("conversation cadence wins over agent and YAML")
        void conversationAfterTurnsWins() {
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, 3, null, 8, false, 5);
            assertThat(e.afterTurns()).isEqualTo(3);
        }

        @Test
        @DisplayName("agent cadence wins when conversation cadence is unset")
        void agentAfterTurnsWinsWhenConversationNull() {
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, null, null, 8, false, 5);
            assertThat(e.afterTurns()).isEqualTo(8);
        }

        @Test
        @DisplayName("falls back to YAML cadence when both overrides are null")
        void yamlCadenceFallback() {
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, null, null, null, true, 7);
            assertThat(e.afterTurns()).isEqualTo(7);
        }

        @Test
        @DisplayName("non-positive overrides are ignored (treated as unset)")
        void nonPositiveOverridesIgnored() {
            // conversation=0 and agent=-1 are both invalid → fall through to YAML
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, 0, null, -1, true, 6);
            assertThat(e.afterTurns()).isEqualTo(6);
        }

        @Test
        @DisplayName("non-positive YAML cadence floors to the default")
        void yamlNonPositiveFloorsToDefault() {
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, null, null, null, true, 0);
            assertThat(e.afterTurns()).isEqualTo(CompactionConfigResolver.DEFAULT_CADENCE_FLOOR);
        }
    }

    @Test
    @DisplayName("enabled and afterTurns resolve independently across tiers")
    void fieldsResolveIndependently() {
        // conversation overrides enabled only; agent supplies cadence only
        CompactionConfigResolver.Effective e =
                CompactionConfigResolver.resolve(true, null, null, 9, false, 5);
        assertThat(e.enabled()).isTrue();
        assertThat(e.afterTurns()).isEqualTo(9);
    }
}
