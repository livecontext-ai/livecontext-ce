package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReasoningEffort")
class ReasoningEffortTest {

    @Test
    @DisplayName("fromString parses known levels case- and whitespace-insensitively")
    void fromStringParsesKnownLevels() {
        assertThat(ReasoningEffort.fromString("minimal")).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(ReasoningEffort.fromString("high")).isEqualTo(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.fromString("  HIGH ")).isEqualTo(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.fromString("XHigh")).isEqualTo(ReasoningEffort.XHIGH);
        // Contract v2: max exists on the Anthropic side (API output_config.effort
        // + Claude Code CLI) - codex has no max and clamps at the adapter.
        assertThat(ReasoningEffort.fromString("max")).isEqualTo(ReasoningEffort.MAX);
    }

    @Test
    @DisplayName("fromString returns null for null, blank, and unrecognized input")
    void fromStringReturnsNullForInvalid() {
        assertThat(ReasoningEffort.fromString(null)).isNull();
        assertThat(ReasoningEffort.fromString("")).isNull();
        assertThat(ReasoningEffort.fromString("   ")).isNull();
        assertThat(ReasoningEffort.fromString("ultra")).isNull();
    }

    @Test
    @DisplayName("isValidOrBlank accepts null/blank/known levels and rejects an unknown non-blank value")
    void isValidOrBlankSemantics() {
        assertThat(ReasoningEffort.isValidOrBlank(null)).isTrue();
        assertThat(ReasoningEffort.isValidOrBlank("   ")).isTrue();
        assertThat(ReasoningEffort.isValidOrBlank("medium")).isTrue();
        assertThat(ReasoningEffort.isValidOrBlank("bogus")).isFalse();
    }

    @Test
    @DisplayName("wire returns the canonical lowercase value the CLIs expect")
    void wireIsCanonicalLowercase() {
        assertThat(ReasoningEffort.HIGH.wire()).isEqualTo("high");
        assertThat(ReasoningEffort.XHIGH.wire()).isEqualTo("xhigh");
        assertThat(ReasoningEffort.MINIMAL.wire()).isEqualTo("minimal");
        assertThat(ReasoningEffort.MAX.wire()).isEqualTo("max");
    }
}
