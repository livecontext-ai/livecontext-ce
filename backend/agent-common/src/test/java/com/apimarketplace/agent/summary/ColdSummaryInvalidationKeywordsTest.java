package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5.4 - pin the strict-match behaviour. The critical invariant
 * here is the <em>false-positive</em> list: these strings caused real
 * bugs in v3's naive matcher (R22, R50) and must stay rejected.
 */
@DisplayName("ColdSummaryInvalidationKeywords - strict patterns + FP control (Stage 5.4)")
class ColdSummaryInvalidationKeywordsTest {

    // ---- Positive matches ----

    @ParameterizedTest(name = "matchesStrict({0}) = true - sentence-initial intent")
    @ValueSource(strings = {
            "Actually, let's use a webhook",
            "actually, forget what I said",
            "Instead, can we use Gmail?",
            "instead let's pick Slack",
            "Forget, I'm done",
            "FORGET that"
    })
    @DisplayName("sentence-initial actually/instead/forget triggers strict match")
    void sentenceInitialTriggers(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg))
                .as("sentence-initial intent word should trigger regen")
                .isTrue();
    }

    @ParameterizedTest(name = "matchesStrict({0}) = true - instead-of phrase")
    @ValueSource(strings = {
            "Let's use Slack instead of Gmail",
            "Do X instead of Y please",
            "it should fire instead of skipping"
    })
    @DisplayName("'instead of <word>' phrase triggers strict match")
    void insteadOfPhrase(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg)).isTrue();
    }

    @ParameterizedTest(name = "matchesStrict({0}) = true - forget determiner phrase")
    @ValueSource(strings = {
            "Please forget the previous step",
            "forget my earlier idea",
            "let's forget that for now",
            "Forget about the old workflow",
            "forget about my prior request",
            "please forget what I said"
    })
    @DisplayName("'forget (about)? the/my/that/what' triggers strict match")
    void forgetDeterminerPhrase(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg)).isTrue();
    }

    // ---- False-positive list (R22, R50) ----

    @Test
    @DisplayName("FP: 'reset password' does NOT trigger - R50 removed 'reset' from sentence-initial regex")
    void fpResetPassword() {
        // R50: the v3 regex included 'reset' which fired on every
        // credential / password-reset conversation. Pin the removal
        // so a well-meaning "let's add reset back" refactor breaks
        // this test loudly.
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict("reset password please")).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict("Reset the machine")).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict("factory reset")).isFalse();
    }

    @Test
    @DisplayName("FP: 'ignore the noise' does NOT trigger - 'ignore' was never in the strict list")
    void fpIgnoreTheNoise() {
        // v3 bug: 'ignore' was flagged as an invalidation keyword, so
        // every mention of "ignore this warning" re-ran the
        // summariser. The strict list has no 'ignore' entry at all;
        // pin the absence.
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict("ignore the noise in the logs")).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict("Please ignore that warning")).isFalse();
    }

    @ParameterizedTest(name = "FP: mid-sentence '{0}' does NOT trigger")
    @ValueSource(strings = {
            "I actually think it's fine",                  // 'actually' mid-sentence
            "we could use A or B instead",                 // 'instead' not followed by 'of'
            "don't forget to save your work",              // 'forget to' is not a retraction
            "I won't forget this experience",              // 'forget this' - 'this' not in det list
            "remember to not forget anything"              // 'forget' but trailing word isn't det
    })
    @DisplayName("mid-sentence occurrences of intent words do NOT trigger (strict)")
    void fpMidSentence(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("null/blank input does NOT trigger")
    void fpNullBlank(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg)).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(msg)).isFalse();
    }

    // ---- Proximity eligibility (base check for caller) ----

    @Test
    @DisplayName("proximityTriggerEligible: 'actually' or 'instead' anywhere triggers BASE check")
    void proximityEligibleKeywordPresent() {
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "I actually meant wf_abc123")).isTrue();
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "use Gmail instead, I changed my mind")).isTrue();
    }

    @Test
    @DisplayName("proximityTriggerEligible: false for messages without the base keywords")
    void proximityEligibleNoKeyword() {
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "let's add a webhook trigger")).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "forget about the previous step"))
                .as("forget is NOT a proximity keyword - only actually/instead are")
                .isFalse();
    }

    @Test
    @DisplayName("proximityTriggerEligible is word-boundary safe - 'institutionally' does NOT match 'instead'")
    void proximityWordBoundary() {
        // The base keyword pattern uses \b so "instead" inside a
        // longer word cannot leak through.
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "institutionally speaking, this is fine")).isFalse();
        assertThat(ColdSummaryInvalidationKeywords.proximityTriggerEligible(
                "reinstead")).isFalse();  // malformed but defensible
    }

    // ---- Case sensitivity ----

    @ParameterizedTest(name = "matchesStrict handles mixed case: {0}")
    @ValueSource(strings = {
            "ACTUALLY, I changed my mind",
            "Instead Of Gmail, use Slack",
            "FORGET THE workflow"
    })
    @DisplayName("strict match is case-insensitive")
    void caseInsensitive(String msg) {
        assertThat(ColdSummaryInvalidationKeywords.matchesStrict(msg)).isTrue();
    }
}
