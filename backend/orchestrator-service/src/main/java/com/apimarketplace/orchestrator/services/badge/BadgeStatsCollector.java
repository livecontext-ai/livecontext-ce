package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.BadgeProfileDto;
import com.apimarketplace.publication.client.PublicationClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads {@link BadgeMetric} values for one user, in as few round-trips as the
 * data ownership allows: four grouped SQL statements against the orchestrator
 * schema, one call to publication-service, one to auth-service.
 *
 * <p><b>Only what is still needed.</b> {@link #collect(String, Set)} takes the
 * set of metrics that still have a locked badge and skips every source no
 * longer relevant. That is what keeps the periodic sweep cheap: a user who has
 * maxed the marketplace family stops costing a publication-service call on
 * every pass, and a fully-decorated user costs nothing at all.
 *
 * <p><b>Failure policy</b>: every source is independent and every failure is
 * swallowed, leaving that metric at 0. Badges are never revoked, so a metric
 * that reads 0 because publication-service was briefly down only postpones an
 * unlock to the next evaluation. The opposite policy (propagate) would turn a
 * sibling service's blip into a 500 on the settings page.
 *
 * <p><b>Scope</b>: everything is keyed on {@code tenant_id} = the user's own id,
 * never the active organization. A trophy belongs to the person, so working
 * inside a team workspace must neither hide the user's own history nor credit
 * them with a teammate's - hence
 * {@link PublicationClient#getPublicationsByPublisherPersonalScope(String)}
 * rather than the org-forwarding read.
 */
@Component
public class BadgeStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(BadgeStatsCollector.class);

    /** Publication rows only count as "published" once they reach this status. */
    private static final String PUBLICATION_STATUS_ACTIVE = "ACTIVE";
    /** Visibility that means "anyone can find it" - the sharing badges' metric. */
    private static final String PUBLICATION_VISIBILITY_PUBLIC = "PUBLIC";

    /**
     * Stand-in bound to the "publications I own" list when the user owns none.
     * SQL has no empty {@code IN ()}, and the nil UUID is not a legal
     * {@code publication.workflow_publications} id, so the clause it sits in
     * matches nothing - exactly what an empty set should mean.
     */
    private static final UUID NO_PUBLICATION = new UUID(0L, 0L);

    private final PublicationClient publicationClient;
    private final AuthClient authClient;

    @PersistenceContext
    private EntityManager entityManager;

    public BadgeStatsCollector(PublicationClient publicationClient, AuthClient authClient) {
        this.publicationClient = publicationClient;
        this.authClient = authClient;
    }

    /**
     * Metric snapshot for one user, restricted to {@code needed}.
     *
     * <p>Read-only; safe to call from the request thread and from the periodic
     * sweep. Metrics outside {@code needed} are absent from the result and read
     * back as 0, which is correct by construction: the caller only omits a
     * metric when every badge measuring it is already unlocked, and unlocks are
     * permanent.
     */
    @Transactional(readOnly = true)
    public BadgeStats collect(String tenantId, Set<BadgeMetric> needed) {
        BadgeStats.Builder stats = BadgeStats.builder();
        if (tenantId == null || tenantId.isBlank() || needed == null || needed.isEmpty()) {
            return stats.build();
        }

        // Publications are read FIRST because the authoring counts need them:
        // telling an application the user built from one they installed is a
        // question about who owns the source publication, and that answer lives
        // in publication-service, not in this schema.
        List<UUID> ownPublicationIds = List.of(NO_PUBLICATION);
        if (needsAny(needed, BadgeMetric.PUBLICATIONS_PUBLISHED, BadgeMetric.PUBLICATIONS_PUBLIC,
                BadgeMetric.PUBLICATION_USES, BadgeMetric.APPLICATIONS_CREATED,
                BadgeMetric.WORKFLOWS_PINNED)) {
            ownPublicationIds = collectPublications(tenantId, stats);
        }
        if (needsAny(needed, BadgeMetric.WORKFLOWS_CREATED,
                BadgeMetric.APPLICATIONS_CREATED, BadgeMetric.WORKFLOWS_PINNED)) {
            collectAuthoring(tenantId, ownPublicationIds, stats);
        }
        // The run-row counts floor BOTH execution metrics, so the statement runs
        // for either of them even when only one is still locked.
        RunRowCounts runRows = RunRowCounts.NONE;
        if (needsAny(needed, BadgeMetric.RUNS_COMPLETED, BadgeMetric.RUNS_LAUNCHED)) {
            runRows = collectRuns(tenantId);
        }
        if (needsAny(needed, BadgeMetric.RUNS_LAUNCHED, BadgeMetric.RUNS_COMPLETED,
                BadgeMetric.ACTIVE_DAYS, BadgeMetric.NIGHT_LAUNCHES)) {
            collectExecutions(tenantId, runRows, stats);
        }
        if (needsAny(needed, BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF, BadgeMetric.MEMBER_DAYS)) {
            collectIdentity(tenantId, stats);
        }
        if (needsAny(needed, BadgeMetric.CHANNELS_CONNECTED, BadgeMetric.CHANNEL_SERVICES,
                BadgeMetric.REMOTE_DECISIONS)) {
            collectChannels(tenantId, stats);
        }
        return stats.build();
    }

    /**
     * Chat channels, all in this schema, in one statement.
     *
     * <p><b>Connected</b> means a destination whose test message arrived ({@code verified_at}),
     * not a row somebody tried to create: that is the moment the product can actually reach the
     * person. <b>Remote decisions</b> are counted from what records WHO decided, never from a
     * status alone: an agent request is only ever resolved from its chat ({@code decided_by} is
     * "&lt;service&gt;:&lt;presser&gt;"), while a workflow approval can also be decided in the app, so
     * only the ones whose signal was resolved by the delivery's own service count.
     *
     * <p>Keyed on the user as everywhere else: the links they connected, the requests their agents
     * sent, the approvals of their runs. So a teammate pressing in a group chat credits the owner of
     * the request, which is why the requirement reads "requests answered from a chat": the presser
     * is only known as a service handle, never as an account. One {@code ask_user} call with several
     * questions counts once per question, each being its own answer.
     *
     * <p><b>Not monotonic, knowingly.</b> Unlike the authored counts above, these rows are deleted
     * rather than soft-deleted: disconnecting a destination removes it and, by cascade, the requests
     * it resolved; re-running a node removes its stale signal wait and its deliveries. So the
     * progress bar toward the next tier can move back. No trophy is lost (unlocks are permanent),
     * and keeping these counts monotonic would need a ledger of their own, which the progress bar
     * alone does not justify.
     *
     * <p>Indexed for this read by V522 (partial tenant indexes), since it runs for nearly every user.
     */
    private void collectChannels(String tenantId, BadgeStats.Builder stats) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT (SELECT COUNT(*)
                              FROM orchestrator.chat_channel_links
                             WHERE tenant_id = :tenantId AND verified_at IS NOT NULL) AS connected,
                           (SELECT COUNT(DISTINCT b.channel)
                              FROM orchestrator.chat_channel_links l
                              JOIN orchestrator.chat_channel_bots b ON b.id = l.bot_id
                             WHERE l.tenant_id = :tenantId AND l.verified_at IS NOT NULL) AS services,
                           (SELECT COUNT(*)
                              FROM orchestrator.chat_authorization_requests
                             WHERE tenant_id = :tenantId AND status = 'RESOLVED'
                               AND decided_by IS NOT NULL)
                         + (SELECT COUNT(*)
                              FROM orchestrator.approval_channel_deliveries d
                              JOIN orchestrator.workflow_signal_waits s ON s.id = d.signal_wait_id
                             WHERE d.tenant_id = :tenantId
                               AND s.resolved_by LIKE d.channel || ':%') AS remote
                    """)
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
            stats.put(BadgeMetric.CHANNELS_CONNECTED, toLong(row[0]));
            stats.put(BadgeMetric.CHANNEL_SERVICES, toLong(row[1]));
            stats.put(BadgeMetric.REMOTE_DECISIONS, toLong(row[2]));
        } catch (RuntimeException ex) {
            log.warn("[badges] channel metrics failed for tenant {}: {}", tenantId, ex.getMessage());
        }
    }

    private static boolean needsAny(Set<BadgeMetric> needed, BadgeMetric... candidates) {
        for (BadgeMetric candidate : candidates) {
            if (needed.contains(candidate)) return true;
        }
        return false;
    }

    /**
     * Workflows / applications the user AUTHORED.
     *
     * <p><b>Why "authored" is not simply {@code source_publication_id IS NULL}.</b>
     * That test is right for a WORKFLOW, which the builder creates from nothing.
     * It is wrong for an APPLICATION, because <b>no APPLICATION row is ever
     * created by the builder</b>: the only writer is the internal
     * {@code create-application} endpoint, called either when the user PUBLISHES
     * (stamping the row with the id of their OWN brand-new publication) or when
     * someone ACQUIRES (stamping the publisher's). So the null test excluded
     * every application its owner had actually built and left the metric reading
     * ~0 for the most prolific app makers - the exact opposite of the intent.
     * Ownership of the source publication is what separates the two cases, hence
     * the {@code ownPublicationIds} list.
     *
     * <p>{@code WORKFLOWS_CREATED} deliberately keeps the strict null test: a
     * user who installs their own publication gets WORKFLOW clones stamped with
     * their own publication id, and installing is not authoring.
     *
     * <p>Soft-deleted rows ({@code is_active = false}) are deliberately counted.
     * The achievement is the act of creating, and excluding them would make the
     * metric non-monotonic - a user would watch a progress bar go backwards
     * after tidying up.
     */
    private void collectAuthoring(String tenantId, List<UUID> ownPublicationIds, BadgeStats.Builder stats) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*) FILTER (
                               WHERE workflow_type = 'WORKFLOW'
                                 AND source_publication_id IS NULL
                           ) AS workflows,
                           COUNT(*) FILTER (
                               WHERE workflow_type = 'APPLICATION'
                                 AND (source_publication_id IS NULL
                                      OR source_publication_id IN (:ownPublicationIds))
                           ) AS applications,
                           COUNT(*) FILTER (
                               WHERE pinned_version IS NOT NULL
                                 AND (source_publication_id IS NULL
                                      OR source_publication_id IN (:ownPublicationIds))
                           ) AS pinned
                      FROM orchestrator.workflows
                     WHERE tenant_id = :tenantId
                    """)
                    .setParameter("tenantId", tenantId)
                    .setParameter("ownPublicationIds", ownPublicationIds)
                    .getSingleResult();
            stats.put(BadgeMetric.WORKFLOWS_CREATED, toLong(row[0]));
            stats.put(BadgeMetric.APPLICATIONS_CREATED, toLong(row[1]));
            stats.put(BadgeMetric.WORKFLOWS_PINNED, toLong(row[2]));
        } catch (RuntimeException ex) {
            log.warn("[badges] authoring metrics failed for tenant {}: {}", tenantId, ex.getMessage());
        }
    }

    /**
     * "This epoch succeeded", on an {@code EPOCH_HEADER} row aliased {@code e}: closed, no
     * failed node, and something other than the trigger ran (see {@link #collectExecutions}
     * for why each clause). Shared with the monthly recap, which counts the same successes
     * over one month, so the trophy page and the recap email can never disagree on what a
     * successful run is.
     */
    public static final String SUCCESSFUL_EPOCH_CONDITION = """
            e.is_active = FALSE
              AND COALESCE(jsonb_array_length(
                      CASE WHEN jsonb_typeof(e.epoch_state -> 'failedNodeIds') = 'array'
                           THEN e.epoch_state -> 'failedNodeIds' END), 0) = 0
              AND (
                  COALESCE(jsonb_array_length(
                      CASE WHEN jsonb_typeof(e.epoch_state -> 'skippedNodeIds') = 'array'
                           THEN e.epoch_state -> 'skippedNodeIds' END), 0) > 0
                  OR EXISTS (
                      SELECT 1 FROM jsonb_array_elements_text(
                          CASE WHEN jsonb_typeof(e.epoch_state -> 'completedNodeIds') = 'array'
                               THEN e.epoch_state -> 'completedNodeIds'
                               ELSE '[]'::jsonb END) AS completed_node_id
                       WHERE completed_node_id NOT LIKE 'trigger:%')
              )""";

    /** Run-row counts, which floor the two execution metrics. */
    private record RunRowCounts(long total, long completed) {
        static final RunRowCounts NONE = new RunRowCounts(0L, 0L);
    }

    private RunRowCounts collectRuns(String tenantId) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*) AS runs,
                           COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed
                      FROM orchestrator.workflow_runs
                     WHERE tenant_id = :tenantId
                    """)
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
            return new RunRowCounts(toLong(row[0]), toLong(row[1]));
        } catch (RuntimeException ex) {
            log.warn("[badges] run metrics failed for tenant {}: {}", tenantId, ex.getMessage());
            return RunRowCounts.NONE;
        }
    }

    /**
     * Executions, counted as epoch fires rather than run rows.
     *
     * <p>A reusable-trigger workflow (schedule / webhook / chat / form) keeps ONE
     * {@code workflow_runs} row forever and opens a new epoch per fire, so
     * counting rows would score a workflow that has fired hourly for a year as a
     * single launch - exactly backwards for the most active users. The row count
     * is still the floor: a brand-new AUTOMATIC run and a single-shot
     * step-by-step run have no EPOCH_HEADER yet, and those really did launch.
     *
     * <p><b>The same asymmetry applies, harder, to successes.</b> A reusable-trigger
     * run NEVER reaches {@code status = 'COMPLETED'} - it settles back to
     * WAITING_TRIGGER for the next fire - so counting completed run rows scored
     * a workflow that has succeeded a thousand times as zero. Success is
     * therefore an EPOCH-level question, answered here exactly as
     * {@code WorkflowEpochService.deriveEpochOutcome} answers it for the run
     * page: a closed epoch, no failed node, and something other than the trigger
     * actually ran (a trigger completes on every fire, so counting it would make
     * "armed" indistinguishable from "ran"; skipped nodes DO count, since a
     * cycle whose downstream was all skipped still reached its end).
     *
     * <p>Every {@code jsonb} read is guarded by {@code jsonb_typeof}: both
     * {@code jsonb_array_length} and {@code jsonb_array_elements_text} RAISE on a
     * non-array, and one malformed epoch state must not take the whole metric
     * sweep down.
     */
    private void collectExecutions(String tenantId, RunRowCounts runRows, BadgeStats.Builder stats) {
        long epochs = 0;
        long successfulEpochs = 0;
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    SELECT COUNT(*) AS launches,
                           COUNT(DISTINCT (e.started_at AT TIME ZONE 'UTC')::date) AS active_days,
                           COUNT(*) FILTER (
                               WHERE EXTRACT(HOUR FROM (e.started_at AT TIME ZONE 'UTC')) < 5
                           ) AS night_launches,
                           COUNT(*) FILTER (WHERE %s) AS successful
                      FROM orchestrator.workflow_epochs e
                      JOIN orchestrator.workflow_runs r ON r.run_id_public = e.run_id
                     WHERE r.tenant_id = :tenantId
                       AND e.entry_type = 'EPOCH_HEADER'
                    """.formatted(SUCCESSFUL_EPOCH_CONDITION))
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
            epochs = toLong(row[0]);
            stats.put(BadgeMetric.ACTIVE_DAYS, toLong(row[1]));
            stats.put(BadgeMetric.NIGHT_LAUNCHES, toLong(row[2]));
            successfulEpochs = toLong(row[3]);
        } catch (RuntimeException ex) {
            log.warn("[badges] execution metrics failed for tenant {}: {}", tenantId, ex.getMessage());
        }
        stats.put(BadgeMetric.RUNS_LAUNCHED, Math.max(epochs, runRows.total()));
        stats.put(BadgeMetric.RUNS_COMPLETED, Math.max(successfulEpochs, runRows.completed()));
    }

    /**
     * Marketplace footprint. One call returns every publication the user owns
     * personally, so published / public / install counts come from one payload
     * instead of three endpoints.
     *
     * @return the ids of the publications this user owns, for the authoring
     *         counts. Never empty - {@link #NO_PUBLICATION} stands in - so the
     *         caller can bind it to an SQL {@code IN} list unconditionally.
     */
    private List<UUID> collectPublications(String tenantId, BadgeStats.Builder stats) {
        List<Map<String, Object>> publications =
                publicationClient.getPublicationsByPublisherPersonalScope(tenantId);
        if (publications == null || publications.isEmpty()) {
            return List.of(NO_PUBLICATION);
        }
        long published = 0;
        long publicCount = 0;
        long uses = 0;
        Set<UUID> ids = new LinkedHashSet<>();
        for (Map<String, Object> pub : publications) {
            if (pub == null) continue;
            if (PUBLICATION_STATUS_ACTIVE.equals(asString(pub.get("status")))) {
                published++;
                if (PUBLICATION_VISIBILITY_PUBLIC.equals(asString(pub.get("visibility")))) {
                    publicCount++;
                }
            }
            // Installs count on every publication, active or not: an app that
            // was installed 500 times and later retired still earned those.
            uses += toLong(pub.get("useCount"));
            // Ownership is what the authoring counts need, so EVERY publication
            // contributes its id - a retired one still minted the application
            // row that its author built.
            UUID id = parseUuid(pub.get("id"));
            if (id != null) ids.add(id);
        }
        stats.put(BadgeMetric.PUBLICATIONS_PUBLISHED, published);
        stats.put(BadgeMetric.PUBLICATIONS_PUBLIC, publicCount);
        stats.put(BadgeMetric.PUBLICATION_USES, uses);

        return ids.isEmpty() ? List.of(NO_PUBLICATION) : new ArrayList<>(ids);
    }

    /** Cohort + tenure, both derived from the account's creation date. */
    private void collectIdentity(String tenantId, BadgeStats.Builder stats) {
        Instant joinedAt = parseJoinedAt(authClient.getBadgeProfile(tenantId));
        if (joinedAt == null) {
            return;
        }
        stats.put(BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF, BadgeCatalog.daysUntilJoinCutoff(joinedAt));
        stats.put(BadgeMetric.MEMBER_DAYS, ChronoUnit.DAYS.between(joinedAt, Instant.now()));
    }

    /**
     * auth-service sends {@code users.created_at} as a zone-less ISO local
     * date-time that is stored in UTC. Parsing it as UTC here is what makes the
     * cohort thresholds mean the same thing whatever the server's timezone.
     * An offset-carrying value is still accepted, so tightening the producer
     * later cannot silently zero the metric.
     */
    static Instant parseJoinedAt(BadgeProfileDto profile) {
        if (profile == null || profile.joinedAt() == null || profile.joinedAt().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(profile.joinedAt()).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            try {
                return Instant.parse(profile.joinedAt());
            } catch (DateTimeParseException ignored) {
                log.warn("[badges] unparseable joinedAt '{}'", profile.joinedAt());
                return null;
            }
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** A publication id as sent over the wire (a String), or null if unusable. */
    private static UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
