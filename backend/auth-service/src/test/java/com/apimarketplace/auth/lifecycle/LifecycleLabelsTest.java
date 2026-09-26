package com.apimarketplace.auth.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LifecycleLabels - trophy, tier and month labels per locale")
class LifecycleLabelsTest {

    private final LifecycleLabels labels = new LifecycleLabels();

    @Test
    @DisplayName("a badge and a tier read in the recipient's language")
    void badgeAndTierPerLocale() {
        assertThat(labels.badgeName("popularity_1", "fr")).isEqualTo("Première installation");
        assertThat(labels.badgeName("popularity_1", "en")).isEqualTo("First Install");
        assertThat(labels.tierName("GOLD", "fr")).isEqualTo("Or");
        assertThat(labels.tierName("GOLD", "zh")).isEqualTo("黄金");
        assertThat(labels.familyName("POPULARITY", "fr")).isEqualTo("Audience");
        assertThat(labels.familyName("POPULARITY", "en")).isEqualTo("Reach");
    }

    @Test
    @DisplayName("an unknown or missing locale reads English")
    void unknownLocaleIsEnglish() {
        assertThat(labels.badgeName("popularity_1", "it")).isEqualTo("First Install");
        assertThat(labels.tierName("GOLD", null)).isEqualTo("Gold");
        assertThat(labels.monthLabel(YearMonth.of(2026, 9), "xx")).isEqualTo("September 2026");
    }

    @Test
    @DisplayName("the month label follows each language's form")
    void monthLabels() {
        YearMonth sept = YearMonth.of(2026, 9);
        assertThat(labels.monthLabel(sept, "fr")).isEqualTo("septembre 2026");
        assertThat(labels.monthLabel(sept, "es")).isEqualTo("septiembre de 2026");
        assertThat(labels.monthLabel(sept, "de")).isEqualTo("September 2026");
        assertThat(labels.monthLabel(sept, "pt")).isEqualTo("setembro de 2026");
        assertThat(labels.monthLabel(sept, "zh")).isEqualTo("2026年9月");
        assertThat(labels.monthLabel(YearMonth.of(2026, 1), "fr")).isEqualTo("janvier 2026");
        assertThat(labels.monthLabel(YearMonth.of(2026, 12), "en")).isEqualTo("December 2026");
    }

    @Test
    @DisplayName("counts use the locale's digit grouping")
    void counts() {
        assertThat(labels.count(1234, "en")).isEqualTo("1,234");
        assertThat(labels.count(1234, "de")).isEqualTo("1.234");
        assertThat(labels.count(7, "fr")).isEqualTo("7");
        assertThat(labels.count(1234, "fr").replaceAll("\\s", " ")).matches("1.234");
    }

    @Test
    @DisplayName("known codes and tiers are recognised; anything else is not")
    void knownCodes() {
        assertThat(labels.isKnownBadge("publisher_1")).isTrue();
        assertThat(labels.isKnownBadge("publisher_2")).isFalse();
        assertThat(labels.isKnownBadge(null)).isFalse();
        assertThat(labels.isKnownTier("PLATINUM")).isTrue();
        assertThat(labels.isKnownTier("gold")).isFalse();
        assertThat(labels.isKnownFamily("PUBLISHER")).isTrue();
        assertThat(labels.isKnownFamily("publisher")).isFalse();
    }

    @Test
    @DisplayName("an unknown code or tier falls back to itself instead of an empty label")
    void unknownFallsBackToItself() {
        assertThat(labels.badgeName("retired_badge", "fr")).isEqualTo("retired_badge");
        assertThat(labels.tierName("MYTHIC", "fr")).isEqualTo("MYTHIC");
    }
}
