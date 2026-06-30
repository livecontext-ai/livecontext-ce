package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.provider.LLMProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Rate limiter for LLM providers with multiple strategies and modes.
 *
 * Strategies (WHO is limited):
 * - GLOBAL: All users share limits
 * - PER_TENANT: Each tenant has independent limits
 * - HYBRID: Both global AND per-tenant limits
 *
 * Modes (HOW to handle limit exceeded):
 * - FAIL_FAST: Throw exception immediately
 * - WAIT: Block until rate allows (invisible to caller)
 * - TRY_ACQUIRE: Return result, caller decides
 *
 * Thread-safe with acquire/release pattern to prevent race conditions.
 *
 * <p><b>Per-model overrides:</b> When a {@link ModelRateLimitProvider} is available,
 * per-model limits override provider-level defaults on a per-dimension basis
 * (null = inherit from provider config). Model-keyed windows use "provider:model"
 * as the map key; provider-level windows use "provider" alone.</p>
 *
 * <p>Window backend is pluggable via {@link RateLimitWindowFactory}:
 * default uses {@link InMemoryRateLimitWindow}; with {@code scaling.backend=redis},
 * {@link RateLimitRedisConfig} provides Redis-backed windows.</p>
 *
 * <p><b>Multi-instance note:</b> the compound check-then-reserve operation is
 * serialized by a JVM-local lock and, when the window factory is Redis-backed,
 * by {@link RateLimitWindowFactory#withAtomicReservationLock}. This prevents
 * horizontally scaled workers from overshooting shared RPM/TPM windows.</p>
 */
@Slf4j
@Service
public class ProviderRateLimiter {

    private static final int WINDOW_SIZE_SECONDS = 60;

    private final RateLimitConfig config;
    private final RateLimitWindowFactory windowFactory;
    private final ModelRateLimitProvider modelRateLimitProvider;

    // Mode and wait time - initialized from config, can be overridden per call
    private RateLimitMode defaultMode;
    private Duration maxWaitTime;

    // Global tracking - key is resolve().key() result ("provider" or "provider:model")
    private final Map<String, RateLimitWindow> globalTokenWindows = new ConcurrentHashMap<>();
    private final Map<String, RateLimitWindow> globalRequestWindows = new ConcurrentHashMap<>();

    // Per-tenant tracking - outer key is resolve().key() result
    private final Map<String, Map<String, RateLimitWindow>> tenantTokenWindows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RateLimitWindow>> tenantRequestWindows = new ConcurrentHashMap<>();

    // Locks for atomic check-and-reserve operations - key is reservationLockKey().
    private final Map<String, ReservationLock> windowLocks = new ConcurrentHashMap<>();

    // Concurrency limiters per provider (optional, for extra protection)
    private final Map<String, Semaphore> concurrencyLimiters = new ConcurrentHashMap<>();

    // Event counters for metrics (per window key, aggregate across tenants)
    private final Map<String, java.util.concurrent.atomic.AtomicLong> acquiredCounters = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> blockedCounters = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> timeoutCounters = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> waitMsCounters = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> tokensConsumedCounters = new ConcurrentHashMap<>();

    // Per-tenant event counters - outer key is windowKey, inner key is tenantId.
    // Populated only when the caller supplies a non-null tenantId; lets the metrics
    // publisher emit a `tenant` Prometheus label for fairness/isolation dashboards.
    private final Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantAcquiredCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantBlockedCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantTimeoutCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantWaitMsCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantTokensConsumedCounters = new ConcurrentHashMap<>();

    public ProviderRateLimiter(RateLimitConfig config) {
        this(config, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderRateLimiter(RateLimitConfig config,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              RateLimitWindowFactory windowFactory,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              ModelRateLimitProvider modelRateLimitProvider) {
        this.config = config;
        this.windowFactory = windowFactory != null ? windowFactory
                : (windowId, windowSizeSeconds) -> new InMemoryRateLimitWindow(windowSizeSeconds);
        this.modelRateLimitProvider = modelRateLimitProvider;
        this.defaultMode = config.getDefaultMode();
        this.maxWaitTime = Duration.ofSeconds(config.getMaxWaitTimeSeconds());
        log.info("ProviderRateLimiter initialized - strategy: {}, mode: {}, maxWait: {}s, windowFactory: {}, modelOverrides: {}",
                config.getStrategy(), defaultMode, maxWaitTime.getSeconds(),
                this.windowFactory.getClass().getSimpleName(),
                modelRateLimitProvider != null ? "enabled" : "disabled");
    }

    // ==================== CONFIGURATION ====================

    public void setDefaultMode(RateLimitMode mode) {
        this.defaultMode = mode;
    }

    public void setMaxWaitTime(Duration maxWaitTime) {
        this.maxWaitTime = maxWaitTime;
    }

    // ==================== KEY/LIMIT RESOLUTION ====================

    /**
     * Resolved key + effective limit pair - avoids double cache lookup.
     */
    private record ResolvedKeyLimit(String key, RateLimitConfig.ProviderLimit limit) {}

    /**
     * Resolve window key and effective limits in a single pass.
     * <p>Returns "provider:model" key when a model override exists (at least one non-null
     * rate limit field), "provider" otherwise. Merges non-null override fields onto
     * provider defaults; null fields inherit from provider config.</p>
     */
    private ResolvedKeyLimit resolve(String providerName, String modelId) {
        RateLimitConfig.ProviderLimit base = config.getProviderLimit(providerName);
        if (modelId == null || modelRateLimitProvider == null) {
            return new ResolvedKeyLimit(providerName, base);
        }
        ModelRateLimit override = modelRateLimitProvider.getModelLimit(providerName, modelId);
        if (override == null) {
            return new ResolvedKeyLimit(providerName, base);
        }
        // Merge: non-null override fields win, null falls through to base
        RateLimitConfig.ProviderLimit merged = new RateLimitConfig.ProviderLimit(
                override.tpm() != null ? override.tpm() : base.getTokensPerMinute(),
                override.rpm() != null ? override.rpm() : base.getRequestsPerMinute(),
                override.tpmPerTenant() != null ? override.tpmPerTenant() : base.getTokensPerMinutePerTenant(),
                override.rpmPerTenant() != null ? override.rpmPerTenant() : base.getRequestsPerMinutePerTenant()
        );
        return new ResolvedKeyLimit(providerName + ":" + modelId, merged);
    }

    // ==================== MAIN API ====================

    /**
     * Check rate limit using default mode (provider-level).
     */
    public void checkRateLimit(String providerName, String tenantId, int estimatedTokens) {
        checkRateLimit(providerName, null, tenantId, estimatedTokens, defaultMode);
    }

    /**
     * Check rate limit with specific mode (provider-level).
     */
    public void checkRateLimit(String providerName, String tenantId, int estimatedTokens, RateLimitMode mode) {
        checkRateLimit(providerName, null, tenantId, estimatedTokens, mode);
    }

    /**
     * Check rate limit for a specific model using default mode.
     */
    public void checkRateLimit(String providerName, String modelId, String tenantId, int estimatedTokens) {
        checkRateLimit(providerName, modelId, tenantId, estimatedTokens, defaultMode);
    }

    /**
     * Check rate limit for a specific model with specific mode.
     */
    public void checkRateLimit(String providerName, String modelId, String tenantId, int estimatedTokens, RateLimitMode mode) {
        checkRateLimit(providerName, modelId, tenantId, estimatedTokens, mode, () -> false);
    }

    /**
     * F1.3 - overload that uses the default mode and accepts a {@code stopCheck}.
     * Convenience for callers (like {@code AbstractLLMProvider.completeStreaming})
     * that don't care about mode but DO want STOP-aware waiting.
     */
    public void checkRateLimit(String providerName, String modelId, String tenantId, int estimatedTokens,
                               BooleanSupplier stopCheck) {
        checkRateLimit(providerName, modelId, tenantId, estimatedTokens, defaultMode, stopCheck);
    }

    /**
     * F1.3 - variant that accepts a {@code stopCheck} predicate. In WAIT mode the
     * blocking sleep is split into 100ms slices and re-checks {@code stopCheck}
     * each slice; if it ever returns true, we abort with a non-retryable
     * {@code rate_limit_stopped} exception so the caller (the LLM provider's
     * streaming entry point) can surface a STOPPED_BY_USER outcome to the loop
     * instead of waiting up to {@code maxWaitTime} (~60s) on a cancelled stream.
     */
    public void checkRateLimit(String providerName, String modelId, String tenantId, int estimatedTokens,
                               RateLimitMode mode, BooleanSupplier stopCheck) {
        if (!config.isEnabled()) {
            return;
        }
        if (stopCheck == null) stopCheck = () -> false;

        switch (mode) {
            case WAIT -> acquireWithWait(providerName, modelId, tenantId, estimatedTokens, stopCheck);
            case FAIL_FAST -> acquireOrFail(providerName, modelId, tenantId, estimatedTokens);
            case TRY_ACQUIRE -> {
                RateLimitResult result = tryAcquire(providerName, modelId, tenantId, estimatedTokens);
                if (result.isBlocked()) {
                    String key = resolve(providerName, modelId).key();
                    incrementCounter(blockedCounters, key);
                    incrementTenantCounter(tenantBlockedCounters, key, tenantId);
                    throw createException(providerName, result);
                }
            }
            case QUEUE -> throw new UnsupportedOperationException("QUEUE mode requires using acquireAsync()");
        }
    }

    // ==================== NON-BLOCKING API ====================

    /**
     * Try to acquire rate limit capacity without blocking (provider-level).
     */
    public RateLimitResult tryAcquire(String providerName, String tenantId, int estimatedTokens) {
        return tryAcquire(providerName, null, tenantId, estimatedTokens);
    }

    /**
     * Try to acquire rate limit capacity without blocking, with per-model resolution.
     *
     * If allowed, tokens are RESERVED (counted) immediately.
     * Caller should proceed with API call.
     *
     * @param providerName  LLM provider name
     * @param modelId       model identifier (null for provider-level only)
     * @param tenantId      tenant ID (null for global-only strategies)
     * @param estimatedTokens tokens to reserve
     */
    public RateLimitResult tryAcquire(String providerName, String modelId, String tenantId, int estimatedTokens) {
        if (!config.isEnabled()) {
            return RateLimitResult.allowed(0, Integer.MAX_VALUE);
        }

        ResolvedKeyLimit resolved = resolve(providerName, modelId);
        String windowKey = resolved.key();
        RateLimitConfig.ProviderLimit limit = resolved.limit();
        RateLimitStrategy strategy = config.getStrategy();
        String reservationLockKey = reservationLockKey(windowKey, tenantId, strategy);

        return windowFactory.withAtomicReservationLock(reservationLockKey, () -> {
            ReservationLock reservationLock = acquireLocalReservationLock(reservationLockKey);
            reservationLock.lock.lock();
            try {
                // Check global limits
                if (strategy == RateLimitStrategy.GLOBAL || strategy == RateLimitStrategy.HYBRID) {
                    RateLimitResult globalResult = checkGlobalLimitsNonBlocking(windowKey, estimatedTokens, limit);
                    if (globalResult.isBlocked()) {
                        return globalResult;
                    }
                }

                // Check tenant limits
                if ((strategy == RateLimitStrategy.PER_TENANT || strategy == RateLimitStrategy.HYBRID) && tenantId != null) {
                    RateLimitResult tenantResult = checkTenantLimitsNonBlocking(windowKey, tenantId, estimatedTokens, limit);
                    if (tenantResult.isBlocked()) {
                        return tenantResult;
                    }
                }

                // All checks passed - reserve capacity
                reserveCapacity(windowKey, tenantId, estimatedTokens, limit);
                incrementCounter(acquiredCounters, windowKey);
                incrementTenantCounter(tenantAcquiredCounters, windowKey, tenantId);
                addToCounter(tokensConsumedCounters, windowKey, estimatedTokens);
                addToTenantCounter(tenantTokensConsumedCounters, windowKey, tenantId, estimatedTokens);

                // Calculate usage percentage
                double usagePercent = calculateUsagePercent(windowKey, tenantId, limit);
                int remaining = calculateRemainingCapacity(windowKey, tenantId, limit);

                log.debug("Rate limit acquired for {} (tenant: {}) - {} tokens, {}% used",
                        windowKey, tenantId, estimatedTokens, String.format("%.1f", usagePercent));

                return RateLimitResult.allowed(usagePercent, remaining);

            } finally {
                reservationLock.lock.unlock();
                reservationLock.references.decrementAndGet();
            }
        });
    }

    private ReservationLock acquireLocalReservationLock(String reservationLockKey) {
        return windowLocks.compute(reservationLockKey, (key, existing) -> {
            ReservationLock lock = existing != null ? existing : new ReservationLock();
            lock.references.incrementAndGet();
            return lock;
        });
    }

    private String reservationLockKey(String windowKey, String tenantId, RateLimitStrategy strategy) {
        if (strategy == RateLimitStrategy.PER_TENANT && tenantId != null && !tenantId.isBlank()) {
            return "reserve:" + windowKey + ":tenant:" + tenantId;
        }
        return "reserve:" + windowKey;
    }

    // ==================== BLOCKING API (INVISIBLE RATE LIMITING) ====================

    /**
     * Acquire rate limit capacity, waiting if necessary (provider-level).
     */
    public void acquireWithWait(String providerName, String tenantId, int estimatedTokens) {
        acquireWithWait(providerName, null, tenantId, estimatedTokens);
    }

    /**
     * Acquire rate limit capacity, waiting if necessary, with per-model resolution.
     * This is the "invisible" mode - caller doesn't know about rate limiting.
     *
     * @param providerName Provider name
     * @param modelId      Model ID (null for provider-level only)
     * @param tenantId     Tenant ID (can be null)
     * @param estimatedTokens Tokens to reserve
     * @throws LLMProviderException if max wait time exceeded
     */
    public void acquireWithWait(String providerName, String modelId, String tenantId, int estimatedTokens) {
        acquireWithWait(providerName, modelId, tenantId, estimatedTokens, () -> false);
    }

    /**
     * F1.3 - variant that polls {@code stopCheck} during the back-off sleep, so a
     * STOP signal arriving while the worker is blocked behind the rate-limit
     * doesn't have to wait for the next slot before being honored. The sleep is
     * sliced into 100ms chunks; each slice re-evaluates {@code stopCheck} and
     * exits with {@code rate_limit_stopped} when true.
     *
     * <p>Behavior summary:</p>
     * <ul>
     *   <li>{@code stopCheck} returns true → throw {@link LLMProviderException}
     *       with code {@code rate_limit_stopped}, retryable=false.</li>
     *   <li>{@code Thread.interrupt()} → restore the flag, throw
     *       {@code rate_limit_interrupted} (existing behavior preserved).</li>
     *   <li>Block counter is incremented exactly once per request (whether the
     *       request ultimately succeeds, times out, or is stopped) - we never
     *       skip it on the stopped path so observability still reflects that
     *       this request was throttled.</li>
     * </ul>
     */
    public void acquireWithWait(String providerName, String modelId, String tenantId, int estimatedTokens,
                                BooleanSupplier stopCheck) {
        if (!config.isEnabled()) {
            return;
        }
        if (stopCheck == null) stopCheck = () -> false;

        ResolvedKeyLimit resolved = resolve(providerName, modelId);
        String windowKey = resolved.key();
        RateLimitConfig.ProviderLimit limits = resolved.limit();

        // Pre-flight: a single request larger than the per-tenant TPM ceiling can NEVER
        // succeed by waiting - every future window has the same cap. Without this guard
        // the loop below burns the full maxWaitTime (default 600s on this platform -
        // bumped 2026-04-29 for split-aware Gemini bursts) before throwing
        // rate_limit_timeout, starving the agent worker pool and the orchestrator zombie
        // watchdog. Convert it into an immediate, non-retryable error so the caller sees
        // a true "request too large for tenant" failure with actionable context (estimate
        // vs cap), and the worker thread is freed within 1 ms instead of 10 minutes.
        //
        // Cap = 0 is "block all tenant traffic" (legitimate ops kill-switch - see
        // RateLimitConfig.hasTenantTokenLimit, which treats >= 0 as enabled). Any positive
        // estimate must reject in that mode too, so we use {@code estimatedTokens > cap}
        // which naturally covers cap=0 (every estimate >= 1 trips it). Earlier draft
        // gated on {@code cap > 0} which silently fell through into the 600s wait when
        // a tenant was killswitch'd - the very config most likely to need fast-fail.
        //
        // Strategy gate (added 2026-05-14, see PR3 audit): the preflight applies only
        // to per-tenant enforcement. Under GLOBAL strategy the per-tenant cap is data
        // but not a check (tryAcquire's checkTenantLimitsNonBlocking only fires when
        // strategy ∈ {PER_TENANT, HYBRID}, see line 269), so the preflight was checking
        // a cap that the rest of the limiter would have ignored - inconsistent. With
        // this gate, the preflight now mirrors tryAcquire's per-tenant branch exactly.
        RateLimitStrategy strategy = config.getStrategy();
        boolean perTenantActive = strategy == RateLimitStrategy.PER_TENANT
                                  || strategy == RateLimitStrategy.HYBRID;
        if (perTenantActive
                && limits != null
                && limits.hasTenantTokenLimit()
                && estimatedTokens > limits.getTokensPerMinutePerTenant()) {
            incrementCounter(blockedCounters, windowKey);
            incrementTenantCounter(tenantBlockedCounters, windowKey, tenantId);
            throw new LLMProviderException(providerName,
                    String.format(
                            "Request estimated at %d tokens exceeds per-tenant TPM capacity (%d) for %s%s - no rate-limit wait can satisfy it. Reduce prompt size or raise the tenant cap.",
                            estimatedTokens,
                            limits.getTokensPerMinutePerTenant(),
                            providerName,
                            modelId != null ? "/" + modelId : ""),
                    "request_exceeds_tenant_capacity",
                    false);
        }

        Instant deadline = Instant.now().plus(maxWaitTime);
        int attempts = 0;
        long totalWaitMs = 0;
        boolean countedAsBlocked = false;

        while (Instant.now().isBefore(deadline)) {
            // Fast path: caller already cancelled before we even tried.
            // Counted as blocked first (consistent with normal block path)
            // so metrics see this request was rate-limited, not just abandoned.
            if (stopCheck.getAsBoolean()) {
                if (!countedAsBlocked) {
                    incrementCounter(blockedCounters, windowKey);
                    incrementTenantCounter(tenantBlockedCounters, windowKey, tenantId);
                }
                addToCounter(waitMsCounters, windowKey, totalWaitMs);
                addToTenantCounter(tenantWaitMsCounters, windowKey, tenantId, totalWaitMs);
                throw new LLMProviderException(providerName,
                        "Rate limit wait stopped by user", "rate_limit_stopped", false);
            }

            RateLimitResult result = tryAcquire(providerName, modelId, tenantId, estimatedTokens);

            if (result.isAllowed()) {
                if (attempts > 0) {
                    log.info("Rate limit acquired after {} attempts for {} model={} (tenant: {}), waited {}ms",
                            attempts, providerName, modelId, tenantId, totalWaitMs);
                    addToCounter(waitMsCounters, windowKey, totalWaitMs);
                    addToTenantCounter(tenantWaitMsCounters, windowKey, tenantId, totalWaitMs);
                }
                return;
            }

            // Count this unique request as delayed only once (not per retry attempt)
            if (!countedAsBlocked) {
                incrementCounter(blockedCounters, windowKey);
                incrementTenantCounter(tenantBlockedCounters, windowKey, tenantId);
                countedAsBlocked = true;
            }

            attempts++;
            Duration waitTime = result.waitTime();

            // Cap wait time to remaining time before deadline
            Duration remainingTime = Duration.between(Instant.now(), deadline);
            if (waitTime.compareTo(remainingTime) > 0) {
                waitTime = remainingTime;
            }

            if (waitTime.isNegative() || waitTime.isZero()) {
                waitTime = Duration.ofMillis(100); // Minimum wait
            }

            log.debug("Rate limit exceeded for {} model={} (tenant: {}), waiting {}ms (attempt {})",
                    providerName, modelId, tenantId, waitTime.toMillis(), attempts);

            // F1.3 - slice the sleep into 100ms chunks so STOP signals arriving
            // mid-sleep are honored within ~100ms instead of waiting up to the
            // full backoff (could be 60s+ on a heavily-throttled tenant).
            long remainingMs = waitTime.toMillis();
            try {
                while (remainingMs > 0) {
                    long slice = Math.min(100L, remainingMs);
                    Thread.sleep(slice);
                    remainingMs -= slice;
                    totalWaitMs += slice;
                    if (stopCheck.getAsBoolean()) {
                        addToCounter(waitMsCounters, windowKey, totalWaitMs);
                        addToTenantCounter(tenantWaitMsCounters, windowKey, tenantId, totalWaitMs);
                        throw new LLMProviderException(providerName,
                                "Rate limit wait stopped by user", "rate_limit_stopped", false);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMProviderException(providerName,
                        "Rate limit wait interrupted", "rate_limit_interrupted", false);
            }
        }

        // Max wait time exceeded - also counts as delayed if not already counted
        if (!countedAsBlocked) {
            incrementCounter(blockedCounters, windowKey);
            incrementTenantCounter(tenantBlockedCounters, windowKey, tenantId);
        }
        incrementCounter(timeoutCounters, windowKey);
        incrementTenantCounter(tenantTimeoutCounters, windowKey, tenantId);
        addToCounter(waitMsCounters, windowKey, totalWaitMs);
        addToTenantCounter(tenantWaitMsCounters, windowKey, tenantId, totalWaitMs);
        throw new LLMProviderException(providerName,
                String.format("Rate limit wait timeout after %d seconds", maxWaitTime.getSeconds()),
                "rate_limit_timeout", true);
    }

    /**
     * Acquire or fail immediately (provider-level).
     */
    public void acquireOrFail(String providerName, String tenantId, int estimatedTokens) {
        acquireOrFail(providerName, null, tenantId, estimatedTokens);
    }

    /**
     * Acquire or fail immediately with per-model resolution.
     */
    public void acquireOrFail(String providerName, String modelId, String tenantId, int estimatedTokens) {
        RateLimitResult result = tryAcquire(providerName, modelId, tenantId, estimatedTokens);
        if (result.isBlocked()) {
            String key = resolve(providerName, modelId).key();
            incrementCounter(blockedCounters, key);
            incrementTenantCounter(tenantBlockedCounters, key, tenantId);
            throw createException(providerName, result);
        }
    }

    // ==================== RECORD USAGE ====================

    /**
     * Record actual usage after API call completes (provider-level).
     */
    public void recordRequest(String providerName, String tenantId, int actualTokens) {
        recordRequest(providerName, null, tenantId, actualTokens);
    }

    /**
     * Record actual usage after API call completes with per-model resolution.
     */
    public void recordRequest(String providerName, String modelId, String tenantId, int actualTokens) {
        if (!config.isEnabled()) {
            return;
        }

        log.debug("Request completed for {} model={} (tenant: {}) - {} actual tokens",
                providerName, modelId, tenantId, actualTokens);
    }

    // ==================== CAN ACQUIRE SOON ====================

    /**
     * Check whether a permit can likely be acquired soon (provider-level).
     */
    public boolean canAcquireSoon(String providerName, String tenantId, Duration probeWindow) {
        return canAcquireSoon(providerName, null, tenantId, probeWindow);
    }

    /**
     * Check whether a permit for the given provider/model can likely be acquired
     * within the specified probe window, without actually reserving capacity.
     *
     * <p>Used by worker loops to avoid blocking on saturated providers. If this
     * returns {@code false}, the caller should requeue the task with a delay
     * instead of calling {@code acquireWithWait} (which would block the thread).</p>
     *
     * <p><b>Intentionally advisory:</b> reads window state under {@code synchronized(window)}
     * but does NOT acquire the reservation lock, so it may race with
     * a concurrent {@code tryAcquire} mid-reserve. This is acceptable: the method is a
     * best-effort probe that never modifies state; a momentarily stale read just means the
     * caller retries one cycle later.</p>
     *
     * @param providerName provider name
     * @param modelId      model identifier (null for provider-level only)
     * @param tenantId     tenant ID (may be null for global-only strategies)
     * @param probeWindow  how far ahead to look for available capacity
     * @return true if capacity is likely available within the probe window
     */
    public boolean canAcquireSoon(String providerName, String modelId, String tenantId, Duration probeWindow) {
        if (!config.isEnabled()) {
            return true;
        }

        ResolvedKeyLimit resolved = resolve(providerName, modelId);
        String windowKey = resolved.key();
        RateLimitConfig.ProviderLimit limit = resolved.limit();
        RateLimitStrategy strategy = config.getStrategy();
        long now = System.currentTimeMillis();

        // Check global limits - if full, see if oldest entry expires within probeWindow
        if (strategy == RateLimitStrategy.GLOBAL || strategy == RateLimitStrategy.HYBRID) {
            // Limit=0 means permanently blocked - will never be acquirable
            if (limit.hasGlobalRequestLimit() && limit.getRequestsPerMinute() == 0) return false;
            if (limit.hasGlobalTokenLimit() && limit.getTokensPerMinute() == 0) return false;

            if (limit.hasGlobalRequestLimit()) {
                RateLimitWindow window = globalRequestWindows.get(windowKey);
                if (window != null) {
                    synchronized (window) {
                        window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                        if (window.getCount() >= limit.getRequestsPerMinute()) {
                            long oldest = window.getOldestTimestamp();
                            long expiresAt = oldest + (WINDOW_SIZE_SECONDS * 1000L);
                            if (expiresAt - now > probeWindow.toMillis()) {
                                return false;
                            }
                        }
                    }
                }
            }

            if (limit.hasGlobalTokenLimit()) {
                RateLimitWindow window = globalTokenWindows.get(windowKey);
                if (window != null) {
                    synchronized (window) {
                        window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                        if (window.getSum() >= limit.getTokensPerMinute()) {
                            long oldest = window.getOldestTimestamp();
                            long expiresAt = oldest + (WINDOW_SIZE_SECONDS * 1000L);
                            if (expiresAt - now > probeWindow.toMillis()) {
                                return false;
                            }
                        }
                    }
                }
            }
        }

        // Check tenant limits
        if ((strategy == RateLimitStrategy.PER_TENANT || strategy == RateLimitStrategy.HYBRID) && tenantId != null) {
            if (limit.hasTenantRequestLimit() && limit.getRequestsPerMinutePerTenant() == 0) return false;
            if (limit.hasTenantTokenLimit() && limit.getTokensPerMinutePerTenant() == 0) return false;

            if (limit.hasTenantRequestLimit()) {
                Map<String, RateLimitWindow> keyWindows = tenantRequestWindows.get(windowKey);
                if (keyWindows != null) {
                    RateLimitWindow window = keyWindows.get(tenantId);
                    if (window != null) {
                        synchronized (window) {
                            window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                            if (window.getCount() >= limit.getRequestsPerMinutePerTenant()) {
                                long oldest = window.getOldestTimestamp();
                                long expiresAt = oldest + (WINDOW_SIZE_SECONDS * 1000L);
                                if (expiresAt - now > probeWindow.toMillis()) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }

            if (limit.hasTenantTokenLimit()) {
                Map<String, RateLimitWindow> keyWindows = tenantTokenWindows.get(windowKey);
                if (keyWindows != null) {
                    RateLimitWindow window = keyWindows.get(tenantId);
                    if (window != null) {
                        synchronized (window) {
                            window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                            if (window.getSum() >= limit.getTokensPerMinutePerTenant()) {
                                long oldest = window.getOldestTimestamp();
                                long expiresAt = oldest + (WINDOW_SIZE_SECONDS * 1000L);
                                if (expiresAt - now > probeWindow.toMillis()) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    // ==================== PRIVATE HELPERS ====================

    private RateLimitResult checkGlobalLimitsNonBlocking(String windowKey, int estimatedTokens,
                                                          RateLimitConfig.ProviderLimit limit) {
        long now = System.currentTimeMillis();

        // Check RPM (limit=0 means block ALL traffic unconditionally)
        if (limit.hasGlobalRequestLimit()) {
            if (limit.getRequestsPerMinute() == 0) {
                return RateLimitResult.blocked(Duration.ofSeconds(60),
                        "Global request limit: 0 RPM (blocked)",
                        "rate_limit_global_rpm", 100.0);
            }
            RateLimitWindow window = globalRequestWindows.computeIfAbsent(windowKey,
                    k -> windowFactory.create("global:" + windowKey + ":rpm", WINDOW_SIZE_SECONDS));
            synchronized (window) {
                window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                if (window.getCount() >= limit.getRequestsPerMinute()) {
                    Duration waitTime = calculateWaitTime(window, now);
                    double usagePercent = (window.getCount() * 100.0) / limit.getRequestsPerMinute();
                    return RateLimitResult.blocked(waitTime,
                            String.format("Global request limit: %d/%d RPM", window.getCount(), limit.getRequestsPerMinute()),
                            "rate_limit_global_rpm", usagePercent);
                }
            }
        }

        // Check TPM (limit=0 means block ALL traffic unconditionally; otherwise skip 0-token requests)
        if (limit.hasGlobalTokenLimit()) {
            if (limit.getTokensPerMinute() == 0) {
                // Block-all: reject unconditionally regardless of token count
                return RateLimitResult.blocked(Duration.ofSeconds(60),
                        String.format("Global token limit: 0 TPM (blocked)"),
                        "rate_limit_global_tpm", 100.0);
            }
            if (estimatedTokens > 0) {
                RateLimitWindow window = globalTokenWindows.computeIfAbsent(windowKey,
                        k -> windowFactory.create("global:" + windowKey + ":tpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    int currentTokens = window.getSum();
                    if (currentTokens + estimatedTokens > limit.getTokensPerMinute()) {
                        Duration waitTime = calculateWaitTime(window, now);
                        double usagePercent = (currentTokens * 100.0) / limit.getTokensPerMinute();
                        return RateLimitResult.blocked(waitTime,
                                String.format("Global token limit: %d+%d > %d TPM", currentTokens, estimatedTokens, limit.getTokensPerMinute()),
                                "rate_limit_global_tpm", usagePercent);
                    }
                }
            }
        }

        return RateLimitResult.allowed(0, 0); // Placeholder, real values calculated later
    }

    private RateLimitResult checkTenantLimitsNonBlocking(String windowKey, String tenantId, int estimatedTokens,
                                                          RateLimitConfig.ProviderLimit limit) {
        long now = System.currentTimeMillis();

        // Check RPM (limit=0 means block ALL traffic unconditionally)
        if (limit.hasTenantRequestLimit()) {
            if (limit.getRequestsPerMinutePerTenant() == 0) {
                return RateLimitResult.blocked(Duration.ofSeconds(60),
                        "Tenant request limit: 0 RPM (blocked)",
                        "rate_limit_tenant_rpm", 100.0);
            }
            Map<String, RateLimitWindow> keyWindows = tenantRequestWindows.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>());
            RateLimitWindow window = keyWindows.computeIfAbsent(tenantId,
                    k -> windowFactory.create("tenant:" + tenantId + ":" + windowKey + ":rpm", WINDOW_SIZE_SECONDS));
            synchronized (window) {
                window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                if (window.getCount() >= limit.getRequestsPerMinutePerTenant()) {
                    Duration waitTime = calculateWaitTime(window, now);
                    double usagePercent = (window.getCount() * 100.0) / limit.getRequestsPerMinutePerTenant();
                    return RateLimitResult.blocked(waitTime,
                            String.format("Tenant request limit: %d/%d RPM", window.getCount(), limit.getRequestsPerMinutePerTenant()),
                            "rate_limit_tenant_rpm", usagePercent);
                }
            }
        }

        // Check TPM (limit=0 means block ALL traffic unconditionally)
        if (limit.hasTenantTokenLimit()) {
            if (limit.getTokensPerMinutePerTenant() == 0) {
                return RateLimitResult.blocked(Duration.ofSeconds(60),
                        String.format("Tenant token limit: 0 TPM (blocked)"),
                        "rate_limit_tenant_tpm", 100.0);
            }
            if (estimatedTokens > 0) {
                Map<String, RateLimitWindow> keyWindows = tenantTokenWindows.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>());
                RateLimitWindow window = keyWindows.computeIfAbsent(tenantId,
                        k -> windowFactory.create("tenant:" + tenantId + ":" + windowKey + ":tpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    int currentTokens = window.getSum();
                    if (currentTokens + estimatedTokens > limit.getTokensPerMinutePerTenant()) {
                        Duration waitTime = calculateWaitTime(window, now);
                        double usagePercent = (currentTokens * 100.0) / limit.getTokensPerMinutePerTenant();
                        return RateLimitResult.blocked(waitTime,
                                String.format("Tenant token limit: %d+%d > %d TPM", currentTokens, estimatedTokens, limit.getTokensPerMinutePerTenant()),
                                "rate_limit_tenant_tpm", usagePercent);
                    }
                }
            }
        }

        return RateLimitResult.allowed(0, 0);
    }

    /**
     * Reserve capacity BEFORE making the API call.
     * This prevents race conditions.
     */
    private void reserveCapacity(String windowKey, String tenantId, int tokens,
                                  RateLimitConfig.ProviderLimit limit) {
        long now = System.currentTimeMillis();
        RateLimitStrategy strategy = config.getStrategy();

        // Reserve global
        if (strategy == RateLimitStrategy.GLOBAL || strategy == RateLimitStrategy.HYBRID) {
            if (limit.hasGlobalRequestLimit()) {
                RateLimitWindow window = globalRequestWindows.computeIfAbsent(windowKey,
                        k -> windowFactory.create("global:" + windowKey + ":rpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.add(now, 1);
                }
            }
            if (limit.hasGlobalTokenLimit() && tokens > 0) {
                RateLimitWindow window = globalTokenWindows.computeIfAbsent(windowKey,
                        k -> windowFactory.create("global:" + windowKey + ":tpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.add(now, tokens);
                }
            }
        }

        // Reserve tenant
        if ((strategy == RateLimitStrategy.PER_TENANT || strategy == RateLimitStrategy.HYBRID) && tenantId != null) {
            if (limit.hasTenantRequestLimit()) {
                Map<String, RateLimitWindow> keyWindows = tenantRequestWindows.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>());
                RateLimitWindow window = keyWindows.computeIfAbsent(tenantId,
                        k -> windowFactory.create("tenant:" + tenantId + ":" + windowKey + ":rpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.add(now, 1);
                }
            }
            if (limit.hasTenantTokenLimit() && tokens > 0) {
                Map<String, RateLimitWindow> keyWindows = tenantTokenWindows.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>());
                RateLimitWindow window = keyWindows.computeIfAbsent(tenantId,
                        k -> windowFactory.create("tenant:" + tenantId + ":" + windowKey + ":tpm", WINDOW_SIZE_SECONDS));
                synchronized (window) {
                    window.add(now, tokens);
                }
            }
        }
    }

    private Duration calculateWaitTime(RateLimitWindow window, long now) {
        long oldestTimestamp = window.getOldestTimestamp();
        if (oldestTimestamp == 0) {
            return Duration.ofSeconds(1); // No entries, wait a bit
        }
        long expiresAt = oldestTimestamp + (WINDOW_SIZE_SECONDS * 1000L);
        long waitMs = expiresAt - now;
        if (waitMs <= 0) {
            return Duration.ofMillis(100); // Already expired, just retry quickly
        }
        return Duration.ofMillis(Math.min(waitMs, 60000)); // Cap at 60 seconds
    }

    private double calculateUsagePercent(String windowKey, String tenantId, RateLimitConfig.ProviderLimit limit) {
        double maxUsage = 0;

        if (limit.hasGlobalTokenLimit()) {
            RateLimitWindow window = globalTokenWindows.get(windowKey);
            if (window != null) {
                synchronized (window) {
                    maxUsage = Math.max(maxUsage, (window.getSum() * 100.0) / limit.getTokensPerMinute());
                }
            }
        }

        if (limit.hasGlobalRequestLimit()) {
            RateLimitWindow window = globalRequestWindows.get(windowKey);
            if (window != null) {
                synchronized (window) {
                    maxUsage = Math.max(maxUsage, (window.getCount() * 100.0) / limit.getRequestsPerMinute());
                }
            }
        }

        if (tenantId != null && limit.hasTenantTokenLimit()) {
            Map<String, RateLimitWindow> keyWindows = tenantTokenWindows.get(windowKey);
            if (keyWindows != null) {
                RateLimitWindow window = keyWindows.get(tenantId);
                if (window != null) {
                    synchronized (window) {
                        maxUsage = Math.max(maxUsage, (window.getSum() * 100.0) / limit.getTokensPerMinutePerTenant());
                    }
                }
            }
        }

        return maxUsage;
    }

    private int calculateRemainingCapacity(String windowKey, String tenantId, RateLimitConfig.ProviderLimit limit) {
        int minRemaining = Integer.MAX_VALUE;

        if (limit.hasGlobalTokenLimit()) {
            RateLimitWindow window = globalTokenWindows.get(windowKey);
            if (window != null) {
                synchronized (window) {
                    minRemaining = Math.min(minRemaining, limit.getTokensPerMinute() - window.getSum());
                }
            }
        }

        if (tenantId != null && limit.hasTenantTokenLimit()) {
            Map<String, RateLimitWindow> keyWindows = tenantTokenWindows.get(windowKey);
            if (keyWindows != null) {
                RateLimitWindow window = keyWindows.get(tenantId);
                if (window != null) {
                    synchronized (window) {
                        minRemaining = Math.min(minRemaining, limit.getTokensPerMinutePerTenant() - window.getSum());
                    }
                }
            }
        }

        return Math.max(0, minRemaining);
    }

    private LLMProviderException createException(String providerName, RateLimitResult result) {
        return new LLMProviderException(
                providerName,
                String.format("%s. Retry in %d seconds.", result.reason(), result.waitTime().getSeconds()),
                result.errorCode(),
                true
        );
    }

    // ==================== STATS API ====================

    /**
     * Get global usage stats for a provider (provider-level).
     */
    public UsageStats getGlobalUsageStats(String providerName) {
        return getGlobalUsageStats(providerName, null);
    }

    /**
     * Get global usage stats for a provider/model.
     */
    public UsageStats getGlobalUsageStats(String providerName, String modelId) {
        ResolvedKeyLimit resolved = resolve(providerName, modelId);
        String windowKey = resolved.key();
        RateLimitConfig.ProviderLimit limit = resolved.limit();
        long now = System.currentTimeMillis();

        int currentTokens = 0;
        int currentRequests = 0;

        RateLimitWindow tokenWindow = globalTokenWindows.get(windowKey);
        if (tokenWindow != null) {
            synchronized (tokenWindow) {
                tokenWindow.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                currentTokens = tokenWindow.getSum();
            }
        }

        RateLimitWindow requestWindow = globalRequestWindows.get(windowKey);
        if (requestWindow != null) {
            synchronized (requestWindow) {
                requestWindow.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                currentRequests = requestWindow.getCount();
            }
        }

        return new UsageStats(
                currentTokens,
                limit.hasGlobalTokenLimit() ? limit.getTokensPerMinute() : -1,
                currentRequests,
                limit.hasGlobalRequestLimit() ? limit.getRequestsPerMinute() : -1
        );
    }

    /**
     * Get tenant usage stats (provider-level).
     */
    public UsageStats getTenantUsageStats(String providerName, String tenantId) {
        return getTenantUsageStats(providerName, null, tenantId);
    }

    /**
     * Get tenant usage stats for a provider/model.
     */
    public UsageStats getTenantUsageStats(String providerName, String modelId, String tenantId) {
        ResolvedKeyLimit resolved = resolve(providerName, modelId);
        String windowKey = resolved.key();
        RateLimitConfig.ProviderLimit limit = resolved.limit();
        long now = System.currentTimeMillis();

        int currentTokens = 0;
        int currentRequests = 0;

        Map<String, RateLimitWindow> tokenWindows = tenantTokenWindows.get(windowKey);
        if (tokenWindows != null) {
            RateLimitWindow window = tokenWindows.get(tenantId);
            if (window != null) {
                synchronized (window) {
                    window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    currentTokens = window.getSum();
                }
            }
        }

        Map<String, RateLimitWindow> requestWindows = tenantRequestWindows.get(windowKey);
        if (requestWindows != null) {
            RateLimitWindow window = requestWindows.get(tenantId);
            if (window != null) {
                synchronized (window) {
                    window.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    currentRequests = window.getCount();
                }
            }
        }

        return new UsageStats(
                currentTokens,
                limit.hasTenantTokenLimit() ? limit.getTokensPerMinutePerTenant() : -1,
                currentRequests,
                limit.hasTenantRequestLimit() ? limit.getRequestsPerMinutePerTenant() : -1
        );
    }

    // ==================== CLEANUP ====================

    /**
     * Cleanup inactive tenant and model-keyed windows periodically.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupInactiveTenants() {
        long cutoff = System.currentTimeMillis() - 300000; // 5 minutes ago
        int cleaned = 0;

        // Cleanup tenant windows
        for (Map<String, RateLimitWindow> keyWindows : tenantTokenWindows.values()) {
            Iterator<Map.Entry<String, RateLimitWindow>> it = keyWindows.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, RateLimitWindow> entry = it.next();
                RateLimitWindow window = entry.getValue();
                synchronized (window) {
                    window.cleanup(System.currentTimeMillis() - (WINDOW_SIZE_SECONDS * 1000L));
                    if (window.isEmpty() && window.getLastAccessTime() < cutoff) {
                        it.remove();
                        cleaned++;
                    }
                }
            }
        }

        for (Map<String, RateLimitWindow> keyWindows : tenantRequestWindows.values()) {
            Iterator<Map.Entry<String, RateLimitWindow>> it = keyWindows.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, RateLimitWindow> entry = it.next();
                RateLimitWindow window = entry.getValue();
                synchronized (window) {
                    window.cleanup(System.currentTimeMillis() - (WINDOW_SIZE_SECONDS * 1000L));
                    if (window.isEmpty() && window.getLastAccessTime() < cutoff) {
                        it.remove();
                        cleaned++;
                    }
                }
            }
        }

        // Cleanup model-keyed global windows (keys containing ":")
        cleaned += cleanupModelKeyedWindows(globalTokenWindows, cutoff);
        cleaned += cleanupModelKeyedWindows(globalRequestWindows, cutoff);

        // Cleanup stale reservation lock entries. A lock may be removed only
        // after no caller has acquired a reference and no active window remains.
        Iterator<Map.Entry<String, ReservationLock>> lockIt = windowLocks.entrySet().iterator();
        while (lockIt.hasNext()) {
            Map.Entry<String, ReservationLock> entry = lockIt.next();
            ReservationLock lock = entry.getValue();
            if (lock.references.get() == 0
                    && !lock.lock.isLocked()
                    && !lock.lock.hasQueuedThreads()
                    && !hasActiveWindowForReservationLock(entry.getKey())) {
                lockIt.remove();
                cleaned++;
            }
        }

        // Cleanup counter maps for keys with no active windows
        cleaned += cleanupStaleCounterMaps(cutoff);

        if (cleaned > 0) {
            log.info("Cleaned up {} inactive rate limit windows/locks/counters", cleaned);
        }
    }

    private int cleanupModelKeyedWindows(Map<String, RateLimitWindow> windowMap, long cutoff) {
        int cleaned = 0;
        Iterator<Map.Entry<String, RateLimitWindow>> it = windowMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RateLimitWindow> entry = it.next();
            String key = entry.getKey();
            if (!key.contains(":")) {
                continue; // Skip provider-level entries
            }
            RateLimitWindow window = entry.getValue();
            synchronized (window) {
                window.cleanup(System.currentTimeMillis() - (WINDOW_SIZE_SECONDS * 1000L));
                if (window.isEmpty() && window.getLastAccessTime() < cutoff) {
                    it.remove();
                    cleaned++;
                }
            }
        }
        return cleaned;
    }

    private boolean hasActiveWindowForReservationLock(String reservationLockKey) {
        if (reservationLockKey == null || !reservationLockKey.startsWith("reserve:")) {
            return globalTokenWindows.containsKey(reservationLockKey)
                    || globalRequestWindows.containsKey(reservationLockKey);
        }

        String body = reservationLockKey.substring("reserve:".length());
        int tenantMarker = body.indexOf(":tenant:");
        if (tenantMarker >= 0) {
            String windowKey = body.substring(0, tenantMarker);
            String tenantId = body.substring(tenantMarker + ":tenant:".length());
            return containsTenantWindow(tenantRequestWindows.get(windowKey), tenantId)
                    || containsTenantWindow(tenantTokenWindows.get(windowKey), tenantId);
        }

        return globalTokenWindows.containsKey(body)
                || globalRequestWindows.containsKey(body)
                || hasAnyTenantWindows(body);
    }

    private boolean containsTenantWindow(Map<String, RateLimitWindow> windows, String tenantId) {
        return windows != null && tenantId != null && windows.containsKey(tenantId);
    }

    private boolean hasAnyTenantWindows(String windowKey) {
        Map<String, RateLimitWindow> requestWindows = tenantRequestWindows.get(windowKey);
        if (requestWindows != null && !requestWindows.isEmpty()) {
            return true;
        }
        Map<String, RateLimitWindow> tokenWindows = tenantTokenWindows.get(windowKey);
        return tokenWindows != null && !tokenWindows.isEmpty();
    }

    /**
     * Remove counter entries for model-keyed windows that no longer have active windows.
     * Provider-level counters are kept indefinitely (bounded cardinality).
     *
     * <p>Also prunes per-tenant counters: drops outer entries whose windowKey is gone,
     * and within surviving windows drops inner entries for tenants that no longer have
     * any active RPM/TPM window (i.e. were cleaned up by {@link #cleanupInactiveTenants()}).
     * This keeps Prometheus cardinality bounded when tenants go idle.</p>
     */
    private int cleanupStaleCounterMaps(long cutoff) {
        int cleaned = 0;
        java.util.Set<String> activeKeys = getActiveWindowKeys();

        for (Map<String, java.util.concurrent.atomic.AtomicLong> counterMap :
                java.util.List.of(acquiredCounters, blockedCounters, timeoutCounters, waitMsCounters, tokensConsumedCounters)) {
            Iterator<String> it = counterMap.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key.contains(":") && !activeKeys.contains(key)) {
                    it.remove();
                    cleaned++;
                }
            }
        }

        for (Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> tenantMap :
                java.util.List.of(tenantAcquiredCounters, tenantBlockedCounters, tenantTimeoutCounters,
                        tenantWaitMsCounters, tenantTokensConsumedCounters)) {
            Iterator<Map.Entry<String, Map<String, java.util.concurrent.atomic.AtomicLong>>> outerIt =
                    tenantMap.entrySet().iterator();
            while (outerIt.hasNext()) {
                Map.Entry<String, Map<String, java.util.concurrent.atomic.AtomicLong>> entry = outerIt.next();
                String windowKey = entry.getKey();
                if (windowKey.contains(":") && !activeKeys.contains(windowKey)) {
                    outerIt.remove();
                    cleaned++;
                    continue;
                }
                Map<String, RateLimitWindow> reqWindows = tenantRequestWindows.get(windowKey);
                Map<String, RateLimitWindow> tokWindows = tenantTokenWindows.get(windowKey);
                Iterator<String> innerIt = entry.getValue().keySet().iterator();
                while (innerIt.hasNext()) {
                    String tenantId = innerIt.next();
                    boolean inReq = reqWindows != null && reqWindows.containsKey(tenantId);
                    boolean inTok = tokWindows != null && tokWindows.containsKey(tenantId);
                    if (!inReq && !inTok) {
                        innerIt.remove();
                        cleaned++;
                    }
                }
            }
        }
        return cleaned;
    }

    // ==================== COUNTER HELPERS ====================

    private void incrementCounter(Map<String, java.util.concurrent.atomic.AtomicLong> map, String key) {
        map.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong(0)).incrementAndGet();
    }

    private void addToCounter(Map<String, java.util.concurrent.atomic.AtomicLong> map, String key, long value) {
        map.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong(0)).addAndGet(value);
    }

    private void incrementTenantCounter(Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> map,
                                        String windowKey, String tenantId) {
        if (tenantId == null) return;
        map.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(tenantId, k -> new java.util.concurrent.atomic.AtomicLong(0))
                .incrementAndGet();
    }

    private void addToTenantCounter(Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> map,
                                    String windowKey, String tenantId, long value) {
        if (tenantId == null) return;
        map.computeIfAbsent(windowKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(tenantId, k -> new java.util.concurrent.atomic.AtomicLong(0))
                .addAndGet(value);
    }

    private long getTenantCounterValue(Map<String, Map<String, java.util.concurrent.atomic.AtomicLong>> map,
                                        String windowKey, String tenantId) {
        Map<String, java.util.concurrent.atomic.AtomicLong> inner = map.get(windowKey);
        if (inner == null) return 0;
        var counter = inner.get(tenantId);
        return counter != null ? counter.get() : 0;
    }

    // ==================== METRICS API ====================

    /**
     * Returns all active window keys (provider or provider:model format).
     */
    public java.util.Set<String> getActiveWindowKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        keys.addAll(globalTokenWindows.keySet());
        keys.addAll(globalRequestWindows.keySet());
        return keys;
    }

    /**
     * Split a window key into [provider, model]. If no model, second element is null.
     */
    public static String[] splitWindowKey(String windowKey) {
        int idx = windowKey.indexOf(':');
        if (idx < 0) return new String[]{windowKey, null};
        return new String[]{windowKey.substring(0, idx), windowKey.substring(idx + 1)};
    }

    /**
     * Get event counters for a given window key. Returns [acquired, blocked, timeout, waitMs, tokensConsumed].
     */
    public long[] getEventCounters(String windowKey) {
        return new long[]{
                getCounterValue(acquiredCounters, windowKey),
                getCounterValue(blockedCounters, windowKey),
                getCounterValue(timeoutCounters, windowKey),
                getCounterValue(waitMsCounters, windowKey),
                getCounterValue(tokensConsumedCounters, windowKey)
        };
    }

    private long getCounterValue(Map<String, java.util.concurrent.atomic.AtomicLong> map, String key) {
        var counter = map.get(key);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Snapshot of all rate limit metrics for a single window key.
     */
    public record WindowMetrics(
            String windowKey,
            String provider,
            String model,
            int currentRpm,
            int rpmLimit,
            int currentTpm,
            int tpmLimit,
            long acquiredTotal,
            long blockedTotal,
            long timeoutTotal,
            long waitMsTotal,
            long tokensConsumedTotal
    ) {
        public double rpmUsagePercent() {
            return rpmLimit > 0 ? (currentRpm * 100.0 / rpmLimit) : 0;
        }
        public double tpmUsagePercent() {
            return tpmLimit > 0 ? (currentTpm * 100.0 / tpmLimit) : 0;
        }
    }

    /**
     * Get metrics for all active windows. Called by the Prometheus metrics publisher.
     */
    public java.util.List<WindowMetrics> getAllWindowMetrics() {
        java.util.List<WindowMetrics> result = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();

        for (String windowKey : getActiveWindowKeys()) {
            String[] parts = splitWindowKey(windowKey);
            String provider = parts[0];
            String model = parts[1];

            // Resolve limits
            ResolvedKeyLimit resolved = resolve(provider, model);
            RateLimitConfig.ProviderLimit limit = resolved.limit();

            // Current RPM
            int currentRpm = 0;
            RateLimitWindow rpmWindow = globalRequestWindows.get(windowKey);
            if (rpmWindow != null) {
                synchronized (rpmWindow) {
                    rpmWindow.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    currentRpm = rpmWindow.getCount();
                }
            }

            // Current TPM
            int currentTpm = 0;
            RateLimitWindow tpmWindow = globalTokenWindows.get(windowKey);
            if (tpmWindow != null) {
                synchronized (tpmWindow) {
                    tpmWindow.cleanup(now - (WINDOW_SIZE_SECONDS * 1000L));
                    currentTpm = tpmWindow.getSum();
                }
            }

            long[] counters = getEventCounters(windowKey);

            result.add(new WindowMetrics(
                    windowKey, provider, model != null ? model : "all",
                    currentRpm, limit.hasGlobalRequestLimit() ? limit.getRequestsPerMinute() : 0,
                    currentTpm, limit.hasGlobalTokenLimit() ? limit.getTokensPerMinute() : 0,
                    counters[0], counters[1], counters[2], counters[3], counters[4]
            ));
        }

        return result;
    }

    /**
     * Per-tenant rate limit snapshot for a single (windowKey, tenant) pair.
     * RPM/TPM gauges reflect the tenant's own rolling window; the limits
     * reflect the per-tenant caps (0 if the strategy has no tenant cap).
     */
    public record TenantWindowMetrics(
            String windowKey,
            String provider,
            String model,
            String tenant,
            int currentRpm,
            int rpmLimit,
            int currentTpm,
            int tpmLimit,
            long acquiredTotal,
            long blockedTotal,
            long timeoutTotal,
            long waitMsTotal,
            long tokensConsumedTotal
    ) {
        public double rpmUsagePercent() {
            return rpmLimit > 0 ? (currentRpm * 100.0 / rpmLimit) : 0;
        }
        public double tpmUsagePercent() {
            return tpmLimit > 0 ? (currentTpm * 100.0 / tpmLimit) : 0;
        }
    }

    /**
     * Get per-tenant metrics across every active window. Called by the Prometheus
     * metrics publisher to emit series tagged with a {@code tenant} label, so
     * fairness/isolation dashboards can break down acquire/block by tenant.
     *
     * <p>A tenant is "active" for a given windowKey if it has an entry in either
     * {@link #tenantRequestWindows} or {@link #tenantTokenWindows}, or has any
     * tenant counter recorded for that windowKey (covers the transient case where
     * the window has been cleaned up but the counter has not yet).</p>
     */
    public java.util.List<TenantWindowMetrics> getAllTenantWindowMetrics() {
        java.util.List<TenantWindowMetrics> result = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        long cutoff = now - (WINDOW_SIZE_SECONDS * 1000L);

        for (String windowKey : getActiveWindowKeys()) {
            String[] parts = splitWindowKey(windowKey);
            String provider = parts[0];
            String model = parts[1];

            ResolvedKeyLimit resolved = resolve(provider, model);
            RateLimitConfig.ProviderLimit limit = resolved.limit();

            Map<String, RateLimitWindow> reqWindows = tenantRequestWindows.get(windowKey);
            Map<String, RateLimitWindow> tokWindows = tenantTokenWindows.get(windowKey);

            java.util.Set<String> tenants = new java.util.HashSet<>();
            if (reqWindows != null) tenants.addAll(reqWindows.keySet());
            if (tokWindows != null) tenants.addAll(tokWindows.keySet());
            // Union across all 5 tenant counter maps so that a tenant seen only via
            // timeout/waitMs/tokensConsumed is still published, not just the ones we
            // saw on acquired/blocked paths.
            Map<String, java.util.concurrent.atomic.AtomicLong> ackInner = tenantAcquiredCounters.get(windowKey);
            if (ackInner != null) tenants.addAll(ackInner.keySet());
            Map<String, java.util.concurrent.atomic.AtomicLong> blkInner = tenantBlockedCounters.get(windowKey);
            if (blkInner != null) tenants.addAll(blkInner.keySet());
            Map<String, java.util.concurrent.atomic.AtomicLong> toInner = tenantTimeoutCounters.get(windowKey);
            if (toInner != null) tenants.addAll(toInner.keySet());
            Map<String, java.util.concurrent.atomic.AtomicLong> waitInner = tenantWaitMsCounters.get(windowKey);
            if (waitInner != null) tenants.addAll(waitInner.keySet());
            Map<String, java.util.concurrent.atomic.AtomicLong> tokInner = tenantTokensConsumedCounters.get(windowKey);
            if (tokInner != null) tenants.addAll(tokInner.keySet());

            for (String tenantId : tenants) {
                int currentRpm = 0;
                if (reqWindows != null) {
                    RateLimitWindow w = reqWindows.get(tenantId);
                    if (w != null) {
                        synchronized (w) {
                            w.cleanup(cutoff);
                            currentRpm = w.getCount();
                        }
                    }
                }
                int currentTpm = 0;
                if (tokWindows != null) {
                    RateLimitWindow w = tokWindows.get(tenantId);
                    if (w != null) {
                        synchronized (w) {
                            w.cleanup(cutoff);
                            currentTpm = w.getSum();
                        }
                    }
                }

                long acquired = getTenantCounterValue(tenantAcquiredCounters, windowKey, tenantId);
                long blocked = getTenantCounterValue(tenantBlockedCounters, windowKey, tenantId);
                long timeout = getTenantCounterValue(tenantTimeoutCounters, windowKey, tenantId);
                long waitMs = getTenantCounterValue(tenantWaitMsCounters, windowKey, tenantId);
                long tokensConsumed = getTenantCounterValue(tenantTokensConsumedCounters, windowKey, tenantId);

                result.add(new TenantWindowMetrics(
                        windowKey, provider, model != null ? model : "all", tenantId,
                        currentRpm, limit.hasTenantRequestLimit() ? limit.getRequestsPerMinutePerTenant() : 0,
                        currentTpm, limit.hasTenantTokenLimit() ? limit.getTokensPerMinutePerTenant() : 0,
                        acquired, blocked, timeout, waitMs, tokensConsumed
                ));
            }
        }

        return result;
    }

    // ==================== INNER TYPES ====================

    private static final class ReservationLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger();
    }

    /**
     * Usage statistics.
     */
    public record UsageStats(
            int currentTokens,
            int tokenLimit,
            int currentRequests,
            int requestLimit
    ) {
        public double getTokenUsagePercent() {
            return tokenLimit > 0 ? (currentTokens * 100.0 / tokenLimit) : 0;
        }

        public double getRequestUsagePercent() {
            return requestLimit > 0 ? (currentRequests * 100.0 / requestLimit) : 0;
        }

        public int getRemainingTokens() {
            return tokenLimit > 0 ? Math.max(0, tokenLimit - currentTokens) : Integer.MAX_VALUE;
        }

        public int getRemainingRequests() {
            return requestLimit > 0 ? Math.max(0, requestLimit - currentRequests) : Integer.MAX_VALUE;
        }
    }
}
