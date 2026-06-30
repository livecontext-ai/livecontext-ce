package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.domain.ModelCatalogSyncLogEntity;
import com.apimarketplace.agent.repository.ModelCatalogSyncLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Writes a {@link ModelCatalogSyncLogEntity} row in a separate transaction
 * so that a failed merge in {@link ModelCatalogSyncService#sync} doesn't
 * poison the log insert. Without {@link Propagation#REQUIRES_NEW}, a
 * RuntimeException from the merge rolls back the enclosing TX AND blocks
 * the subsequent log insert with "current transaction is aborted".
 *
 * <p>The contract is: every sync attempt produces exactly one log row,
 * whatever the outcome - operators rely on this to audit failures.
 */
@Component
@RequiredArgsConstructor
public class ModelCatalogSyncLogWriter {

    private final ModelCatalogSyncLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelCatalogSyncLogEntity write(
            String source, Instant fetchedAt, int modelCount, String checksum,
            String triggeredBy, boolean dryRun,
            ModelCatalogSyncLogEntity.Outcome outcome, String errorDetail,
            Map<String, Object> guardFailures,
            int added, int updated, int deprecated, int flagged,
            Integer liteLlmCount, Integer openRouterCount) {

        ModelCatalogSyncLogEntity e = new ModelCatalogSyncLogEntity();
        e.setSource(source);
        e.setFetchedAt(fetchedAt);
        e.setModelCount(modelCount);
        e.setChecksum(checksum);
        e.setTriggeredBy(triggeredBy);
        e.setDryRun(dryRun);
        e.setOutcome(outcome);
        e.setErrorDetail(errorDetail);
        e.setGuardFailures(guardFailures);
        e.setAddedCount(added);
        e.setUpdatedCount(updated);
        e.setDeprecatedCount(deprecated);
        e.setFlaggedCount(flagged);
        e.setLiteLlmCount(liteLlmCount);
        e.setOpenRouterCount(openRouterCount);
        return repo.save(e);
    }
}
