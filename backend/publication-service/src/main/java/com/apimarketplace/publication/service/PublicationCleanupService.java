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
        // 1. Candidate set = ACTIVE publications of type WORKFLOW (same schema, no cross-schema issue).
        //
        // The type filter is the point, and it is not defensive dressing. V38 made workflow_id
        // nullable precisely because "AGENT publications have no workflow", so an AGENT row's
        // null flowed into the set below and UUID::toString then raised an NPE from OUTSIDE
        // getExistingWorkflowIds' try block: the run aborted into the outer catch at
        // cleanupStalePublications and NOT ONE of the 131 WORKFLOW publications was ever checked
        // for orphanhood. The scheduler survived, the work did not, silently, every night since
        // the first ACTIVE NON-WORKFLOW publication existed - AGENT is merely the type prod
        // happens to hold; INTERFACE, TABLE and SKILL rows never get a workflow_id either
        // (ResourcePublicationService never sets one). This is a whole dead job, not one odd row.
        //
        // Filtering on publication_type rather than on "workflow_id IS NOT NULL" says what is
        // actually meant: this job answers "does the referenced workflow still exist", a question
        // with no meaning for a publication whose subject is an agent, a table or a page.
        // The IS NOT NULL stays as a data-hygiene guard for a malformed WORKFLOW row.
        //
        // KNOWN GAP, deliberately not widened here: WORKFLOW is one of five publication types
        // (WORKFLOW, AGENT, TABLE, INTERFACE, SKILL), so the other FOUR are never orphan-checked
        // at all. Each needs an existence probe against a different service, keyed on a different
        // column - a different client call and a different contract than this method's.
        List<UUID> activeWorkflowIds = em.createNativeQuery(
                "SELECT workflow_id FROM workflow_publications "
                        + "WHERE status = 'ACTIVE' AND publication_type = 'WORKFLOW' "
                        + "AND workflow_id IS NOT NULL")
                .getResultList();

        if (activeWorkflowIds.isEmpty()) {
            return 0;
        }

        // Second gate, and it is NOT redundant with the SQL above. Two different things throw on
        // a null: the client's own mapping at step 2 (guarded there), and the orphan test at
        // step 3, which calls existingIds.contains(id) - and on the client's two EMPTY branches
        // existingIds is an immutable Set.of(), whose contains(null) THROWS. (Its fail-safe
        // branch hands back our own HashSet, which tolerates a null; the empty ones do not.)
        // Neither gate covers the other, so this one filters as the set is built, which is the
        // only placement that also holds for a future caller bypassing the query above.
        Set<UUID> workflowIdSet = activeWorkflowIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (workflowIdSet.isEmpty()) {
            return 0;
        }

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
