package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleApplier;
import com.apimarketplace.agent.catalog.bundle.CatalogBundleVerifier;
import com.apimarketplace.agent.catalog.bundle.SignedBundle;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CE boot-time apply of the SIGNED model-catalog seed bundle
 * ({@code catalog-seeds/model-bundle.json}, baked into every CE release by
 * publish-ce from the cloud's public {@code /api/catalog-bundles/seed}).
 *
 * <p>Same shape as the API-catalog seed (a release-time snapshot file shipped
 * in {@code catalog-seeds/}), but goes through the FULL bundle pipeline:
 * Ed25519 signature verification against the pinned/bootstrapped trust keys,
 * then {@link CatalogBundleApplier} (merge with {@code user_modified_fields}
 * preservation, version-gated idempotency). A fresh, never-linked install
 * therefore starts with the models of its release instead of the static
 * classpath seed's ancient list - and each {@code docker compose pull}
 * refreshes them. Continuous freshness between releases stays a cloud-link
 * benefit (the 15-minute sync).
 *
 * <p>Ordering: both seeds listen on {@link ApplicationReadyEvent};
 * {@link ModelSeedBootstrapService} runs first ({@code @Order(100)} vs 200)
 * so the insert-only classpath baseline lands, then this signed bundle - the
 * authoritative cloud snapshot - updates/supersedes matching rows and
 * deprecates leftovers absent from it. Both are idempotent and the bundle's
 * version-gate makes replays no-ops.
 *
 * <p>Never blocks startup: a missing file is normal on the cloud and on
 * source checkouts; malformed/unverifiable content logs a warning and the
 * install keeps whatever catalog it has.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "model-catalog.seed.enabled", havingValue = "true")
public class SeedBundleBootstrap {

    private final CatalogBundleVerifier verifier;
    private final CatalogBundleApplier applier;
    private final CatalogBundleSyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;
    private final String bundlePath;

    public SeedBundleBootstrap(CatalogBundleVerifier verifier,
                               CatalogBundleApplier applier,
                               CatalogBundleSyncStatusRepository syncStatusRepo,
                               ObjectMapper objectMapper,
                               @Value("${model-catalog.seed.bundle-path:/app/catalog-seeds/model-bundle.json}") String bundlePath) {
        this.verifier = verifier;
        this.applier = applier;
        this.syncStatusRepo = syncStatusRepo;
        this.objectMapper = objectMapper;
        this.bundlePath = bundlePath;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(200) // AFTER ModelSeedBootstrapService (@Order(100)): the signed bundle is authoritative
    public void applySeedBundle() {
        try {
            Path path = Path.of(bundlePath);
            if (!Files.isRegularFile(path)) {
                log.debug("No model seed bundle at {} - skipping (normal on cloud/source checkouts)", bundlePath);
                return;
            }

            SignedBundle bundle = objectMapper.readValue(Files.readAllBytes(path), SignedBundle.class);

            Long lastApplied = syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)
                    .map(CatalogBundleSyncStatusEntity::getLastAppliedVersion)
                    .orElse(null);
            if (lastApplied != null && lastApplied >= bundle.version()) {
                log.info("Model seed bundle v{} is not newer than applied v{} - skipping", bundle.version(), lastApplied);
                return;
            }

            CatalogBundleVerifier.Result verified = verifier.verify(bundle);
            if (!verified.ok()) {
                log.warn("Model seed bundle at {} failed verification ({}: {}) - ignoring it",
                        bundlePath, verified.status(), verified.detail());
                return;
            }

            CatalogBundleApplier.ApplyResult result =
                    applier.apply(bundle, verified.payloadBytes(), "seed:" + path.getFileName());
            log.info("Model seed bundle v{} applied at boot: status={} inserted={} updated={}",
                    bundle.version(), result.status(), result.inserted(), result.updated());
        } catch (Exception e) {
            // A bad seed file must never take the install down.
            log.warn("Model seed bundle apply failed (install keeps its current catalog): {}", e.getMessage());
        }
    }
}
