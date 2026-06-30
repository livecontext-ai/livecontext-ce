package com.apimarketplace.common.credit;

import com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PricingSnapshotClient}.
 *
 * <p>Tests the client in isolation (no HTTP calls) by verifying lookup logic,
 * fallback behaviour, retry backoff, and the PricingRates record.</p>
 */
@DisplayName("PricingSnapshotClient")
class PricingSnapshotClientTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null authServiceUrl")
        void rejectsNullUrl() {
            assertThatThrownBy(() -> new PricingSnapshotClient(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("rejects blank authServiceUrl")
        void rejectsBlankUrl() {
            assertThatThrownBy(() -> new PricingSnapshotClient("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }
    }

    @Nested
    @DisplayName("Lookup behaviour (no snapshot loaded)")
    class LookupTests {

        @Test
        @DisplayName("getRates returns empty when snapshot has not been loaded")
        void emptyWhenNoSnapshot() {
            PricingSnapshotClient client = new PricingSnapshotClient("http://localhost:9999");
            Optional<PricingRates> rates = client.getRates("anthropic", "claude-sonnet-4-6");
            assertThat(rates).isEmpty();
        }

        @Test
        @DisplayName("getRatesOrDefault returns supplied defaults when model not found")
        void defaultsWhenNotFound() {
            PricingSnapshotClient client = new PricingSnapshotClient("http://localhost:9999");
            PricingRates rates = client.getRatesOrDefault("anthropic", "claude-sonnet-4-6",
                new BigDecimal("0.1"), new BigDecimal("0.3"));
            assertThat(rates.inputRate()).isEqualByComparingTo("0.1");
            assertThat(rates.outputRate()).isEqualByComparingTo("0.3");
            assertThat(rates.fixedCost()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("getRates with null provider returns empty (not NPE)")
        void nullProviderIsEmpty() {
            PricingSnapshotClient client = new PricingSnapshotClient("http://localhost:9999");
            assertThat(client.getRates(null, "claude-sonnet-4-6")).isEmpty();
        }

        @Test
        @DisplayName("getRates with null model returns empty (not NPE)")
        void nullModelIsEmpty() {
            PricingSnapshotClient client = new PricingSnapshotClient("http://localhost:9999");
            assertThat(client.getRates("anthropic", null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Health and retry backoff")
    class HealthTests {

        @Test
        @DisplayName("isHealthy returns false before first successful refresh")
        void notHealthyInitially() {
            PricingSnapshotClient client = new PricingSnapshotClient("http://localhost:9999");
            assertThat(client.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("after a failed refresh, subsequent getRates does not immediately retry (backoff)")
        void retryBackoffAfterFailure() {
            // Use a very short TTL so the client considers its cache stale immediately
            PricingSnapshotClient client = new PricingSnapshotClient(
                "http://localhost:9999", Duration.ofMillis(1));

            // First call triggers a refresh attempt (fails - port 9999 not listening)
            client.getRates("anthropic", "test");
            assertThat(client.isHealthy()).isFalse();

            // Second call within the 30s backoff window should NOT trigger another HTTP call.
            // We verify indirectly: if it did retry, we'd see a noticeable delay from the
            // connection timeout. Since we're testing backoff logic, the key assertion is
            // that the client stays unhealthy but doesn't hang.
            long start = System.currentTimeMillis();
            client.getRates("anthropic", "test");
            long elapsed = System.currentTimeMillis() - start;
            // Should be near-instant (< 100ms) because the backoff skips the HTTP call.
            // The first call already set lastFailureAt, so the second is throttled.
            assertThat(elapsed).isLessThan(500);
        }
    }

    @Nested
    @DisplayName("PricingRates record")
    class PricingRatesTests {

        @Test
        @DisplayName("normalizes nulls to ZERO")
        void nullSafety() {
            PricingRates rates = new PricingRates(null, null, null);
            assertThat(rates.inputRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rates.outputRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rates.fixedCost()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("preserves non-null values")
        void preservesValues() {
            PricingRates rates = new PricingRates(
                new BigDecimal("0.003"), new BigDecimal("0.015"), new BigDecimal("0.001"));
            assertThat(rates.inputRate()).isEqualByComparingTo("0.003");
            assertThat(rates.outputRate()).isEqualByComparingTo("0.015");
            assertThat(rates.fixedCost()).isEqualByComparingTo("0.001");
        }
    }
}
