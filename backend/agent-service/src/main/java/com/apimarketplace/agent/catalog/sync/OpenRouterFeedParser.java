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
 * Parses OpenRouter's {@code GET /api/v1/models} response into normalised
 * model maps. Every accepted row is emitted under
 * {@code provider='openrouter'} - OpenRouter is a BYOK proxy and its rows
 * are a separate identity space from the native provider rows.
 *
 * <p>Shape returned by OpenRouter:
 * <pre>
 * { "data": [
 *   {
 *     "id": "anthropic/claude-sonnet-4-20250514",
 *     "name": "Anthropic: Claude Sonnet 4",
 *     "description": "…",
 *     "context_length": 200000,
 *     "pricing": { "prompt": "0.000003", "completion": "0.000015",
 *                  "image": "…", "request": "…", "input_cache_read": "…",
 *                  "input_cache_write": "…" },
 *     "top_provider": { "max_completion_tokens": 8192, "is_moderated": false },
 *     "supported_parameters": ["tools", "response_format", ...],
 *     "architecture": { "input_modalities": ["text", "image"],
 *                        "output_modalities": ["text"] },
 *     "per_request_limits": { "prompt_tokens": "…", "completion_tokens": "…" }
 *   }
 * ]}
 * </pre>
 *
 * <p>Filters (must match {@code scripts/models/sync_openrouter.py} so parity
 * is verifiable):
 * <ol>
 *   <li>Drop ids whose suffix matches {@code :free|:beta|:extended|:thinking|:nitro|:floor}
 *       (duplicate variants of the canonical paid version).</li>
 *   <li>Drop rows missing {@code pricing.prompt} or {@code pricing.completion}.</li>
 *   <li>Drop rows whose {@code supported_parameters} does NOT include "tools".</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRouterFeedParser {

    /** Suffixes on OpenRouter ids that duplicate the canonical paid model. */
    static final List<String> DUPLICATE_SUFFIXES = List.of(
            ":free", ":beta", ":extended", ":thinking", ":nitro", ":floor");

    private static final TypeReference<Map<String, Object>> ENVELOPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /** Parse the OpenRouter JSON bytes. */
    @SuppressWarnings("unchecked")
    public ParseResult parse(byte[] feedBytes, String sourceUrl, String fetchedAtIso) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(feedBytes, ENVELOPE);
        } catch (Exception e) {
            return ParseResult.failure("OpenRouter feed parse failed: " + e.getMessage());
        }

        Object dataObj = envelope.get("data");
        if (!(dataObj instanceof List<?> rawList)) {
            return ParseResult.failure("OpenRouter response has no 'data' array");
        }

        List<Map<String, Object>> accepted = new ArrayList<>();
        int rejectedSuffix = 0, rejectedNoPricing = 0, rejectedNoTools = 0, rejectedSchema = 0;

        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> mapRaw)) { rejectedSchema++; continue; }
            Map<String, Object> m = (Map<String, Object>) mapRaw;

            String id = strOf(m.get("id"));
            if (id == null || id.isEmpty()) { rejectedSchema++; continue; }

            if (hasDuplicateSuffix(id)) { rejectedSuffix++; continue; }

            Map<String, Object> pricing = asMap(m.get("pricing"));
            if (pricing == null) { rejectedNoPricing++; continue; }

            BigDecimal priceInput  = costPerTokenToPricePerMillion(pricing.get("prompt"));
            BigDecimal priceOutput = costPerTokenToPricePerMillion(pricing.get("completion"));
            if (priceInput == null || priceOutput == null) { rejectedNoPricing++; continue; }
            // Reject fully zero-priced entries. OpenRouter lists these under
            // "openrouter/openrouter/free" etc. - they'd slip the credit gate.
            if (priceInput.signum() == 0 && priceOutput.signum() == 0) {
                rejectedNoPricing++; continue;
            }

            List<String> supportedParams = asStringList(m.get("supported_parameters"));
            if (supportedParams == null || !supportedParams.contains("tools")) {
                rejectedNoTools++; continue;
            }

            accepted.add(normalise(id, m, pricing, supportedParams, priceInput, priceOutput,
                    sourceUrl, fetchedAtIso));
        }

        log.info("OpenRouter parse: total={}, accepted={}, rejected=[suffix:{} noPricing:{} noTools:{} schema:{}]",
                rawList.size(), accepted.size(), rejectedSuffix, rejectedNoPricing, rejectedNoTools, rejectedSchema);

        return ParseResult.success(accepted, rejectedSuffix, rejectedNoPricing, rejectedNoTools, rejectedSchema);
    }

    static boolean hasDuplicateSuffix(String id) {
        for (String suf : DUPLICATE_SUFFIXES) {
            if (id.endsWith(suf)) return true;
        }
        return false;
    }

    private static Map<String, Object> normalise(String id, Map<String, Object> m,
                                                 Map<String, Object> pricing,
                                                 List<String> supportedParams,
                                                 BigDecimal priceInput, BigDecimal priceOutput,
                                                 String sourceUrl, String fetchedAtIso) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", "openrouter");
        out.put("modelId", id);
        // Stamp source for the model_config_overrides_source_check constraint.
        out.put("source", "openrouter");

        String displayName = strOf(m.get("name"));
        if (displayName == null) displayName = id;
        out.put("displayName", displayName);

        out.put("description",   strOf(m.get("description")));
        out.put("priceInput",    priceInput);
        out.put("priceOutput",   priceOutput);
        out.put("tier",          classifyTier(priceOutput));

        // Context window - OpenRouter carries it at top level.
        Object ctx = m.get("context_length");
        if (ctx != null) out.put("contextWindow", ctx);

        // top_provider may carry max_completion_tokens.
        Map<String, Object> topProvider = asMap(m.get("top_provider"));
        if (topProvider != null) {
            Object maxComp = topProvider.get("max_completion_tokens");
            if (maxComp != null) out.put("maxOutputTokens", maxComp);
        }

        // Capability flags derived from supported_parameters.
        out.put("supportsTools",          supportedParams.contains("tools"));
        out.put("supportsResponseSchema", supportedParams.contains("response_format")
                || supportedParams.contains("structured_outputs"));
        out.put("supportsReasoning",      supportedParams.contains("reasoning"));

        // Modalities from architecture.
        Map<String, Object> arch = asMap(m.get("architecture"));
        if (arch != null) {
            List<String> inMod  = asStringList(arch.get("input_modalities"));
            List<String> outMod = asStringList(arch.get("output_modalities"));
            if (inMod != null) {
                out.put("supportedModalities", inMod);
                out.put("supportsVision", inMod.contains("image"));
            }
            if (outMod != null) out.put("supportedOutputModalities", outMod);
        }

        // Cache pricing if present.
        BigDecimal cacheRead  = costPerTokenToPricePerMillion(pricing.get("input_cache_read"));
        BigDecimal cacheWrite = costPerTokenToPricePerMillion(pricing.get("input_cache_write"));
        out.put("priceCacheRead",  cacheRead);
        out.put("priceCacheWrite", cacheWrite);
        out.put("supportsPromptCaching", cacheRead != null || cacheWrite != null);

        // Floor = standard for OpenRouter (no batch/flex tier exposed).
        out.put("priceFloorInput",  priceInput);
        out.put("priceFloorOutput", priceOutput);

        out.put("mode", "chat");

        // Best-effort release date from suffix.
        LocalDate release = extractReleaseDateFromModelId(id);
        if (release != null) out.put("releaseDate", release.toString());

        // Provenance metadata.
        Map<String, Object> feedMeta = new LinkedHashMap<>();
        feedMeta.put("source",    "openrouter");
        feedMeta.put("sourceUrl", sourceUrl);
        feedMeta.put("fetchedAt", fetchedAtIso);
        feedMeta.put("raw",       m);
        out.put("feedMetadata", feedMeta);

        return out;
    }

    private static String strOf(Object v) { return v == null ? null : v.toString(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return null;
    }

    public record ParseResult(boolean isSuccess, String errorMessage,
                              List<Map<String, Object>> models,
                              int rejectedSuffix, int rejectedNoPricing,
                              int rejectedNoTools, int rejectedSchema) {
        public static ParseResult success(List<Map<String, Object>> models,
                                          int suf, int noPrice, int noTools, int schema) {
            return new ParseResult(true, null, models, suf, noPrice, noTools, schema);
        }
        public static ParseResult failure(String err) {
            return new ParseResult(false, err, List.of(), 0, 0, 0, 0);
        }
    }
}
