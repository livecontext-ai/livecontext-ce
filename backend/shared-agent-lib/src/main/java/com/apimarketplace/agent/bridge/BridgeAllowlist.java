package com.apimarketplace.agent.bridge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hand-curated source of truth for which models each local-CLI bridge
 * actually routes. This list is the ONLY authority for bridge catalog
 * content - feed sync ({@code ModelCatalogSyncService}) skips bridges
 * entirely, and migrations derive their seed content from here.
 *
 * <p><b>Why hand-curated?</b> As of 2026-04, none of the 4 CLIs exposes a
 * programmatic {@code --list-models} / {@code /models} endpoint (Anthropic
 * has an open feature request, Claude Code issue #12612). Auto-derivation
 * from LiteLLM was audited by 3 independent reviewers and scored 5-6/10
 * because LiteLLM taxonomy does not match CLI routing tables for 3 of 4
 * bridges (codex subset, gemini-cli preview lifecycle, mistral-vibe uses
 * config-file aliases that differ from LiteLLM ids).
 *
 * <p><b>How to update.</b> When a CLI announces support for a new model in
 * its official release notes, add the id to the right {@link #MODELS} entry
 * and ship a new {@code Vxxx__bridge_catalog_sync.sql} migration that
 * reconciles {@code agent.model_config_overrides} with this map.
 * Expected cadence: 3-5 updates per year per CLI.
 *
 * <p><b>Auto-discovery (pattern layer).</b> A NEW version of an already-routed
 * family (e.g. {@code claude-opus-4-8} once {@code 4-7} is listed) no longer
 * needs a code change: {@link #DISCOVERY_PATTERNS} lets {@code BridgeModelDeriver}
 * pick it up from the LiteLLM feed on the next sync, like a direct-API provider.
 * {@link #MODELS} stays the curated floor (and the only path for
 * {@code mistral-vibe}, whose ids are not in the feed). You only edit
 * {@link #MODELS} + a migration when adding a model that does NOT match an
 * existing pattern (a new family, or a mistral alias).
 *
 * <p><b>Id conventions per bridge</b>:
 * <ul>
 *   <li><b>claude-code</b> - Anthropic API model ids, no suffix. The
 *       {@code claude-adapter.mjs} adapter passes the model verbatim to
 *       {@code claude --model &lt;id&gt;}; the CLI accepts both full names
 *       (e.g. {@code claude-opus-4-7}) and aliases (e.g. {@code opus}).</li>
 *   <li><b>codex</b> - ids must match codex CLI exactly; the adapter passes
 *       verbatim to {@code codex --model &lt;id&gt;}. ChatGPT-account auth
 *       rejects unsupported ids with a typed error.</li>
 *   <li><b>gemini-cli</b> - ids must match gemini-cli {@code --model}.
 *       Preview variants have lifecycle (2026-03-09 shutdown of Gemini 3
 *       Pro Preview) - keep this list in sync with release notes.</li>
 *   <li><b>mistral-vibe</b> - ids are {@code active_model} aliases from
 *       {@code ~/.vibe/config.toml}, NOT LiteLLM's dated ids. See
 *       {@link #LITELLM_LOOKUP_ALIAS} for the LiteLLM↔Vibe id map used
 *       when enriching the row with LiteLLM metadata (context, capabilities).</li>
 * </ul>
 */
public final class BridgeAllowlist {

    private BridgeAllowlist() {}

    /** The four bridges wired in the platform. Order = UI {@code display_order}. */
    public static final Set<String> BRIDGE_PROVIDERS =
            Set.of("claude-code", "codex", "gemini-cli", "mistral-vibe");

    /**
     * Per-bridge model allowlist. {@code (provider, modelId)} pairs
     * authorized for this bridge. Every pair listed here:
     * <ul>
     *   <li>appears in the model picker under its provider slot;</li>
     *   <li>gets {@code provider_kind='bridge'}, {@code price=0};</li>
     *   <li>is sourced from the CLI's own published docs (see links below).</li>
     * </ul>
     */
    public static final Map<String, Set<String>> MODELS = Map.of(
            // https://code.claude.com/docs/en/model-config
            // https://platform.claude.com/docs/en/about-claude/models/overview
            "claude-code",  Set.of(
                    "claude-opus-4-7",
                    "claude-opus-4-6",
                    "claude-sonnet-4-6",
                    "claude-sonnet-4-5",
                    "claude-haiku-4-5"
            ),

            // https://developers.openai.com/codex/models
            "codex",        Set.of(
                    "gpt-5.4",
                    "gpt-5.4-mini",
                    "gpt-5.3-codex",
                    "gpt-5.2"
            ),

            // https://geminicli.com/docs/cli/model/
            // https://github.com/google-gemini/gemini-cli (release notes)
            "gemini-cli",   Set.of(
                    "gemini-3.1-pro-preview",
                    "gemini-3-flash-preview",
                    "gemini-2.5-pro",
                    "gemini-2.5-flash"
            ),

            // https://github.com/mistralai/mistral-vibe (config.toml active_model)
            // https://mistral.ai/news/devstral-2-vibe-cli
            "mistral-vibe", Set.of(
                    "devstral-2",
                    "devstral-small-2"
            )
    );

    /**
     * For enrichment only: when the bridge id doesn't match a LiteLLM feed
     * entry verbatim (mistral-vibe uses {@code devstral-2}, LiteLLM uses
     * {@code mistral/devstral-2512}), this map declares the lookup key so
     * the sync service can still pull context_window / supports_* flags
     * from LiteLLM for the bridge row.
     *
     * <p>Empty entries mean "bridge id matches LiteLLM id directly" - no
     * lookup needed.
     */
    public static final Map<String, String> LITELLM_LOOKUP_ALIAS = Map.of(
            "devstral-2",       "devstral-latest",
            "devstral-small-2", "devstral-small-latest",
            // gpt-5.3-codex is mode=responses in LiteLLM (not chat), so our
            // feed parser rejects it. Prices match gpt-5.3-chat-latest
            // exactly per the 2026-04 LiteLLM snapshot, so we enrich from
            // there. Confirm on each release.
            "gpt-5.3-codex",    "gpt-5.3-chat-latest"
    );

    /**
     * Version-flexible auto-discovery layer, added ON TOP of {@link #MODELS}
     * (the curated floor) - not a replacement. At feed-sync time
     * ({@code BridgeModelDeriver}), any model from the underlying cloud
     * provider whose id matches a bridge's pattern is auto-derived as a bridge
     * model, so a NEW version of an already-routed family (e.g. a fresh
     * {@code claude-opus-4-8}) surfaces under {@code claude-code} on the next
     * "Refresh from providers" - exactly like the direct-API providers - with
     * no code change. The id is used verbatim as the bridge model id.
     *
     * <p><b>Why this is NOT the auto-derive approach that was audited 5/6/5
     * and rejected</b> (see {@code V128__bridge_catalog_allowlist_v1.sql}): the
     * rejected design took the cloud provider's ENTIRE chat catalog (filter too
     * loose). These patterns are TIGHT - they only match the exact routable
     * families the CLI accepts, so unrelated cloud models never leak in. The
     * curation simply moves from "every id" to "the routable family", staying
     * in lockstep with {@link #MODELS}: {@code BridgeAllowlistDiscoveryPatternTest}
     * asserts every seeded id matches its bridge pattern AND that patterns never
     * match across bridges.
     *
     * <p><b>Routability caveat</b>: a model can appear in the feed before the
     * CLI binary on the bridge host is upgraded to route it - discovery surfaces
     * it in the catalog but {@code cli --model <id>} would fail until the binary
     * catches up. Unlike direct APIs (where the platform key routes any current
     * model), this gap is irreducible because no CLI exposes {@code --list-models}.
     * Operators verify CLI support out of band.
     *
     * <p><b>mistral-vibe is deliberately absent</b>: its model ids are
     * {@code ~/.vibe/config.toml} aliases ({@code devstral-2}) that do NOT
     * appear in the LiteLLM feed (which carries {@code devstral-2512} /
     * {@code devstral-latest}). A new mistral generation cannot be discovered
     * from the feed without a new {@link #LITELLM_LOOKUP_ALIAS} entry, so
     * mistral-vibe stays on the explicit {@link #MODELS} list.
     */
    public static final Map<String, List<Pattern>> DISCOVERY_PATTERNS = Map.of(
            // claude-code → anthropic. The Claude Code CLI routes every Anthropic
            // API id of the opus/sonnet/haiku families. Matches "claude-opus-4-8",
            // "claude-sonnet-4-6"; rejects legacy "claude-3-opus-…" (wrong shape)
            // and dated pins: the minor-version group is capped at 2 digits, so
            // 6-8 digit date suffixes never match -- neither the 3-segment
            // "claude-opus-4-7-20260416" (also deduped upstream) NOR the
            // 2-segment "claude-opus-4-20250514" (which is NOT deduped, since it
            // has no canonical "claude-opus-4" twin in the feed).
            "claude-code", List.of(Pattern.compile("^claude-(opus|sonnet|haiku)-\\d+(-\\d{1,2})?$")),

            // codex → openai. Tight to the GPT-5.x reasoning family codex routes.
            // Matches "gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex", "gpt-5.2";
            // rejects "gpt-5.3-chat-latest", "gpt-4o", embeddings, etc.
            "codex", List.of(Pattern.compile("^gpt-5\\.\\d+(-mini|-codex)?$")),

            // gemini-cli → google. Matches "gemini-2.5-pro", "gemini-3-flash-preview",
            // "gemini-3.1-pro-preview"; rejects "gemini-1.5-flash-8b", embeddings.
            "gemini-cli", List.of(Pattern.compile("^gemini-\\d+(\\.\\d+)?-(pro|flash)(-preview)?$"))
    );

    /**
     * True when {@code modelId} matches one of {@code provider}'s
     * {@link #DISCOVERY_PATTERNS}. Returns false for providers with no pattern
     * (mistral-vibe, non-bridges) and for null inputs.
     */
    public static boolean matchesDiscoveryPattern(String provider, String modelId) {
        if (provider == null || modelId == null) return false;
        List<Pattern> patterns = DISCOVERY_PATTERNS.get(provider);
        if (patterns == null) return false;
        for (Pattern p : patterns) {
            if (p.matcher(modelId).matches()) return true;
        }
        return false;
    }

    /** Convenience: is this provider one of the 4 local-CLI bridges? */
    public static boolean isBridgeProvider(String provider) {
        return provider != null && BRIDGE_PROVIDERS.contains(provider);
    }

    /** Convenience: is {@code (provider, modelId)} allow-listed? */
    public static boolean isAllowed(String provider, String modelId) {
        Set<String> models = MODELS.get(provider);
        return models != null && models.contains(modelId);
    }
}
