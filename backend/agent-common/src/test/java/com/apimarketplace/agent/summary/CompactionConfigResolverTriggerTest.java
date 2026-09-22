package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the trigger half of the effective-config ladder.
 *
 * <p>The rule worth defending here is that an explicitly set cadence is never
 * silently discarded. {@link CompactionTrigger#SIZE} ignores the cadence by
 * definition, so a globally configured {@code size} would make a user's own
 * "compact after N turns" inert while the agent tool help and the agent UI
 * keep promising it works. That is the green-when-wrong shape: the setting is
 * accepted, validated and persisted, and then does nothing.
 */
@DisplayName("CompactionConfigResolver - trigger resolution")
class CompactionConfigResolverTriggerTest {

    private static final int SIZE_TRIGGER = 32_000;

    @Nested
    @DisplayName("An explicit cadence is never voided by size mode")
    class ExplicitCadenceSurvives {

        @Test
        @DisplayName("Conversation sets a cadence under global SIZE - upgraded to SIZE_OR_TURNS")
        void conversationCadenceUpgrades() {
            CompactionConfigResolver.Effective e = CompactionConfigResolver.resolve(
                    null, /*convAfterTurns*/ 3, null, null,
                    true, 5, CompactionTrigger.SIZE, SIZE_TRIGGER);

            assertThat(e.trigger()).isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
            assertThat(e.afterTurns()).isEqualTo(3);
        }

        @Test
        @DisplayName("Agent sets a cadence under global SIZE - upgraded to SIZE_OR_TURNS")
        void agentCadenceUpgrades() {
            CompactionConfigResolver.Effective e = CompactionConfigResolver.resolve(
                    null, null, null, /*agentAfterTurns*/ 7,
                    true, 5, CompactionTrigger.SIZE, SIZE_TRIGGER);

            assertThat(e.trigger()).isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
            assertThat(e.afterTurns()).isEqualTo(7);
        }

        @Test
        @DisplayName("No explicit cadence - SIZE stays SIZE, the operator's choice is respected")
        void inheritedCadenceDoesNotUpgrade() {
            // The YAML cadence is a default, not a user's intent, so it must not
            // quietly turn every deployment's SIZE into SIZE_OR_TURNS.
            CompactionConfigResolver.Effective e = CompactionConfigResolver.resolve(
                    null, null, null, null,
                    true, 5, CompactionTrigger.SIZE, SIZE_TRIGGER);

            assertThat(e.trigger()).isEqualTo(CompactionTrigger.SIZE);
        }

        @Test
        @DisplayName("A non-positive per-scope cadence is not an explicit setting, so it does not upgrade")
        void nonPositiveCadenceDoesNotUpgrade() {
            CompactionConfigResolver.Effective e = CompactionConfigResolver.resolve(
                    null, 0, null, -1,
                    true, 5, CompactionTrigger.SIZE, SIZE_TRIGGER);

            assertThat(e.trigger()).isEqualTo(CompactionTrigger.SIZE);
            assertThat(e.afterTurns()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Pass-through and defaults")
    class PassThrough {

        @Test
        @DisplayName("TURNS is never upgraded, whatever cadence a scope sets")
        void turnsNeverUpgrades() {
            assertThat(CompactionConfigResolver.resolve(null, 3, null, null,
                    true, 5, CompactionTrigger.TURNS, SIZE_TRIGGER).trigger())
                    .isEqualTo(CompactionTrigger.TURNS);
        }

        @Test
        @DisplayName("SIZE_OR_TURNS is already both, so an explicit cadence changes nothing")
        void sizeOrTurnsIsStable() {
            assertThat(CompactionConfigResolver.resolve(null, 3, null, null,
                    true, 5, CompactionTrigger.SIZE_OR_TURNS, SIZE_TRIGGER).trigger())
                    .isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
        }

        @Test
        @DisplayName("A null mode resolves to TURNS rather than propagating a null downstream")
        void nullTriggerIsTurns() {
            assertThat(CompactionConfigResolver.resolve(null, null, null, null,
                    true, 5, null, SIZE_TRIGGER).trigger())
                    .isEqualTo(CompactionTrigger.TURNS);
        }

        @Test
        @DisplayName("The growth threshold is carried through untouched")
        void thresholdCarriedThrough() {
            assertThat(CompactionConfigResolver.resolve(null, null, null, null,
                    true, 5, CompactionTrigger.SIZE, 12_345).sizeTriggerColdTokens())
                    .isEqualTo(12_345);
        }

        @Test
        @DisplayName("The six-argument overload still yields cadence-only, unchanged for existing callers")
        void legacyOverloadIsTurns() {
            CompactionConfigResolver.Effective e =
                    CompactionConfigResolver.resolve(null, null, null, null, true, 5);

            assertThat(e.trigger()).isEqualTo(CompactionTrigger.TURNS);
            assertThat(e.enabled()).isTrue();
            assertThat(e.afterTurns()).isEqualTo(5);
        }

        @Test
        @DisplayName("Enablement resolution is untouched by the trigger work")
        void enablementUnchanged() {
            assertThat(CompactionConfigResolver.resolve(false, null, true, null,
                    true, 5, CompactionTrigger.SIZE, SIZE_TRIGGER).enabled()).isFalse();
            assertThat(CompactionConfigResolver.resolve(null, null, true, null,
                    false, 5, CompactionTrigger.SIZE, SIZE_TRIGGER).enabled()).isTrue();
        }
    }
}
