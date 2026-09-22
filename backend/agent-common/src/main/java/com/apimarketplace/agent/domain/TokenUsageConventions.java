package com.apimarketplace.agent.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Re-expresses a token count reported by one provider in another provider's counting
 * convention.
 *
 * <p><b>Why this has to exist.</b> Providers disagree about what {@code promptTokens}
 * means. The Anthropic API reports {@code input_tokens} EXCLUDING the cache, with
 * {@code cache_creation_input_tokens} and {@code cache_read_input_tokens} as two
 * additive counters beside it. The Claude Code bridge reports the SUM of the three as
 * its prompt total and repeats the cache counters separately. Everyone else reports a
 * cached SUBSET already inside the prompt total, under yet another field name. Billing
 * interprets the numbers from the provider NAME stamped on the result, so a model
 * execution link - which bills {@code anthropic/claude-fable-5} while running it on
 * {@code claude-code} - hands the billing layer one provider's numbers under another
 * provider's label. The cache is then charged twice, or not at all. Measured on
 * production, the first case made one run cost 3.80x what the same run cost when the
 * same bridge was selected directly.
 *
 * <p><b>The conversion has TWO axes, and getting only one right is worse than getting
 * neither.</b> It is not enough to ask whether the prompt total contains the cache; the
 * billed provider also decides WHICH FIELD the cache has to arrive in, because
 * {@code ModelPricingService} reads {@code cacheReadInputTokens} for the Anthropic
 * family and {@code cachedTokens} for everyone else. Stripping the cache out of the
 * prompt for a provider that then looks for it in a field nobody filled bills it
 * nowhere: for a link billed {@code openai} and executed on {@code claude-code}, that
 * turned a 58% over-bill into an 86% UNDER-bill, silently. So the conversion decomposes
 * into a canonical breakdown and recomposes into the target's shape, rather than
 * patching the prompt total.
 *
 * <p>Which shape a provider uses is driven by its NAME, never by inspecting the numbers.
 * Inspection cannot work: a direct Anthropic call with a large plain input and a small
 * cache read is numerically indistinguishable from an inclusive bridge total.
 */
public final class TokenUsageConventions {

    private TokenUsageConventions() {}

    /**
     * How a provider lays its input tokens out.
     *
     * <p>{@link #ADDITIVE_EXCLUSIVE} and {@link #ADDITIVE_INCLUSIVE} differ only in
     * whether the cache is already inside the prompt total; both report cache write and
     * cache read as their own counters, and both are billed off those counters. They
     * must stay aligned with {@code ModelPricingService.ProviderFamily}: the Anthropic
     * names map to the two additive shapes and everything else to {@link #SUBSET}.
     * {@code TokenUsageConventionsFamilyParityTest} fails the build if the two ever
     * disagree about which provider is Anthropic-shaped.
     */
    public enum PromptShape {
        /** Anthropic API: prompt is plain input, cache write/read counted beside it. */
        ADDITIVE_EXCLUSIVE,
        /** Claude Code bridge: prompt is plain + write + read, cache ALSO counted beside it. */
        ADDITIVE_INCLUSIVE,
        /** Everyone else: the cached portion is a subset of the prompt, named cachedTokens. */
        SUBSET
    }

    /** The shape {@code provider} reports and is billed in. Null or unknown is SUBSET. */
    public static PromptShape shapeOf(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "claude-code" -> PromptShape.ADDITIVE_INCLUSIVE;
            case "anthropic", "claude" -> PromptShape.ADDITIVE_EXCLUSIVE;
            default -> PromptShape.SUBSET;
        };
    }

    /** True when {@code provider} reports a prompt total that already contains the cache. */
    public static boolean promptIncludesAdditiveCache(String provider) {
        return shapeOf(provider) == PromptShape.ADDITIVE_INCLUSIVE;
    }

    /**
     * True when {@code provider} counts cache tokens BESIDE the prompt total and is
     * billed off those counters, rather than off a cached subset inside it.
     */
    public static boolean cacheIsAdditiveToPrompt(String provider) {
        return shapeOf(provider) != PromptShape.SUBSET;
    }

    /**
     * The canonical breakdown every shape decomposes into: input tokens the provider
     * charged in full, tokens it charged at its cache-write price, and tokens it charged
     * at its cache-read price. Shape-independent, so it is the only place the two
     * conventions meet.
     */
    private record Breakdown(long plainInput, long cacheWrite, long cacheRead) {}

    /** Input-side counters in the billed provider's own convention. */
    public record InputCounts(long promptTokens, long cacheReadTokens,
                              long cachedTokens, long cacheCreationTokens) {}

    private static Breakdown decompose(PromptShape shape, long prompt, long cacheWrite,
                                       long cacheRead, long cached) {
        long p = Math.max(0, prompt);
        long w = Math.max(0, cacheWrite);
        // The cached portion travels under either name depending on who reported it, and
        // never under both, so the larger is the one that was filled.
        long r = Math.max(Math.max(0, cacheRead), Math.max(0, cached));
        return switch (shape) {
            case ADDITIVE_EXCLUSIVE -> new Breakdown(p, w, r);
            case ADDITIVE_INCLUSIVE -> new Breakdown(Math.max(0, p - w - r), w, r);
            // A subset reporter has no separate write counter; whatever is not cached is
            // plain input, and the cached part is clamped because it is a subset of the
            // prompt, so a provider that over-reports must not drive plain input negative.
            case SUBSET -> new Breakdown(Math.max(0, p - Math.min(r, p)), w, Math.min(r, p));
        };
    }

    private static InputCounts recompose(PromptShape shape, Breakdown b) {
        return switch (shape) {
            case ADDITIVE_EXCLUSIVE ->
                    new InputCounts(b.plainInput(), b.cacheRead(), 0, b.cacheWrite());
            case ADDITIVE_INCLUSIVE ->
                    new InputCounts(b.plainInput() + b.cacheWrite() + b.cacheRead(),
                            b.cacheRead(), 0, b.cacheWrite());
            // A subset-billed provider has no slot that prices a cache WRITE, so those
            // tokens are folded into the prompt and billed at full input rate. That is the
            // closest available truth (Anthropic charges 1.25x input for a write, so this
            // slightly under-bills) and it is bounded, unlike dropping them.
            case SUBSET ->
                    new InputCounts(b.plainInput() + b.cacheWrite() + b.cacheRead(),
                            0, b.cacheRead(), 0);
        };
    }

    /**
     * Re-express {@code usage} - as reported by {@code reportedBy} - in the convention
     * {@code billedAs} is read with. Returns the same instance when both providers share
     * a shape, which is every run except one moved by a model execution link.
     *
     * @param usage      the counts the execution actually produced; {@code null} passes through
     * @param reportedBy the provider that PRODUCED these numbers (the execution target)
     * @param billedAs   the provider the result will be stamped with (the billed identity)
     */
    public static UsageInfo toBilledConvention(UsageInfo usage, String reportedBy, String billedAs) {
        if (usage == null) {
            return usage;
        }
        PromptShape from = shapeOf(reportedBy);
        PromptShape to = shapeOf(billedAs);
        // Same guard as the map variant: with no prompt total there is nothing to
        // re-express, and converting would replace whatever the object DID carry with zeros.
        if (from == to || usage.promptTokens() == null) {
            return usage;
        }
        InputCounts counts = recompose(to, decompose(from,
                orZero(usage.promptTokens()), orZero(usage.cacheCreationInputTokens()),
                orZero(usage.cacheReadInputTokens()), orZero(usage.cachedTokens())));
        int completion = orZero(usage.completionTokens());
        return UsageInfo.builder()
                .promptTokens((int) counts.promptTokens())
                .completionTokens(usage.completionTokens())
                // Every shape defines the total as prompt + completion (cache counters are
                // reported beside it), so it follows the prompt it is derived from rather
                // than keeping a figure computed in the other convention.
                .totalTokens((int) counts.promptTokens() + completion)
                .cacheCreationInputTokens((int) counts.cacheCreationTokens())
                .cacheReadInputTokens((int) counts.cacheReadTokens())
                .cachedTokens((int) counts.cachedTokens())
                .reasoningTokens(usage.reasoningTokens())
                .thoughtsTokenCount(usage.thoughtsTokenCount())
                .cachedContentTokenCount(usage.cachedContentTokenCount())
                .build();
    }

    /**
     * Map-shaped variant for the transport DTOs, which carry usage as a plain
     * {@code Map<String, Object>} with the same key names {@link UsageInfo} uses.
     * Unknown keys are preserved untouched and the caller's map is never mutated.
     */
    public static Map<String, Object> toBilledConvention(Map<String, Object> usage,
                                                         String reportedBy, String billedAs) {
        if (usage == null || usage.isEmpty()) {
            return usage;
        }
        PromptShape from = shapeOf(reportedBy);
        PromptShape to = shapeOf(billedAs);
        // No prompt total means there is nothing to re-express, and inventing one from the
        // absent keys would zero whatever the map DID carry - a usage map holding only a
        // total had its total destroyed before this guard existed.
        if (from == to || !usage.containsKey("promptTokens")) {
            return usage;
        }
        InputCounts counts = recompose(to, decompose(from,
                intValue(usage.get("promptTokens")), intValue(usage.get("cacheCreationInputTokens")),
                intValue(usage.get("cacheReadInputTokens")), intValue(usage.get("cachedTokens"))));
        Map<String, Object> out = new HashMap<>(usage);
        out.put("promptTokens", (int) counts.promptTokens());
        // Only ever write a cache key the map already had or that now carries a value, so a
        // conversion does not sprout zero counters onto a turn that had no cache at all.
        putCount(out, usage, "cacheCreationInputTokens", counts.cacheCreationTokens());
        putCount(out, usage, "cacheReadInputTokens", counts.cacheReadTokens());
        putCount(out, usage, "cachedTokens", counts.cachedTokens());
        if (usage.containsKey("totalTokens")) {
            out.put("totalTokens", (int) counts.promptTokens() + intValue(usage.get("completionTokens")));
        }
        return out;
    }

    /**
     * The input-side counters a producer should report when its own prompt total
     * INCLUDES the cache reads but EXCLUDES any cache creation.
     *
     * <p>That is the shape browser-use normalises every provider to, and it matches no
     * billing convention on its own: reporting it verbatim under an Anthropic label bills
     * the cached tokens once inside the prompt and again on the cache line, while
     * reporting the cache under {@code cacheReadTokens} for an OpenAI-family model lands
     * in a field that family does not read, so the discount never applies.
     *
     * @param promptIncludingReads the producer's prompt total, cache reads included
     * @param cacheRead            the cached portion of that total
     * @param cacheCreation        cache-creation tokens, already disjoint from the total
     * @param billedProvider       the provider the result will be billed as
     */
    public static InputCounts fromPromptIncludingCacheReads(long promptIncludingReads, long cacheRead,
                                                            long cacheCreation, String billedProvider) {
        long prompt = Math.max(0, promptIncludingReads);
        long read = Math.min(Math.max(0, cacheRead), prompt);
        Breakdown breakdown = new Breakdown(prompt - read, Math.max(0, cacheCreation), read);
        return recompose(shapeOf(billedProvider), breakdown);
    }

    private static void putCount(Map<String, Object> out, Map<String, Object> source,
                                 String key, long value) {
        if (value != 0 || source.containsKey(key)) {
            out.put(key, (int) value);
        }
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
