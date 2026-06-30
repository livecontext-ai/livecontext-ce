package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.ratelimit.RateLimitConfig;
import com.apimarketplace.agent.ratelimit.RateLimitMode;
import com.apimarketplace.agent.ratelimit.RateLimitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the perimeter goal end-to-end WITHOUT a real API key: a request driven
 * through {@link MockLLMProvider} flows through the SAME {@link ProviderRateLimiter}
 * as the HTTP providers, so per-model TPM/RPM is enforced on the mock path. This is
 * the unit-level counterpart of the live cluster TPM/RPM test - it lets CI assert
 * global rate-limit enforcement with zero spend.
 *
 * <p>Distributed (cross-instance) enforcement is covered separately by
 * {@code RedisRateLimitWindowTest} + the {@code scaling.backend=redis} window; here
 * we assert the in-window GLOBAL semantics the mock participates in, mirroring
 * {@code RateLimitE2ETest} RL-E2E-001/002.
 */
class MockProviderGlobalRateLimitTest {

    private static ProviderRateLimiter limiter(RateLimitStrategy strategy, int tpm, int rpm) {
        RateLimitConfig cfg = new RateLimitConfig();
        cfg.setEnabled(true);
        cfg.setStrategy(strategy);
        cfg.setDefaultMode(RateLimitMode.FAIL_FAST);
        cfg.setMaxWaitTimeSeconds(2);
        cfg.getProviders().put(MockLLMProvider.PROVIDER_NAME, new RateLimitConfig.ProviderLimit(tpm, rpm));
        return new ProviderRateLimiter(cfg);
    }

    private static CompletionRequest req() {
        return CompletionRequest.builder().tenantId("t1").model("mock-model").userPrompt("hi").build();
    }

    @Test
    @DisplayName("GLOBAL RPM is enforced on the mock provider path: the (rpm+1)th complete() is throttled, no real key")
    void globalRpmEnforcedThroughMock() {
        int rpm = 3;
        ProviderRateLimiter rl = limiter(RateLimitStrategy.GLOBAL, 1_000_000, rpm);
        MockLLMProvider provider = new MockLLMProvider(rl, "ok", 0L, 10, "mock-model");

        for (int i = 0; i < rpm; i++) {
            final int n = i;
            assertDoesNotThrow(() -> provider.complete(req()), "call " + n + " within RPM should pass");
        }

        var ex = assertThrows(RuntimeException.class, () -> provider.complete(req()),
                "the (rpm+1)th call must be throttled by the GLOBAL limiter");
        assertTrue(ex.getMessage() != null
                && (ex.getMessage().contains("RPM") || ex.getMessage().toLowerCase().contains("request")
                    || ex.getMessage().toLowerCase().contains("rate")),
                "throttle error should mention RPM/request/rate. Got: " + ex.getMessage());
    }

    @Test
    @DisplayName("GLOBAL TPM is enforced on the mock provider path: completion tokens accumulate toward the global TPM cap")
    void globalTpmEnforcedThroughMock() {
        // completion-tokens=200 per call; TPM cap 500 -> 3rd call exceeds (200*3 > 500 with prompt est).
        ProviderRateLimiter rl = limiter(RateLimitStrategy.GLOBAL, 500, 1_000);
        MockLLMProvider provider = new MockLLMProvider(rl, "ok", 0L, 200, "mock-model");

        assertDoesNotThrow(() -> provider.complete(req()));
        assertThrows(RuntimeException.class, () -> {
            // keep calling until the TPM window rejects; bounded so a mis-tuned cap can't loop forever
            for (int i = 0; i < 10; i++) {
                provider.complete(req());
            }
        }, "cumulative completion tokens must trip the GLOBAL TPM cap");
    }
}
