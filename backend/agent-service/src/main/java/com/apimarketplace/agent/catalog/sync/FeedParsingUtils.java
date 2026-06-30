package com.apimarketplace.agent.catalog.sync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers used by the LiteLLM and OpenRouter feed parsers.
 *
 * <p>Tier classification and release-date extraction are deterministic rules,
 * not heuristics - defined once here so both feeds agree and so the Python
 * {@code sync_openrouter.py} script, the Java parsers, and the admin UI
 * tooltips stay in lockstep.
 */
public final class FeedParsingUtils {

    private FeedParsingUtils() {}

    // Tier thresholds on USD per 1M output tokens. Match scripts/models/sync_openrouter.py.
    public static final BigDecimal TIER_TOP_MIN  = new BigDecimal("15.00");
    public static final BigDecimal TIER_HIGH_MIN = new BigDecimal("5.00");
    public static final BigDecimal TIER_MID_MIN  = new BigDecimal("1.50");

    /**
     * Classify a model into budget / mid / high / top based on its output
     * price per 1M tokens. Returns {@code "unknown"} when the price is null
     * (never throw - sync is defensive).
     */
    public static String classifyTier(BigDecimal outputPricePerMillion) {
        if (outputPricePerMillion == null) return "unknown";
        if (outputPricePerMillion.compareTo(TIER_TOP_MIN)  >= 0) return "top";
        if (outputPricePerMillion.compareTo(TIER_HIGH_MIN) >= 0) return "high";
        if (outputPricePerMillion.compareTo(TIER_MID_MIN)  >= 0) return "mid";
        return "budget";
    }

    // Suffix date patterns on model_ids. Anthropic / OpenAI / Google
    // typically embed the release date in the model_id itself.
    private static final Pattern DATE_SUFFIX_YYYYMMDD = Pattern.compile("-(\\d{8})$");
    private static final Pattern DATE_SUFFIX_YYYY_MM_DD = Pattern.compile("-(\\d{4}-\\d{2}-\\d{2})$");

    /**
     * Best-effort release-date extraction from a model id. Returns null when
     * no date suffix is present.
     *
     * <pre>
     *   claude-opus-4-7-20260416   → 2026-04-16
     *   gpt-5.4-2026-03-05         → 2026-03-05
     *   claude-opus-4-6            → null
     * </pre>
     */
    public static LocalDate extractReleaseDateFromModelId(String modelId) {
        if (modelId == null) return null;

        Matcher m1 = DATE_SUFFIX_YYYYMMDD.matcher(modelId);
        if (m1.find()) {
            try {
                return LocalDate.parse(m1.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }

        Matcher m2 = DATE_SUFFIX_YYYY_MM_DD.matcher(modelId);
        if (m2.find()) {
            try {
                return LocalDate.parse(m2.group(1));
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }

        return null;
    }

    /**
     * Convert a per-token USD cost (LiteLLM shape, e.g. 2.5e-06) into USD per
     * 1M tokens at scale 4, matching the {@code model_config_overrides}
     * column precision.
     */
    public static BigDecimal costPerTokenToPricePerMillion(Object costPerToken) {
        if (costPerToken == null) return null;
        BigDecimal cpt;
        try {
            cpt = new BigDecimal(costPerToken.toString());
        } catch (NumberFormatException e) {
            return null;
        }
        return cpt.multiply(new BigDecimal("1000000")).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Pick the cheapest of several BigDecimals, skipping nulls. Used to
     * derive {@code price_floor_input / price_floor_output}.
     */
    public static BigDecimal minNonNull(BigDecimal... values) {
        BigDecimal min = null;
        for (BigDecimal v : values) {
            if (v == null) continue;
            if (min == null || v.compareTo(min) < 0) min = v;
        }
        return min;
    }
}
