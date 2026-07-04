package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.domain.ModelSeedStateEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.repository.ModelSeedStateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Boot-time seed of the curated LLM model catalog from a versioned classpath
 * resource ({@code model-catalog/models.json}), shipped with the code so a
 * fresh CE / {@code git clone} - and every RELEASE UPGRADE - gets the current
 * curated models WITHOUT a new Flyway migration per change (regenerate the JSON
 * from the cloud catalog at release, bump its {@code version}, commit).
 *
 * <p><b>CE-only.</b> Gated on {@code model-catalog.seed.enabled=true} (set in
 * the CE profile). The cloud is the authoritative source of the catalog
 * (admin UI + V112 floor + feed sync), so it never runs this. Runs BEFORE the
 * signed-bundle bootstrap ({@code @Order}) so the seed is the baseline and a
 * cloud bundle (when linked) still wins on top.
 *
 * <p><b>Versioned, update-capable, user-config-preserving.</b> The seed carries
 * a top-level {@code version}. It re-applies ONLY when that version is greater
 * than the last-applied value tracked in {@link ModelSeedStateEntity} (single
 * row, V387) - so a release refresh happens exactly once and an unchanged
 * version is a pure no-op (no per-boot churn). When it does apply it goes
 * through the SAME {@link CatalogMergeService#merge} as the signed bundle, so it
 * inherits the bundle's safety contract:
 * <ul>
 *   <li>{@code is_custom=true} rows (CE-local adds) are never touched.</li>
 *   <li>Every field an admin edited (recorded in {@code user_modified_fields}:
 *       {@code enabled}, {@code ranking}/order, prices, …) is preserved - a
 *       version bump never clobbers the operator's choices.</li>
 *   <li>{@link MergeOptions#forSeed()} is a PARTIAL/patch merge
 *       ({@code partialUpdate=true}): on an UPDATE it only writes the fields the
 *       payload carries, so a minimal curated seed never nulls the enrichment
 *       (tier, contextWindow, capability flags…) a bundle/feed/admin set on a
 *       row. {@code deprecateMissing=false}: models absent from the seed are
 *       left alone. The seed carries NO {@code categories} field (per-category
 *       settings are cloud-authoritative).</li>
 * </ul>
 *
 * <p><b>Unversioned fallback.</b> If the JSON has no positive {@code version},
 * the seed falls back to legacy INSERT-ONLY behaviour (additive, idempotent, no
 * marker write) so an old/hand-trimmed seed can never trigger a full merge on
 * every boot.
 *
 * <p>Failures are swallowed (logged) - a malformed/missing seed must never
 * block application startup; the install simply keeps whatever catalog it has.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "model-catalog.seed.enabled", havingValue = "true")
public class ModelSeedBootstrapService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final CatalogMergeService mergeService;
    private final ModelConfigOverrideRepository repository;
    private final ModelSeedStateRepository seedStateRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Resource seedResource;

    public ModelSeedBootstrapService(
            CatalogMergeService mergeService,
            ModelConfigOverrideRepository repository,
            ModelSeedStateRepository seedStateRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager txManager,
            @Value("${model-catalog.seed.resource:classpath:model-catalog/models.json}") Resource seedResource) {
        this.mergeService = mergeService;
        this.repository = repository;
        this.seedStateRepository = seedStateRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(txManager);
        this.seedResource = seedResource;
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(100) // BEFORE SeedBundleBootstrap (@Order(200)) - baseline first, authoritative bundle second
    public void seedOnStartup() {
        try {
            seedNow();
        } catch (Exception e) {
            // Non-fatal: a bad seed must not crash-loop the app. Log and move on.
            log.error("Model-catalog seed failed (non-fatal - keeping existing catalog): {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Apply the seed once. Returns the number of inserted rows. Package-private
     * so unit tests can invoke it without firing an {@link ApplicationReadyEvent}.
     */
    int seedNow() throws Exception {
        if (seedResource == null || !seedResource.exists()) {
            log.warn("Model-catalog seed resource missing ({}) - skipping seed", seedResource);
            return 0;
        }

        SeedDoc doc = readSeed();
        List<Map<String, Object>> seedModels = doc.models();
        if (seedModels.isEmpty()) {
            log.info("Model-catalog seed has no models - nothing to apply");
            return 0;
        }

        Long seedVersion = doc.version();
        if (seedVersion == null || seedVersion <= 0) {
            // Unversioned / invalid: never full-merge on every boot. Stay additive.
            return applyInsertOnly(seedModels);
        }

        Long applied = loadAppliedVersion();
        if (applied != null && applied >= seedVersion) {
            log.info("Model-catalog seed v{} already applied (marker=v{}) - no changes",
                    seedVersion, applied);
            return 0;
        }

        // Version bumped (or first ever apply): run the merge so existing curated
        // rows are refreshed too. forSeed() is a PARTIAL/patch merge, so is_custom
        // + user_modified_fields keep operator edits AND omitted fields keep their
        // bundle/feed enrichment; deprecateMissing=false keeps unrelated rows.
        CatalogMergeService.MergeResult result = txTemplate.execute(status -> {
            CatalogMergeService.MergeResult r = mergeService.merge(seedModels, MergeOptions.forSeed());
            saveAppliedVersion(seedVersion);
            return r;
        });
        int inserted = result == null ? 0 : result.inserted();
        int updated = result == null ? 0 : result.updated();
        int skippedUserModified = result == null ? 0 : result.skippedUserModified();
        log.info("Model-catalog seed v{} applied (was v{}): inserted={} updated={} " +
                        "(user-modified rows preserved={}) of {} curated model(s)",
                seedVersion, applied, inserted, updated, skippedUserModified, seedModels.size());
        return inserted;
    }

    /**
     * Legacy additive path: insert only models absent from the catalog, never
     * update. Used when the seed carries no usable {@code version}. Does not
     * advance the seed-state marker (there is no version to record).
     */
    private int applyInsertOnly(List<Map<String, Object>> seedModels) {
        Set<String> present = repository.findAllByOrderByRankingAsc().stream()
                .map(r -> key(r.getProvider(), r.getModelId()))
                .collect(Collectors.toSet());

        List<Map<String, Object>> toInsert = seedModels.stream()
                .filter(m -> m.get("provider") != null && m.get("modelId") != null)
                .filter(m -> !present.contains(key(str(m.get("provider")), str(m.get("modelId")))))
                .collect(Collectors.toList());

        if (toInsert.isEmpty()) {
            log.info("Model-catalog seed (unversioned): all {} curated models already present - no inserts",
                    seedModels.size());
            return 0;
        }

        CatalogMergeService.MergeResult result = txTemplate.execute(status ->
                mergeService.merge(toInsert, MergeOptions.forSeed()));
        int inserted = result == null ? 0 : result.inserted();
        log.info("Model-catalog seed (unversioned) applied: inserted={} new model(s) ({} of {} already present)",
                inserted, seedModels.size() - toInsert.size(), seedModels.size());
        return inserted;
    }

    private Long loadAppliedVersion() {
        return seedStateRepository.findById(ModelSeedStateEntity.SINGLETON_ID)
                .map(ModelSeedStateEntity::getAppliedVersion)
                .orElse(null);
    }

    private void saveAppliedVersion(long version) {
        ModelSeedStateEntity row = seedStateRepository.findById(ModelSeedStateEntity.SINGLETON_ID)
                .orElseGet(ModelSeedStateEntity::new);
        row.setId(ModelSeedStateEntity.SINGLETON_ID);
        row.setAppliedVersion(version);
        row.setAppliedAt(Instant.now());
        seedStateRepository.save(row);
    }

    /** Parsed seed document: top-level {@code version} (nullable) + {@code models}. */
    private record SeedDoc(Long version, List<Map<String, Object>> models) {}

    private SeedDoc readSeed() throws Exception {
        try (InputStream in = seedResource.getInputStream()) {
            Map<String, Object> doc = objectMapper.readValue(in, JSON_MAP);
            Long version = toLong(doc.get("version"));
            Object models = doc.get("models");
            if (!(models instanceof List<?> list)) {
                log.warn("Model-catalog seed has no 'models' array - skipping");
                return new SeedDoc(version, List.of());
            }
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
            return new SeedDoc(version, out);
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String key(String provider, String modelId) {
        return provider + '\0' + modelId;
    }
}
