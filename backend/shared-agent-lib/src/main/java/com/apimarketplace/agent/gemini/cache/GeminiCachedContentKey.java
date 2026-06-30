package com.apimarketplace.agent.gemini.cache;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 2.1 - canonical hash of the Gemini {@code cachedContent} prefix
 * (static system block {@code [0]} + sorted tool schemas).
 *
 * <p><b>Why canonical.</b> Gemini re-uses a cached prefix only when the
 * bytes of its input - {@code systemInstruction} + {@code tools[]} - are
 * identical across requests. Any drift (map-entry order, whitespace,
 * Unicode composition form) forces a new upload, doubling storage costs
 * and throwing away the 70%+ input-token discount. This class produces
 * a fingerprint of what Gemini will see, and the {@code GeminiCachedContentManager}
 * uses hash equality as the "cache still valid" check.
 *
 * <p><b>Canonicalisation rules</b> (R15):
 * <ol>
 *   <li>Jackson {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}
 *       serialises every map in alphabetical key order. Without this,
 *       two equal {@code HashMap}s can emit different JSON on different
 *       JVMs. Cache-breaking drift.</li>
 *   <li>{@link Include#NON_NULL} drops unset properties so a field added
 *       later (set to {@code null} in one code path, omitted in another)
 *       does not falsely invalidate the hash.</li>
 *   <li>Tools are sorted by {@code name} ASCII before serialisation.
 *       The {@link ToolDefinition} record doesn't guarantee order on
 *       its own, and iteration order of the caller's {@code List} is
 *       load-bearing for the cache.</li>
 *   <li>All text inputs (system block text, tool descriptions) are
 *       pre-normalised to Unicode {@link Normalizer.Form#NFC}. Input
 *       coming from Mac filesystems (NFD) or copy-pasted from certain
 *       mobile keyboards (mixed) would otherwise hash differently on
 *       different platforms for visually identical strings.</li>
 *   <li>Output is SHA-256, hex-encoded, lowercase - 64 ASCII chars.
 *       Stable across JVMs and operating systems.</li>
 * </ol>
 *
 * <p><b>Security invariant</b> (R6 / Stage 2.4). The only inputs allowed
 * into the hash are:
 * <ul>
 *   <li>The <em>static</em> system block (block index 0 in
 *       {@code SystemBlocksLayout} terms) - policy preamble that is
 *       identical across every tenant for a given model.</li>
 *   <li>The sorted {@code ToolDefinition} list - tool schemas are
 *       global, not tenant-scoped.</li>
 * </ul>
 * Adding tenant-id, user-id, conversation-id, or any other per-caller
 * string to the hash would mean the same static prefix is cached once
 * per tenant - N× storage cost with zero token savings beyond what a
 * shared cache already delivers. See
 * {@code GeminiCacheKeyAllowedFieldsTest} for the reflection-based
 * invariant that enforces this at author-time.
 */
public final class GeminiCachedContentKey {

    /**
     * Canonical {@link ObjectMapper} - shared because its configuration
     * is the actual contract. A refactor that swapped the features or
     * inclusion policy would silently change every emitted hash.
     *
     * <p>Package-private so the mapper-config invariant test
     * ({@code GeminiCachedContentKeyTest}) can reflect over it and pin
     * the feature + inclusion state. Do NOT expose publicly - callers
     * must go through {@link #compute(String, List)}.
     */
    static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .setSerializationInclusion(Include.NON_NULL);

    private GeminiCachedContentKey() {}

    /**
     * Compute the cache key for a {@code (staticSystemBlock, tools)}
     * pair. Returns a lowercase hex-encoded SHA-256 digest.
     *
     * @param staticSystemBlockText block-0 text; already stripped of
     *                              any per-tenant sections by the
     *                              caller. Never {@code null}; pass an
     *                              empty string to represent "no
     *                              system prompt" (hash is still
     *                              stable).
     * @param tools                 tool list. May be empty or
     *                              {@code null}; the hash treats both
     *                              as "no tools" identically so that
     *                              callers don't accidentally create
     *                              two distinct caches for the same
     *                              empty toolset.
     * @throws NullPointerException if {@code staticSystemBlockText} is
     *                              {@code null}. Empty string is fine.
     */
    public static String compute(String staticSystemBlockText, List<ToolDefinition> tools) {
        Objects.requireNonNull(staticSystemBlockText, "staticSystemBlockText must not be null");

        String normalisedBlock = normaliseText(staticSystemBlockText);
        List<ToolDefinition> sortedNormalised = canonicaliseTools(tools);

        try {
            String blockJson = CANONICAL.writeValueAsString(normalisedBlock);
            String toolsJson = CANONICAL.writeValueAsString(sortedNormalised);
            return sha256Hex(blockJson + toolsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Jackson failed to serialise canonical cache-key inputs; this is a bug",
                    e);
        }
    }

    private static List<ToolDefinition> canonicaliseTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> sorted = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            if (tool == null) continue;
            sorted.add(normaliseTool(tool));
        }
        sorted.sort(Comparator.comparing(ToolDefinition::name,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    /**
     * Rebuild a {@link ToolDefinition} with all user-visible text
     * fields normalised to NFC - including every {@link ToolParameter}
     * and nested-parameter text surface (name, description, enum
     * values, regex pattern).
     *
     * <p>Structural numeric fields ({@code minimum}, {@code maximum},
     * {@code minLength}, {@code maxLength}, {@code defaultValue},
     * {@code required}) are passed through; Jackson handles their
     * deterministic serialisation via {@code ORDER_MAP_ENTRIES_BY_KEYS}.
     */
    private static ToolDefinition normaliseTool(ToolDefinition tool) {
        String name = normaliseText(tool.name());
        String description = normaliseText(tool.description());
        List<ToolParameter> normalisedParams = normaliseParameters(tool.parameters());
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(normalisedParams)
                .build();
    }

    private static List<ToolParameter> normaliseParameters(List<ToolParameter> params) {
        if (params == null || params.isEmpty()) return params;
        List<ToolParameter> out = new ArrayList<>(params.size());
        for (ToolParameter p : params) {
            out.add(p == null ? null : normaliseParameter(p));
        }
        return out;
    }

    private static ToolParameter normaliseParameter(ToolParameter p) {
        List<String> normalisedEnum = null;
        if (p.enumValues() != null) {
            normalisedEnum = new ArrayList<>(p.enumValues().size());
            for (String v : p.enumValues()) {
                normalisedEnum.add(normaliseText(v));
            }
        }

        // Nested properties - recurse so deep tool schemas (object →
        // object → string) normalise every level.
        Map<String, ToolParameter> normalisedProps = null;
        if (p.properties() != null) {
            // LinkedHashMap preserves author order; canonical JSON
            // emitter will re-sort by key, so the ordering here is
            // purely a debug aid.
            normalisedProps = new LinkedHashMap<>();
            for (Map.Entry<String, ToolParameter> e : p.properties().entrySet()) {
                String key = normaliseText(e.getKey());
                ToolParameter value = e.getValue() == null ? null : normaliseParameter(e.getValue());
                normalisedProps.put(key, value);
            }
        }

        return ToolParameter.builder()
                .name(normaliseText(p.name()))
                .type(normaliseText(p.type()))
                .description(normaliseText(p.description()))
                .required(p.required())
                .defaultValue(p.defaultValue())
                .enumValues(normalisedEnum)
                .properties(normalisedProps)
                .minLength(p.minLength())
                .maxLength(p.maxLength())
                .minimum(p.minimum())
                .maximum(p.maximum())
                .pattern(normaliseText(p.pattern()))
                .build();
    }

    private static String normaliseText(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
