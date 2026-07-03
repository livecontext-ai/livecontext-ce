package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CLOUD-side auto-republish of the model-catalog bundle (V381).
 *
 * <p>Every catalog mutation (admin Models panel, provider feed sync) makes the
 * ACTIVE bundle lag the live table. Pre-V381 that lag made every bundle
 * download 409 until an operator manually clicked Build + Activate; with
 * payload persistence the old bundle keeps serving, and this scheduler closes
 * the freshness gap: when the active bundle is stale it builds a new one,
 * activates it, and prunes old inactive rows so the table stays bounded.
 *
 * <p>Deliberately conservative:
 * <ul>
 *   <li>Never runs where signing is impossible ({@code signer.canSign()} is
 *       false on every CE - the Ed25519 private key is a cloud secret), so the
 *       bean is inert on CE installs even though the class is on the classpath.</li>
 *   <li>Never performs the FIRST activation: an install with no active bundle
 *       keeps that state until an admin decides to start distributing
 *       (manual Build + Activate). Automation only FOLLOWS a decision already
 *       made.</li>
 *   <li>Multi-pod: {@code @SchedulerLock} (the service-wide pattern) keeps a
 *       single pod per tick. Even without it the flow stays correct
 *       (version-collision retry + one-active partial unique index), but the
 *       lock avoids duplicate builds and version churn.</li>
 * </ul>
 *
 * <p>Operator note - deliberate ROLLBACK interaction: activating an OLDER
 * bundle by hand makes it stale by definition (its checksum lags the live
 * table), so the next tick republishes from live and supersedes the rollback.
 * To hold a rollback, disable this scheduler first
 * ({@code catalog.bundle.auto-rebuild.enabled=false}) or fix the live catalog
 * (the rollback's real goal) and let the republish carry the correction.
 *
 * <p>Config: {@code catalog.bundle.auto-rebuild.enabled} (default true),
 * {@code catalog.bundle.auto-rebuild.interval-ms} (default 5 min),
 * {@code catalog.bundle.retention.keep} (default 20 inactive bundles).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.bundle.auto-rebuild.enabled", havingValue = "true", matchIfMissing = true)
public class CatalogBundleAutoRebuildScheduler {

    private final CatalogBundleService bundleService;
    private final CatalogBundleSigner signer;
    private final int retentionKeep;

    public CatalogBundleAutoRebuildScheduler(
            CatalogBundleService bundleService,
            CatalogBundleSigner signer,
            @Value("${catalog.bundle.retention.keep:20}") int retentionKeep) {
        this.bundleService = bundleService;
        this.signer = signer;
        this.retentionKeep = retentionKeep;
    }

    @Scheduled(
            fixedDelayString = "${catalog.bundle.auto-rebuild.interval-ms:300000}",
            initialDelayString = "${catalog.bundle.auto-rebuild.initial-delay-ms:120000}")
    @SchedulerLock(name = "catalog-bundle-auto-rebuild",
            lockAtMostFor = "4m", lockAtLeastFor = "30s")
    public void tick() {
        if (!signer.canSign()) {
            return; // CE / unsigned deployment: nothing to publish, stay silent.
        }
        try {
            if (!bundleService.isActiveBundleStale()) {
                return;
            }
            CatalogBundleEntity built = bundleService.buildBundle();
            bundleService.activateBundle(built.getId());
            int pruned = bundleService.pruneInactiveBundles(retentionKeep);
            log.info("Auto-republished catalog bundle: version={} models={} (pruned {} old bundles)",
                    built.getVersion(), built.getModelCount(), pruned);
        } catch (Exception e) {
            // Never let a failed republish break the scheduler - the active
            // bundle keeps serving (payload persisted) and the next tick retries.
            log.warn("Catalog bundle auto-rebuild tick failed (will retry): {}", e.getMessage());
        }
    }
}
