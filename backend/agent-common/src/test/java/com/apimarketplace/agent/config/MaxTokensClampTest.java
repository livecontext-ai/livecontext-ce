package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaxTokensClamp")
class MaxTokensClampTest {

    @Test
    @DisplayName("caps a request above the model ceiling down to the ceiling")
    void capsAboveCeiling() {
        // Opus default 16000 against DeepSeek-chat's real 8192 cap → 8192 (avoids 400).
        assertThat(MaxTokensClamp.clamp(16000, 8192)).isEqualTo(8192);
    }

    @Test
    @DisplayName("leaves a request at or below the model ceiling unchanged")
    void belowCeilingUnchanged() {
        assertThat(MaxTokensClamp.clamp(16000, 128000)).isEqualTo(16000); // Opus 128K
        assertThat(MaxTokensClamp.clamp(8192, 8192)).isEqualTo(8192);     // exact boundary
        assertThat(MaxTokensClamp.clamp(4500, 64000)).isEqualTo(4500);    // legacy default
    }

    @Test
    @DisplayName("null request stays null (provider keeps its own default)")
    void nullRequestStaysNull() {
        assertThat(MaxTokensClamp.clamp(null, 8192)).isNull();
        assertThat(MaxTokensClamp.clamp(null, null)).isNull();
    }

    @Test
    @DisplayName("unknown model cap falls back to the safe 8192 floor, not the requested value")
    void unknownCapUsesSafeFloor() {
        // Catalog has no maxOutputTokens (e.g. unsynced CE) → never send > 8192.
        assertThat(MaxTokensClamp.clamp(16000, null)).isEqualTo(MaxTokensClamp.UNKNOWN_MODEL_OUTPUT_CAP);
        assertThat(MaxTokensClamp.clamp(16000, null)).isEqualTo(8192);
        assertThat(MaxTokensClamp.clamp(16000, 0)).isEqualTo(8192);   // non-positive cap == unknown
        assertThat(MaxTokensClamp.clamp(16000, -1)).isEqualTo(8192);
    }

    @Test
    @DisplayName("a request below the safe floor stays unchanged even when the cap is unknown")
    void belowFloorWithUnknownCapUnchanged() {
        assertThat(MaxTokensClamp.clamp(4096, null)).isEqualTo(4096);
        assertThat(MaxTokensClamp.clamp(500, null)).isEqualTo(500); // classify-sized request
    }
}
