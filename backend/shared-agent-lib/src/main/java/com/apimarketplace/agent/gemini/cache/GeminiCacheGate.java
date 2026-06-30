package com.apimarketplace.agent.gemini.cache;

/**
 * Stage 2.2 - eligibility gate for Gemini {@code cachedContent} creation.
 *
 * <p><b>Why gate at all.</b> Gemini charges storage fees on cached
 * prefixes; the discount only pays off when (a) the cached content is
 * large enough to clear the provider's minimum-token floor, and (b) the
 * tenant generates enough traffic to amortise the upload. Creating a
 * cache for a tenant that sends one request per day is pure loss -
 * 24h of storage fees for zero re-use.
 *
 * <p>The two floors from Google's docs (see
 * {@code gemini-cache-floors.md} and R17) are:
 * <ul>
 *   <li><b>Flash family:</b> 1,024 token minimum on the cached prefix.
 *       Requests with a smaller static prefix are rejected by the API
 *       with 400 BADREQUEST.</li>
 *   <li><b>Pro family:</b> 4,096 token minimum on the cached prefix.
 *       Same rejection semantics. Stage 4a slimming reduces tool
 *       schemas significantly - on lightly-tooled agents the slim
 *       prefix can dip below this threshold.</li>
 * </ul>
 *
 * <p>The traffic floor is a per-tenant configuration
 * ({@code gemini.cache.min-req-per-hour}, default 3) that callers pass
 * in. Below that we emit the {@code gemini.cache.below_min_threshold}
 * counter instead of POSTing a cache that will never be re-used.
 *
 * <p><b>Returns a decision, not a void.</b> Callers log the decision
 * with structured fields so Grafana can chart skip rates per reason -
 * a high {@code belowMinTokens} rate means Stage 4a trimmed too
 * aggressively for the tenant's tool mix; a high {@code belowMinTraffic}
 * rate means the tenant is a poor cache candidate regardless.
 */
public final class GeminiCacheGate {

    /** Minimum cached-prefix tokens required by Gemini Flash models. */
    public static final int FLASH_MIN_CACHED_TOKENS = 1024;

    /** Minimum cached-prefix tokens required by Gemini Pro models. */
    public static final int PRO_MIN_CACHED_TOKENS = 4096;

    /** Default traffic floor; can be overridden per deployment. */
    public static final int DEFAULT_MIN_REQ_PER_HOUR = 3;

    private GeminiCacheGate() {}

    /**
     * Evaluate cache eligibility.
     *
     * @param modelName        lowercased model ID (e.g. {@code
     *                         "gemini-1.5-flash"}, {@code
     *                         "gemini-1.5-pro"}). Case-insensitive.
     * @param prefixTokenCount estimated tokens of the static prefix
     *                         (system block 0 + slim tool schemas),
     *                         computed by the caller via the same
     *                         estimator used for rate-limit preflight.
     * @param recentReqPerHour observed traffic for this tenant +
     *                         model over the last hour; 0 means
     *                         "new tenant / no data".
     * @param minReqPerHour    configured traffic floor; pass
     *                         {@link #DEFAULT_MIN_REQ_PER_HOUR} if
     *                         unset.
     * @return a {@link Decision} carrying the outcome and - when
     *         skipped - the reason so the caller can emit a
     *         structured log line.
     */
    public static Decision decide(String modelName,
                                  int prefixTokenCount,
                                  int recentReqPerHour,
                                  int minReqPerHour) {
        if (modelName == null || modelName.isBlank()) {
            return Decision.skip(SkipReason.UNKNOWN_MODEL, "<null>", 0);
        }

        int floor = minCachedTokensFor(modelName);
        if (prefixTokenCount < floor) {
            return Decision.skip(SkipReason.BELOW_MIN_TOKENS, modelName, floor);
        }

        int traffic = Math.max(0, minReqPerHour);
        if (recentReqPerHour < traffic) {
            return Decision.skip(SkipReason.BELOW_MIN_TRAFFIC, modelName, traffic);
        }

        return Decision.eligible(modelName);
    }

    /**
     * Look up the prefix-token floor for a given Gemini model family.
     * Unknown models fall back to the stricter Pro floor so a typo or
     * a newly-added tier never accidentally undershoots the real API
     * minimum.
     */
    public static int minCachedTokensFor(String modelName) {
        if (modelName == null) return PRO_MIN_CACHED_TOKENS;
        // Locale.ROOT: avoid the Turkish-locale dotless-i pitfall where
        // "FLASH".toLowerCase() in tr-TR would yield "flasş" (wrong "i").
        // Gemini model strings are ASCII; ROOT locale keeps the
        // comparison deterministic across JVMs.
        String lower = modelName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("flash")) {
            return FLASH_MIN_CACHED_TOKENS;
        }
        return PRO_MIN_CACHED_TOKENS;
    }

    public enum SkipReason {
        /** Prefix too small for the model's API floor (1024 / 4096). */
        BELOW_MIN_TOKENS,
        /** Tenant traffic below the configured {@code min-req-per-hour}. */
        BELOW_MIN_TRAFFIC,
        /** Model string was null / blank; we refuse to guess a floor. */
        UNKNOWN_MODEL
    }

    /**
     * Decision carrier. {@code eligible == true} → caller should
     * create/refresh the cache; {@code false} → caller skips, logs
     * {@code reason} and {@code threshold}.
     */
    public record Decision(boolean eligible,
                           SkipReason reason,
                           String modelName,
                           int threshold) {

        static Decision eligible(String modelName) {
            return new Decision(true, null, modelName, 0);
        }

        static Decision skip(SkipReason reason, String modelName, int threshold) {
            return new Decision(false, reason, modelName, threshold);
        }
    }
}
