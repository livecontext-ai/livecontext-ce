package com.apimarketplace.orchestrator.services.analytics;

import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.orchestrator.services.badge.BadgeDefinition;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.notification.delivery.DeliveryMode;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Product-analytics (PostHog) emitter for the engagement surfaces that reach a person
 * outside the app: notification delivery and preferences, the workspace chat channel,
 * the requests delivered to and answered from that channel, and trophies.
 *
 * <p>Sibling of {@link WorkflowAnalyticsEmitter}, same contract: best-effort (inert
 * without a configured client, never throws, never blocks: the client only enqueues),
 * PII-free (ids, enums, counts, booleans; never a chat id, chat title, email, message or
 * answer text), distinct_id = the internal numeric user id (the value the gateway sends
 * as X-User-ID), {@code surface=backend}, and the workspace as both
 * {@code organization_id} and the PostHog {@code organization} group when it is known.
 *
 * <p>Each call site emits where the OUTCOME is decided, so a property like
 * {@code status} or {@code delivered} reports what really happened, never what was
 * attempted.
 */
@Component
public class EngagementAnalyticsEmitter {

    private static final Logger log = LoggerFactory.getLogger(EngagementAnalyticsEmitter.class);

    static final String NOTIFICATION_SENT = "notification_sent";
    static final String NOTIFICATION_SUPPRESSED = "notification_suppressed";
    static final String NOTIFICATION_PREFERENCE_CHANGED = "notification_preference_changed";
    static final String CHANNEL_CONNECTED = "channel_connected";
    static final String CHANNEL_DISCONNECTED = "channel_disconnected";
    static final String CHANNEL_DEFAULT_SET = "channel_default_set";
    static final String CHANNEL_REQUEST_DELIVERED = "channel_request_delivered";
    static final String CHANNEL_REQUEST_ANSWERED = "channel_request_answered";
    static final String BADGE_UNLOCKED = "badge_unlocked";

    /** Outcome of one notification send, as {@code notification_sent.status}. */
    public enum SendStatus { SENT, FAILED, DEFERRED, NO_ADDRESS, NO_CHANNEL }

    /** Why a notification did not leave the platform, as {@code notification_suppressed.reason}. */
    public enum SuppressionReason { PREFERENCE_OFF, INCIDENT_JOINED, EMAIL_NOT_ON_PLAN, DAILY_CAP }

    /** Which interactive request went to the chat channel. */
    public enum RequestType { AGENT_PERMISSION, ASK_USER, WORKFLOW_APPROVAL }

    /** Outcome of one request delivery, as {@code channel_request_delivered.status}. */
    public enum RequestStatus {
        SENT, FAILED, NO_CHANNEL, ALREADY_PENDING;

        /**
         * The same-named value of a delivery service's own status enum, or null for a name
         * this taxonomy does not know. Never throws: a status added later must not break a
         * delivery because a chart has no bucket for it.
         */
        public static RequestStatus of(Enum<?> status) {
            if (status == null) return null;
            for (RequestStatus s : values()) {
                if (s.name().equals(status.name())) return s;
            }
            return null;
        }
    }

    /** What the answer was, as {@code channel_request_answered.decision}. */
    public enum Decision { APPROVED, REJECTED, ANSWERED }

    /** How the answer came in, as {@code channel_request_answered.input}. */
    public enum Input { BUTTON, TEXT }

    @Autowired(required = false)
    private PostHogAnalyticsClient postHog;

    /**
     * True when events would actually be sent. Lets a call site skip a read it only
     * performs for analytics (a previous value, a label) when nothing would use it.
     */
    public boolean isActive() {
        return postHog != null && postHog.isActive();
    }

    private void capture(String userId, String event, Map<String, Object> props) {
        if (!isActive() || userId == null || userId.isBlank()) return;
        try {
            postHog.capture(userId, event, props);
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", event, e.toString());
        }
    }

    // ── notifications ─────────────────────────────────────────────────────────

    /**
     * @param channel the chat provider, for {@code medium=channel} only (null otherwise,
     *                or when no channel is connected)
     */
    public void notificationSent(String userId, String organizationId, Kind kind, Medium medium,
                                 SendStatus status, NotificationTopic topic, String category, String channel) {
        try {
            capture(userId, NOTIFICATION_SENT,
                    buildNotificationSentProps(organizationId, kind, medium, status, topic, category, channel));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", NOTIFICATION_SENT, e.toString());
        }
    }

    static Map<String, Object> buildNotificationSentProps(String organizationId, Kind kind, Medium medium,
                                                          SendStatus status, NotificationTopic topic,
                                                          String category, String channel) {
        Map<String, Object> props = base(organizationId);
        props.put("kind", lower(kind));
        props.put("medium", lower(medium));
        props.put("status", lower(status));
        putIfPresent(props, "topic", lower(topic));
        putIfPresent(props, "category", lower(category));
        if (medium == Medium.CHANNEL) putIfPresent(props, "channel", lower(channel));
        return props;
    }

    /**
     * @param medium the medium that was held back, for a per-medium reason ({@code daily_cap},
     *               {@code email_not_on_plan}); null for a reason that stops every medium at once
     *               ({@code preference_off}, {@code incident_joined})
     */
    public void notificationSuppressed(String userId, String organizationId, SuppressionReason reason,
                                       Medium medium, NotificationTopic topic, String category) {
        try {
            capture(userId, NOTIFICATION_SUPPRESSED,
                    buildNotificationSuppressedProps(organizationId, reason, medium, topic, category));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", NOTIFICATION_SUPPRESSED, e.toString());
        }
    }

    static Map<String, Object> buildNotificationSuppressedProps(String organizationId, SuppressionReason reason,
                                                                Medium medium, NotificationTopic topic,
                                                                String category) {
        Map<String, Object> props = base(organizationId);
        props.put("reason", lower(reason));
        putIfPresent(props, "medium", lower(medium));
        putIfPresent(props, "topic", lower(topic));
        putIfPresent(props, "category", lower(category));
        return props;
    }

    /** @param previous the choice before this save; null when it could not be read */
    public void notificationPreferenceChanged(String userId, String organizationId, NotificationTopic topic,
                                              DeliveryMode delivery, DeliveryMode previous,
                                              boolean personScoped, boolean emailAvailable) {
        try {
            capture(userId, NOTIFICATION_PREFERENCE_CHANGED, buildPreferenceChangedProps(
                    organizationId, topic, delivery, previous, personScoped, emailAvailable));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", NOTIFICATION_PREFERENCE_CHANGED, e.toString());
        }
    }

    static Map<String, Object> buildPreferenceChangedProps(String organizationId, NotificationTopic topic,
                                                           DeliveryMode delivery, DeliveryMode previous,
                                                           boolean personScoped, boolean emailAvailable) {
        Map<String, Object> props = base(organizationId);
        props.put("topic", lower(topic));
        props.put("delivery", lower(delivery));
        putIfPresent(props, "previous_delivery", lower(previous));
        props.put("person_scoped", personScoped);
        props.put("email_available", emailAvailable);
        return props;
    }

    // ── chat channel ──────────────────────────────────────────────────────────

    /** @param isNewLink true when the connect created the destination, false for a reconnect or update */
    public void channelConnected(String userId, String organizationId, String channel,
                                 ChatChannelService.ChangeSource source, boolean delivered, boolean isDefault,
                                 ChatChannelService.WebhookOutcome webhook, boolean isNewLink) {
        try {
            capture(userId, CHANNEL_CONNECTED, buildChannelConnectedProps(organizationId, channel, source,
                    delivered, isDefault, webhook, isNewLink));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", CHANNEL_CONNECTED, e.toString());
        }
    }

    static Map<String, Object> buildChannelConnectedProps(String organizationId, String channel,
                                                          ChatChannelService.ChangeSource source,
                                                          boolean delivered, boolean isDefault,
                                                          ChatChannelService.WebhookOutcome webhook,
                                                          boolean isNewLink) {
        Map<String, Object> props = base(organizationId);
        putIfPresent(props, "channel", lower(channel));
        putIfPresent(props, "source", lower(source));
        props.put("delivered", delivered);
        props.put("is_default", isDefault);
        putIfPresent(props, "webhook_outcome", lower(webhook));
        props.put("is_new_link", isNewLink);
        return props;
    }

    public void channelDisconnected(String userId, String organizationId, String channel,
                                    ChatChannelService.ChangeSource source, boolean wasDefault) {
        try {
            capture(userId, CHANNEL_DISCONNECTED,
                    buildChannelDisconnectedProps(organizationId, channel, source, wasDefault));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", CHANNEL_DISCONNECTED, e.toString());
        }
    }

    static Map<String, Object> buildChannelDisconnectedProps(String organizationId, String channel,
                                                             ChatChannelService.ChangeSource source,
                                                             boolean wasDefault) {
        Map<String, Object> props = base(organizationId);
        putIfPresent(props, "channel", lower(channel));
        putIfPresent(props, "source", lower(source));
        props.put("was_default", wasDefault);
        return props;
    }

    public void channelDefaultSet(String userId, String organizationId, String channel) {
        try {
            capture(userId, CHANNEL_DEFAULT_SET, buildChannelDefaultSetProps(organizationId, channel));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", CHANNEL_DEFAULT_SET, e.toString());
        }
    }

    static Map<String, Object> buildChannelDefaultSetProps(String organizationId, String channel) {
        Map<String, Object> props = base(organizationId);
        putIfPresent(props, "channel", lower(channel));
        return props;
    }

    // ── requests through the channel ─────────────────────────────────────────

    public void channelRequestDelivered(String userId, String organizationId, RequestType requestType,
                                        String channel, RequestStatus status) {
        if (status == null) return;
        try {
            capture(userId, CHANNEL_REQUEST_DELIVERED,
                    buildRequestDeliveredProps(organizationId, requestType, channel, status));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", CHANNEL_REQUEST_DELIVERED, e.toString());
        }
    }

    static Map<String, Object> buildRequestDeliveredProps(String organizationId, RequestType requestType,
                                                          String channel, RequestStatus status) {
        Map<String, Object> props = base(organizationId);
        props.put("request_type", lower(requestType));
        putIfPresent(props, "channel", lower(channel));
        props.put("status", lower(status));
        return props;
    }

    /** @param input how the answer came in; null when the site cannot tell */
    public void channelRequestAnswered(String userId, String organizationId, RequestType requestType,
                                       String channel, Decision decision, Input input) {
        try {
            capture(userId, CHANNEL_REQUEST_ANSWERED,
                    buildRequestAnsweredProps(organizationId, requestType, channel, decision, input));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", CHANNEL_REQUEST_ANSWERED, e.toString());
        }
    }

    static Map<String, Object> buildRequestAnsweredProps(String organizationId, RequestType requestType,
                                                         String channel, Decision decision, Input input) {
        Map<String, Object> props = base(organizationId);
        props.put("request_type", lower(requestType));
        putIfPresent(props, "channel", lower(channel));
        props.put("decision", lower(decision));
        putIfPresent(props, "input", lower(input));
        return props;
    }

    // ── trophies ──────────────────────────────────────────────────────────────

    public void badgeUnlocked(String userId, String organizationId, BadgeDefinition badge, boolean backfill) {
        if (badge == null) return;
        try {
            capture(userId, BADGE_UNLOCKED, buildBadgeUnlockedProps(organizationId, badge, backfill));
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", BADGE_UNLOCKED, e.toString());
        }
    }

    static Map<String, Object> buildBadgeUnlockedProps(String organizationId, BadgeDefinition badge,
                                                       boolean backfill) {
        Map<String, Object> props = base(organizationId);
        props.put("badge_code", badge.code());
        putIfPresent(props, "badge_family", lower(badge.family()));
        putIfPresent(props, "badge_tier", lower(badge.tier()));
        props.put("backfill", backfill);
        return props;
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private static Map<String, Object> base(String organizationId) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("surface", "backend");
        if (organizationId != null && !organizationId.isBlank()) {
            props.put("organization_id", organizationId);
            props.put("$groups", Map.of("organization", organizationId));
        }
        return props;
    }

    private static void putIfPresent(Map<String, Object> props, String key, Object value) {
        if (value != null) props.put(key, value);
    }

    static String lower(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    static String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
