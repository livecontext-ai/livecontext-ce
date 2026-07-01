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
 *   xai        → xai
 *   perplexity → perplexity
 *   cohere     → cohere
 *   cohere_chat→ cohere         (alias)
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
 *   <li>{@code litellm_provider} must be in the 8-native mapping above.</li>
 *   <li>{@code mode == "chat"} - skip embeddings, image, audio-only models.</li>
 *   <li>{@code supports_function_calling == true} - the agent platform
 *       requires tool-calling on every model it exposes.</li>
 *   <li>Model id must not contain {@code "/"} - OpenRouter-style namespaced
 *       ids would collide with the native row identity contract.</li>
 *   <li>Both {@code input_cost_per_token} and {@code output_cost_per_token}
 *       at 0 → reject (experimental/preview rows would slip past the
 *       credit gate).</li>
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

    /** 8 native providers we accept from LiteLLM. zai is NOT in the feed. */
    static final Map<String, String> PROVIDER_MAP = Map.ofEntries(
            Map.entry("anthropic",   "anthropic"),
            Map.entry("openai",      "openai"),
            Map.entry("gemini",      "google"),
            Map.entry("mistral",     "mistral"),
            Map.entry("deepseek",    "deepseek"),
            Map.entry("xai",         "xai"),
            Map.entry("perplexity",  "perplexity"),
            Map.entry("cohere",      "cohere"),
            Map.entry("cohere_chat", "cohere")
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
            if (!"chat".equals(mode)) { rejectedMode++; continue; }

            Boolean tools = boolOf(fields.get("supports_function_calling"));
            if (!Boolean.TRUE.equals(tools)) { rejectedNoTools++; continue; }

            // Reject fully unpriced entries - LiteLLM carries a handful of
            // "experimental" / preview rows with both prices at 0 (e.g.
            // gemini-exp-*, gemma-3-*, learnlm-*). They'd slip past the
            // CreditService gate with zero cost and enable free LLM use.
            // Every row we publish MUST have a real price.
            Object inC = fields.get("input_cost_per_token");
            Object outC = fields.get("output_cost_per_token");
            boolean hasInput  = isPositive(inC);
            boolean hasOutput = isPositive(outC);
            if (!hasInput && !hasOutput) { rejectedZeroPrice++; continue; }

            Map<String, Object> normalised = normalise(ourProvider, nativeModelId, fields, sourceSha, fetchedAtIso);
            accepted.add(normalised);
        }

        // Dedup dated aliases: LiteLLM lists each release under both the
        // canonical id ("claude-opus-4-7") AND a pinned dated twin
        // ("claude-opus-4-7-20260416"). Same pricing, same metadata -
        // exposing both in the picker is just noise. Drop the dated
        // variant if a canonical-same-base row already survived.
        int beforeDedup = accepted.size();
        accepted = dedupDatedAliases(accepted);
        int rejectedDatedDup = beforeDedup - accepted.size();

        log.info("LiteLLM parse: total={}, accepted={}, rejected=[provider:{} mode:{} noTools:{} slash:{} schema:{} zeroPrice:{} datedDup:{} ftTemplate:{}]",
                raw.size(), accepted.size(), rejectedProvider, rejectedMode, rejectedNoTools,
                rejectedSlash, rejectedSchema, rejectedZeroPrice, rejectedDatedDup, rejectedFineTuneTemplate);

        return ParseResult.success(accepted, rejectedProvider, rejectedMode, rejectedNoTools, rejectedSlash, rejectedSchema);
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

    static List<Map<String, Object>> dedupDatedAliases(List<Map<String, Object>> models) {
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
                    continue; // drop dated variant when canonical twin survived
                }
            }
            kept.add(m);
        }
        return kept;
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
        BigDecimal priceInput  = costPerTokenToPricePerMillion(lm.get("input_cost_per_token"));
        BigDecimal priceOutput = costPerTokenToPricePerMillion(lm.get("output_cost_per_token"));
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
        out.put("priceCacheRead",   costPerTokenToPricePerMillion(lm.get("cache_read_input_token_cost")));
        out.put("priceCacheWrite",  costPerTokenToPricePerMillion(lm.get("cache_creation_input_token_cost")));

        // Derived floors - cheapest variant the caller can obtain.
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

    private static String humanDisplayName(String provider, String modelId) {
        String p = provider.substring(0, 1).toUpperCase(Locale.ROOT) + provider.substring(1);
        return p + ": " + modelId;
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
    public record ParseResult(boolean isSuccess, String errorMessage,
                              List<Map<String, Object>> models,
                              int rejectedProvider, int rejectedMode,
                              int rejectedNoTools, int rejectedSlash,
                              int rejectedSchema) {
        public static ParseResult success(List<Map<String, Object>> models,
                                          int prov, int mode, int tools, int slash, int schema) {
            return new ParseResult(true, null, models, prov, mode, tools, slash, schema);
        }
        public static ParseResult failure(String err) {
            return new ParseResult(false, err, List.of(), 0, 0, 0, 0, 0);
        }
    }
}
