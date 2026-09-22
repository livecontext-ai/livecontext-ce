package com.apimarketplace.agent.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the trigger-mode vocabulary: which condition each mode consults, and
 * the lenient parse that keeps a mistyped config value from turning into a
 * boot failure or, worse, into "no compaction at all".
 */
@DisplayName("CompactionTrigger - mode capabilities + lenient config parse")
class CompactionTriggerTest {

    @ParameterizedTest(name = "{0}: usesSize={1} usesTurns={2}")
    @CsvSource({
            "TURNS,         false, true",
            "SIZE,          true,  false",
            "SIZE_OR_TURNS, true,  true"
    })
    @DisplayName("Each mode consults exactly the conditions its name advertises")
    void modeCapabilities(CompactionTrigger mode, boolean usesSize, boolean usesTurns) {
        assertThat(mode.usesSize()).isEqualTo(usesSize);
        assertThat(mode.usesTurns()).isEqualTo(usesTurns);
    }

    @Test
    @DisplayName("Every mode consults at least one condition, so no mode can silently disable compaction")
    void noModeIsInert() {
        for (CompactionTrigger mode : CompactionTrigger.values()) {
            assertThat(mode.usesSize() || mode.usesTurns())
                    .as("%s consults no condition, which would stop compaction entirely", mode)
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "parse(\"{0}\") = SIZE_OR_TURNS")
    @ValueSource(strings = {"SIZE_OR_TURNS", "size_or_turns", "size-or-turns", "  Size-Or-Turns  "})
    @DisplayName("parse accepts the YAML dash spelling and any casing, trimmed")
    void parseAcceptsRelaxedSpellings(String raw) {
        assertThat(CompactionTrigger.parse(raw)).isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
    }

    @ParameterizedTest(name = "parse(\"{0}\") = SIZE")
    @ValueSource(strings = {"SIZE", "size", " Size "})
    @DisplayName("parse resolves the size-only mode")
    void parseResolvesSize(String raw) {
        assertThat(CompactionTrigger.parse(raw)).isEqualTo(CompactionTrigger.SIZE);
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    @DisplayName("Turkish locale: the size spellings still parse, they do not silently become TURNS")
    void parseIsLocaleIndependent() {
        // Regression. With a default-locale toUpperCase(), "size" becomes "SIZE"
        // with a dotted capital I (U+0130) on a tr-TR JVM, matches no enum name,
        // and falls back to TURNS. Both size spellings contain an "i" and
        // "turns" does not, so the failure is one-directional and invisible:
        // the operator sets the mode, the service boots, and compaction quietly
        // keeps running in the old mode with no WARN and no metric.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            assertThat(CompactionTrigger.parse("size")).isEqualTo(CompactionTrigger.SIZE);
            assertThat(CompactionTrigger.parse("SIZE")).isEqualTo(CompactionTrigger.SIZE);
            assertThat(CompactionTrigger.parse("size-or-turns"))
                    .isEqualTo(CompactionTrigger.SIZE_OR_TURNS);
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "turnz", "SIZE_OR", "both", "42"})
    @DisplayName("Blank or unknown parses to TURNS, the pre-feature behaviour, instead of throwing")
    void parseFallsBackToTurns(String raw) {
        // A typo must degrade to "as before", never to a failed context bind
        // (which would stop the service booting) and never to "no compaction".
        assertThat(CompactionTrigger.parse(raw)).isEqualTo(CompactionTrigger.TURNS);
    }
}
