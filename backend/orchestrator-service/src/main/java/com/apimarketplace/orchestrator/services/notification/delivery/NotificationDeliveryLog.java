package com.apimarketplace.orchestrator.services.notification.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What left the platform, where, and whether it arrived. It is also the memory
 * the anti-spam rules read: the daily cap counts SENT rows, a DEFERRED row is a
 * message the cap held back for the next digest, and the last SENT DIGEST row is
 * where the next digest starts.
 */
@Component
public class NotificationDeliveryLog {

    public enum Kind { ALERT, REMINDER, RECOVERED, DIGEST }
    public enum Medium { EMAIL, CHANNEL }
    public enum Status { SENT, FAILED, DEFERRED }

    /** One (person, workspace) pair that has something for its digest. */
    public record Recipient(String tenantId, String organizationId) {}

    private final JdbcTemplate jdbc;

    public NotificationDeliveryLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String tenantId, String organizationId, Long notificationId,
                       Kind kind, Medium medium, Status status, String detail) {
        record(tenantId, organizationId, notificationId, kind, medium, status, detail, Instant.now());
    }

    /**
     * @param at when the row counts as written. A digest passes the instant its load query was
     *           bounded by, so its cursor and its load agree exactly: a row that commits between
     *           the load and the send is picked up by the NEXT digest instead of falling behind
     *           a cursor stamped a moment later.
     */
    public void record(String tenantId, String organizationId, Long notificationId,
                       Kind kind, Medium medium, Status status, String detail, Instant at) {
        jdbc.update("INSERT INTO orchestrator.notification_deliveries "
                        + "(tenant_id, organization_id, notification_id, kind, medium, status, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, organizationId, notificationId, kind.name(), medium.name(), status.name(),
                detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail, Timestamp.from(at));
    }

    /**
     * Immediate messages (everything but digests) already sent to this person on
     * this medium since {@code since}, across all their workspaces: the cap is
     * about one inbox, not one workspace.
     */
    public int countImmediateSentSince(String tenantId, Medium medium, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM orchestrator.notification_deliveries "
                        + "WHERE tenant_id = ? AND medium = ? AND status = 'SENT' AND kind <> 'DIGEST' "
                        + "AND created_at >= ?",
                Integer.class, tenantId, medium.name(), Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /**
     * When this person's last digest in this workspace went out ON THIS MEDIUM, or null if never.
     * One cursor per medium: an email digest that failed (SMTP down) must not be skipped because
     * the channel digest of the same morning went out.
     */
    public Instant lastDigestAt(String tenantId, String organizationId, Medium medium) {
        Timestamp ts = jdbc.queryForObject("SELECT max(created_at) FROM orchestrator.notification_deliveries "
                        + "WHERE tenant_id = ? AND organization_id = ? AND medium = ? AND kind = 'DIGEST' "
                        + "AND status = 'SENT'",
                Timestamp.class, tenantId, organizationId, medium.name());
        return ts == null ? null : ts.toInstant();
    }

    /** Notifications the daily cap held back since {@code since}, on the given medium. */
    /** Notifications the daily cap held back in ({@code since}, {@code until}], on the given medium. */
    public List<Long> deferredBetween(String tenantId, String organizationId, Medium medium, Instant since,
                                      Instant until) {
        return jdbc.queryForList("SELECT DISTINCT notification_id FROM orchestrator.notification_deliveries "
                        + "WHERE tenant_id = ? AND organization_id = ? AND medium = ? AND status = 'DEFERRED' "
                        + "AND notification_id IS NOT NULL AND created_at > ? AND created_at <= ?",
                Long.class, tenantId, organizationId, medium.name(), Timestamp.from(since), Timestamp.from(until));
    }

    /**
     * Everyone who may have something for a digest since {@code since}: a row in
     * a digest topic, or a message the cap deferred. A candidate with nothing left
     * after its own cursor and preferences are applied simply gets no digest.
     */
    public List<Recipient> digestCandidates(List<String> digestCategories, Instant since) {
        List<Object> args = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (String c : digestCategories) {
            if (in.length() > 0) in.append(',');
            in.append('?');
            args.add(c);
        }
        args.add(Timestamp.from(since));
        args.add(Timestamp.from(since));
        return jdbc.query("SELECT tenant_id, organization_id FROM orchestrator.notifications "
                        + "WHERE category IN (" + in + ") AND occurred_at > ? AND organization_id IS NOT NULL "
                        + "UNION "
                        + "SELECT tenant_id, organization_id FROM orchestrator.notification_deliveries "
                        + "WHERE status = 'DEFERRED' AND created_at > ?",
                (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)), args.toArray());
    }

    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM orchestrator.notification_deliveries WHERE created_at < ?",
                Timestamp.from(cutoff));
    }
}
