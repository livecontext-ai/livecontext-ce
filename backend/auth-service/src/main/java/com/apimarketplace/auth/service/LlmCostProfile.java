package com.apimarketplace.auth.service;

/**
 * Typical token workloads, one per shape of LLM work the product actually runs.
 * They exist so a model picker can answer "what will this model cost me?" BEFORE
 * the call, in the same credits the ledger will later debit.
 *
 * <h2>Why several profiles and not one</h2>
 * The three differ by two orders of magnitude, and the gap is structural rather
 * than statistical: a chat turn carries the whole agent context on every message,
 * while a classify step sends one prompt and returns a label. A single "average"
 * would be true of almost no one and would read as either alarming or dishonest
 * depending on which surface showed it. The picker therefore says which shape of
 * work it is pricing.
 *
 * <h2>The chat profile carries CACHE tokens, and that is the whole point</h2>
 * It did not, and the omission was worth a factor of two. The cache weights used
 * to differ per provider FAMILY, so a profile carrying cache tokens made the
 * estimate depend on which family a model belonged to rather than on its price.
 * V491 ended that: a cached token is priced by the MODEL's own published cache
 * price, exactly like an input token.
 *
 * <p>Leaving it cache-free was not the neutral choice it looked like. It priced
 * every cached token at the FULL input rate, and on Anthropic <b>a cache WRITE
 * costs 1.25x the input rate</b> while a read costs a fortieth of it. A real chat
 * turn writes tens of thousands of tokens into the cache, so the cache-free
 * profile did not over-state as its comment claimed: it UNDER-stated, and the
 * picker quoted about half of what a message really costs.
 *
 * <h2>Where the chat numbers come from</h2>
 * Measured on production over the 30 days to 2026-09-16: 62 Anthropic chat turns,
 * MEDIAN 6 plain input tokens, 24,601 cache writes, 85,816 cache reads and 2,112
 * output tokens. That median message costs 579 credits on Claude Fable 5.1, which
 * is the order of magnitude a user sees for one "hello" to a configured agent.
 * (It was 869 at the 2.0 margin lever; the counts did not move, because a token
 * median is a measurement and only what a token COSTS is a pricing decision.)
 *
 * <p>The counts below are that median scaled by <b>1.6106</b>, which prices the
 * profile at 933 credits on Claude Fable 5.1: a short exchange rather than a
 * single message. That scale was chosen on 2026-09-16 to hold the published price
 * at 1,400 while the margin ROSE; the cut to 1.333333 on 2026-09-17 deliberately
 * did not re-scale it back, because a cut exists so a user pays less for the same
 * work. The scale is a product decision about the UNIT; the
 * proportions between the four classes are the measurement and must not be
 * tuned, because they are what makes the estimate track a model's real cost
 * instead of the ratio of its input rates.
 *
 * <p>Guardrail and classify stay cache-free on purpose. A classify prompt is
 * 1,200 tokens, under the 1,024-token floor where a cache entry is worth writing
 * at all, and no production measurement shows guardrail prompts being cached.
 * Inventing a split for them would trade a figure derived from production for one
 * derived from an assumption, on items costing single-digit credits. Their
 * numbers are still the 2026-09-03 medians: guardrail check 37 credits (n=7),
 * classify step 2.9 credits (n=2,649).
 *
 * <h2>The safety property a reader should check before editing</h2>
 * A cached token is priced by the MODEL's own published rate; where the catalogue
 * has none, by its provider family's weight - the same weight the ledger falls
 * back to, and the picker is handed it rather than inventing one
 * ({@code LlmCostEstimateService} publishes it as {@code cacheFallback}). A stored
 * rate of ZERO counts as no price on both sides: a cached token is never free.
 *
 * <p>A model with no prompt caching at all is a separate shape, not a fallback:
 * see {@link #breakdownWithoutCache()}. Its whole prompt costs the input rate,
 * which is the dearest way to run this workload and the class whose quote moved
 * most when these counts arrived - do not read it as the unaffected case.
 *
 * <p>Re-measure whenever the platform's typical workload changes. The pricing page
 * mirrors the TOKEN COUNTS of {@link #CHAT_CONVERSATION} and {@link #CLASSIFY_STEP}
 * (see {@code frontend/lib/billing/pricing-constants.ts}, {@code CREDIT_WORKLOADS}), so
 * a change here is a change there; it prices them on a lightweight catalogue model of
 * its own choosing, so the CREDIT figures on that page are not these.
 */
public enum LlmCostProfile {

    /** A short chat exchange, agent context included: what a picker quotes. */
    CHAT_CONVERSATION("chatConversation", 10, 39_600, 138_200, 3_400),

    GUARDRAIL_CHECK("guardrailCheck", 16_000, 0, 0, 100),

    CLASSIFY_STEP("classifyStep", 1_200, 0, 0, 60);

    private final String key;
    private final int inputTokens;
    private final int cacheWriteTokens;
    private final int cacheReadTokens;
    private final int outputTokens;

    LlmCostProfile(String key, int inputTokens, int cacheWriteTokens, int cacheReadTokens, int outputTokens) {
        this.key = key;
        this.inputTokens = inputTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.outputTokens = outputTokens;
    }

    public String key() {
        return key;
    }

    /** Tokens sent as PLAIN input: neither served from a cache nor written to one. */
    public int inputTokens() {
        return inputTokens;
    }

    /** Tokens written INTO the cache, the dearest class on every Anthropic model. */
    public int cacheWriteTokens() {
        return cacheWriteTokens;
    }

    /** Tokens served FROM the cache, the cheapest class wherever a cache exists. */
    public int cacheReadTokens() {
        return cacheReadTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    /** Every prompt-side token of the workload, whatever a family calls them. */
    public int promptTokensTotal() {
        return inputTokens + cacheWriteTokens + cacheReadTokens;
    }

    /**
     * The SAME workload as the billing code reads it for {@code billedProvider}.
     *
     * <p>The four counts above are one conversation described once, in the Anthropic
     * API's convention because that is the only one in which they are disjoint. They are
     * not what {@link ModelPricingService#calculateCost} reads for every provider:
     * providers disagree about what a prompt total contains, and billing interprets the
     * numbers from the provider NAME (see {@code TokenUsageConventions}, which does this
     * conversion for real executions and is pinned against
     * {@code ModelPricingService.ProviderFamily}).
     *
     * <ul>
     *   <li><b>Anthropic API</b>: prompt is the plain input, cache write and read sit
     *       beside it and are billed off their own rates.</li>
     *   <li><b>Claude Code bridge</b>: same two counters, but the prompt total already
     *       contains them.</li>
     *   <li><b>Everyone else</b>: no counter prices a cache WRITE, so a first-send is
     *       plain prompt input; only the re-read part is a cached SUBSET of the prompt.</li>
     * </ul>
     *
     * <p>Handing the Anthropic shape to an OpenAI-family model instead would price a
     * whole conversation as ten plain tokens, because that family reads neither cache
     * counter. The estimate would then be off by three orders of magnitude, silently.
     */
    public LlmTokenBreakdown breakdownFor(String billedProvider) {
        return switch (ModelPricingService.ProviderFamily.of(billedProvider)) {
            case ANTHROPIC_API ->
                    new LlmTokenBreakdown(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens, 0, 0);
            case ANTHROPIC_CLI ->
                    new LlmTokenBreakdown(promptTokensTotal(), outputTokens, cacheWriteTokens, cacheReadTokens, 0, 0);
            default ->
                    new LlmTokenBreakdown(promptTokensTotal(), outputTokens, 0, 0, cacheReadTokens, 0);
        };
    }

    /**
     * The same workload on a model that does NOT cache: every prompt token is sent in
     * full, every time, and no cache rate of any kind enters the bill.
     *
     * <p>This is the honest shape for a catalogue row whose model has no prompt caching,
     * and it is what a picker must quote there. Its FORM is the two-coefficient one that
     * preceded cache tokens - every prompt token at the input rate - but not its figure:
     * {@link #promptTokensTotal()} is 177,810 where the cache-free profile it replaced
     * carried 15,000, so a non-caching model's quote rose about twelve-fold on
     * 2026-09-16. That is the measured cost of carrying a whole agent context with no
     * cache to carry it in, and it is the largest move any class of model took.
     */
    public LlmTokenBreakdown breakdownWithoutCache() {
        return LlmTokenBreakdown.of(promptTokensTotal(), outputTokens);
    }
}
