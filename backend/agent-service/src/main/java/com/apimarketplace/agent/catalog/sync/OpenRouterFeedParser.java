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
 *   <li>Drop ids whose suffix matches {@code :free|:beta|:extended|:thinking|:nitro|:floor|:batch}
 *       (duplicate variants of the canonical paid version).</li>
 *   <li>Drop rows missing {@code pricing.prompt} or {@code pricing.completion}.</li>
 *   <li>Drop rows whose {@code supported_parameters} does NOT include "tools".</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRouterFeedParser {

    /**
     * Named because it is read twice, and the two readers must not drift: the
     * suffix filter that drops the row, and {@link #collectBatchRates} that
     * harvests its rate first. Declared BEFORE the list that uses it - a static
     * field referenced by simple name above its own declaration is an illegal
     * forward reference, not a style question.
     */
    static final String BATCH_SUFFIX = ":batch";

    /**
     * Suffixes on OpenRouter ids that duplicate the canonical paid model.
     *
     * <p>{@code :batch} is the highest-volume entry and the one that was
     * missing longest: OpenRouter publishes a batch-tier twin of a model as a
     * SEPARATE id at roughly half price ({@code anthropic/claude-opus-5:batch},
     * {@code z-ai/glm-5.2:batch}). Measured against the live feed on
     * 2026-09-15, 76 of the 356 ids this parser accepted were {@code :batch}
     * twins, so more than a fifth of the OpenRouter catalog was duplicate rows
     * a picker cannot tell apart from the real model.
     *
     * <p>Exposing them as models mis-sells them: a batch endpoint has different
     * latency semantics and is not interchangeable with the interactive one at
     * the point of a picker click, and being cheaper it sorts ABOVE the model
     * it duplicates in any cost-ordered list.
     *
     * <p>{@code :batch} is also the one suffix here whose row carries
     * information worth keeping, so it alone is not simply discarded: its rate
     * is copied onto the canonical row's {@code priceInputBatch} /
     * {@code priceOutputBatch} columns by {@link #collectBatchRates}. Those
     * columns exist and the LiteLLM feed already fills them; before that
     * carry-over this parser left them null and the batch rate was lost
     * outright, which is a silent downgrade dressed up as a cleanup.
     */
    static final List<String> DUPLICATE_SUFFIXES = List.of(
            ":free", ":beta", ":extended", ":thinking", ":nitro", ":floor", BATCH_SUFFIX);

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

        // First pass over the SAME list: harvest the batch-tier rates before
        // the suffix filter throws those rows away, so the canonical row can
        // carry them. Reading the list twice is cheap (a few hundred entries)
        // and keeps the accept path a single straight-line filter.
        Map<String, BigDecimal[]> batchRates = collectBatchRates(rawList);

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
            // Reject non-positive-priced entries. Zero-priced rows live under
            // "openrouter/openrouter/free"; the "openrouter/auto" router returns a "-1"
            // sentinel (variable pricing) which x1e6 becomes -1000000 and overflows the
            // auth NUMERIC(10,6) billing mirror. Neither is billable. `<= 0` (not `== 0`)
            // matches the Python parity gate (sync_openrouter.py) and keeps legit
            // 0-input / >0-output rows.
            if (priceInput.signum() <= 0 && priceOutput.signum() <= 0) {
                rejectedNoPricing++; continue;
            }

            List<String> supportedParams = asStringList(m.get("supported_parameters"));
            if (supportedParams == null || !supportedParams.contains("tools")) {
                rejectedNoTools++; continue;
            }

            Map<String, Object> row = normalise(id, m, pricing, supportedParams,
                    priceInput, priceOutput, sourceUrl, fetchedAtIso);
            BigDecimal[] batch = batchRates.get(id);
            if (batch != null) {
                row.put("priceInputBatch", batch[0]);
                row.put("priceOutputBatch", batch[1]);
            }
            accepted.add(row);
        }

        // Collected, not carried: a rate whose base row is later rejected for
        // no-tools or no-pricing is counted here and attached to nothing.
        log.info("OpenRouter parse: total={}, accepted={}, batchRatesCollected={}, rejected=[suffix:{} noPricing:{} noTools:{} schema:{}]",
                rawList.size(), accepted.size(), batchRates.size(),
                rejectedSuffix, rejectedNoPricing, rejectedNoTools, rejectedSchema);

        return ParseResult.success(accepted, rejectedSuffix, rejectedNoPricing, rejectedNoTools, rejectedSchema);
    }

    /**
     * Index the {@code :batch} rows by the id they are a twin of, so the
     * canonical row can adopt their rate on the way past.
     *
     * <p>Keyed by the BASE id ({@code anthropic/claude-opus-5}), which is what
     * the accept pass holds. A {@code :batch} row whose base the feed does not
     * carry indexes nothing and is simply dropped: there is no row to put the
     * rate on, and inventing one would publish a model that exists only as a
     * batch endpoint.
     *
     * <p>Deliberately tolerant. A malformed or unpriced batch row contributes
     * nothing rather than failing the parse: a missing batch rate leaves one
     * optional column null, which is where it already was, whereas an
     * exception here would cost the whole feed.
     */
    private static Map<String, BigDecimal[]> collectBatchRates(List<?> rawList) {
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> mapRaw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) mapRaw;
            String id = strOf(m.get("id"));
            if (id == null || !id.endsWith(BATCH_SUFFIX)) continue;

            Map<String, Object> pricing = asMap(m.get("pricing"));
            if (pricing == null) continue;
            BigDecimal in  = costPerTokenToPricePerMillion(pricing.get("prompt"));
            BigDecimal outRate = costPerTokenToPricePerMillion(pricing.get("completion"));
            if (in == null || outRate == null) continue;
            // Two rejections, and the second is STRICTER than the accept path
            // on purpose, so they are not one condition.
            //
            // Both non-positive: an unpriced row, not a free batch tier.
            // Stamping 0/0 onto a paid model would advertise a rate the
            // provider never offered, and a caller reading priceInputBatch
            // cannot tell an advertised zero from a missing one.
            if (in.signum() <= 0 && outRate.signum() <= 0) continue;
            // EITHER side negative: the accept path tolerates a mixed sign on
            // the interactive rate because a "-1" there is OpenRouter's
            // variable-pricing sentinel on a row that still has a real price
            // on the other side. A batch column has no such reading: a
            // negative rate copied onto a canonical row would flow into the
            // billing mirror as a negative NUMERIC.
            if (in.signum() < 0 || outRate.signum() < 0) continue;

            out.put(id.substring(0, id.length() - BATCH_SUFFIX.length()),
                    new BigDecimal[]{in, outRate});
        }
        return out;
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

        // Floor = standard, and this is where the two feeds diverge on what
        // price_floor_* means. LiteLlmFeedParser folds priceInputBatch into
        // the floor because there batch is a tier of the SAME id, so a caller
        // holding that id can obtain it. Here batch is a separate id that this
        // parser drops, so no caller can reach the rate through the published
        // model and the floor stays at the interactive price.
        //
        // In other words: the cheapest a caller can be charged for a normal
        // request is that interactive rate, NOT the batch rate collectBatchRates
        // may have put on priceInputBatch/priceOutputBatch. A batch endpoint is
        // a different request with different latency, so letting it set the
        // floor would quote a price no interactive call can obtain.
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
