package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.domain.SkillEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.*;

/**
 * Canonical JSON payload of a skill bundle (the admin-managed GLOBAL skills).
 *
 * <p><b>Canonicalisation rules</b> (must stay stable - CE verifies the exact same bytes
 * we signed, and the cloud re-derives the payload at serve time and re-checks the stored
 * checksum):
 * <ul>
 *   <li>Map keys sorted alphabetically at every level
 *       ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}).</li>
 *   <li>Skills sorted by {@code key} (the cloud skill UUID) before serialisation.</li>
 *   <li>No pretty-printing. Unicode not escaped. UTF-8 bytes.</li>
 *   <li>{@code null} fields omitted entirely (e.g. a skill with no icon).</li>
 * </ul>
 *
 * <p>The field names emitted here MUST match {@link SkillBundleApplier} one-to-one - a
 * rename on the publisher silently breaks CE apply.
 */
public final class SkillBundlePayload {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private SkillBundlePayload() {}

    /**
     * Serialise a snapshot of the global-skill rows into the canonical bundle payload
     * bytes. Two calls with the same inputs produce byte-identical output - the signer
     * depends on this.
     */
    public static byte[] canonicalBytes(long version, int schemaVersion, String issuer,
                                        Instant snapshotTakenAt, List<SkillEntity> skills) {
        if (issuer == null || snapshotTakenAt == null || skills == null) {
            throw new IllegalArgumentException(
                    "canonicalBytes requires non-null issuer, snapshotTakenAt, skills");
        }

        Map<String, Object> root = new TreeMap<>();
        root.put("version", version);
        root.put("schemaVersion", schemaVersion);
        root.put("issuer", issuer);
        root.put("snapshotAt", snapshotTakenAt.toString());

        List<SkillEntity> sorted = new ArrayList<>(skills);
        sorted.sort(Comparator.comparing(SkillBundlePayload::bundleKey,
                Comparator.nullsLast(String::compareTo)));

        List<Map<String, Object>> skillJson = new ArrayList<>(sorted.size());
        for (SkillEntity s : sorted) {
            skillJson.add(toCanonicalMap(s));
        }
        root.put("skills", skillJson);

        try {
            return CANONICAL_MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise canonical skill payload", e);
        }
    }

    /** The bundle key for a global skill = its cloud UUID, as a string. */
    static String bundleKey(SkillEntity s) {
        return s.getId() == null ? null : s.getId().toString();
    }

    private static Map<String, Object> toCanonicalMap(SkillEntity s) {
        Map<String, Object> row = new TreeMap<>();
        row.put("key", bundleKey(s));
        row.put("name", s.getName());
        row.put("description", s.getDescription());
        putIfNotNull(row, "icon", s.getIcon());
        row.put("instructions", s.getInstructions());
        // The cloud controls whether a global skill is auto-active in NEW chats for every
        // user that can see it. CE applies this verbatim; users opt out per-user afterwards.
        row.put("isDefaultActive", Boolean.TRUE.equals(s.getIsDefaultActive()));
        return row;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
