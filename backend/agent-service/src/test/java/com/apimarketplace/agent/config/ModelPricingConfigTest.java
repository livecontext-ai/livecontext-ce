package com.apimarketplace.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ModelPricingConfig correctly loads YAML pricing entries,
 * especially for model IDs containing dots (e.g. gemini-2.5-pro).
 */
class ModelPricingConfigTest {

    @Test
    void shouldLoadDottedModelPricingFromYaml() throws IOException {
        // Load the actual application.yml
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));

        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addFirst(source);
        }

        // Bind to ModelPricingConfig
        Binder binder = Binder.get(env);
        ModelPricingConfig config = binder.bind("ai.agent", ModelPricingConfig.class).get();

        System.out.println("=== Pricing keys (" + config.getPricing().size() + ") ===");
        config.getPricing().forEach((k, v) -> System.out.println("  " + k + " → input=" + v.getInput() + ", output=" + v.getOutput()));

        System.out.println("\n=== Rankings keys (" + config.getRankings().size() + ") ===");
        config.getRankings().forEach((k, v) -> System.out.println("  " + k + " → " + v));

        System.out.println("\n=== Tiers keys (" + config.getTiers().size() + ") ===");
        config.getTiers().forEach((k, v) -> System.out.println("  " + k + " → " + v));

        System.out.println("\n=== Rate-limits keys (" + config.getRateLimits().size() + ") ===");
        config.getRateLimits().forEach((k, v) -> System.out.println(
                "  " + k + " → tpm=" + v.getTpm() + ", itpm=" + v.getItpm() + ", otpm=" + v.getOtpm()
                        + ", rpm=" + v.getRpm() + ", tpm/t=" + v.getTpmPerTenant() + ", rpm/t=" + v.getRpmPerTenant()));

        // Test dotted model IDs (bracket notation)
        assertNotNull(config.getPricingForModel("gemini-2.5-pro"),
                "gemini-2.5-pro should be loaded via [gemini-2.5-pro] bracket notation");
        assertEquals(1.25, config.getPricingForModel("gemini-2.5-pro").getInput(), 0.001);

        assertNotNull(config.getPricingForModel("gemini-2.5-flash"),
                "gemini-2.5-flash should be loaded");
        assertEquals(0.30, config.getPricingForModel("gemini-2.5-flash").getInput(), 0.001);

        assertNotNull(config.getPricingForModel("gemini-3.1-pro-preview"),
                "gemini-3.1-pro-preview should be loaded");

        assertNotNull(config.getPricingForModel("gemini-3.1-flash"),
                "gemini-3.1-flash should be loaded");

        assertNotNull(config.getPricingForModel("gpt-5.4"),
                "gpt-5.4 should be loaded via [gpt-5.4] bracket notation");

        // Non-dotted keys
        assertNotNull(config.getPricingForModel("claude-sonnet-4-6"),
                "claude-sonnet-4-6 (no dots) should be loaded");

        // Rankings
        assertEquals(21, config.getRankingForModel("gemini-2.5-pro"));
        assertEquals(4, config.getRankingForModel("claude-sonnet-4-6"));

        // Tiers
        assertEquals("high", config.getTierForModel("gemini-2.5-pro"));
        assertEquals("high", config.getTierForModel("claude-sonnet-4-6"));

        // Rate limits - exercises the full Spring binding pipeline against the real YAML
        // including dotted-model bracket notation ([gpt-5.4]) and Anthropic itpm/otpm split.
        ModelPricingConfig.ModelRateLimitInfo gpt54 = config.getRateLimitForModel("gpt-5.4");
        assertNotNull(gpt54, "gpt-5.4 rate limit should be loaded via [gpt-5.4] bracket notation");
        assertEquals(4_000_000, gpt54.getTpm());
        assertEquals(10_000, gpt54.getRpm());
        assertEquals(200_000, gpt54.getTpmPerTenant());
        assertEquals(500, gpt54.getRpmPerTenant());

        ModelPricingConfig.ModelRateLimitInfo opus = config.getRateLimitForModel("claude-opus-4-6");
        assertNotNull(opus, "claude-opus-4-6 rate limit should be loaded");
        assertNull(opus.getTpm(), "Anthropic uses itpm/otpm split, explicit tpm should be null");
        assertEquals(800_000, opus.getItpm());
        assertEquals(160_000, opus.getOtpm());
        // effectiveTpm collapses the split into aggregate used by the sliding window.
        assertEquals(960_000, opus.effectiveTpm());

        ModelPricingConfig.ModelRateLimitInfo gemini25Pro = config.getRateLimitForModel("gemini-2.5-pro");
        assertNotNull(gemini25Pro, "gemini-2.5-pro rate limit should be loaded (bracket notation)");
        assertEquals(1_000_000, gemini25Pro.getTpm());
        // Retuned 2026-04-29 in application.yml to align with Google T1 preview ~25 RPM.
        assertEquals(20, gemini25Pro.getRpm());
    }
}
