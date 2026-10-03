package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** What a partner link, the public code read and an offer page may promise: one rule for the three. */
class RewardCodeLivePartnerTest {

    private final Instant now = Instant.parse("2026-10-01T12:00:00Z");
    private RewardCode code;

    @BeforeEach
    void setUp() {
        code = new RewardCode();
        code.setProgram(RewardProgram.PARTNER);
        code.setActive(true);
        code.setValidFrom(now.minus(1, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("a partner code that is active, in its window and not capped out is live")
    void live() {
        assertThat(code.isLivePartnerCode(now)).isTrue();
    }

    @Test
    @DisplayName("a code of another program is never a live partner code")
    void otherProgram() {
        code.setProgram(RewardProgram.PROMO);

        assertThat(code.isLivePartnerCode(now)).isFalse();
    }

    @Test
    @DisplayName("inactive, not yet valid, or expired: not live")
    void outsideItsWindow() {
        code.setActive(false);
        assertThat(code.isLivePartnerCode(now)).isFalse();

        code.setActive(true);
        code.setValidFrom(now.plus(1, ChronoUnit.HOURS));
        assertThat(code.isLivePartnerCode(now)).isFalse();

        code.setValidFrom(now.minus(1, ChronoUnit.DAYS));
        code.setValidUntil(now.minus(1, ChronoUnit.SECONDS));
        assertThat(code.isLivePartnerCode(now)).isFalse();
    }

    @Test
    @DisplayName("every allowed sign-up taken (a global cap reached): not live")
    void cappedOut() {
        code.setCapScope(CapScope.GLOBAL);
        code.setCapLimit(3);
        code.setCurrentRedemptions(3);

        assertThat(code.isLivePartnerCode(now)).isFalse();
    }
}
