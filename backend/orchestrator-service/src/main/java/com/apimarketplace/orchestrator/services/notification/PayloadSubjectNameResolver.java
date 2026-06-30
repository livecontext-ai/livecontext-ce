package com.apimarketplace.orchestrator.services.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Base resolver for cross-service categories where the producer ships the
 * subject name inline via {@code payload.subjectName}. Concrete subclasses
 * differ only by {@link #subjectType()}.
 *
 * <p>Why not a live name JOIN like {@link WorkflowSubjectNameResolver}? For
 * cross-service subjects (triggers in trigger-service, credentials in
 * auth-service, agent_tasks in agent-service) the orchestrator schema does
 * NOT own the source-of-truth row. Calling out via {@code trigger-client} /
 * {@code auth-client} / {@code agent-client} on every bell fetch would burn
 * 4 cross-service round-trips per fetch - unacceptable at 60s polling
 * cadence per user.
 *
 * <p>The producer already includes {@code subjectName} in the notification
 * payload at emit time (see P4/P5/P6 wiring). The resolver reads the
 * latest-per-{@code subject_id} payload string, scoped to its
 * {@link #subjectType()} for tenant safety.
 *
 * <p>Tradeoff: a rename in trigger-service / agent-service does NOT propagate
 * to existing notification rows. Acceptable because (a) the bell shows
 * recent rows (30d retention) and (b) renames are rare for these resources.
 * If/when this gap matters, swap to a live cross-service lookup with cache.
 */
abstract class PayloadSubjectNameResolver implements SubjectNameResolver {

    private static final Logger logger = LoggerFactory.getLogger(PayloadSubjectNameResolver.class);

    /** Fallback when {@code payload.subjectName} is missing or null. */
    static final String DEFAULT_NAME_FALLBACK = "Notification";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<UUID, String> resolveNames(Set<UUID> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) return Map.of();
        try {
            // Postgres-only: SELECT DISTINCT ON returns the first row per
            // subject_id under the ORDER BY (= latest occurred_at). Bound
            // by subject_type so a UUID collision across types (extremely
            // unlikely with random UUIDs but cheap to guard) cannot leak
            // a wrong name into the wrong bucket.
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT DISTINCT ON (subject_id) subject_id, payload->>'subjectName' AS name " +
                            "  FROM orchestrator.notifications " +
                            " WHERE subject_type = :st AND subject_id IN (:ids) " +
                            " ORDER BY subject_id, occurred_at DESC")
                    .setParameter("st", subjectType())
                    .setParameter("ids", subjectIds)
                    .getResultList();

            Map<UUID, String> out = new HashMap<>(rows.size());
            for (Object[] row : rows) {
                UUID id = (UUID) row[0];
                String name = (String) row[1];
                out.put(id, name != null && !name.isBlank() ? name : DEFAULT_NAME_FALLBACK);
            }
            return out;
        } catch (Exception ex) {
            // Resolver failure is non-fatal - bell renders DELETED_WORKFLOW_LABEL
            // for missing names. Log + return empty so the read path stays up.
            logger.warn("[notification] {} resolver failed: {}",
                    subjectType(), ex.getMessage());
            return Map.of();
        }
    }
}
