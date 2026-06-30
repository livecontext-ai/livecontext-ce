package com.apimarketplace.agent.catalog.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic helpers used by both feed parsers. Every rule must be
 * deterministic and match the Python reference script
 * ({@code scripts/models/sync_openrouter.py}) - drift between the two
 * implementations is an incident.
 */
class FeedParsingUtilsTest {

    @Nested
    @DisplayName("classifyTier(outputPrice)")
    class TierClassification {

        @Test
        @DisplayName("output ≥ $15/1M → top")
        void topThreshold() {
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("15.00"))).isEqualTo("top");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("75.00"))).isEqualTo("top");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("14.99"))).isEqualTo("high");
        }

        @Test
        @DisplayName("$5 ≤ output < $15 → high")
        void highThreshold() {
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("5.00"))).isEqualTo("high");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("10.00"))).isEqualTo("high");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("4.99"))).isEqualTo("mid");
        }

        @Test
        @DisplayName("$1.5 ≤ output < $5 → mid")
        void midThreshold() {
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("1.50"))).isEqualTo("mid");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("2.00"))).isEqualTo("mid");
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("1.49"))).isEqualTo("budget");
        }

        @Test
        @DisplayName("output < $1.5 → budget")
        void budgetThreshold() {
            assertThat(FeedParsingUtils.classifyTier(new BigDecimal("0.25"))).isEqualTo("budget");
            assertThat(FeedParsingUtils.classifyTier(BigDecimal.ZERO)).isEqualTo("budget");
        }

        @Test
        @DisplayName("null price → unknown (no throw)")
        void nullSafe() {
            assertThat(FeedParsingUtils.classifyTier(null)).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("extractReleaseDateFromModelId(modelId)")
    class ReleaseDateExtraction {

        @Test
        @DisplayName("YYYYMMDD suffix → parsed")
        void yyyymmdd() {
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("claude-opus-4-7-20260416"))
                    .isEqualTo(LocalDate.of(2026, 4, 16));
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("gpt-5-2025-08-07"))
                    .isEqualTo(LocalDate.of(2025, 8, 7));
        }

        @Test
        @DisplayName("YYYY-MM-DD suffix → parsed")
        void yyyyDashMmDashDd() {
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("gpt-5.4-2026-03-05"))
                    .isEqualTo(LocalDate.of(2026, 3, 5));
        }

        @Test
        @DisplayName("no date suffix → null")
        void noSuffix() {
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("claude-opus-4-6")).isNull();
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("gpt-5-mini")).isNull();
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId("o1")).isNull();
        }

        @Test
        @DisplayName("null id → null (no throw)")
        void nullSafe() {
            assertThat(FeedParsingUtils.extractReleaseDateFromModelId(null)).isNull();
        }
    }

    @Nested
    @DisplayName("costPerTokenToPricePerMillion(x)")
    class PriceConversion {

        @Test
        @DisplayName("2.5e-06 → 2.5000 per 1M")
        void standardConversion() {
            // Number inputs - typical Jackson deserialisation result.
            assertThat(FeedParsingUtils.costPerTokenToPricePerMillion(0.0000025))
                    .isEqualByComparingTo(new BigDecimal("2.5"));
            // String input (pricing can come through as strings for precision).
            assertThat(FeedParsingUtils.costPerTokenToPricePerMillion("0.000003"))
                    .isEqualByComparingTo(new BigDecimal("3.0"));
        }

        @Test
        @DisplayName("scale is fixed at 4 (matches column precision)")
        void scaleFixed() {
            BigDecimal result = FeedParsingUtils.costPerTokenToPricePerMillion("0.00000175");
            assertThat(result.scale()).isEqualTo(4);
            assertThat(result).isEqualByComparingTo(new BigDecimal("1.75"));
        }

        @Test
        @DisplayName("null input → null (no NPE)")
        void nullSafe() {
            assertThat(FeedParsingUtils.costPerTokenToPricePerMillion(null)).isNull();
        }

        @Test
        @DisplayName("non-numeric input → null (not a throw)")
        void nonNumericReturnsNull() {
            assertThat(FeedParsingUtils.costPerTokenToPricePerMillion("not-a-number")).isNull();
        }
    }

    @Nested
    @DisplayName("minNonNull(values…)")
    class MinNonNull {

        @Test
        @DisplayName("picks the smallest, ignoring nulls")
        void basicMin() {
            assertThat(FeedParsingUtils.minNonNull(
                    new BigDecimal("3"), new BigDecimal("1.5"), null, new BigDecimal("5")))
                    .isEqualByComparingTo(new BigDecimal("1.5"));
        }

        @Test
        @DisplayName("all-null input → null")
        void allNull() {
            assertThat(FeedParsingUtils.minNonNull(null, null)).isNull();
            assertThat(FeedParsingUtils.minNonNull()).isNull();
        }
    }
}
