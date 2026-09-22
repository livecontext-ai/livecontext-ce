package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the execution-link cache double-charge (observed in production
 * 2026-09-16).
 *
 * <p>A model execution link bills {@code anthropic/claude-fable-5} while running it on
 * the {@code claude-code} bridge. The bridge reports {@code promptTokens} as
 * {@code input + cache_creation + cache_read}; the Anthropic API reports the three
 * separately. Re-stamping the billed identity without re-expressing the counts handed
 * billing an inclusive total under an API label, so the cache was charged once at full
 * input rate inside the prompt total and a second time on its own discounted line. The
 * production ledger showed 3.80x the cost of the identical run on the directly-selected
 * bridge - same model, same cache, only the label differed.
 */
@DisplayName("TokenUsageConventions")
class TokenUsageConventionsTest {

    @Nested
    @DisplayName("Which providers fold the cache into their prompt total")
    class ConventionLookup {

        @Test
        @DisplayName("only the Claude Code bridge reports an inclusive prompt total")
        void onlyClaudeCodeIsInclusive() {
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("claude-code")).isTrue();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("anthropic")).isFalse();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("codex")).isFalse();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("gemini-cli")).isFalse();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("mistral-vibe")).isFalse();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("openai")).isFalse();
        }

        @Test
        @DisplayName("the lookup is null-safe and tolerates casing and padding, because the value often arrives from a persisted snapshot")
        void lookupIsLenientOnShape() {
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache(null)).isFalse();
            assertThat(TokenUsageConventions.promptIncludesAdditiveCache("  Claude-Code ")).isTrue();
        }
    }

    @Nested
    @DisplayName("UsageInfo conversion")
    class UsageInfoConversion {

        /** Shape taken from a real production ledger row (6 plain input tokens). */
        private UsageInfo bridgeReported() {
            return UsageInfo.builder()
                    .promptTokens(98_319)
                    .completionTokens(1_915)
                    .totalTokens(100_234)
                    .cacheCreationInputTokens(18_945)
                    .cacheReadInputTokens(79_368)
                    .build();
        }

        @Test
        @DisplayName("bridge to API strips the cache out of the prompt total and leaves the cache counters alone")
        void inclusiveToExclusive() {
            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    bridgeReported(), "claude-code", "anthropic");

            assertThat(billed.promptTokens()).isEqualTo(6);
            assertThat(billed.completionTokens()).isEqualTo(1_915);
            // The cache counters ARE the billable cache; only their double-count inside
            // the prompt total was wrong.
            assertThat(billed.cacheCreationInputTokens()).isEqualTo(18_945);
            assertThat(billed.cacheReadInputTokens()).isEqualTo(79_368);
        }

        @Test
        @DisplayName("the total follows the prompt it is derived from, so prompt + completion stays coherent")
        void totalIsRecomputed() {
            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    bridgeReported(), "claude-code", "anthropic");

            assertThat(billed.totalTokens()).isEqualTo(1_921);
        }

        @Test
        @DisplayName("API to bridge folds the cache back in, so the conversion is reversible")
        void exclusiveToInclusiveIsTheInverse() {
            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    bridgeReported(), "claude-code", "anthropic");

            UsageInfo roundTrip = TokenUsageConventions.toBilledConvention(
                    billed, "anthropic", "claude-code");

            assertThat(roundTrip.promptTokens()).isEqualTo(98_319);
        }

        @Test
        @DisplayName("same convention on both sides returns the SAME instance - a codex link converts nothing")
        void sameConventionIsIdentity() {
            UsageInfo usage = bridgeReported();

            assertThat(TokenUsageConventions.toBilledConvention(usage, "codex", "openai")).isSameAs(usage);
            assertThat(TokenUsageConventions.toBilledConvention(usage, "anthropic", "anthropic")).isSameAs(usage);
            assertThat(TokenUsageConventions.toBilledConvention(usage, "claude-code", "claude-code")).isSameAs(usage);
        }

        @Test
        @DisplayName("a turn with no cache at all keeps every count, because there is nothing to move")
        void noCacheMeansNothingToMove() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(2_579).completionTokens(68).totalTokens(2_647).build();

            UsageInfo billed = TokenUsageConventions.toBilledConvention(usage, "claude-code", "anthropic");

            assertThat(billed.promptTokens()).isEqualTo(2_579);
            assertThat(billed.completionTokens()).isEqualTo(68);
            assertThat(billed.totalTokens()).isEqualTo(2_647);
        }

        @Test
        @DisplayName("counters that exceed the prompt total clamp at zero rather than going negative")
        void clampsAtZero() {
            UsageInfo inconsistent = UsageInfo.builder()
                    .promptTokens(100).completionTokens(10).totalTokens(110)
                    .cacheReadInputTokens(5_000).build();

            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    inconsistent, "claude-code", "anthropic");

            assertThat(billed.promptTokens()).isZero();
            assertThat(billed.totalTokens()).isEqualTo(10);
        }

        @Test
        @DisplayName("null usage passes through, because a failed turn reports none")
        void nullPassesThrough() {
            assertThat(TokenUsageConventions.toBilledConvention(
                    (UsageInfo) null, "claude-code", "anthropic")).isNull();
        }

        @Test
        @DisplayName("output-side counters ride along untouched - only the INPUT side has two conventions")
        void outputCountersAreCarriedOver() {
            UsageInfo usage = UsageInfo.builder()
                    .promptTokens(10_000).completionTokens(100).totalTokens(10_100)
                    .cacheReadInputTokens(9_000)
                    .reasoningTokens(56).thoughtsTokenCount(78).cachedContentTokenCount(90)
                    .build();

            UsageInfo billed = TokenUsageConventions.toBilledConvention(usage, "claude-code", "anthropic");

            assertThat(billed.reasoningTokens()).isEqualTo(56);
            assertThat(billed.thoughtsTokenCount()).isEqualTo(78);
            assertThat(billed.cachedContentTokenCount()).isEqualTo(90);
        }

        @Test
        @DisplayName("the cached portion lands under ONE name - the target's - never under both, which would count it twice")
        void cacheLandsUnderExactlyOneName() {
            // Google bills max(cachedTokens, cacheReadTokens) and Anthropic bills
            // cacheReadInputTokens; leaving a stale value in the other field is how the
            // same tokens get counted under two names.
            UsageInfo bridge = UsageInfo.builder()
                    .promptTokens(10_000).completionTokens(100).totalTokens(10_100)
                    .cacheCreationInputTokens(0).cacheReadInputTokens(9_000).build();

            UsageInfo toAnthropic = TokenUsageConventions.toBilledConvention(bridge, "claude-code", "anthropic");
            assertThat(toAnthropic.cacheReadInputTokens()).isEqualTo(9_000);
            assertThat(toAnthropic.cachedTokens()).isZero();

            UsageInfo toOpenAi = TokenUsageConventions.toBilledConvention(bridge, "claude-code", "openai");
            assertThat(toOpenAi.cachedTokens()).isEqualTo(9_000);
            assertThat(toOpenAi.cacheReadInputTokens()).isZero();
        }
    }

    /**
     * browser-use normalises every provider to "prompt total includes its cache reads,
     * cache creation is disjoint". Reported verbatim that shape billed the cached tokens
     * twice under an Anthropic label, and not at all under an OpenAI one (that family
     * reads {@code cachedTokens}, and the browser path filled {@code cacheReadTokens}).
     */
    @Nested
    @DisplayName("A prompt total that includes its cache reads (browser agent)")
    class PromptIncludingCacheReads {

        @Test
        @DisplayName("anthropic: the cache is pulled OUT of the prompt, because that family counts it beside")
        void anthropicGetsPlainInputPlusAnAdditiveCacheLine() {
            TokenUsageConventions.InputCounts counts = TokenUsageConventions
                    .fromPromptIncludingCacheReads(600_000, 500_000, 40_000, "anthropic");

            assertThat(counts.promptTokens()).isEqualTo(100_000);
            assertThat(counts.cacheReadTokens()).isEqualTo(500_000);
            assertThat(counts.cacheCreationTokens()).isEqualTo(40_000);
            // cachedTokens is the OTHER family's field and must stay empty, or the same
            // tokens would be counted under two names.
            assertThat(counts.cachedTokens()).isZero();
        }

        @Test
        @DisplayName("openai: the cache stays INSIDE the prompt and moves to cachedTokens, the field that family actually reads")
        void openAiGetsASubsetUnderTheRightName() {
            TokenUsageConventions.InputCounts counts = TokenUsageConventions
                    .fromPromptIncludingCacheReads(600_000, 500_000, 0, "openai");

            assertThat(counts.promptTokens()).isEqualTo(600_000);
            assertThat(counts.cachedTokens()).isEqualTo(500_000);
            assertThat(counts.cacheReadTokens()).isZero();
        }

        @Test
        @DisplayName("google and any unknown provider are treated as subset reporters too")
        void subsetIsTheDefaultForEveryoneElse() {
            for (String provider : new String[] {"google", "gemini", "deepseek", "xai", "moonshot", null}) {
                TokenUsageConventions.InputCounts counts = TokenUsageConventions
                        .fromPromptIncludingCacheReads(1_000, 400, 0, provider);
                assertThat(counts.promptTokens()).as(String.valueOf(provider)).isEqualTo(1_000);
                assertThat(counts.cachedTokens()).as(String.valueOf(provider)).isEqualTo(400);
            }
        }

        @Test
        @DisplayName("claude-code is Anthropic-shaped as well, so a bridge-billed browser session converts the same way")
        void claudeCodeIsAdditiveToo() {
            assertThat(TokenUsageConventions.cacheIsAdditiveToPrompt("claude-code")).isTrue();
            assertThat(TokenUsageConventions.cacheIsAdditiveToPrompt("anthropic")).isTrue();
            assertThat(TokenUsageConventions.cacheIsAdditiveToPrompt("openai")).isFalse();
        }

        @Test
        @DisplayName("a cache read larger than the prompt total clamps instead of producing a negative or over-counted prompt")
        void clampsInconsistentCounters() {
            TokenUsageConventions.InputCounts anthropic = TokenUsageConventions
                    .fromPromptIncludingCacheReads(100, 5_000, 0, "anthropic");
            assertThat(anthropic.promptTokens()).isZero();

            TokenUsageConventions.InputCounts openai = TokenUsageConventions
                    .fromPromptIncludingCacheReads(100, 5_000, 0, "openai");
            assertThat(openai.cachedTokens()).isEqualTo(100);
        }

        @Test
        @DisplayName("a session with no cache at all is unchanged for every provider")
        void noCacheIsAPassThrough() {
            assertThat(TokenUsageConventions.fromPromptIncludingCacheReads(5_000, 0, 0, "anthropic").promptTokens())
                    .isEqualTo(5_000);
            assertThat(TokenUsageConventions.fromPromptIncludingCacheReads(5_000, 0, 0, "openai").promptTokens())
                    .isEqualTo(5_000);
        }
    }

    /**
     * The conversion has two axes and getting only the first right is worse than getting
     * neither. Billing reads the cache from {@code cacheReadInputTokens} for the Anthropic
     * family and from {@code cachedTokens} for everyone else, so stripping the cache out
     * of the prompt for a provider that then looks in an empty field bills it NOWHERE.
     * On the production-shaped row below, billed as openai at $2/$10, that was the
     * difference between 19.2 credits and the correct 136.4.
     */
    @Nested
    @DisplayName("Across provider families (the field the cache must arrive in)")
    class CrossFamilyConversion {

        private UsageInfo bridgeReported() {
            return UsageInfo.builder()
                    .promptTokens(98_319).completionTokens(1_915).totalTokens(100_234)
                    .cacheCreationInputTokens(18_945).cacheReadInputTokens(79_368).build();
        }

        @Test
        @DisplayName("bridge billed as openai KEEPS the cache inside the prompt and names it cachedTokens, the field that family bills from")
        void inclusiveToSubsetKeepsTheCacheBillable() {
            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    bridgeReported(), "claude-code", "openai");

            // Nothing is stripped: the OpenAI family discounts the cached SUBSET of the
            // prompt, so the prompt must still contain it.
            assertThat(billed.promptTokens()).isEqualTo(98_319);
            assertThat(billed.cachedTokens()).isEqualTo(79_368);
            // And it must NOT also appear under the Anthropic name, or Google - which reads
            // max(cachedTokens, cacheReadTokens) - would count it twice.
            assertThat(billed.cacheReadInputTokens()).isZero();
        }

        @Test
        @DisplayName("cache-creation tokens are folded into the prompt for a subset-billed provider, because that family has no slot that prices a write")
        void cacheWriteIsFoldedRatherThanDropped() {
            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    bridgeReported(), "claude-code", "openai");

            // plain 6 + write 18,945 + read 79,368 = 98,319. Dropping the write would lose
            // 18,945 real tokens; folding bills them at full input rate, which is the
            // closest available truth and is bounded.
            assertThat(billed.cacheCreationInputTokens()).isZero();
            assertThat(billed.promptTokens())
                    .isEqualTo(6 + billed.cachedTokens() + 18_945);
        }

        @Test
        @DisplayName("every subset-billed family converts identically - openai, google, deepseek and an unknown vendor")
        void everySubsetFamilyGetsTheSameShape() {
            for (String billedAs : new String[] {"openai", "google", "deepseek", "xai", "moonshot"}) {
                UsageInfo billed = TokenUsageConventions.toBilledConvention(
                        bridgeReported(), "claude-code", billedAs);
                assertThat(billed.promptTokens()).as(billedAs).isEqualTo(98_319);
                assertThat(billed.cachedTokens()).as(billedAs).isEqualTo(79_368);
            }
        }

        @Test
        @DisplayName("an openai-shaped producer billed as anthropic pulls its cached subset OUT of the prompt, the mirror case")
        void subsetToAdditiveExclusive() {
            UsageInfo codexReported = UsageInfo.builder()
                    .promptTokens(50_000).completionTokens(500).totalTokens(50_500)
                    .cachedTokens(40_000).build();

            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    codexReported, "codex", "anthropic");

            assertThat(billed.promptTokens()).isEqualTo(10_000);
            assertThat(billed.cacheReadInputTokens()).isEqualTo(40_000);
            assertThat(billed.cachedTokens()).isZero();
        }

        @Test
        @DisplayName("an openai-shaped producer billed on the claude-code bridge keeps an inclusive prompt, which is what that family subtracts from")
        void subsetToAdditiveInclusive() {
            UsageInfo codexReported = UsageInfo.builder()
                    .promptTokens(50_000).completionTokens(500).totalTokens(50_500)
                    .cachedTokens(40_000).build();

            UsageInfo billed = TokenUsageConventions.toBilledConvention(
                    codexReported, "codex", "claude-code");

            assertThat(billed.promptTokens()).isEqualTo(50_000);
            assertThat(billed.cacheReadInputTokens()).isEqualTo(40_000);
        }

        /** Total input tokens the counts describe, whichever shape they are in. */
        private long totalInput(UsageInfo u) {
            long prompt = u.promptTokens() == null ? 0 : u.promptTokens();
            boolean inclusive = prompt >= orZero(u.cacheCreationInputTokens()) + orZero(u.cacheReadInputTokens())
                    && orZero(u.cacheCreationInputTokens()) + orZero(u.cacheReadInputTokens()) > 0
                    && orZero(u.cachedTokens()) == 0;
            // Subset shapes and the inclusive bridge shape already contain their cache;
            // only the exclusive Anthropic shape counts it beside the prompt.
            return prompt + (inclusive ? 0 : orZero(u.cacheCreationInputTokens()) + orZero(u.cacheReadInputTokens()));
        }

        private long orZero(Integer v) {
            return v == null ? 0 : v;
        }

        @Test
        @DisplayName("a round trip between the two Anthropic shapes is EXACT - both carry a cache-write counter")
        void roundTripBetweenAdditiveShapesIsExact() {
            UsageInfo toApi = TokenUsageConventions.toBilledConvention(bridgeReported(), "claude-code", "anthropic");
            UsageInfo andBack = TokenUsageConventions.toBilledConvention(toApi, "anthropic", "claude-code");

            assertThat(andBack.promptTokens()).isEqualTo(98_319);
            assertThat(andBack.cacheCreationInputTokens()).isEqualTo(18_945);
            assertThat(andBack.cacheReadInputTokens()).isEqualTo(79_368);
        }

        @Test
        @DisplayName("a round trip THROUGH a subset shape is lossy on the write/plain split, and that is by construction - but it never loses or invents a token")
        void roundTripThroughASubsetShapeIsLossyButConserving() {
            // A subset-billed provider has no counter that prices a cache WRITE, so the
            // write is folded into the prompt and cannot be recovered afterwards. This is
            // not a defect to fix - there is no field to put it in - but it IS a reason
            // never to chain conversions: convert once, from the producer to the payer.
            UsageInfo viaOpenAi = TokenUsageConventions.toBilledConvention(bridgeReported(), "claude-code", "openai");
            UsageInfo thenAnthropic = TokenUsageConventions.toBilledConvention(viaOpenAi, "openai", "anthropic");
            UsageInfo direct = TokenUsageConventions.toBilledConvention(bridgeReported(), "claude-code", "anthropic");

            assertThat(thenAnthropic.promptTokens()).isNotEqualTo(direct.promptTokens());
            assertThat(thenAnthropic.cacheCreationInputTokens()).isZero();
            // What survives is the only thing that must: no token is created or destroyed.
            assertThat(totalInput(thenAnthropic)).isEqualTo(totalInput(direct));
            assertThat(totalInput(direct)).isEqualTo(98_319);
        }
    }

    @Nested
    @DisplayName("Map conversion (transport DTOs)")
    class MapConversion {

        private Map<String, Object> bridgeUsage() {
            Map<String, Object> usage = new HashMap<>();
            usage.put("promptTokens", 98_319);
            usage.put("completionTokens", 1_915);
            usage.put("totalTokens", 100_234);
            usage.put("cacheCreationInputTokens", 18_945);
            usage.put("cacheReadInputTokens", 79_368);
            return usage;
        }

        @Test
        @DisplayName("converts the prompt total and recomputes the total, leaving the cache counters intact")
        void convertsTheMap() {
            Map<String, Object> billed = TokenUsageConventions.toBilledConvention(
                    bridgeUsage(), "claude-code", "anthropic");

            assertThat(billed).containsEntry("promptTokens", 6);
            assertThat(billed).containsEntry("totalTokens", 1_921);
            assertThat(billed).containsEntry("cacheCreationInputTokens", 18_945);
            assertThat(billed).containsEntry("cacheReadInputTokens", 79_368);
        }

        @Test
        @DisplayName("does not mutate the caller's map - a shared usage map is read elsewhere for observability")
        void doesNotMutateTheInput() {
            Map<String, Object> original = bridgeUsage();

            TokenUsageConventions.toBilledConvention(original, "claude-code", "anthropic");

            assertThat(original).containsEntry("promptTokens", 98_319);
        }

        @Test
        @DisplayName("keys the conversion does not know are preserved")
        void preservesUnknownKeys() {
            Map<String, Object> usage = bridgeUsage();
            usage.put("someFutureCounter", 7);

            Map<String, Object> billed = TokenUsageConventions.toBilledConvention(
                    usage, "claude-code", "anthropic");

            assertThat(billed).containsEntry("someFutureCounter", 7);
        }

        @Test
        @DisplayName("a map without totalTokens does not grow one")
        void doesNotInventATotal() {
            Map<String, Object> usage = new HashMap<>();
            usage.put("promptTokens", 1_000);
            usage.put("cacheReadInputTokens", 400);

            Map<String, Object> billed = TokenUsageConventions.toBilledConvention(
                    usage, "claude-code", "anthropic");

            assertThat(billed).containsEntry("promptTokens", 600);
            assertThat(billed).doesNotContainKey("totalTokens");
        }

        @Test
        @DisplayName("null and empty maps pass through untouched")
        void nullAndEmptyPassThrough() {
            assertThat(TokenUsageConventions.toBilledConvention(
                    (Map<String, Object>) null, "claude-code", "anthropic")).isNull();
            Map<String, Object> empty = Map.of();
            assertThat(TokenUsageConventions.toBilledConvention(empty, "claude-code", "anthropic"))
                    .isSameAs(empty);
        }

        @Test
        @DisplayName("same convention returns the same instance, so an unlinked run allocates nothing")
        void sameConventionIsIdentity() {
            Map<String, Object> usage = bridgeUsage();
            assertThat(TokenUsageConventions.toBilledConvention(usage, "codex", "openai")).isSameAs(usage);
        }
    }
}
