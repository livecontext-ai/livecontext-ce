package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelCatalogSyncLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelCatalogSyncLogRepository extends JpaRepository<ModelCatalogSyncLogEntity, Long> {

    /** Most recent successful apply (non-dry-run). Feeds the count-floor guard. */
    Optional<ModelCatalogSyncLogEntity>
    findFirstByOutcomeAndDryRunOrderByCreatedAtDesc(
            ModelCatalogSyncLogEntity.Outcome outcome, Boolean dryRun);

    /**
     * Most recent successful apply whose LiteLLM count is non-null. Used by
     * the per-feed count-floor baseline - skips runs where LiteLLM failed
     * to fetch.
     */
    Optional<ModelCatalogSyncLogEntity>
    findFirstByOutcomeAndDryRunAndLiteLlmCountIsNotNullOrderByCreatedAtDesc(
            ModelCatalogSyncLogEntity.Outcome outcome, Boolean dryRun);

    /** Counterpart for OpenRouter. */
    Optional<ModelCatalogSyncLogEntity>
    findFirstByOutcomeAndDryRunAndOpenRouterCountIsNotNullOrderByCreatedAtDesc(
            ModelCatalogSyncLogEntity.Outcome outcome, Boolean dryRun);

    /** For the admin UI history tab. */
    List<ModelCatalogSyncLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
