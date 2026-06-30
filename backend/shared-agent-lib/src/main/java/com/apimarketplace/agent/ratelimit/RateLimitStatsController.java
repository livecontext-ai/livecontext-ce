package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for monitoring rate limit statistics.
 * Only enabled when rate limiting is active.
 *
 * Endpoints:
 * - GET /api/rate-limits/stats - Get stats for all providers
 * - GET /api/rate-limits/stats/{provider} - Get stats for specific provider
 * - GET /api/rate-limits/stats/{provider}?tenantId=xxx - Get stats for specific tenant
 * - GET /api/rate-limits/config - Get current configuration
 * - GET /api/rate-limits/health - Get health status
 */
@RestController
@RequestMapping("/api/rate-limits")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitStatsController {

    private final ProviderRateLimiter rateLimiter;
    private final RateLimitConfig config;
    private final LLMProviderFactory providerFactory;

    /**
     * Get rate limit statistics for all configured providers.
     */
    @GetMapping("/stats")
    public Map<String, Object> getAllStats() {
        Map<String, Object> result = new HashMap<>();

        List<String> providerNames = providerFactory.getAvailableProviderNames();
        Map<String, Object> providerStats = new HashMap<>();

        for (String providerName : providerNames) {
            providerStats.put(providerName, getProviderStats(providerName, null));
        }

        result.put("providers", providerStats);
        result.put("enabled", config.isEnabled());
        result.put("strategy", config.getStrategy().name());
        result.put("mode", config.getDefaultMode().name());

        return result;
    }

    /**
     * Get rate limit statistics for a specific provider.
     * Optionally include tenantId to get tenant-specific stats.
     */
    @GetMapping("/stats/{provider}")
    public Map<String, Object> getStats(
            @PathVariable String provider,
            @RequestParam(required = false) String tenantId) {
        return getProviderStats(provider, tenantId);
    }

    /**
     * Get current rate limit configuration.
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("strategy", config.getStrategy().name());
        result.put("defaultMode", config.getDefaultMode().name());
        result.put("maxWaitTimeSeconds", config.getMaxWaitTimeSeconds());

        Map<String, Object> providers = new HashMap<>();
        for (Map.Entry<String, RateLimitConfig.ProviderLimit> entry : config.getProviders().entrySet()) {
            RateLimitConfig.ProviderLimit limit = entry.getValue();
            Map<String, Object> limitInfo = new HashMap<>();

            // Global limits
            limitInfo.put("tokensPerMinute", limit.getTokensPerMinute());
            limitInfo.put("requestsPerMinute", limit.getRequestsPerMinute());
            limitInfo.put("hasGlobalTokenLimit", limit.hasGlobalTokenLimit());
            limitInfo.put("hasGlobalRequestLimit", limit.hasGlobalRequestLimit());

            // Per-tenant limits
            limitInfo.put("tokensPerMinutePerTenant", limit.getTokensPerMinutePerTenant());
            limitInfo.put("requestsPerMinutePerTenant", limit.getRequestsPerMinutePerTenant());
            limitInfo.put("hasTenantTokenLimit", limit.hasTenantTokenLimit());
            limitInfo.put("hasTenantRequestLimit", limit.hasTenantRequestLimit());

            providers.put(entry.getKey(), limitInfo);
        }
        result.put("providers", providers);

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("tokensPerMinute", config.getDefaults().getTokensPerMinute());
        defaults.put("requestsPerMinute", config.getDefaults().getRequestsPerMinute());
        defaults.put("tokensPerMinutePerTenant", config.getDefaults().getTokensPerMinutePerTenant());
        defaults.put("requestsPerMinutePerTenant", config.getDefaults().getRequestsPerMinutePerTenant());
        result.put("defaults", defaults);

        return result;
    }

    /**
     * Reset statistics for a specific provider (for testing/debugging).
     */
    @PostMapping("/reset/{provider}")
    public Map<String, String> resetStats(@PathVariable String provider) {
        // Note: Current implementation doesn't support explicit reset
        // Stats naturally expire after 60 seconds
        return Map.of(
            "message", "Stats will naturally expire after 60 seconds. Manual reset not implemented.",
            "provider", provider
        );
    }

    /**
     * Get health status - checks if any provider is close to limits.
     */
    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        Map<String, Object> result = new HashMap<>();
        List<String> providerNames = providerFactory.getAvailableProviderNames();

        int warningCount = 0;
        int criticalCount = 0;

        Map<String, String> providerStatus = new HashMap<>();

        for (String providerName : providerNames) {
            ProviderRateLimiter.UsageStats stats = rateLimiter.getGlobalUsageStats(providerName);

            double tokenUsage = stats.getTokenUsagePercent();
            double requestUsage = stats.getRequestUsagePercent();
            double maxUsage = Math.max(tokenUsage, requestUsage);

            String status;
            if (maxUsage >= 90) {
                status = "CRITICAL";
                criticalCount++;
            } else if (maxUsage >= 70) {
                status = "WARNING";
                warningCount++;
            } else {
                status = "OK";
            }

            providerStatus.put(providerName, status);
        }

        String overallHealth;
        if (criticalCount > 0) {
            overallHealth = "CRITICAL";
        } else if (warningCount > 0) {
            overallHealth = "WARNING";
        } else {
            overallHealth = "OK";
        }

        result.put("status", overallHealth);
        result.put("providers", providerStatus);
        result.put("warningCount", warningCount);
        result.put("criticalCount", criticalCount);
        result.put("strategy", config.getStrategy().name());
        result.put("mode", config.getDefaultMode().name());

        return result;
    }

    /**
     * Get formatted stats for a provider, optionally for a specific tenant.
     */
    private Map<String, Object> getProviderStats(String providerName, String tenantId) {
        RateLimitConfig.ProviderLimit limit = config.getProviderLimit(providerName);
        Map<String, Object> result = new HashMap<>();

        // Global stats (always included)
        ProviderRateLimiter.UsageStats globalStats = rateLimiter.getGlobalUsageStats(providerName);
        result.put("global", formatStats(globalStats, limit.hasGlobalTokenLimit(), limit.hasGlobalRequestLimit()));

        // Tenant stats (if requested and strategy supports it)
        if (tenantId != null && (config.getStrategy() == RateLimitStrategy.PER_TENANT ||
                                  config.getStrategy() == RateLimitStrategy.HYBRID)) {
            ProviderRateLimiter.UsageStats tenantStats = rateLimiter.getTenantUsageStats(providerName, tenantId);
            result.put("tenant", formatStats(tenantStats, limit.hasTenantTokenLimit(), limit.hasTenantRequestLimit()));
            result.put("tenantId", tenantId);
        }

        // Overall status based on the most constrained limit
        double maxUsage = calculateMaxUsage(globalStats, tenantId != null ?
            rateLimiter.getTenantUsageStats(providerName, tenantId) : null);
        result.put("status", getStatusFromUsage(maxUsage));

        return result;
    }

    /**
     * Format stats into a response object.
     */
    private Map<String, Object> formatStats(ProviderRateLimiter.UsageStats stats,
                                             boolean hasTokenLimit, boolean hasRequestLimit) {
        Map<String, Object> result = new HashMap<>();

        // Token usage
        Map<String, Object> tokenStats = new HashMap<>();
        tokenStats.put("current", stats.currentTokens());
        tokenStats.put("limit", stats.tokenLimit());
        tokenStats.put("usagePercent", Math.round(stats.getTokenUsagePercent() * 100) / 100.0);
        tokenStats.put("remaining", stats.getRemainingTokens());
        tokenStats.put("enabled", hasTokenLimit);
        result.put("tokens", tokenStats);

        // Request usage
        Map<String, Object> requestStats = new HashMap<>();
        requestStats.put("current", stats.currentRequests());
        requestStats.put("limit", stats.requestLimit());
        requestStats.put("usagePercent", Math.round(stats.getRequestUsagePercent() * 100) / 100.0);
        requestStats.put("remaining", stats.getRemainingRequests());
        requestStats.put("enabled", hasRequestLimit);
        result.put("requests", requestStats);

        return result;
    }

    /**
     * Calculate max usage across global and tenant stats.
     */
    private double calculateMaxUsage(ProviderRateLimiter.UsageStats globalStats,
                                      ProviderRateLimiter.UsageStats tenantStats) {
        double maxUsage = Math.max(globalStats.getTokenUsagePercent(), globalStats.getRequestUsagePercent());

        if (tenantStats != null) {
            maxUsage = Math.max(maxUsage, tenantStats.getTokenUsagePercent());
            maxUsage = Math.max(maxUsage, tenantStats.getRequestUsagePercent());
        }

        return maxUsage;
    }

    /**
     * Get status string from usage percentage.
     */
    private String getStatusFromUsage(double maxUsage) {
        if (maxUsage >= 90) {
            return "CRITICAL";
        } else if (maxUsage >= 70) {
            return "WARNING";
        } else {
            return "OK";
        }
    }
}
