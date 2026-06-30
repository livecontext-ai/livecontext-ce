package com.apimarketplace.agent.ratelimit;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for RateLimitStatsController - REST API for rate limit statistics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitStatsController")
class RateLimitStatsControllerTest {

    @Mock
    private ProviderRateLimiter rateLimiter;

    @Mock
    private LLMProviderFactory providerFactory;

    private RateLimitConfig config;
    private RateLimitStatsController controller;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        controller = new RateLimitStatsController(rateLimiter, config, providerFactory);
    }

    private ProviderRateLimiter.UsageStats createUsageStats(int currentTokens, int currentRequests, int tokenLimit, int requestLimit) {
        return new ProviderRateLimiter.UsageStats(currentTokens, tokenLimit, currentRequests, requestLimit);
    }

    @Nested
    @DisplayName("getAllStats()")
    class GetAllStatsTests {

        @Test
        @DisplayName("should return stats for all providers")
        void shouldReturnAllProviderStats() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of("openai", "anthropic"));
            when(rateLimiter.getGlobalUsageStats(anyString())).thenReturn(createUsageStats(1000, 10, 90000, 3500));

            Map<String, Object> result = controller.getAllStats();

            assertThat(result).containsKey("providers");
            assertThat(result).containsEntry("enabled", true);
            assertThat(result).containsEntry("strategy", "GLOBAL");
            assertThat(result).containsEntry("mode", "WAIT");
        }

        @Test
        @DisplayName("should return empty providers when no providers available")
        void shouldReturnEmptyProviders() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of());

            Map<String, Object> result = controller.getAllStats();

            @SuppressWarnings("unchecked")
            Map<String, Object> providers = (Map<String, Object>) result.get("providers");
            assertThat(providers).isEmpty();
        }
    }

    @Nested
    @DisplayName("getStats()")
    class GetStatsTests {

        @Test
        @DisplayName("should return stats for specific provider")
        void shouldReturnProviderStats() {
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(5000, 50, 90000, 3500));

            Map<String, Object> result = controller.getStats("openai", null);

            assertThat(result).containsKey("global");
            assertThat(result).containsKey("status");
        }

        @Test
        @DisplayName("should include tenant stats when tenantId provided and strategy supports it")
        void shouldIncludeTenantStats() {
            config.setStrategy(RateLimitStrategy.PER_TENANT);
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(5000, 50, 90000, 3500));
            when(rateLimiter.getTenantUsageStats("openai", "tenant-1")).thenReturn(createUsageStats(1000, 10, 10000, 100));

            Map<String, Object> result = controller.getStats("openai", "tenant-1");

            assertThat(result).containsKey("global");
            assertThat(result).containsKey("tenant");
            assertThat(result).containsEntry("tenantId", "tenant-1");
        }

        @Test
        @DisplayName("should not include tenant stats when strategy is GLOBAL")
        void shouldNotIncludeTenantStatsForGlobal() {
            config.setStrategy(RateLimitStrategy.GLOBAL);
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(5000, 50, 90000, 3500));

            Map<String, Object> result = controller.getStats("openai", "tenant-1");

            assertThat(result).containsKey("global");
            assertThat(result).doesNotContainKey("tenant");
        }
    }

    @Nested
    @DisplayName("getConfig()")
    class GetConfigTests {

        @Test
        @DisplayName("should return current configuration")
        void shouldReturnConfig() {
            Map<String, Object> result = controller.getConfig();

            assertThat(result).containsEntry("enabled", true);
            assertThat(result).containsEntry("strategy", "GLOBAL");
            assertThat(result).containsEntry("defaultMode", "WAIT");
            assertThat(result).containsEntry("maxWaitTimeSeconds", 60);
            assertThat(result).containsKey("providers");
            assertThat(result).containsKey("defaults");
        }

        @Test
        @DisplayName("should include provider limits in config")
        void shouldIncludeProviderLimits() {
            RateLimitConfig.ProviderLimit openaiLimit = new RateLimitConfig.ProviderLimit(90000, 3500, 10000, 100);
            config.setProviders(Map.of("openai", openaiLimit));

            Map<String, Object> result = controller.getConfig();

            @SuppressWarnings("unchecked")
            Map<String, Object> providers = (Map<String, Object>) result.get("providers");
            assertThat(providers).containsKey("openai");

            @SuppressWarnings("unchecked")
            Map<String, Object> openaiConfig = (Map<String, Object>) providers.get("openai");
            assertThat(openaiConfig.get("tokensPerMinute")).isEqualTo(90000);
            assertThat(openaiConfig.get("requestsPerMinute")).isEqualTo(3500);
            assertThat(openaiConfig.get("hasGlobalTokenLimit")).isEqualTo(true);
            assertThat(openaiConfig.get("hasTenantTokenLimit")).isEqualTo(true);
        }

        @Test
        @DisplayName("should include default limits")
        void shouldIncludeDefaults() {
            Map<String, Object> result = controller.getConfig();

            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) result.get("defaults");
            assertThat(defaults).containsKey("tokensPerMinute");
            assertThat(defaults).containsKey("requestsPerMinute");
            assertThat(defaults).containsKey("tokensPerMinutePerTenant");
            assertThat(defaults).containsKey("requestsPerMinutePerTenant");
        }
    }

    @Nested
    @DisplayName("resetStats()")
    class ResetStatsTests {

        @Test
        @DisplayName("should return informational message")
        void shouldReturnMessage() {
            Map<String, String> result = controller.resetStats("openai");

            assertThat(result).containsKey("message");
            assertThat(result).containsEntry("provider", "openai");
            assertThat(result.get("message")).contains("60 seconds");
        }
    }

    @Nested
    @DisplayName("getHealth()")
    class GetHealthTests {

        @Test
        @DisplayName("should return OK status when usage is low")
        void shouldReturnOkStatus() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of("openai"));
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(1000, 10, 90000, 3500));

            Map<String, Object> result = controller.getHealth();

            assertThat(result.get("status")).isEqualTo("OK");
            assertThat(result.get("warningCount")).isEqualTo(0);
            assertThat(result.get("criticalCount")).isEqualTo(0);
        }

        @Test
        @DisplayName("should return WARNING status when usage is between 70-90%")
        void shouldReturnWarningStatus() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of("openai"));
            // 75% token usage: 67500/90000
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(67500, 10, 90000, 3500));

            Map<String, Object> result = controller.getHealth();

            assertThat(result.get("status")).isEqualTo("WARNING");
            assertThat(result.get("warningCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("should return CRITICAL status when usage exceeds 90%")
        void shouldReturnCriticalStatus() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of("openai"));
            // 95% token usage: 85500/90000
            when(rateLimiter.getGlobalUsageStats("openai")).thenReturn(createUsageStats(85500, 10, 90000, 3500));

            Map<String, Object> result = controller.getHealth();

            assertThat(result.get("status")).isEqualTo("CRITICAL");
            assertThat(result.get("criticalCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("should return OK when no providers")
        void shouldReturnOkWhenNoProviders() {
            when(providerFactory.getAvailableProviderNames()).thenReturn(List.of());

            Map<String, Object> result = controller.getHealth();

            assertThat(result.get("status")).isEqualTo("OK");
        }
    }
}
