package com.apimarketplace.agent.config;

/**
 * Clamps a requested LLM output-token budget ({@code max_tokens}) to a model's
 * real output ceiling, so a high platform default (e.g. 16000) never exceeds
 * what a given model accepts - which would otherwise make the provider reject
 * the request with HTTP 400 ("max_tokens too large").
 *
 * <p>This guard exists because the platform default {@code max_tokens} is shared
 * across every provider/model, but output ceilings differ widely: Claude Opus
 * 4.x accepts 128K, but DeepSeek-chat caps at 8192. Sending 16000 to
 * DeepSeek-chat (the default model on CE / local) returns a 400.
 *
 * <p>Resolution:
 * <ul>
 *   <li>{@code requested == null} ⇒ {@code null} - leave the provider's own
 *       default in place; some call paths intentionally omit {@code max_tokens}.</li>
 *   <li>known model cap ({@code modelMaxOutputTokens > 0}) ⇒
 *       {@code min(requested, cap)}.</li>
 *   <li>unknown cap ({@code null} or ≤ 0) ⇒ {@code min(requested,
 *       UNKNOWN_MODEL_OUTPUT_CAP)} - a safe floor every modern chat model
 *       accepts (it is DeepSeek-chat's output cap, the lowest among the
 *       platform's default models). This keeps the request valid even when the
 *       model catalog carries no per-model output limit, e.g. a CE deployment
 *       whose catalog has not been synced.</li>
 * </ul>
 *
 * <p>Source of the cap is the model catalog's {@code maxOutputTokens}
 * (LiteLLM/OpenRouter feed + admin overrides), resolved by the caller before
 * the {@link com.apimarketplace.agent.loop.AgentLoopContext} is built.
 */
public final class MaxTokensClamp {

    /**
     * Output-token ceiling assumed when a model's real cap is unknown. 8192 is
     * DeepSeek-chat's cap - the lowest among the platform's default models - and
     * is accepted by essentially every modern chat model, so it never triggers a
     * 400 even with no catalog data. Residual edge case: a legacy model whose real
     * cap is below 8192 (e.g. a 4096-output model) AND which carries no catalog
     * {@code maxOutputTokens} could still 400 - fix that by syncing the catalog so
     * its real cap is known, not by lowering this floor (which would needlessly cap
     * every uncatalogued high-capacity model).
     */
    public static final int UNKNOWN_MODEL_OUTPUT_CAP = 8192;

    private MaxTokensClamp() {
    }

    /**
     * @param requested            caller-requested {@code max_tokens} (nullable)
     * @param modelMaxOutputTokens the model's real output ceiling from the
     *                             catalog, or {@code null}/≤0 when unknown
     * @return the requested value capped to the model's ceiling (or the safe
     *         floor when unknown); {@code null} iff {@code requested} is null
     */
    public static Integer clamp(Integer requested, Integer modelMaxOutputTokens) {
        if (requested == null) {
            return null;
        }
        int cap = (modelMaxOutputTokens != null && modelMaxOutputTokens > 0)
                ? modelMaxOutputTokens
                : UNKNOWN_MODEL_OUTPUT_CAP;
        return Math.min(requested, cap);
    }
}
