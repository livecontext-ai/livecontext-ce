package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-time backfill that captures {@code showcase_snapshot} JSONB on every
 * existing publication that still references a legacy {@code showcase_*}
 * clone-row in {@code orchestrator.workflow_runs}, then deletes the clone.
 *
 * <p>Idempotent: skips publications that already carry a snapshot. Safe to
 * call repeatedly - designed to be triggered from an authenticated admin
 * endpoint after deployment.
 */
@Service
public class ShowcaseSnapshotBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseSnapshotBackfillService.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final OrchestratorInternalClient orchestratorClient;
    @PersistenceContext
    private EntityManager entityManager;

    public ShowcaseSnapshotBackfillService(WorkflowPublicationRepository publicationRepository,
                                            OrchestratorInternalClient orchestratorClient) {
        this.publicationRepository = publicationRepository;
        this.orchestratorClient = orchestratorClient;
    }

    /**
     * Backfill every publication whose {@code showcase_snapshot} is null but
     * whose {@code showcase_run_id} resolves to a clone row.
     *
     * @return per-pub result rows for caller observability
     */
    @Transactional
    public List<Map<String, Object>> backfillAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        Pageable page = PageRequest.of(0, 50);
        Page<WorkflowPublicationEntity> batch;
        do {
            batch = publicationRepository.findAll(page);
            batch.getContent().stream()
                    .filter(p -> p.getShowcaseSnapshot() == null)
                    .filter(p -> p.getShowcaseRunId() != null && !p.getShowcaseRunId().isEmpty())
                    .map(this::backfillOne)
                    .forEach(results::add);
            entityManager.flush();
            entityManager.clear();
            page = batch.nextPageable();
        } while (batch.hasNext());
        return results;
    }

    @Transactional
    public Map<String, Object> backfillOne(WorkflowPublicationEntity pub) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("publicationId", pub.getId().toString());
        result.put("oldRunId", pub.getShowcaseRunId());
        try {
            Map<String, Object> snapshot = orchestratorClient.captureShowcaseSnapshot(
                    pub.getShowcaseRunId(), pub.getPublisherId());
            if (snapshot == null || snapshot.isEmpty()) {
                result.put("status", "skipped_empty");
                return result;
            }
            pub.setShowcaseSnapshot(snapshot);
            pub.setShowcaseSnapshotCapturedAt(Instant.now());
            publicationRepository.save(pub);

            // Delete the legacy clone row so the publisher's tenant stops
            // showing it as a phantom run. Best-effort - failure here does
            // not invalidate the snapshot we just stored.
            String oldRunId = pub.getShowcaseRunId();
            if (oldRunId != null && oldRunId.startsWith("showcase_")) {
                try {
                    orchestratorClient.deleteClonedRun(oldRunId);
                    result.put("clonedRunDeleted", true);
                } catch (Exception e) {
                    log.warn("Failed to delete legacy clone {} for pub {}: {}",
                            oldRunId, pub.getId(), e.getMessage());
                    result.put("clonedRunDeleted", false);
                }
            }
            result.put("status", "captured");
        } catch (Exception e) {
            log.error("Backfill failed for pub {}: {}", pub.getId(), e.getMessage(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }
}
