package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for the {@code 📧 Daily Email Digest} 10-minute hang observed on app-host
 * 2026-05-03 06:00:09 and 18:05:04 UTC.
 *
 * <p>Pre-fix: {@code analyze_emails} aggregated ~30 emails into a single deepseek-chat
 * request whose estimated tokens exceeded the configured {@code tpm-per-tenant=100000}
 * ceiling. With {@code rate-limit.max-wait-time-seconds=600} (bumped 2026-04-29 for
 * Gemini split bursts), {@code acquireWithWait} entered the retry loop and waited the
 * full 10 minutes before throwing {@code rate_limit_timeout} - by which time the
 * {@code OrchestrationRecoveryService} 5-minute zombie watchdog had already force-FAILed
 * the run. The agent worker thread stayed parked the whole time, starving the pool.
 *
 * <p>Post-fix: the loop checks the per-tenant TPM ceiling first; if a single request
 * cannot fit in any future window, we fail immediately with {@code request_exceeds_tenant_capacity}
 * (non-retryable). The error message names the estimate AND the cap so the user can
 * either trim the prompt or raise the tenant limit. Worker time saved per occurrence:
 * 600 s → &lt; 1 ms.
 */
@DisplayName("ProviderRateLimiter - pre-flight reject when request > tenant TPM (Daily-Email-Digest regression)")
class RateLimiterRequestExceedsTenantCapacityTest {

    private RateLimitConfig config;
    private ProviderRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        config.setEnabled(true);
        config.setDefaultMode(RateLimitMode.WAIT);
        // The pre-flight reject branch is gated on strategy ∈ {PER_TENANT, HYBRID}
        // since 2026-05-14 (PR3 alignment with tryAcquire's per-tenant branch). The
        // original test set lived in default GLOBAL strategy and accidentally
        // exercised a bug where the preflight fired under GLOBAL too. We pin
        // PER_TENANT here so the tests below assert the intended behavior; a
        // separate test below verifies that GLOBAL strategy now skips the preflight.
        config.setStrategy(RateLimitStrategy.PER_TENANT);
        // Mirror the prod misconfiguration: long max wait + a per-tenant cap that some
        // single requests will exceed.
        config.setMaxWaitTimeSeconds(600);

        RateLimitConfig.ProviderLimit deepseek = new RateLimitConfig.ProviderLimit();
        deepseek.setTokensPerMinute(2_000_000);
        deepseek.setRequestsPerMinute(5_000);
        deepseek.setTokensPerMinutePerTenant(100_000);   // the prod value as of 2026-05-03
        deepseek.setRequestsPerMinutePerTenant(250);
        config.getProviders().put("deepseek", deepseek);

        rateLimiter = new ProviderRateLimiter(config);
    }

    @Test
    @DisplayName("Single request larger than per-tenant TPM fails fast and non-retryable (no 600s hang)")
    void singleRequestAboveTenantCapFailsFastNonRetryable() {
        long start = System.currentTimeMillis();

        assertThatThrownBy(() ->
                rateLimiter.acquireWithWait("deepseek", null, "tenant-1", 150_000, () -> false))
                .isInstanceOf(LLMProviderException.class)
                .satisfies(ex -> {
                    LLMProviderException llm = (LLMProviderException) ex;
                    assertThat(llm.getErrorCode()).isEqualTo("request_exceeds_tenant_capacity");
                    assertThat(llm.isRetryable()).isFalse();
                    assertThat(llm.getMessage())
                            .contains("150000")     // estimate surfaced
                            .contains("100000")     // cap surfaced
                            .contains("deepseek");
                });

        long elapsedMs = System.currentTimeMillis() - start;
        // Pre-fix this would block ~600_000 ms before throwing rate_limit_timeout.
        // Post-fix: should be near-instant. Assert generously to keep the test green
        // on slow CI without losing signal that no real wait occurred.
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    @Test
    @DisplayName("Request equal to per-tenant TPM is allowed to enter the wait loop (boundary)")
    void requestEqualToTenantCapIsNotPreFlightRejected() {
        // Exactly at the cap: one window can satisfy this if empty. Pre-flight must NOT
        // reject - fall through to the normal acquire path. With an empty window this
        // should succeed immediately.
        assertThatCode(() ->
                rateLimiter.acquireWithWait("deepseek", null, "tenant-1", 100_000, () -> false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Request well below per-tenant TPM is unaffected by the new gate")
    void smallRequestUnaffected() {
        assertThatCode(() ->
                rateLimiter.acquireWithWait("deepseek", null, "tenant-1", 5_000, () -> false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Pre-flight rejects when tenant TPM is set to 0 (kill-switch) - every positive request hits cap")
    void preFlightRejectsWhenTenantTpmIsZero() {
        // ops kill-switch: cap=0 means "no agent traffic for this tenant". Any positive
        // estimate must fail-fast - without this branch, the loop would burn maxWaitTime
        // before throwing rate_limit_timeout, defeating the kill-switch purpose.
        RateLimitConfig.ProviderLimit killed = new RateLimitConfig.ProviderLimit();
        killed.setTokensPerMinute(2_000_000);
        killed.setRequestsPerMinute(5_000);
        killed.setTokensPerMinutePerTenant(0);   // kill-switch
        killed.setRequestsPerMinutePerTenant(0);
        config.getProviders().put("deepseek-killed", killed);

        long start = System.currentTimeMillis();

        assertThatThrownBy(() ->
                rateLimiter.acquireWithWait("deepseek-killed", null, "tenant-killed", 100, () -> false))
                .isInstanceOf(LLMProviderException.class)
                .satisfies(ex -> {
                    LLMProviderException llm = (LLMProviderException) ex;
                    assertThat(llm.getErrorCode()).isEqualTo("request_exceeds_tenant_capacity");
                    assertThat(llm.isRetryable()).isFalse();
                    assertThat(llm.getMessage()).contains("100").contains("(0)");
                });

        long elapsedMs = System.currentTimeMillis() - start;
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    @Test
    @DisplayName("Strategy=GLOBAL: pre-flight DOES NOT reject even when estimate > tenant cap (PR3 latent-bug fix)")
    void preFlightSkippedUnderGlobalStrategy() {
        // Pre-PR3 the preflight branch fired regardless of strategy, even though
        // tryAcquire's per-tenant check (line 269) only runs under PER_TENANT/HYBRID -
        // so under GLOBAL, the limiter would reject a request that no other branch
        // would have throttled. That's the bug that surfaced in prod as
        // "Request estimated at 40216 tokens exceeds per-tenant TPM capacity (20000)
        // for deepseek/deepseek-chat" on a stack where strategy was the default
        // GLOBAL. The fix: gate the preflight on strategy ∈ {PER_TENANT, HYBRID}.
        config.setStrategy(RateLimitStrategy.GLOBAL);
        ProviderRateLimiter globalLimiter = new ProviderRateLimiter(config);

        // 150k > tenant cap 100k. Under GLOBAL strategy the tenant cap is data, not
        // a check - the request must NOT pre-flight reject. With an empty global
        // window (cap=2M) the call should succeed immediately.
        assertThatCode(() ->
                globalLimiter.acquireWithWait("deepseek", null, "tenant-global", 150_000, () -> false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Strategy=HYBRID: pre-flight still rejects (HYBRID enforces per-tenant as well as global)")
    void preFlightFiresUnderHybridStrategy() {
        config.setStrategy(RateLimitStrategy.HYBRID);
        ProviderRateLimiter hybridLimiter = new ProviderRateLimiter(config);

        assertThatThrownBy(() ->
                hybridLimiter.acquireWithWait("deepseek", null, "tenant-hybrid", 150_000, () -> false))
                .isInstanceOf(LLMProviderException.class)
                .satisfies(ex -> {
                    LLMProviderException llm = (LLMProviderException) ex;
                    assertThat(llm.getErrorCode()).isEqualTo("request_exceeds_tenant_capacity");
                    assertThat(llm.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("Pre-flight gate is no-op when tenant TPM is disabled (-1) - provider-level limits still apply normally")
    void preFlightSkippedWhenTenantTpmDisabled() {
        RateLimitConfig.ProviderLimit unlimited = new RateLimitConfig.ProviderLimit();
        unlimited.setTokensPerMinute(2_000_000);
        unlimited.setRequestsPerMinute(5_000);
        unlimited.setTokensPerMinutePerTenant(-1);   // disabled
        unlimited.setRequestsPerMinutePerTenant(-1);
        config.getProviders().put("deepseek-untiered", unlimited);

        // 1M tokens against an untiered provider must NOT pre-flight reject - the
        // provider-level cap (2M) governs and a 1M request is within it.
        assertThatCode(() ->
                rateLimiter.acquireWithWait("deepseek-untiered", null, "tenant-3", 1_000_000, () -> false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tenant TPM disabled (-1) still enforces global TPM in HYBRID mode")
    void tenantTpmDisabledStillEnforcesGlobalTpm() {
        config.setStrategy(RateLimitStrategy.HYBRID);

        RateLimitConfig.ProviderLimit untiered = new RateLimitConfig.ProviderLimit();
        untiered.setTokensPerMinute(1_000);
        untiered.setRequestsPerMinute(5_000);
        untiered.setTokensPerMinutePerTenant(-1);
        untiered.setRequestsPerMinutePerTenant(250);
        config.getProviders().put("deepseek-untiered-global", untiered);

        ProviderRateLimiter hybridLimiter = new ProviderRateLimiter(config);

        assertThatThrownBy(() ->
                hybridLimiter.checkRateLimit("deepseek-untiered-global", null, "tenant-3", 1_500, RateLimitMode.FAIL_FAST))
                .isInstanceOf(LLMProviderException.class)
                .satisfies(ex -> {
                    LLMProviderException llm = (LLMProviderException) ex;
                    assertThat(llm.getErrorCode()).isEqualTo("rate_limit_global_tpm");
                    assertThat(llm.isRetryable()).isTrue();
                    assertThat(llm.getMessage())
                            .contains("Global token limit")
                            .contains("1500")
                            .contains("1000");
                });
    }
}
