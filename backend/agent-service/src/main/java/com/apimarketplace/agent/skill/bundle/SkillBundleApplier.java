package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * CE-side: apply a verified {@link SignedSkillBundle} into {@code agent.skills} as
 * read-only, cloud-managed GLOBAL rows. Sibling of
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleApplier}.
 *
 * <p>Merge semantics:
 * <ul>
 *   <li>Each bundle skill is upserted by {@code source_bundle_key} (the cloud skill UUID):
 *       inserted on first sight, otherwise its name/description/icon/instructions and
 *       {@code is_default_active} are overwritten from the bundle. Rows are
 *       {@code is_global=true} so every user/org sees them; {@code is_default_active}
 *       (from the cloud) auto-activates them in new chats. A user hides one for themselves
 *       via the per-user override - that override is keyed on the row id, which is stable
 *       across re-syncs (we upsert, never recreate).</li>
 *   <li>Bundle-owned rows whose cloud skill disappeared from the latest bundle are
 *       soft-removed ({@code is_global=false, is_active=false}) - the row (and any user
 *       override referencing it) is preserved rather than hard-deleted.</li>
 *   <li>Rows the bundle never owned ({@code source_bundle_key IS NULL}: personal, default,
 *       admin-authored globals) are never touched.</li>
 * </ul>
 *
 * <p>Idempotent: re-applying the same version is a no-op (detected via
 * {@code skill_bundles.is_active} + version match).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBundleApplier {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    /**
     * Synthetic owner for bundle-applied rows. NON-NULL so {@code OrgScopedEntityListener}
     * does not fail-loud on the scheduler thread (no request scope); it never matches a
     * real org, so visibility relies on {@code is_global=true}.
     */
    static final String BUNDLE_TENANT = "__skill_bundle__";

    private final SkillRepository skillRepository;
    private final SkillBundleRepository bundleRepo;
    private final SkillBundleSyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;

    public record ApplyResult(Status status, long version, int inserted, int updated,
                              int removed, String detail) {
        public static ApplyResult alreadyApplied(long v) {
            return new ApplyResult(Status.ALREADY_APPLIED, v, 0, 0, 0, null);
        }
        public static ApplyResult failed(String d) {
            return new ApplyResult(Status.APPLY_FAILED, 0, 0, 0, 0, d);
        }
    }

    public enum Status { APPLIED, ALREADY_APPLIED, APPLY_FAILED }

    /**
     * Apply a verified bundle. Caller MUST have verified the signature first (via
     * {@link SkillBundleVerifier}) - this method assumes the bytes are authentic.
     */
    @Transactional
    public ApplyResult apply(SignedSkillBundle bundle, byte[] verifiedPayloadBytes, String sourceUrl) {
        // 0. Idempotency: this version is already the active bundle.
        Optional<SkillBundleEntity> existing = bundleRepo.findByVersion(bundle.version());
        if (existing.isPresent() && existing.get().isActive()) {
            writeSuccessStatus(bundle.version(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            return ApplyResult.alreadyApplied(bundle.version());
        }

        // 1. Parse payload.
        List<Map<String, Object>> skillMaps;
        try {
            Map<String, Object> root = objectMapper.readValue(verifiedPayloadBytes, JSON_MAP);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("skills");
            if (raw == null) return ApplyResult.failed("payload has no 'skills' array");
            skillMaps = raw;
        } catch (Exception e) {
            return ApplyResult.failed("payload parse failed: " + e.getMessage());
        }
        // The cloud refuses to build an empty bundle, so an empty payload is malformed -
        // never mass-soft-remove the whole global set off a bad/empty fetch.
        if (skillMaps.isEmpty()) {
            return ApplyResult.failed("payload 'skills' array is empty - refusing to wipe global skills");
        }

        // 2. Upsert each bundle skill, tracking which keys are still present.
        int inserted = 0, updated = 0;
        Set<String> presentKeys = new HashSet<>();
        for (Map<String, Object> m : skillMaps) {
            String key = asString(m.get("key"));
            if (key == null || key.isBlank()) {
                return ApplyResult.failed("a skill entry has no 'key'");
            }
            presentKeys.add(key);

            String name = asString(m.get("name"));
            String description = asString(m.get("description"));
            String icon = asString(m.get("icon"));
            String instructions = asString(m.get("instructions"));
            boolean defaultActive = Boolean.TRUE.equals(m.get("isDefaultActive"));

            Optional<SkillEntity> row = skillRepository.findBySourceBundleKey(key);
            if (row.isPresent()) {
                SkillEntity s = row.get();
                s.setName(name);
                s.setDescription(description);
                s.setIcon(icon);
                s.setInstructions(instructions);
                s.setIsDefaultActive(defaultActive);
                // Re-globalise / re-activate a previously soft-removed row that came back.
                s.setIsGlobal(true);
                s.setIsActive(true);
                skillRepository.save(s);
                updated++;
            } else {
                SkillEntity s = new SkillEntity(BUNDLE_TENANT, name, description, icon, instructions, true);
                s.setOrganizationId(BUNDLE_TENANT); // explicit -> OrgScopedEntityListener skips
                s.setIsGlobal(true);
                s.setIsDefaultActive(defaultActive);
                s.setSourceBundleKey(key);
                skillRepository.save(s);
                inserted++;
            }
        }

        // 3. Soft-remove bundle-owned rows whose cloud skill disappeared from the bundle.
        int removed = 0;
        for (SkillEntity s : skillRepository.findBySourceBundleKeyIsNotNull()) {
            if (!presentKeys.contains(s.getSourceBundleKey())
                    && Boolean.TRUE.equals(s.getIsGlobal())) {
                s.setIsGlobal(false);
                s.setIsActive(false);
                skillRepository.save(s);
                removed++;
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 4. Persist / flip the skill_bundles row (mirrors the catalog applier).
        SkillBundleEntity entity = existing.orElseGet(() -> {
            SkillBundleEntity e = new SkillBundleEntity();
            e.setVersion(bundle.version());
            e.setSchemaVersion(bundle.schemaVersion());
            e.setChecksum(bundle.checksum());
            e.setSignature(bundle.signature());
            e.setSigningKeyId(bundle.signingKeyId());
            e.setIssuer(bundle.issuer());
            e.setSkillCount(bundle.skillCount());
            e.setRawBytesSize(bundle.rawBytesSize());
            e.setImportedAt(now);
            return e;
        });
        entity.setSourceUrl(sourceUrl);
        bundleRepo.deactivateAll();
        entity.setActive(true);
        entity.setActivatedAt(now);
        bundleRepo.save(entity);

        // 5. Sync-status OK.
        writeSuccessStatus(bundle.version(), now);

        log.info("Applied skill bundle v{} - inserted={}, updated={}, softRemoved={}",
                bundle.version(), inserted, updated, removed);
        return new ApplyResult(Status.APPLIED, bundle.version(), inserted, updated, removed, null);
    }

    private void writeSuccessStatus(long version, Instant now) {
        SkillBundleSyncStatusEntity status = syncStatusRepo
                .findById(SkillBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(SkillBundleSyncStatusEntity::new);
        status.setLastAppliedVersion(version);
        status.setLastAppliedAt(now);
        status.setLastFetchAt(now);
        status.setLastFetchStatus("OK");
        status.setLastFetchError(null);
        status.setConsecutiveFailures(0);
        status.setUpdatedAt(now);
        syncStatusRepo.save(status);
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
