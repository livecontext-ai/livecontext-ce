package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Boot-time seed of the curated LLM model catalog from a versioned classpath
 * resource ({@code model-catalog/models.json}), shipped with the code so a
 * fresh CE / {@code git clone} has the latest curated models WITHOUT a new
 * Flyway migration per change (edit the JSON, commit - that's it).
 *
 * <p><b>CE-only.</b> Gated on {@code model-catalog.seed.enabled=true} (set in
 * the CE profile). The cloud is the authoritative source of the catalog
 * (admin UI + V112 floor + feed sync), so it never runs this.
 *
 * <p><b>Insert-only / additive.</b> Applies ONLY models absent from
 * {@code model_config_overrides}; it never updates or deprecates existing rows.
 * Rationale:
 * <ul>
 *   <li>The seed payload is intentionally minimal (provider, modelId,
 *       displayName, enabled, prices). Pushing it through the full merge as an
 *       <em>update</em> would null the enrichment fields it doesn't carry
 *       (tier, contextWindow, …) on rows a bundle/admin already enriched.
 *       Insert-only sidesteps that clobber entirely.</li>
 *   <li>Idempotent with zero state: once a model is present (via this seed, the
 *       V112 floor, a bundle, or an admin add) it is skipped forever - no
 *       version marker table, no per-boot writes, no audit-chain churn.</li>
 *   <li>Mutations to EXISTING models (pricing/tier refresh) are the cloud
 *       bundle's job (the live, signed channel) - not the seed's.</li>
 * </ul>
 * New rows are inserted via {@link MergeOptions#forSeed()} ({@code source=curated},
 * {@code honorEnabledOnInsert=true} so they're usable out of the box, and
 * {@code deprecateMissing=false} so models the seed omits are left alone).
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
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Resource seedResource;

    public ModelSeedBootstrapService(
            CatalogMergeService mergeService,
            ModelConfigOverrideRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager txManager,
            @Value("${model-catalog.seed.resource:classpath:model-catalog/models.json}") Resource seedResource) {
        this.mergeService = mergeService;
        this.repository = repository;
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

        List<Map<String, Object>> seedModels = readModels();
        if (seedModels.isEmpty()) {
            log.info("Model-catalog seed has no models - nothing to apply");
            return 0;
        }

        Set<String> present = repository.findAllByOrderByRankingAsc().stream()
                .map(r -> key(r.getProvider(), r.getModelId()))
                .collect(Collectors.toSet());

        List<Map<String, Object>> toInsert = seedModels.stream()
                .filter(m -> m.get("provider") != null && m.get("modelId") != null)
                .filter(m -> !present.contains(key(str(m.get("provider")), str(m.get("modelId")))))
                .collect(Collectors.toList());

        if (toInsert.isEmpty()) {
            log.info("Model-catalog seed: all {} curated models already present - no inserts",
                    seedModels.size());
            return 0;
        }

        CatalogMergeService.MergeResult result = txTemplate.execute(status ->
                mergeService.merge(toInsert, MergeOptions.forSeed()));
        int inserted = result == null ? 0 : result.inserted();
        log.info("Model-catalog seed applied: inserted={} new model(s) ({} of {} already present)",
                inserted, seedModels.size() - toInsert.size(), seedModels.size());
        return inserted;
    }

    private List<Map<String, Object>> readModels() throws Exception {
        try (InputStream in = seedResource.getInputStream()) {
            Map<String, Object> doc = objectMapper.readValue(in, JSON_MAP);
            Object models = doc.get("models");
            if (!(models instanceof List<?> list)) {
                log.warn("Model-catalog seed has no 'models' array - skipping");
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
            return out;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String key(String provider, String modelId) {
        return provider + '\0' + modelId;
    }
}
