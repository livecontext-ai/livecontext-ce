package com.apimarketplace.agent.bridge;

import com.apimarketplace.agent.domain.BridgeProviders;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /**
     * The four bridges wired in the platform. Order = UI {@code display_order}.
     *
     * <p>Re-exported from {@link BridgeProviders#NAMES} in {@code agent-common}
     * so modules outside the agent runtime (the marketplace's CE-exclusive
     * detector, for one) read the same list without depending on this jar.
     */
    public static final Set<String> BRIDGE_PROVIDERS = BridgeProviders.NAMES;

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
                    "claude-fable-5",
                    "claude-opus-4-7",
                    "claude-opus-4-6",
                    "claude-sonnet-4-6",
                    "claude-sonnet-4-5",
                    "claude-haiku-4-5"
            ),

            // https://developers.openai.com/codex/models
            // CURATED-ONLY (no discovery pattern - see DISCOVERY_PATTERNS note).
            // OpenAI ships the 5.6 generation as three codenamed tiers, NOT a
            // bare "gpt-5.6": sol=frontier, terra=balanced/everyday, luna=fast &
            // affordable (analogous to normal/mini/nano). A bare "gpt-5.6" is a
            // real openai *API* id but is NOT routable via Codex with a ChatGPT
            // account (the CLI returns a typed 400 "not supported when using
            // Codex with a ChatGPT account"), so it must never be exposed here.
            // The 6 generation keeps the codename shape (astra), which is exactly
            // why no pattern can be reintroduced: a rule able to guess "astra"
            // would also match the bare gpt-6 ids Codex refuses. Added by hand
            // with V484, like every codex entry.
            // This is the exact set the Codex CLI model list returns for a
            // ChatGPT Plus account, verified out of band.
            "codex",        Set.of(
                    "gpt-6-astra",
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna",
                    "gpt-5.5",
                    "gpt-5.4",
                    "gpt-5.4-mini",
                    "gpt-5.4-nano",
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
     * The underlying cloud provider each bridge routes to, for the CATALOG: the sync
     * ({@code BridgeModelDeriver}) derives its bridge rows from the cloud provider
     * named here, and the admin Models panel reads the INVERSE to offer a one-click
     * execution link from a billed cloud model to its CLI. Deliberately NOT shared with
     * {@code ChatCompactionOrchestrator}'s same-shaped map, which answers a different
     * question (which provider family's COLD-zone cap applies) and differs on purpose:
     * it sends {@code mistral-vibe} to {@code openai}, where this one sends it to
     * {@code mistral}.
     *
     * <p>{@code gemini-cli} maps to our {@code google} slug (LiteLLM calls it
     * {@code gemini}); {@code mistral-vibe} maps to {@code mistral}, whose ids it
     * only partly shares (see {@link #LITELLM_LOOKUP_ALIAS}).
     */
    public static final Map<String, String> BRIDGE_TO_CLOUD_PROVIDER = Map.of(
            "claude-code",  "anthropic",
            "codex",        "openai",
            "gemini-cli",   "google",
            "mistral-vibe", "mistral"
    );

    /** Inverse of {@link #BRIDGE_TO_CLOUD_PROVIDER} - one bridge per cloud provider. */
    private static final Map<String, String> CLOUD_PROVIDER_TO_BRIDGE =
            BRIDGE_TO_CLOUD_PROVIDER.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

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
     *
     * <p><b>codex is also deliberately absent</b> (removed 2026-07): OpenAI's
     * Codex-routable set is NOT derivable from the openai feed. The feed carries
     * many bare {@code gpt-5.x} ids the Codex CLI cannot route (a bare
     * {@code gpt-5.6} returns a typed 400 with a ChatGPT account) and names the
     * 5.6 tiers with codenames ({@code -sol}/{@code -terra}/{@code -luna}) that
     * a numeric pattern cannot tell apart from the unroutable bare ids. A
     * pattern here fabricated a phantom {@code codex/gpt-5.6} that failed at
     * runtime, so codex now stays fully curated (like mistral-vibe): new codex
     * models ship via a {@link #MODELS} entry + a bridge_catalog_sync migration,
     * verified against the Codex CLI model list out of band.
     */
    public static final Map<String, List<Pattern>> DISCOVERY_PATTERNS = Map.of(
            // claude-code → anthropic. The Claude Code CLI routes every Anthropic
            // API id of the opus/sonnet/haiku/fable families. Matches
            // "claude-opus-4-8", "claude-sonnet-4-6", "claude-fable-5"; rejects
            // legacy "claude-3-opus-…" (wrong shape) and dated pins: the
            // minor-version group is capped at 2 digits, so 6-8 digit date
            // suffixes never match -- neither the 3-segment
            // "claude-opus-4-7-20260416" (also deduped upstream) NOR the
            // 2-segment "claude-opus-4-20250514" (which is NOT deduped, since it
            // has no canonical "claude-opus-4" twin in the feed).
            // "mythos" is deliberately NOT routed: claude-mythos-5 is gated to
            // approved orgs (Project Glasswing), so the public Claude Code CLI
            // subscription cannot run it.
            "claude-code", List.of(Pattern.compile("^claude-(opus|sonnet|haiku|fable)-\\d+(-\\d{1,2})?$")),

            // codex has NO discovery pattern (curated-only) - see the docblock
            // above. OpenAI's Codex-routable set is irregular (codenamed 5.6
            // tiers + unroutable bare gpt-5.x in the feed), so it ships fully
            // via MODELS + migration, never auto-discovered.

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

    /**
     * Convenience: is {@code (provider, modelId)} allow-listed? Null-safe on both
     * halves, like {@link #matchesDiscoveryPattern}: {@link #MODELS} is an immutable
     * map, whose {@code get(null)} throws rather than answering "not allowed".
     */
    public static boolean isAllowed(String provider, String modelId) {
        if (provider == null || modelId == null) return false;
        Set<String> models = MODELS.get(provider);
        return models != null && models.contains(modelId);
    }

    /**
     * The CLI bridge that executes {@code cloudProvider}'s models, or {@code null}
     * when that provider has no CLI (deepseek, openrouter, ...). Provider slugs are
     * lowercase, so the lookup trims + lowercases; a bridge slug maps to nothing
     * (a bridge is not the cloud side of another bridge).
     */
    public static String bridgeForCloudProvider(String cloudProvider) {
        if (cloudProvider == null || cloudProvider.isBlank()) return null;
        return CLOUD_PROVIDER_TO_BRIDGE.get(cloudProvider.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when {@code bridgeProvider}'s CLI can run {@code modelId} - either it is
     * on the curated floor ({@link #MODELS}) or it matches the bridge's
     * version-flexible {@link #DISCOVERY_PATTERNS}. This is the same union the
     * catalog derives its bridge rows from, so it answers "would
     * {@code cli --model <id>} be a routable pair?" without needing a catalog row.
     */
    public static boolean routesModel(String bridgeProvider, String modelId) {
        // Slugs are matched verbatim here (unlike bridgeForCloudProvider, which trims and
        // lowercases): every caller passes a canonical bridge slug, either a MODELS key or
        // the value bridgeForCloudProvider just returned.
        return isAllowed(bridgeProvider, modelId) || matchesDiscoveryPattern(bridgeProvider, modelId);
    }

    /**
     * The CLI bridge that could EXECUTE a billed {@code (cloudProvider, modelId)}
     * pair verbatim, or {@code null} when there is none. Both halves must hold: the
     * provider has a CLI, AND that CLI routes this model id - so an OpenAI id the
     * Codex CLI rejects (a bare {@code gpt-5.6}, an image model) yields {@code null}
     * instead of a link that fails at run time.
     *
     * <p>The second half is {@link #routesModel}, i.e. the curated floor UNION the
     * discovery patterns, which is what lets a just-released version of a routed
     * family be linked without a code change. It inherits that layer's documented
     * limit (see {@link #DISCOVERY_PATTERNS}): a brand-new id can reach the feed
     * before the CLI binary on the bridge host is upgraded to route it, and no CLI
     * exposes a model list to close the gap. So this answers "does that CLI route
     * this family", not "is that exact id runnable on the host right now".
     *
     * <p>The model id is deliberately used verbatim on both sides: the bridge id
     * conventions match the cloud API ids for claude-code / codex / gemini-cli.
     * {@code mistral-vibe} is the odd one out, since its ids are {@code ~/.vibe} config
     * aliases: a real mistral catalog id ({@code devstral-2512}) therefore has no
     * counterpart, while the alias itself ({@code devstral-2}) would legitimately map
     * to {@code mistral-vibe} if a row for it ever existed under {@code mistral}.
     */
    public static String cliCounterpart(String cloudProvider, String modelId) {
        String bridge = bridgeForCloudProvider(cloudProvider);
        if (bridge == null || modelId == null || modelId.isBlank()) return null;
        return routesModel(bridge, modelId) ? bridge : null;
    }
}
