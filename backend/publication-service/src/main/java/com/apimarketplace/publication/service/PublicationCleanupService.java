package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled cleanup for stale publications.
 * <ul>
 *   <li>Detects orphaned ACTIVE publications (workflow deleted) and sets them INACTIVE.</li>
 * </ul>
 *
 * Note: planSnapshot is NEVER cleared, even on INACTIVE publications,
 * because receipt holders can re-acquire at any time and need the snapshot.
 *
 * Runs daily at 3 AM. Checks workflow existence via orchestrator-service HTTP API
 * instead of cross-schema SQL queries.
 */
@Service
public class PublicationCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(PublicationCleanupService.class);

    @PersistenceContext
    private EntityManager em;

    private final OrchestratorInternalClient orchestratorClient;

    public PublicationCleanupService(OrchestratorInternalClient orchestratorClient) {
        this.orchestratorClient = orchestratorClient;
    }

    /**
     * Daily cleanup job for orphaned publications.
     * Deactivates ACTIVE publications whose workflow no longer exists.
     */
    @Scheduled(cron = "0 0 3 * * *") // Daily at 3 AM
    @SchedulerLock(name = "publication_cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupStalePublications() {
        try {
            int orphansDeactivated = deactivateOrphanedPublications();

            if (orphansDeactivated > 0) {
                logger.info("[PublicationCleanup] Deactivated {} orphaned publications", orphansDeactivated);
            } else {
                logger.debug("[PublicationCleanup] No orphaned publications found");
            }
        } catch (Exception e) {
            logger.error("[PublicationCleanup] Error in orphaned publications cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Deactivate ACTIVE publications whose workflow no longer exists in orchestrator-service.
     * Uses HTTP API to check workflow existence instead of cross-schema SQL.
     *
     * @return number of orphaned publications deactivated
     */
    @SuppressWarnings("unchecked")
    int deactivateOrphanedPublications() {
        // 1. Get all ACTIVE publication workflow IDs (same schema, no cross-schema issue)
        List<UUID> activeWorkflowIds = em.createNativeQuery(
                "SELECT workflow_id FROM workflow_publications WHERE status = 'ACTIVE'")
                .getResultList();

        if (activeWorkflowIds.isEmpty()) {
            return 0;
        }

        Set<UUID> workflowIdSet = new HashSet<>(activeWorkflowIds);

        // 2. Ask orchestrator which of these workflows still exist (HTTP call, no cross-schema SQL)
        Set<UUID> existingIds = orchestratorClient.getExistingWorkflowIds(workflowIdSet);

        // 3. Find orphaned ones (in publications but not in orchestrator)
        Set<UUID> orphanedIds = workflowIdSet.stream()
                .filter(id -> !existingIds.contains(id))
                .collect(Collectors.toSet());

        if (orphanedIds.isEmpty()) {
            return 0;
        }

        // 4. Deactivate orphaned publications (same schema)
        int updated = em.createNativeQuery(
                "UPDATE workflow_publications SET status = 'INACTIVE', updated_at = now() " +
                "WHERE status = 'ACTIVE' AND workflow_id IN (:ids)")
                .setParameter("ids", orphanedIds)
                .executeUpdate();

        if (updated > 0) {
            logger.info("[PublicationCleanup] Deactivated {} orphaned ACTIVE publication(s)", updated);
        }
        return updated;
    }

}
