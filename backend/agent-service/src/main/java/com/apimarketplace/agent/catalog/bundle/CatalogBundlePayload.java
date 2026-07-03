package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Canonical JSON payload of a catalog bundle.
 *
 * <p><b>Canonicalisation rules</b> (must stay stable - CE verifies the exact
 * same bytes we signed):
 * <ul>
 *   <li>Map keys sorted alphabetically at every level
 *       ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}).</li>
 *   <li>Models sorted by (provider, modelId) before serialisation.</li>
 *   <li>No pretty-printing. Unicode not escaped. UTF-8 bytes.</li>
 *   <li>{@code BigDecimal} fields serialised as strings to avoid
 *       locale/precision drift across languages.</li>
 *   <li>{@code null} fields omitted entirely - a bundle only lists what the
 *       cloud actually set.</li>
 * </ul>
 *
 * <p>The payload is <em>only</em> the model list + bundle metadata; it does
 * NOT include the signature (which signs the payload). The bundle wrapper
 * struct is {@code { payload: <canonical JSON>, signature, keyId, checksum }}.
 */
public final class CatalogBundlePayload {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    private CatalogBundlePayload() {}

    /**
     * Serialise a snapshot of the model-catalog rows into the canonical bundle
     * payload bytes. Two calls with the same inputs produce byte-identical
     * output - the signer depends on this.
     *
     * <p>Backward-compat overload that omits per-category settings - emits a
     * schemaVersion=1 shape (no {@code categories} field on rows). Use
     * {@link #canonicalBytes(long, int, String, Instant, List, List)} to ship
     * V156 sidecar data.
     */
    public static byte[] canonicalBytes(long version, int schemaVersion, String issuer,
                                        Instant snapshotTakenAt,
                                        List<ModelConfigOverrideEntity> models) {
        return canonicalBytes(version, schemaVersion, issuer, snapshotTakenAt, models, List.of());
    }

    /**
     * Category-aware serialiser. {@code categorySettings} carries the V156
     * sidecar rows; they are indexed by {@code model_config_id} and embedded
     * as a {@code categories} field on each model row. Sidecar rows whose
     * {@code model_config_id} doesn't match any model in {@code models} are
     * silently dropped (orphaned-row defence).
     *
     * <p>Wire shape:
     * <pre>
     *   "categories": {
     *     "browser_agent":     {"enabled": true, "rank": 1},
     *     "chat":              {"enabled": true, "rank": 1},
     *     "image_generation":  {"enabled": false, "rank": 105}
     *   }
     * </pre>
     * Inner keys sorted alphabetically; per-row category map omitted when no
     * sidecar rows exist for that model. {@code rank} omitted when null.
     */
    public static byte[] canonicalBytes(long version, int schemaVersion, String issuer,
                                        Instant snapshotTakenAt,
                                        List<ModelConfigOverrideEntity> models,
                                        List<ModelCategorySettingsEntity> categorySettings) {
        if (issuer == null || snapshotTakenAt == null || models == null) {
            throw new IllegalArgumentException(
                    "canonicalBytes requires non-null issuer, snapshotTakenAt, models");
        }
        if (categorySettings == null) categorySettings = List.of();

        // Build {modelConfigId → {category → entity}} once so toCanonicalMap is O(1) per model.
        Map<Long, Map<String, ModelCategorySettingsEntity>> bySource = new HashMap<>();
        for (ModelCategorySettingsEntity s : categorySettings) {
            if (s.getModelConfigId() == null || s.getCategory() == null) continue;
            bySource.computeIfAbsent(s.getModelConfigId(), k -> new HashMap<>())
                    .put(s.getCategory(), s);
        }

        Map<String, Object> root = new TreeMap<>();
        root.put("version", version);
        root.put("schemaVersion", schemaVersion);
        root.put("issuer", issuer);
        root.put("snapshotAt", snapshotTakenAt.toString());

        // Sort deterministically; TreeMap handles inner key ordering via the
        // ORDER_MAP_ENTRIES_BY_KEYS feature.
        List<ModelConfigOverrideEntity> sorted = new ArrayList<>(models);
        sorted.sort(Comparator
                .comparing(ModelConfigOverrideEntity::getProvider, Comparator.nullsLast(String::compareTo))
                .thenComparing(ModelConfigOverrideEntity::getModelId, Comparator.nullsLast(String::compareTo)));

        List<Map<String, Object>> modelJson = new ArrayList<>(sorted.size());
        for (ModelConfigOverrideEntity m : sorted) {
            Map<String, ModelCategorySettingsEntity> rowCategories =
                    m.getId() == null ? null : bySource.get(m.getId());
            modelJson.add(toCanonicalMap(m, rowCategories));
        }
        root.put("models", modelJson);

        try {
            return CANONICAL_MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise canonical catalog payload", e);
        }
    }

    private static Map<String, Object> toCanonicalMap(ModelConfigOverrideEntity m,
                                                       Map<String, ModelCategorySettingsEntity> categories) {
        Map<String, Object> row = new TreeMap<>();
        row.put("provider", m.getProvider());
        row.put("modelId", m.getModelId());
        row.put("displayName", m.getDisplayName());
        putIfNotNull(row, "description", m.getDescription());
        putIfNotNull(row, "tier", m.getTier());
        putIfNotNull(row, "ranking", m.getRanking());
        putIfNotNull(row, "recommended", m.getRecommended());
        // Effective enabled: the cloud admin's bundle_enabled override wins over
        // the cloud's own greying (bundle_enabled TRUE ships the model active to
        // CE installs even when the cloud disables it locally, FALSE the
        // reverse; NULL inherits). Resolved HERE so the signed bytes carry the
        // decision and nothing downstream needs to know the column exists.
        putIfNotNull(row, "enabled", m.getBundleEnabled() != null ? m.getBundleEnabled() : m.getEnabled());
        putIfNotNull(row, "priceInput", bigDecString(m.getPriceInput()));
        putIfNotNull(row, "priceOutput", bigDecString(m.getPriceOutput()));
        putIfNotNull(row, "rateLimitTpm", m.getRateLimitTpm());
        putIfNotNull(row, "rateLimitRpm", m.getRateLimitRpm());
        putIfNotNull(row, "rateLimitTpmPerTenant", m.getRateLimitTpmPerTenant());
        putIfNotNull(row, "rateLimitRpmPerTenant", m.getRateLimitRpmPerTenant());
        putIfNotNull(row, "contextWindow", m.getContextWindow());
        putIfNotNull(row, "maxOutputTokens", m.getMaxOutputTokens());
        putIfNotNull(row, "supportsTools", m.getSupportsTools());
        putIfNotNull(row, "supportsVision", m.getSupportsVision());
        putIfNotNull(row, "canonicalId", m.getCanonicalId());
        putIfNotNull(row, "source", m.getSource());
        putIfNotNull(row, "sourceModelId", m.getSourceModelId());
        putIfNotNull(row, "deprecatedAt", m.getDeprecatedAt() != null ? m.getDeprecatedAt().toString() : null);
        if (m.getModalities() != null && !m.getModalities().isEmpty()) {
            // Deep-canonicalise: JSONB can decode as HashMap/ArrayList with
            // arbitrary iteration order at every level. Jackson's
            // ORDER_MAP_ENTRIES_BY_KEYS only applies to Map instances - if a
            // nested value is another Map, we wrap it to TreeMap too. Lists
            // are intentionally preserved as-is (ordered data, not sets).
            row.put("modalities", canonicalise(m.getModalities()));
        }
        // V156: per-category (rank, enabled) overrides, when present. Keys are
        // sorted alphabetically (TreeMap) so the byte-determinism contract
        // holds. Per-category map is omitted entirely when no sidecar rows
        // exist for this model - old CE code ignores the unknown key, new CE
        // code (CatalogMergeService.applyCategorySettings) writes them through.
        if (categories != null && !categories.isEmpty()) {
            Map<String, Object> catMap = new TreeMap<>();
            for (Map.Entry<String, ModelCategorySettingsEntity> e : categories.entrySet()) {
                ModelCategorySettingsEntity s = e.getValue();
                if (s == null) continue;
                Map<String, Object> v = new TreeMap<>();
                v.put("enabled", s.getEnabled() == null ? Boolean.TRUE : s.getEnabled());
                if (s.getRank() != null) v.put("rank", s.getRank());
                catMap.put(e.getKey(), v);
            }
            if (!catMap.isEmpty()) row.put("categories", catMap);
        }
        // credits_input/output are derived (DB trigger). We deliberately do
        // NOT include them in the signed payload: the CE recomputes them
        // locally from price × markup × 10 on its own billing_settings, which
        // may differ from the cloud's markup.
        return row;
    }

    /** Backward-compat single-arg variant. */
    private static Map<String, Object> toCanonicalMap(ModelConfigOverrideEntity m) {
        return toCanonicalMap(m, null);
    }

    private static String bigDecString(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /**
     * Recursively convert Maps to {@link TreeMap} so that even nested JSONB-
     * decoded containers serialise with sorted keys. Lists are walked but
     * kept as lists (lists are ordered data).
     */
    @SuppressWarnings("unchecked")
    private static Object canonicalise(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalise(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(canonicalise(v));
            return out;
        }
        return value;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
