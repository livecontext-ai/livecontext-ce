package com.apimarketplace.auth.service;

import com.apimarketplace.agent.domain.TokenUsageConventions;
import com.apimarketplace.agent.domain.TokenUsageConventions.PromptShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard between the two places that decide how a provider counts its input
 * tokens.
 *
 * <p>{@link TokenUsageConventions} (agent-common) decides how to RE-EXPRESS a token
 * count when a response is re-stamped with a different billed identity.
 * {@code ModelPricingService.ProviderFamily} (auth-service) decides how to READ that
 * count when the bill is computed. They are separate classes in separate modules, and
 * they must agree on exactly one thing: which providers count the cache BESIDE the
 * prompt (the Anthropic family) and which count it as a subset INSIDE it (everyone
 * else).
 *
 * <p>Disagreement is silent and expensive in both directions. Teach one side that a new
 * Anthropic alias is Anthropic-shaped and not the other, and either the cache is
 * stripped out of the prompt and then billed from a field nobody filled (an 86%
 * under-bill, measured), or it stays in the prompt and is billed a second time on its
 * own line (a 3.8x over-bill, measured in production). Neither raises an error.
 *
 * <p>This test lives in auth-service because that is the only module that can see both
 * classes. It reads {@code ProviderFamily} by reflection because the enum is
 * package-private on purpose: the family split is a billing implementation detail, and
 * widening it just to be testable would invite callers to branch on it.
 */
@DisplayName("TokenUsageConventions and ProviderFamily agree on who is Anthropic-shaped")
class TokenUsageConventionsFamilyParityTest {

    /**
     * Every provider name either class knows about, plus the bridges and the
     * OpenAI-compatible vendors, so a rename on one side cannot slip through by simply
     * not being listed here.
     */
    private static final List<String> PROVIDERS = List.of(
            "anthropic", "claude", "claude-code",
            "openai", "codex", "azure-openai", "openai-compatible",
            "xai", "openrouter", "zai", "perplexity", "cohere",
            "deepseek", "google", "gemini", "gemini-cli",
            "mistral", "mistral-vibe", "moonshot", "qwen", "minimax",
            "some-vendor-nobody-has-added-yet", "");

    /** The billing family for {@code provider}, read out of the package-private enum. */
    private static String familyOf(String provider) throws Exception {
        Class<?> family = Class.forName("com.apimarketplace.auth.service.ModelPricingService$ProviderFamily");
        Method of = family.getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        return String.valueOf(of.invoke(null, provider));
    }

    private static boolean billingTreatsCacheAsAdditive(String family) {
        return family.startsWith("ANTHROPIC");
    }

    @Test
    @DisplayName("for every provider, both classes agree whether the cache is counted beside the prompt or inside it")
    void bothClassesAgreeOnTheCacheAxis() throws Exception {
        List<String> disagreements = new ArrayList<>();
        for (String provider : PROVIDERS) {
            boolean conversionSaysAdditive = TokenUsageConventions.cacheIsAdditiveToPrompt(provider);
            String family = familyOf(provider);
            boolean billingSaysAdditive = billingTreatsCacheAsAdditive(family);
            if (conversionSaysAdditive != billingSaysAdditive) {
                disagreements.add(String.format(
                        "%s: TokenUsageConventions says additive=%s, ProviderFamily.%s says additive=%s",
                        provider.isEmpty() ? "(blank)" : provider,
                        conversionSaysAdditive, family, billingSaysAdditive));
            }
        }
        assertThat(disagreements)
                .as("a provider whose two sides disagree is billed its cache twice, or not at all")
                .isEmpty();
    }

    @Test
    @DisplayName("the INCLUSIVE shape is exactly the family whose billing subtracts the cache back out")
    void inclusiveShapeMatchesTheSubtractingFamily() throws Exception {
        // ANTHROPIC_CLI is the one family whose billing does prompt - write - read. Any
        // provider the conversion marks ADDITIVE_INCLUSIVE must land there, or the
        // subtraction happens to numbers that were never inclusive (billing plain input
        // at ~0) - or fails to happen to numbers that are.
        for (String provider : PROVIDERS) {
            boolean inclusive = TokenUsageConventions.shapeOf(provider) == PromptShape.ADDITIVE_INCLUSIVE;
            boolean subtracts = "ANTHROPIC_CLI".equals(familyOf(provider));
            assertThat(inclusive)
                    .as("%s: conversion inclusive=%s but billing subtracts=%s", provider, inclusive, subtracts)
                    .isEqualTo(subtracts);
        }
    }

    @Test
    @DisplayName("an unknown provider is subset-shaped on both sides, so a vendor added tomorrow is never worse than before")
    void unknownProviderIsSubsetOnBothSides() throws Exception {
        assertThat(TokenUsageConventions.shapeOf("brand-new-vendor")).isEqualTo(PromptShape.SUBSET);
        assertThat(familyOf("brand-new-vendor")).isEqualTo("OTHER");
    }
}
