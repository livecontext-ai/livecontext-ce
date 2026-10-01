package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code nodePolicy.retryOn} and the retry caps. The parser stays lenient on stored values (a plan
 * written before the caps must keep opening) and strict on {@code retryOn}; the caps are a
 * write-time refusal, reported by {@link NodePolicy#writeViolation}.
 */
@DisplayName("NodePolicy - retryOn and retry caps")
class NodePolicyRetryOnAndCapsTest {

    @Test
    @DisplayName("retryOn 'rate_limit' parses and only-rate-limits is reported")
    void rateLimitParses() {
        NodePolicy policy = NodePolicy.fromMap(Map.of("retryCount", 1, "retryOn", "rate_limit"), "mcp:x");

        assertThat(policy.retryOn()).isEqualTo("rate_limit");
        assertThat(policy.retriesOnlyRateLimits()).isTrue();
    }

    @Test
    @DisplayName("an absent or blank retryOn is the default classification")
    void absentRetryOnIsDefault() {
        assertThat(NodePolicy.fromMap(Map.of("retryCount", 1), "mcp:x").retryOn()).isNull();
        assertThat(NodePolicy.fromMap(Map.of("retryCount", 1, "retryOn", " "), "mcp:x").retryOn()).isNull();
    }

    @Test
    @DisplayName("an unknown retryOn is refused, naming the only value")
    void unknownRetryOnIsRefused() {
        assertThatThrownBy(() -> NodePolicy.fromMap(Map.of("retryOn", "always"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate_limit")
                .hasMessageContaining("mcp:x");
    }

    @Test
    @DisplayName("a stored plan above the caps still parses")
    void storedValuesAboveCapsStillParse() {
        NodePolicy policy = NodePolicy.fromMap(Map.of("retryCount", 50, "retryBackoffMs", 1_000_000), "mcp:x");

        assertThat(policy.retryCount()).isEqualTo(50);
        assertThat(policy.maxAttempts()).as("clamped when it runs").isEqualTo(NodePolicy.MAX_RETRY_COUNT + 1);
    }

    @Test
    @DisplayName("writeViolation names the cap for retryCount and for retryBackoffMs, and is null within them")
    void writeViolation() {
        assertThat(new NodePolicy(11, 0L, false).writeViolation("mcp:x")).contains("at most 10");
        assertThat(new NodePolicy(1, 60_001L, false).writeViolation("mcp:x")).contains("at most 60000");
        assertThat(new NodePolicy(10, 60_000L, false).writeViolation("mcp:x")).isNull();
    }

    @Test
    @DisplayName("writeViolation refuses retryOn without retries")
    void retryOnWithoutRetriesIsRefused() {
        assertThat(new NodePolicy(0, 0L, false, 0L, false, "rate_limit").writeViolation("mcp:x"))
                .contains("retryOn only applies when retryCount > 0");
    }

    @Test
    @DisplayName("the default policy has no retryOn, so plans without one stay byte-identical")
    void defaultHasNoRetryOn() {
        assertThat(NodePolicy.DEFAULT.retryOn()).isNull();
        assertThat(NodePolicy.fromMap(Map.of(), "mcp:x").isDefault()).isTrue();
    }
}
