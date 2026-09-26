package com.apimarketplace.orchestrator.services.notification.delivery;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "This workflow is failing" as ONE row, however many runs fail.
 *
 * <p>This is the anti-spam core: a schedule that fails every minute produces
 * 1,440 bell rows a day, and must produce one message when it breaks, at most
 * one reminder a day while it stays broken, and one message when it recovers.
 * The partial unique index on the open row decides which failure is "the first"
 * atomically, across replicas.
 */
@Component
public class NotificationIncidentStore {

    /**
     * An open (or just-closed) incident, as the sender needs it.
     *
     * @param announceRecovery only meaningful on a row returned by {@link #resolve}: true when this
     *                         close is the one to announce, false when a recovery was already
     *                         announced in the last 24 hours (a flapping workflow)
     */
    public record Incident(long id, String tenantId, String organizationId, UUID workflowId,
                           Instant openedAt, Instant lastFailureAt, int failureCount,
                           Instant lastNotifiedAt, int notifiedFailureCount, boolean announceRecovery) {

        public Incident(long id, String tenantId, String organizationId, UUID workflowId,
                        Instant openedAt, Instant lastFailureAt, int failureCount,
                        Instant lastNotifiedAt, int notifiedFailureCount) {
            this(id, tenantId, organizationId, workflowId, openedAt, lastFailureAt, failureCount,
                    lastNotifiedAt, notifiedFailureCount, false);
        }
    }

    /**
     * The flapping window. Within it, a failure after an announced recovery REOPENS the same
     * incident and announces "failing again" once; the recovery after that is announced; every
     * later flip in the window is silent. A workflow alternating all day therefore sends at most:
     * failed, recovered, failing again, recovered. Without it, every flip would send a message.
     */
    static final Duration FLAP_WINDOW = Duration.ofHours(24);

    private static final RowMapper<Incident> MAPPER = (rs, i) -> new Incident(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("organization_id"),
            rs.getObject("workflow_id", UUID.class),
            rs.getTimestamp("opened_at").toInstant(),
            rs.getTimestamp("last_failure_at").toInstant(),
            rs.getInt("failure_count"),
            rs.getTimestamp("last_notified_at").toInstant(),
            rs.getInt("notified_failure_count"),
            hasColumn(rs, "announce") && rs.getBoolean("announce"));

    private static boolean hasColumn(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int c = 1; c <= md.getColumnCount(); c++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(c))) return true;
        }
        return false;
    }

    private static final String COLUMNS = "id, tenant_id, organization_id, workflow_id, opened_at, "
            + "last_failure_at, failure_count, last_notified_at, notified_failure_count";

    private final JdbcTemplate jdbc;

    public NotificationIncidentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What one failure did to the workflow's incident, and therefore whether anything is sent. */
    public enum FailureOutcome {
        /** A new incident: the one failure to send. */
        OPENED,
        /**
         * The workflow recovered (and was announced as recovered) less than FLAP_WINDOW ago and
         * broke again: the same incident reopens and ONE "failing again" message corrects the
         * "recovered" the person last read. Further flips in the window are silent.
         */
        FAILING_AGAIN,
        /**
         * Counted on an incident the person already heard about: nothing is sent. This includes
         * the incidents V528 seeded for workflows that were already failing before delivery
         * existed, so the first deploy does not send one alert per already-broken workflow.
         */
        JOINED
    }

    /**
     * Counts one failure against the workflow's incident: reopens a recently recovered one,
     * joins an open one, or opens a new one.
     */
    public FailureOutcome recordFailure(String tenantId, String organizationId, UUID workflowId, Instant at) {
        return recordFailure(tenantId, organizationId, workflowId, at, Instant.now());
    }

    /**
     * @param at  when the failure HAPPENED (event time: it orders the failure against the close)
     * @param now when it is PROCESSED. Everything compared with a recovery stamp uses this clock,
     *            because resolve() stamps recoveries at processing time: mixing the two would let a
     *            failure that waited in the delivery queue past a recovery look like it came after.
     */
    FailureOutcome recordFailure(String tenantId, String organizationId, UUID workflowId, Instant at, Instant now) {
        Timestamp ts = Timestamp.from(at);
        Timestamp nowTs = Timestamp.from(now);
        Timestamp flapCutoff = Timestamp.from(now.minus(FLAP_WINDOW));

        // 0. A stale failure: it HAPPENED before the recovery that closed its incident, and only
        //    reaches us now (it waited in the delivery queue). It belongs to the incident already
        //    reported and closed, so it neither reopens it nor opens a new one.
        Boolean stale = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orchestrator.notification_incidents "
                        + "              WHERE tenant_id = ? AND organization_id = ? AND workflow_id = ? "
                        + "                AND resolved_at IS NOT NULL AND resolved_at >= ?) "
                        + "   AND NOT EXISTS (SELECT 1 FROM orchestrator.notification_incidents "
                        + "              WHERE tenant_id = ? AND organization_id = ? AND workflow_id = ? "
                        + "                AND resolved_at IS NULL)",
                Boolean.class, tenantId, organizationId, workflowId, ts, tenantId, organizationId, workflowId);
        if (Boolean.TRUE.equals(stale)) return FailureOutcome.JOINED;

        // 1. A flap: a recovery was announced less than FLAP_WINDOW ago. Reopen that incident.
        //    "Failing again" is announced at most once per FLAP_WINDOW, and only after a recovery
        //    announced since the last one; it counts as reporting the incident. Together with the
        //    rule in resolve(), a workflow flipping all day sends at most: failed, recovered,
        //    failing again, recovered; then silence and the daily reminder.
        String announceAgain = "(reopen_announced_at IS NULL OR (reopen_announced_at < recovered_notified_at "
                + "AND reopen_announced_at < ?))";
        try {
            List<Boolean> reopened = jdbc.query("UPDATE orchestrator.notification_incidents "
                            + "SET resolved_at = NULL, failure_count = failure_count + 1, "
                            + "    last_failure_at = GREATEST(last_failure_at, ?), "
                            // Stamped with PROCESSING time, the clock resolve() stamps recoveries with.
                            + "    reopen_announced_at = CASE WHEN " + announceAgain + " THEN ? ELSE reopen_announced_at END, "
                            + "    last_notified_at = CASE WHEN " + announceAgain + " THEN ? ELSE last_notified_at END, "
                            + "    notified_failure_count = CASE WHEN " + announceAgain
                            + "        THEN failure_count + 1 ELSE notified_failure_count END "
                            + "WHERE id = (SELECT id FROM orchestrator.notification_incidents "
                            + "            WHERE tenant_id = ? AND organization_id = ? AND workflow_id = ? "
                            + "              AND resolved_at IS NOT NULL AND resolved_at > ? AND resolved_at < ? "
                            + "              AND recovered_notified_at IS NOT NULL AND recovered_notified_at > ? "
                            + "            ORDER BY resolved_at DESC LIMIT 1) "
                            + "AND NOT EXISTS (SELECT 1 FROM orchestrator.notification_incidents "
                            + "            WHERE tenant_id = ? AND organization_id = ? AND workflow_id = ? "
                            + "              AND resolved_at IS NULL) "
                            + "RETURNING (reopen_announced_at = ?)",
                    (rs, i) -> rs.getBoolean(1),
                    ts, flapCutoff, nowTs, flapCutoff, nowTs, flapCutoff,
                    tenantId, organizationId, workflowId, flapCutoff, ts, flapCutoff,
                    tenantId, organizationId, workflowId, nowTs);
            if (!reopened.isEmpty()) {
                return reopened.get(0) ? FailureOutcome.FAILING_AGAIN : FailureOutcome.JOINED;
            }
        } catch (DuplicateKeyException raced) {
            // A concurrent failure opened a fresh incident between the two probes: join it below.
        }

        // 2. Join the open incident, or open one. xmax = 0 only on a freshly inserted tuple: the
        //    one reliable way to tell the INSERT branch of an upsert from its UPDATE branch.
        Boolean opened = jdbc.queryForObject(
                "INSERT INTO orchestrator.notification_incidents "
                        + "(tenant_id, organization_id, workflow_id, opened_at, last_failure_at, failure_count, "
                        + " last_notified_at, notified_failure_count) VALUES (?, ?, ?, ?, ?, 1, ?, 1) "
                        + "ON CONFLICT (tenant_id, organization_id, workflow_id) WHERE resolved_at IS NULL "
                        + "DO UPDATE SET failure_count = notification_incidents.failure_count + 1, "
                        + "  last_failure_at = GREATEST(notification_incidents.last_failure_at, EXCLUDED.last_failure_at) "
                        + "RETURNING (xmax = 0)",
                Boolean.class, tenantId, organizationId, workflowId, ts, ts, ts);
        return Boolean.TRUE.equals(opened) ? FailureOutcome.OPENED : FailureOutcome.JOINED;
    }

    /** Cheap probe run on every successful production epoch, before anything heavier. */
    public boolean hasOpen(UUID workflowId) {
        Boolean any = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orchestrator.notification_incidents "
                        + "WHERE workflow_id = ? AND resolved_at IS NULL)",
                Boolean.class, workflowId);
        return Boolean.TRUE.equals(any);
    }

    /**
     * Closes every open incident of the workflow and returns what was closed, so
     * each one gets its "recovered" message. The UPDATE ... RETURNING is the
     * claim: a second replica closing the same row gets nothing back.
     */
    public List<Incident> resolve(UUID workflowId, Instant at) {
        Timestamp now = Timestamp.from(at);
        // The recovery is announced (and stamped) only if none was announced in FLAP_WINDOW.
        // Announcing also counts as reporting the incident, so a reopen right after it does not
        // trigger an immediate "still failing" reminder.
        // Also announced when the last message the person got was "failing again": otherwise that
        // would stay the final word on a workflow that has in fact recovered.
        String announce = "(recovered_notified_at IS NULL OR recovered_notified_at < ? "
                + "OR reopen_announced_at > recovered_notified_at)";
        return jdbc.query("UPDATE orchestrator.notification_incidents SET resolved_at = ?, "
                        + "  recovered_notified_at = CASE WHEN " + announce + " THEN ? ELSE recovered_notified_at END, "
                        + "  last_notified_at = CASE WHEN " + announce + " THEN ? ELSE last_notified_at END, "
                        + "  notified_failure_count = CASE WHEN " + announce + " THEN failure_count ELSE notified_failure_count END "
                        + "WHERE workflow_id = ? AND resolved_at IS NULL RETURNING " + COLUMNS
                        + ", (recovered_notified_at = ?) AS announce",
                MAPPER, now, Timestamp.from(at.minus(FLAP_WINDOW)), now, Timestamp.from(at.minus(FLAP_WINDOW)), now,
                Timestamp.from(at.minus(FLAP_WINDOW)), workflowId, now);
    }

    /**
     * One page of open incidents last reported before {@code cutoff} that have failed again
     * since, after {@code afterId}. Keyset-paged by id so the caller visits EVERY due incident:
     * one left unclaimed (its person is capped today) must not hold a fixed first page forever.
     */
    public List<Incident> dueReminders(Instant cutoff, long afterId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orchestrator.notification_incidents "
                        + "WHERE resolved_at IS NULL AND last_notified_at <= ? "
                        + "AND failure_count > notified_failure_count AND id > ? ORDER BY id LIMIT ?",
                MAPPER, Timestamp.from(cutoff), afterId, limit);
    }

    /**
     * Claims a reminder: only succeeds if nobody reported the incident since it
     * was read, so two schedulers can never both send it.
     */
    public boolean claimReminder(Incident incident, Instant now) {
        return jdbc.update("UPDATE orchestrator.notification_incidents "
                        + "SET last_notified_at = ?, notified_failure_count = failure_count "
                        + "WHERE id = ? AND resolved_at IS NULL AND last_notified_at = ?",
                Timestamp.from(now), incident.id(), Timestamp.from(incident.lastNotifiedAt())) == 1;
    }

    /**
     * Closes, WITHOUT a message, incidents whose workflow has not failed for a
     * while: it was probably unpinned or switched off, and "recovered" would be a
     * claim nobody measured.
     */
    public int closeStale(Instant lastFailureBefore, Instant now) {
        return jdbc.update("UPDATE orchestrator.notification_incidents SET resolved_at = ? "
                        + "WHERE resolved_at IS NULL AND last_failure_at < ?",
                Timestamp.from(now), Timestamp.from(lastFailureBefore));
    }

    /** Housekeeping: closed incidents are history nobody reads after a month. */
    public int purgeResolvedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM orchestrator.notification_incidents "
                + "WHERE resolved_at IS NOT NULL AND resolved_at < ?", Timestamp.from(cutoff));
    }
}
