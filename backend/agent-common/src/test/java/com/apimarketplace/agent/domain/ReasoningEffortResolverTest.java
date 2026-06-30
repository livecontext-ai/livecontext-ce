package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReasoningEffortResolver")
class ReasoningEffortResolverTest {

    @Test
    @DisplayName("per-conversation override wins over agent setting and model default")
    void overrideWins() {
        assertThat(ReasoningEffortResolver.resolve("high", "low", "medium")).isEqualTo("high");
    }

    @Test
    @DisplayName("agent setting wins when the override is null or blank")
    void agentWinsWhenNoOverride() {
        assertThat(ReasoningEffortResolver.resolve(null, "low", "medium")).isEqualTo("low");
        assertThat(ReasoningEffortResolver.resolve("   ", "low", "medium")).isEqualTo("low");
    }

    @Test
    @DisplayName("per-model default applies only when override and agent setting are absent")
    void modelDefaultWhenOthersBlank() {
        assertThat(ReasoningEffortResolver.resolve(null, null, "medium")).isEqualTo("medium");
    }

    @Test
    @DisplayName("returns null when every input is null/blank (caller omits the flag)")
    void nullWhenAllBlank() {
        assertThat(ReasoningEffortResolver.resolve(null, "", "  ")).isNull();
    }

    @Test
    @DisplayName("skips an unrecognized value and falls through to the next valid level")
    void skipsUnknownAndFallsThrough() {
        assertThat(ReasoningEffortResolver.resolve("bogus", "high", null)).isEqualTo("high");
        assertThat(ReasoningEffortResolver.resolve("bogus", "garbage", "low")).isEqualTo("low");
    }

    @Test
    @DisplayName("normalizes the winning value to the canonical lowercase wire form")
    void normalizesWinner() {
        assertThat(ReasoningEffortResolver.resolve(" XHIGH ", null, null)).isEqualTo("xhigh");
    }
}
