package com.apimarketplace.agent.catalog.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.apimarketplace.agent.catalog.sync.FeedParsingUtils.*;

/**
 * Parses LiteLLM's {@code model_prices_and_context_window.json} into the
 * normalised model-map shape consumed by
 * {@link com.apimarketplace.agent.catalog.bundle.CatalogMergeService}.
 *
 * <p>Source: {@code https://raw.githubusercontent.com/BerriAI/litellm/<sha>/model_prices_and_context_window.json}.
 *
 * <p>Provider mapping (LiteLLM's {@code litellm_provider} → our internal
 * provider name):
 * <pre>
 *   anthropic  → anthropic
 *   openai     → openai
 *   gemini     → google        (PREFERRED - direct API path)
 *   mistral    → mistral
 *   deepseek   → deepseek
 *   xai        → xai            (the whole grok line)
 *   perplexity → perplexity
 *   cohere     → cohere
 *   cohere_chat→ cohere         (alias)
 *   moonshot   → moonshot       (kimi)
 *   dashscope  → qwen           (Alibaba's API brand, not the model family)
 *   zai        → zai            (glm)
 *   minimax    → minimax        (the M-series)
 * </pre>
 *
 * <p>Entries whose {@code litellm_provider} is {@code vertex_ai-language-models},
 * {@code bedrock}, {@code azure}, etc. are dropped - the Google case is
 * covered by the {@code gemini} key and the rest are not first-class
 * providers in this platform (admin can still add them as {@code is_custom}
 * rows via the UI).
 *
 * <p>Filters applied, in order:
 * <ol>
 *   <li>Model id must not start with {@code "ft:"} - LiteLLM publishes
 *       fine-tuning pricing templates under this prefix; they're not
 *       callable until a tenant fine-tunes the base. Admins add their
 *       real fine-tune id via the is_custom=true UI path.</li>
 *   <li>{@code litellm_provider} must be in the native mapping above.</li>
 *   <li>{@code mode == "chat"} - skip embeddings, image, audio-only models.</li>
 *   <li>{@code supports_function_calling == true} - the agent platform
 *       requires tool-calling on every model it exposes.</li>
 *   <li>Model id must not contain {@code "/"} - OpenRouter-style namespaced
 *       ids would collide with the native row identity contract.</li>
 *   <li>Both {@code input_cost_per_token} and {@code output_cost_per_token}
 *       at 0 → reject (experimental/preview rows would slip past the
 *       credit gate). A row that prices itself through a
 *       {@code tiered_pricing} ladder instead of the flat keys counts as
 *       priced - see {@link #effectiveCost}.</li>
 * </ol>
 *
 * <p>Two more CLASSES of row are dropped after the per-entry filters, because
 * each needs the whole accepted set to decide. Both drop a redundant NAME rather than a
 * model, and both record the id in {@code declinedIds} so the native discovery
 * pass cannot offer it straight back:
 * <ol>
 *   <li>{@link #dedupDatedAliases} - a dated twin
 *       ({@code claude-opus-4-7-20260416}) when its canonical alias
 *       ({@code claude-opus-4-7}) survived.</li>
 *   <li>{@link #dedupVersionedTwins} - a version-suffixed twin
 *       ({@code deepseek-v4-flash}) when its version-free alias
 *       ({@code deepseek-flash}) survived AND the feed describes the two
 *       identically on every published field.</li>
 * </ol>
 *
 * <p>Output map shape - identical to
 * {@code CatalogBundlePayload.toCanonicalMap()} with V125 extensions, so
 * {@code CatalogMergeService.merge()} can ingest it unchanged:
 * <pre>
 *   provider, modelId, displayName, priceInput, priceOutput, tier,
 *   contextWindow, maxOutputTokens, supportsTools, supportsVision,
 *   supportsPromptCaching, supportsReasoning, supportsComputerUse,
 *   supportsResponseSchema, supportsWebSearch, mode,
 *   priceInputBatch, priceOutputBatch, priceCacheRead, priceCacheWrite,
 *   priceFloorInput, priceFloorOutput,
 *   supportedEndpoints, supportedModalities, supportedOutputModalities,
 *   deprecationDate (ISO date string), releaseDate (ISO date string),
 *   rateLimitTpm, rateLimitRpm,
 *   feedMetadata: { source, sourceSha, fetchedAt, raw }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiteLlmFeedParser {

    /**
     * LiteLLM provider key -> our native provider name. MUST cover every
     * non-bridge provider the platform can execute directly, i.e. every entry
     * of {@code CloudRelaySupport.supportedProviders()} except
     * {@code openrouter} (that aggregator has its own feed + parser). A
     * provider missing here is dropped at the {@code rejectedProvider} branch
     * below, which silently freezes its model list on whatever
     * {@code application.yml} hardcodes - the failure mode that kept Kimi,
     * Qwen and GLM stuck for months. {@code LiteLlmProviderCoverageTest}
     * enforces the invariant.
     *
     * <p>Note the key is LiteLLM's, not ours: Google is {@code gemini},
     * Qwen is {@code dashscope} (Alibaba's API brand), and Cohere ships under
     * two keys.
     *
     * <p>Known imprecision on {@code dashscope}: it is the platform, not the
     * model family, so Alibaba also serves third-party weights there. Any such
     * chat+tools+priced row lands under {@code provider="qwen"} with a foreign
     * model id. Accepted rather than filtered by name pattern: those rows are
     * genuinely callable through the DashScope-compatible endpoint we configure
     * for {@code qwen}, and feed inserts are review-gated
     * ({@code honorEnabledOnInsert=false}) so nothing reaches the picker
     * without an admin enabling it.
     */
    static final Map<String, String> PROVIDER_MAP = Map.ofEntries(
            Map.entry("anthropic",   "anthropic"),
            Map.entry("openai",      "openai"),
            Map.entry("gemini",      "google"),
            Map.entry("mistral",     "mistral"),
            Map.entry("deepseek",    "deepseek"),
            Map.entry("xai",         "xai"),
            Map.entry("perplexity",  "perplexity"),
            Map.entry("cohere",      "cohere"),
            Map.entry("cohere_chat", "cohere"),
            Map.entry("moonshot",    "moonshot"),
            Map.entry("dashscope",   "qwen"),
            Map.entry("zai",         "zai"),
            Map.entry("minimax",     "minimax")
    );

    private static final TypeReference<Map<String, Map<String, Object>>> FEED_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Parse the raw LiteLLM JSON bytes. {@code sourceSha} is stamped into
     * every row's {@code feedMetadata} so each model carries its own
     * provenance, independent of the sync-log row.
     */
    public ParseResult parse(byte[] feedBytes, String sourceSha, String fetchedAtIso) {
        Map<String, Map<String, Object>> raw;
        try {
            raw = objectMapper.readValue(feedBytes, FEED_TYPE);
        } catch (Exception e) {
            return ParseResult.failure("LiteLLM feed parse failed: " + e.getMessage());
        }

        List<Map<String, Object>> accepted = new ArrayList<>();
        Set<String> declinedIds = new HashSet<>();
        int rejectedProvider = 0, rejectedMode = 0, rejectedNoTools = 0, rejectedSlash = 0, rejectedSchema = 0, rejectedZeroPrice = 0;
        int rejectedFineTuneTemplate = 0;

        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            String modelId = entry.getKey();
            Map<String, Object> fields = entry.getValue();

            // sample_spec is a documentation placeholder in the feed.
            if ("sample_spec".equals(modelId)) continue;
            if (fields == null) { rejectedSchema++; continue; }

            // Fine-tuning pricing templates: LiteLLM publishes ids like
            // "ft:gpt-4.1-mini-2025-04-14" that carry the per-token rates for
            // ANY fine-tune of that base. They are NOT callable as-is - a real
            // fine-tune id is "ft:<base>:<org>:<job>:<hash>". Exposing the
            // template in the picker would surface a row that every tenant
            // call would 404 on. Admins add their real fine-tune via the
            // is_custom=true UI path instead.
            if (modelId.startsWith("ft:")) { rejectedFineTuneTemplate++; continue; }

            String litellmProvider = strOf(fields.get("litellm_provider"));
            // Non-model meta blocks in the feed carry no litellm_provider (e.g.
            // "fallback_generalizations", "sample_spec"). PROVIDER_MAP is an
            // immutable Map.ofEntries whose get(null) throws NPE, so guard the
            // null before the lookup and route it through the reject path
            // instead of crashing the whole sync on one meta entry.
            String ourProvider = litellmProvider == null ? null : PROVIDER_MAP.get(litellmProvider);
            if (ourProvider == null) { rejectedProvider++; continue; }

            // LiteLLM sometimes prefixes ids with the provider namespace
            // (e.g. "mistral/codestral-latest"). Strip the prefix so the
            // native identity matches what application.yml / existing DB rows
            // use.
            String nativeModelId = stripProviderPrefix(modelId, litellmProvider);

            // no-slash assertion on the native model_id
            if (nativeModelId.contains("/")) { rejectedSlash++; continue; }

            String mode = strOf(fields.get("mode"));
            if (!"chat".equals(mode)) {
                rejectedMode++;
                declinedIds.add(NativeModelDiscoveryService.key(ourProvider, nativeModelId));
                continue;
            }

            Boolean tools = boolOf(fields.get("supports_function_calling"));
            if (!Boolean.TRUE.equals(tools)) {
                rejectedNoTools++;
                declinedIds.add(NativeModelDiscoveryService.key(ourProvider, nativeModelId));
                continue;
            }

            // Reject fully unpriced entries - LiteLLM carries a handful of
            // "experimental" / preview rows with both prices at 0 (e.g.
            // gemini-exp-*, gemma-3-*, learnlm-*). They'd slip past the
            // CreditService gate with zero cost and enable free LLM use.
            // Every row we publish MUST have a real price.
            // effectiveCost (not a raw get) so a context-bracket price ladder
            // counts as priced - see its javadoc.
            Object inC = effectiveCost(fields, "input_cost_per_token");
            Object outC = effectiveCost(fields, "output_cost_per_token");
            boolean hasInput  = isPositive(inC);
            boolean hasOutput = isPositive(outC);
            if (!hasInput && !hasOutput) {
                rejectedZeroPrice++;
                declinedIds.add(NativeModelDiscoveryService.key(ourProvider, nativeModelId));
                continue;
            }

            Map<String, Object> normalised = normalise(ourProvider, nativeModelId, fields, sourceSha, fetchedAtIso);
            accepted.add(normalised);
        }

        // Dedup dated aliases: LiteLLM lists each release under both the
        // canonical id ("claude-opus-4-7") AND a pinned dated twin
        // ("claude-opus-4-7-20260416"). Same pricing, same metadata -
        // exposing both in the picker is just noise. Drop the dated
        // variant if a canonical-same-base row already survived.
        int beforeDedup = accepted.size();
        // The dropped twins are declined ids like any other. Vendor listings
        // return dated ids (Anthropic serves claude-sonnet-4-5-20250929,
        // OpenAI returns both forms), so without recording them here,
        // discovery re-emits a duplicate row for every dated alias on every
        // single sync that has an Anthropic or OpenAI key. That is the
        // highest-volume case of the whole class, and unlike the others it
        // fires with certainty rather than on a particular vendor's catalogue.
        accepted = dedupDatedAliases(accepted, declinedIds);
        int rejectedDatedDup = beforeDedup - accepted.size();

        // Same idea, different suffix shape: a vendor that keeps a version
        // token INSIDE the id publishes both forms of one model.
        int beforeVersionDedup = accepted.size();
        accepted = dedupVersionedTwins(accepted, declinedIds);
        int rejectedVersionTwin = beforeVersionDedup - accepted.size();

        log.info("LiteLLM parse: total={}, accepted={}, rejected=[provider:{} mode:{} noTools:{} slash:{} schema:{} zeroPrice:{} datedDup:{} versionTwin:{} ftTemplate:{}]",
                raw.size(), accepted.size(), rejectedProvider, rejectedMode, rejectedNoTools,
                rejectedSlash, rejectedSchema, rejectedZeroPrice, rejectedDatedDup,
                rejectedVersionTwin, rejectedFineTuneTemplate);

        return ParseResult.success(accepted, declinedIds, rejectedProvider, rejectedMode,
                rejectedNoTools, rejectedSlash, rejectedSchema);
    }

    /**
     * Remove dated-alias duplicates. A model whose id ends with
     * {@code -YYYYMMDD} OR {@code -YYYY-MM-DD} is dropped when another row
     * under the same provider has the id without the date suffix (i.e. the
     * canonical alias). If only the dated form exists (no canonical twin),
     * we keep it - some models are only published under dated ids.
     */
    private static java.util.regex.Pattern DATED_SUFFIX =
            java.util.regex.Pattern.compile("(.*)-(\\d{8}|\\d{4}-\\d{2}-\\d{2})$");

    /**
     * @param declinedInto collects every dropped twin, so the ids this feed
     *                     deliberately hides stay hidden when another source
     *                     offers them again. Not optional, and there is no
     *                     convenience overload that defaults it: a caller that
     *                     forgets to collect the drops re-opens the duplicate
     *                     hole silently, and an overload nobody calls is just
     *                     the place that drift starts.
     */
    static List<Map<String, Object>> dedupDatedAliases(List<Map<String, Object>> models,
                                                       java.util.Set<String> declinedInto) {
        // Index canonical (provider, id-without-date) keys → present?
        java.util.Set<String> canonicalKeys = new java.util.HashSet<>();
        for (Map<String, Object> m : models) {
            String id = (String) m.get("modelId");
            if (id == null) continue;
            if (!DATED_SUFFIX.matcher(id).matches()) {
                canonicalKeys.add(m.get("provider") + ":" + id);
            }
        }
        List<Map<String, Object>> kept = new ArrayList<>(models.size());
        for (Map<String, Object> m : models) {
            String id = (String) m.get("modelId");
            if (id == null) { kept.add(m); continue; }
            java.util.regex.Matcher mat = DATED_SUFFIX.matcher(id);
            if (mat.matches()) {
                String canonicalId = mat.group(1);
                String canonicalKey = m.get("provider") + ":" + canonicalId;
                if (canonicalKeys.contains(canonicalKey)) {
                    // Drop the dated variant when the canonical twin survived,
                    // and record it so no other source can re-add it.
                    declinedInto.add(NativeModelDiscoveryService.key(
                            String.valueOf(m.get("provider")), id));
                    continue;
                }
            }
            kept.add(m);
        }
        return kept;
    }

    /**
     * A hyphen-delimited version token sitting INSIDE an id: the {@code -v4} of
     * {@code deepseek-v4-flash}, or the {@code -v4.1} of a future
     * {@code deepseek-v4.1-flash}. Anchored on a following hyphen so a trailing
     * {@code -v2} (the whole point of ids like {@code deepseek-prover-v2}) is
     * never matched: removing it there would invent an alias that does not
     * exist.
     */
    private static final java.util.regex.Pattern VERSION_TOKEN =
            java.util.regex.Pattern.compile("-v\\d+(?:\\.\\d+)*(?=-)");

    /**
     * Fields excluded from the twin comparison because they restate the ID
     * rather than describe the MODEL: two rows may differ on them and still be
     * the same thing. Everything else is compared, and a key present on one
     * side and absent on the other counts as different.
     *
     * <p>{@code releaseDate} is ALSO derived from the id and is deliberately
     * NOT in this set. Removing a mid-string version token cannot change a
     * trailing date, so in every reachable case the two twins derive the same
     * value and the field is inert; leaving it compared means that if a vendor
     * ever ships a shape where they differ, the rows stay apart. Excluding it
     * would be the choice that fails open.
     */
    private static final java.util.Set<String> TWIN_IGNORED_FIELDS =
            java.util.Set.of("modelId", "displayName", "feedMetadata");

    /**
     * Collapse a version-suffixed id onto its version-free alias when the feed
     * describes the two identically.
     *
     * <p>The case this exists for: DeepSeek publishes {@code deepseek-flash}
     * AND {@code deepseek-v4-flash}, and updates the weights behind BOTH in
     * place. Measured against the live feed on 2026-09-15, the two carry the
     * same price, context, output cap and every capability flag, while the
     * versioned name still says "v4" for weights that are no longer v4. Keeping
     * both puts two indistinguishable rows in the picker, one of which lies
     * about what it serves - and a name that lies is worse than no name,
     * because a user picks on it.
     *
     * <p>The alias is the survivor, deliberately. A version-free id cannot go
     * stale: it says only "the current flash model", which stays true across
     * every future release. This is the same call {@link #dedupDatedAliases}
     * already makes for {@code claude-opus-4-7-20260416} against
     * {@code claude-opus-4-7}; only the shape of the redundant suffix differs.
     *
     * <p><b>Why the equality has to be total.</b> A vendor CAN publish a
     * version-free alias next to a genuinely different versioned model, and
     * that pair must survive untouched. Requiring every published field to
     * match (prices, context, output cap, all capability flags, dates) makes
     * the collapse provable from the feed rather than inferred from the name:
     * if the feed says they differ in any way we can observe, they are two
     * models and both stay.
     *
     * <p>Blast radius, measured end to end against the live feed on 2026-09-15:
     * ONE distinct model, {@code deepseek-v4-flash} onto {@code deepseek-flash}.
     * The counter reads 2 rather than 1 because LiteLLM publishes that id in
     * BOTH its bare and its namespaced form ({@code deepseek-v4-flash} and
     * {@code deepseek/deepseek-v4-flash}), which {@code stripProviderPrefix}
     * reduces to the same native id, so the pass sees the twin twice and
     * collapses both copies. Nothing else in the feed matched.
     *
     * @param declinedInto collects the dropped twin, exactly as the dated-alias
     *                     pass does, so the native discovery pass cannot offer
     *                     the id straight back on the same sync.
     */
    static List<Map<String, Object>> dedupVersionedTwins(List<Map<String, Object>> models,
                                                         java.util.Set<String> declinedInto) {
        Map<String, Map<String, Object>> byKey = new java.util.HashMap<>();
        for (Map<String, Object> m : models) {
            String id = strOf(m.get("modelId"));
            if (id == null) continue;
            byKey.put(m.get("provider") + ":" + id, m);
        }

        List<Map<String, Object>> kept = new ArrayList<>(models.size());
        for (Map<String, Object> m : models) {
            String id = strOf(m.get("modelId"));
            if (id == null) { kept.add(m); continue; }

            boolean collapsed = false;
            java.util.regex.Matcher mat = VERSION_TOKEN.matcher(id);
            while (mat.find()) {
                String aliasId = id.substring(0, mat.start()) + id.substring(mat.end());
                Map<String, Object> alias = byKey.get(m.get("provider") + ":" + aliasId);
                if (alias != null && publishedFieldsEqual(m, alias)) {
                    declinedInto.add(NativeModelDiscoveryService.key(
                            String.valueOf(m.get("provider")), id));
                    // Name both ids, not just a count. This pass decides that
                    // two rows are one model on evidence the feed only implies,
                    // so if it ever collapses a model that was genuinely
                    // distinct, the log line is the only place that will say
                    // which id stopped existing.
                    log.info("LiteLLM parse: collapsed versioned twin {}:{} onto alias {}",
                            m.get("provider"), id, aliasId);
                    collapsed = true;
                    break;
                }
            }
            if (!collapsed) kept.add(m);
        }
        return kept;
    }

    /**
     * Do two normalised feed rows describe the same model? Compares the UNION
     * of both key sets, so a field one row carries and the other omits is a
     * difference, not a silent match.
     */
    static boolean publishedFieldsEqual(Map<String, Object> a, Map<String, Object> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        keys.removeAll(TWIN_IGNORED_FIELDS);
        for (String k : keys) {
            if (!Objects.equals(a.get(k), b.get(k))) return false;
        }
        return true;
    }

    /**
     * Strip a leading {@code provider/} prefix from a LiteLLM model id.
     * Tries the LiteLLM provider key first ({@code cohere_chat/…}) and the
     * mapped native name second ({@code cohere/…}) - LiteLLM uses either
     * depending on the entry. Returns the id unchanged if neither matches.
     */
    static String stripProviderPrefix(String modelId, String litellmProvider) {
        if (modelId == null) return null;
        if (litellmProvider != null) {
            String p1 = litellmProvider + "/";
            if (modelId.startsWith(p1)) return modelId.substring(p1.length());
            String mapped = PROVIDER_MAP.get(litellmProvider);
            if (mapped != null) {
                String p2 = mapped + "/";
                if (modelId.startsWith(p2)) return modelId.substring(p2.length());
            }
        }
        return modelId;
    }

    /** Map one LiteLLM row to our canonical merge shape. */
    private static Map<String, Object> normalise(String ourProvider, String modelId,
                                                 Map<String, Object> lm,
                                                 String sourceSha, String fetchedAtIso) {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("provider", ourProvider);
        out.put("modelId", modelId);
        // Stamp the feed as the row's source. Values must satisfy the
        // model_config_overrides_source_check constraint (manual / curated /
        // openrouter / litellm / bundle).
        out.put("source", "litellm");
        // Default display name is the raw model id (e.g. "claude-opus-4-7",
        // "gpt-5.4-mini"). The provider is already shown in a separate
        // column/badge by the picker UI - prefixing "Openai: " here would
        // duplicate that info. Admins can refine the display name inline;
        // the edit goes into user_modified_fields and survives future syncs.
        out.put("displayName", modelId);

        // Pricing - convert per-token → per-1M tokens (scale 4).
        BigDecimal priceInput  = costPerTokenToPricePerMillion(effectiveCost(lm, "input_cost_per_token"));
        BigDecimal priceOutput = costPerTokenToPricePerMillion(effectiveCost(lm, "output_cost_per_token"));
        out.put("priceInput",  priceInput);
        out.put("priceOutput", priceOutput);

        // Tier derivation (deterministic rule on output price).
        out.put("tier", classifyTier(priceOutput));

        // Context / token caps.
        out.put("contextWindow",   lm.get("max_input_tokens"));
        out.put("maxOutputTokens", lm.get("max_output_tokens"));

        // Capability flags.
        out.put("supportsTools",           boolOf(lm.get("supports_function_calling")));
        out.put("supportsVision",          boolOf(lm.get("supports_vision")));
        out.put("supportsPromptCaching",   boolOf(lm.get("supports_prompt_caching")));
        out.put("supportsReasoning",       boolOf(lm.get("supports_reasoning")));
        out.put("supportsComputerUse",     boolOf(lm.get("supports_computer_use")));
        out.put("supportsResponseSchema",  boolOf(lm.get("supports_response_schema")));
        out.put("supportsWebSearch",       boolOf(lm.get("supports_web_search")));
        out.put("mode", strOf(lm.get("mode")));

        // Alternate pricing variants (batch / cache).
        BigDecimal priceInputBatch  = costPerTokenToPricePerMillion(lm.get("input_cost_per_token_batches"));
        BigDecimal priceOutputBatch = costPerTokenToPricePerMillion(lm.get("output_cost_per_token_batches"));
        out.put("priceInputBatch",  priceInputBatch);
        out.put("priceOutputBatch", priceOutputBatch);
        // Cache rates ride the same ladder on tiered rows (qwen3-coder-plus
        // declares cache_read only inside its brackets), so resolve them the
        // same way or a tiered model looks cache-free.
        out.put("priceCacheRead",   costPerTokenToPricePerMillion(effectiveCost(lm, "cache_read_input_token_cost")));
        out.put("priceCacheWrite",  costPerTokenToPricePerMillion(effectiveCost(lm, "cache_creation_input_token_cost")));

        // Derived floors - cheapest variant the caller can obtain.
        // The floor folds the batch rate in BECAUSE on this feed batch is a
        // tier of the SAME model id: a caller holding that id can obtain it,
        // so it is genuinely the cheapest this model can cost.
        //
        // That is NOT true of the OpenRouter feed, and the two now disagree
        // on purpose. There the batch tier is a separate id ("model:batch")
        // which OpenRouterFeedParser drops, so no caller can reach the rate
        // through the published model; its parser copies the rate onto
        // priceInputBatch for information and deliberately leaves the floor
        // at the interactive price. Anything reading price_floor_* has to
        // know the rule is per-feed, which the V125 column COMMENT predates
        // and does not say.
        out.put("priceFloorInput",
                minNonNull(priceInput, priceInputBatch,
                        costPerTokenToPricePerMillion(lm.get("input_cost_per_token_flex"))));
        out.put("priceFloorOutput",
                minNonNull(priceOutput, priceOutputBatch,
                        costPerTokenToPricePerMillion(lm.get("output_cost_per_token_flex"))));

        // Endpoints / modalities (may be null - optional in the feed).
        out.put("supportedEndpoints",         lm.get("supported_endpoints"));
        out.put("supportedModalities",        lm.get("supported_modalities"));
        out.put("supportedOutputModalities",  lm.get("supported_output_modalities"));

        // Lifecycle dates.
        Object deprecation = lm.get("deprecation_date");
        if (deprecation != null) out.put("deprecationDate", deprecation.toString());
        LocalDate release = extractReleaseDateFromModelId(modelId);
        if (release != null) out.put("releaseDate", release.toString());

        // Rate limits - LiteLLM exposes rpm/tpm for ~47/2672 models. Null
        // otherwise; admin UI can override per-tenant.
        out.put("rateLimitRpm", lm.get("rpm"));
        out.put("rateLimitTpm", lm.get("tpm"));

        // Raw feed metadata for future-proofing - the admin UI can display
        // fields we haven't surfaced as dedicated columns yet.
        Map<String, Object> feedMeta = new LinkedHashMap<>();
        feedMeta.put("source",    "litellm");
        feedMeta.put("sourceSha", sourceSha);
        feedMeta.put("fetchedAt", fetchedAtIso);
        feedMeta.put("raw",       lm);
        out.put("feedMetadata", feedMeta);

        return out;
    }

    /**
     * Resolve one per-token cost, falling back to the base bracket of a
     * {@code tiered_pricing} ladder when the flat key is absent or zero.
     *
     * <p>Alibaba (DashScope) and ByteDance (Volcengine) bill by CONTEXT
     * BRACKET, and LiteLLM mirrors that by publishing {@code tiered_pricing}
     * INSTEAD of the flat {@code input_cost_per_token} /
     * {@code output_cost_per_token} keys - never alongside them. Reading only
     * the flat keys made every such row look unpriced, so the zero-price gate
     * in {@link #parse} deleted it silently: 19 models measured against the
     * live feed on 2026-08-19, 100% of them Chinese (15 dashscope + 4
     * volcengine), including {@code qwen3-max}, the whole {@code qwen3-coder}
     * line and {@code doubao-seed-2-0-pro}. No western provider in the feed
     * publishes this shape, so the fallback is a strict no-op for them.
     *
     * <p>The value returned is the BASE bracket: the tier whose range starts
     * at 0, or the first entry when no range is declared. That is the figure
     * the provider advertises as the model's price - verified 2026-08-19
     * against Alibaba Model Studio (qwen3-max $1.20/$6.00 base, rising to
     * $3.00/$15.00 on the largest bracket) and Volcengine Ark
     * (doubao-seed-2.0-pro $0.47/$2.37 base) - and it keeps a tiered row
     * comparable with its flat-priced siblings in the picker and in tier
     * classification. The upper brackets are not dropped: the whole ladder
     * stays in {@code feedMetadata.raw.tiered_pricing}.
     *
     * <p>Consequence to know: a request whose context lands in an upper
     * bracket is billed at the base rate. Modelling the ladder properly needs
     * a schema change on {@code model_config_overrides}; until then the base
     * rate is the honest headline, and under-billing a long-context call is
     * preferable to over-billing every short one.
     */
    static Object effectiveCost(Map<String, Object> fields, String costKey) {
        Object flat = fields.get(costKey);
        if (isPositive(flat)) return flat;
        Map<String, Object> base = baseTier(fields);
        if (base == null) return flat;
        Object tiered = base.get(costKey);
        return tiered != null ? tiered : flat;
    }

    /**
     * The base bracket of a {@code tiered_pricing} ladder: the tier whose
     * {@code range} starts at 0, else the first well-formed entry. Returns
     * null when the row carries no usable ladder.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> baseTier(Map<String, Object> fields) {
        if (!(fields.get("tiered_pricing") instanceof List<?> tiers)) return null;
        Map<String, Object> first = null;
        for (Object entry : tiers) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Map<String, Object> tier = (Map<String, Object>) raw;
            if (first == null) first = tier;
            if (rangeStartsAtZero(tier)) return tier;
        }
        return first;
    }

    /** True when the tier's {@code range} is {@code [0, …]}. */
    private static boolean rangeStartsAtZero(Map<String, Object> tier) {
        if (!(tier.get("range") instanceof List<?> range) || range.isEmpty()) return false;
        Object low = range.get(0);
        if (low == null) return false;
        try {
            return new BigDecimal(low.toString()).signum() == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String strOf(Object v) { return v == null ? null : v.toString(); }

    private static Boolean boolOf(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    /** True when v is a positive number (not null, not 0, not parse-error). */
    private static boolean isPositive(Object v) {
        if (v == null) return false;
        try {
            return new java.math.BigDecimal(v.toString()).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Outcome of a parse: success with an accepted-models list + rejection
     * counts per bucket (useful for admin UI diagnostics), OR a hard failure
     * with a message. No exceptions escape - callers must check
     * {@link #isSuccess()}.
     */
    /**
     * @param declinedIds {@link NativeModelDiscoveryService#key} for every
     *                    vendor id this feed SAW and refused to publish: the
     *                    wrong mode (embedding / image / audio), no
     *                    tool-calling, or no real price. These are not catalog
     *                    rows, they are policy decisions already taken, and
     *                    {@link NativeModelDiscoveryService} needs them because
     *                    it reads vendor {@code /models} listings that state
     *                    none of those three things. Without this, a model the
     *                    feed rejected walks straight back in through the
     *                    discovery door: {@code text-embedding-3-large} as a
     *                    chat row, or a no-tools model on a platform whose
     *                    whole premise is tool-calling.
     *
     *                    <p>Note this is only about ids the feed KNOWS. An id
     *                    no source has ever heard of is still discovered, and
     *                    lands unpriced and disabled.
     */
    public record ParseResult(boolean isSuccess, String errorMessage,
                              List<Map<String, Object>> models,
                              Set<String> declinedIds,
                              int rejectedProvider, int rejectedMode,
                              int rejectedNoTools, int rejectedSlash,
                              int rejectedSchema) {
        public static ParseResult success(List<Map<String, Object>> models, Set<String> declinedIds,
                                          int prov, int mode, int tools, int slash, int schema) {
            return new ParseResult(true, null, models,
                    declinedIds == null ? Set.of() : Set.copyOf(declinedIds),
                    prov, mode, tools, slash, schema);
        }
        public static ParseResult failure(String err) {
            return new ParseResult(false, err, List.of(), Set.of(), 0, 0, 0, 0, 0);
        }
    }
}
