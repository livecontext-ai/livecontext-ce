package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationMessageComposer.DigestItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two deliveries that are not triggered by a new notification:
 * <ul>
 *   <li><b>Reminders</b>, hourly: an incident last reported 24 hours ago that has
 *       failed again since gets ONE "still failing" message, then waits another
 *       24 hours. Also closes, silently, incidents with no failure for a week.</li>
 *   <li><b>The daily summary</b>: everything in a digest topic since the person's
 *       last summary, plus whatever the daily cap deferred, as ONE message per
 *       medium.</li>
 * </ul>
 * ShedLock keeps each job on one replica; the reminder claim is a compare-and-set
 * on the row as well, so even an overlapping run cannot send one twice.
 */
@Component
public class NotificationDigestScheduler {

    private static final Logger logger = LoggerFactory.getLogger(NotificationDigestScheduler.class);

    static final Duration REMINDER_INTERVAL = Duration.ofHours(24);
    static final Duration STALE_INCIDENT = Duration.ofDays(7);
    /** How far back a first (or long-missed) summary looks, so it never replays a month. */
    static final Duration MAX_DIGEST_WINDOW = Duration.ofHours(48);
    static final Duration RETENTION = Duration.ofDays(30);
    /**
     * How far behind "now" a summary reads. A notification is stamped (occurred_at) before its
     * transaction commits; a row stamped just before the bound but committed just after the load
     * would otherwise land behind the cursor and never be summarised. Nothing commits minutes late.
     */
    static final Duration COMMIT_GRACE = Duration.ofMinutes(5);
    private static final int REMINDER_BATCH = 500;

    private final NotificationIncidentStore incidents;
    private final NotificationDeliveryLog deliveryLog;
    private final NotificationPreferenceStore preferences;
    private final NotificationEmailEntitlement emailEntitlement;
    private final NotificationMessageComposer composer;
    private final NotificationSender sender;
    private final NotificationDeliveryService deliveryService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public NotificationDigestScheduler(NotificationIncidentStore incidents,
                                       NotificationDeliveryLog deliveryLog,
                                       NotificationPreferenceStore preferences,
                                       NotificationEmailEntitlement emailEntitlement,
                                       NotificationMessageComposer composer,
                                       NotificationSender sender,
                                       NotificationDeliveryService deliveryService,
                                       JdbcTemplate jdbc,
                                       ObjectMapper objectMapper,
                                       @Value("${notifications.delivery.enabled:true}") boolean enabled) {
        this.incidents = incidents;
        this.deliveryLog = deliveryLog;
        this.preferences = preferences;
        this.emailEntitlement = emailEntitlement;
        this.composer = composer;
        this.sender = sender;
        this.deliveryService = deliveryService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${notifications.delivery.reminder-cron:0 5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "notification-reminders", lockAtMostFor = "PT30M")
    public void sendReminders() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            int closed = incidents.closeStale(now.minus(STALE_INCIDENT), now);
            if (closed > 0) logger.info("[notification-delivery] closed {} stale incident(s)", closed);
            long after = 0L;
            List<NotificationIncidentStore.Incident> page;
            do {
                page = incidents.dueReminders(now.minus(REMINDER_INTERVAL), after, REMINDER_BATCH);
                for (NotificationIncidentStore.Incident incident : page) {
                    after = incident.id();
                    try {
                        remind(incident, now);
                    } catch (RuntimeException ex) {
                        // One bad incident must not starve every incident behind it, pass after pass.
                        logger.warn("[notification-delivery] reminder for incident {} failed: {}",
                                incident.id(), ex.getMessage());
                    }
                }
            } while (page.size() == REMINDER_BATCH);
        } catch (RuntimeException ex) {
            logger.warn("[notification-delivery] reminder pass failed: {}", ex.getMessage());
        }
    }

    void remind(NotificationIncidentStore.Incident incident, Instant now) {
        DeliveryMode mode = preferences.resolve(incident.tenantId(), incident.organizationId(), NotificationTopic.FAILURES);
        if (mode == DeliveryMode.OFF) return;
        List<Medium> mediums = deliveryService.openMediums(incident.tenantId(), mode);
        // Capped everywhere: leave it unclaimed, the next hourly pass retries once the window slides.
        if (mediums.isEmpty()) return;
        // Built BEFORE the claim: a claim marks the reminder as sent for 24 hours, so anything
        // that can still throw must run first, or the reminder is lost for a day.
        NotificationMessage message = composer.reminder(incident);
        if (!incidents.claimReminder(incident, now)) return;
        if (mediums.contains(Medium.EMAIL)) {
            sender.email(incident.tenantId(), incident.organizationId(), null, Kind.REMINDER, message,
                    NotificationTopic.FAILURES, NotificationDeliveryService.INCIDENT_CATEGORY);
        }
        if (mediums.contains(Medium.CHANNEL)) {
            sender.channel(incident.tenantId(), incident.organizationId(), null, Kind.REMINDER, message,
                    NotificationTopic.FAILURES, NotificationDeliveryService.INCIDENT_CATEGORY);
        }
    }

    @Scheduled(cron = "${notifications.delivery.digest-cron:0 0 7 * * *}", zone = "UTC")
    @SchedulerLock(name = "notification-digest", lockAtMostFor = "PT50M")
    public void sendDigests() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            List<NotificationDeliveryLog.Recipient> recipients = deliveryLog.digestCandidates(
                    NotificationTopic.digestCategories(), now.minus(MAX_DIGEST_WINDOW));
            for (NotificationDeliveryLog.Recipient r : new LinkedHashSet<>(recipients)) {
                try {
                    digest(r.tenantId(), r.organizationId(), now);
                } catch (RuntimeException ex) {
                    logger.warn("[notification-delivery] digest for tenant {} org {} failed: {}",
                            r.tenantId(), r.organizationId(), ex.getMessage());
                }
            }
            deliveryLog.purgeBefore(now.minus(RETENTION));
            incidents.purgeResolvedBefore(now.minus(RETENTION));
        } catch (RuntimeException ex) {
            logger.warn("[notification-delivery] digest pass failed: {}", ex.getMessage());
        }
    }

    /**
     * Where this medium's summary starts: its last successful summary, never further back than
     * {@link #MAX_DIGEST_WINDOW} (a first summary, or one after a long outage, must not replay a
     * month of rows).
     */
    static Instant digestSince(Instant lastDigest, Instant now) {
        Instant floor = now.minus(MAX_DIGEST_WINDOW);
        return lastDigest != null && lastDigest.isAfter(floor) ? lastDigest : floor;
    }

    void digest(String tenantId, String organizationId, Instant pass) {
        Instant now = pass.minus(COMMIT_GRACE);
        Instant emailSince = digestSince(deliveryLog.lastDigestAt(tenantId, organizationId, Medium.EMAIL), now);
        Instant channelSince = digestSince(deliveryLog.lastDigestAt(tenantId, organizationId, Medium.CHANNEL), now);
        Map<NotificationTopic, DeliveryMode> modes = preferences.resolveAll(tenantId, organizationId);

        Set<String> emailCategories = new HashSet<>();
        Set<String> channelCategories = new HashSet<>();
        for (NotificationTopic topic : NotificationTopic.values()) {
            if (!topic.isDigest()) continue;
            DeliveryMode mode = modes.get(topic);
            if (mode.wantsEmail() && emailEntitlement.allows(tenantId, topic)) emailCategories.addAll(topic.categories());
            if (mode.wantsChannel()) channelCategories.addAll(topic.categories());
        }

        // Every read is bounded by `now`, and the digest row is stamped with that same `now`, so
        // the next summary starts exactly where this one's reads stopped.
        List<DigestItem> emailItems = load(tenantId, organizationId, emailCategories,
                deliveryLog.deferredBetween(tenantId, organizationId, Medium.EMAIL, emailSince, now), emailSince, now);
        List<DigestItem> channelItems = load(tenantId, organizationId, channelCategories,
                deliveryLog.deferredBetween(tenantId, organizationId, Medium.CHANNEL, channelSince, now), channelSince, now);

        NotificationMessage emailDigest = composer.digest(tenantId, emailItems);
        if (emailDigest != null) {
            sender.email(tenantId, organizationId, null, Kind.DIGEST, emailDigest, now);
        }
        NotificationMessage channelDigest = composer.digest(tenantId, channelItems);
        if (channelDigest != null) {
            sender.channel(tenantId, organizationId, null, Kind.DIGEST, channelDigest, now);
        }
    }

    /** Bell rows of the given categories since {@code since}, plus the deferred ones, oldest first. */
    List<DigestItem> load(String tenantId, String organizationId, Set<String> categories,
                          List<Long> deferredIds, Instant since, Instant until) {
        if (categories.isEmpty() && deferredIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (!categories.isEmpty()) {
            where.append("(category IN (").append(placeholders(categories.size()))
                    .append(") AND occurred_at > ? AND occurred_at <= ?)");
            args.addAll(categories);
            args.add(Timestamp.from(since));
            args.add(Timestamp.from(until));
        }
        if (!deferredIds.isEmpty()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("id IN (").append(placeholders(deferredIds.size())).append(')');
            args.addAll(deferredIds);
        }
        args.add(0, organizationId);
        args.add(0, tenantId);
        return jdbc.query("SELECT category, subject_type, subject_id, payload::text AS payload, occurred_at "
                        + "FROM orchestrator.notifications WHERE tenant_id = ? AND organization_id = ? AND ("
                        + where + ") ORDER BY occurred_at",
                (rs, i) -> new DigestItem(rs.getString("category"), rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class), parse(rs.getString("payload")),
                        rs.getTimestamp("occurred_at").toInstant()),
                args.toArray());
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
