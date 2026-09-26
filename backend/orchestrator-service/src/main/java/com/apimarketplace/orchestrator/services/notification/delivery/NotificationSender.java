package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SendStatus;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Puts one message on one medium and writes down what happened. No policy here:
 * whether a message SHOULD go out (preference, plan, cap, incident) is decided by
 * {@link NotificationDeliveryService} before this is called.
 *
 * <p>No retry, like the approval notifier: a failed alert is recorded as FAILED
 * with its reason, and the bell still holds the notification. Retrying a message
 * whose content is "this just broke" would only deliver it late.
 */
@Component
public class NotificationSender {

    private static final Logger logger = LoggerFactory.getLogger(NotificationSender.class);

    private final AuthClient authClient;
    private final ChatChannelService chatChannelService;
    private final NotificationDeliveryLog deliveryLog;
    private final MeterRegistry meterRegistry;
    private final String publicBaseUrl;

    /** Product analytics; optional so a unit test can build the sender without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public NotificationSender(AuthClient authClient,
                              ChatChannelService chatChannelService,
                              NotificationDeliveryLog deliveryLog,
                              MeterRegistry meterRegistry,
                              @Value("${app.public-url:${app.base-url:http://localhost:3000}}") String publicBaseUrl) {
        this.authClient = authClient;
        this.chatChannelService = chatChannelService;
        this.deliveryLog = deliveryLog;
        this.meterRegistry = meterRegistry;
        this.publicBaseUrl = publicBaseUrl;
    }

    public boolean email(String tenantId, String organizationId, Long notificationId, Kind kind,
                         NotificationMessage message) {
        return email(tenantId, organizationId, notificationId, kind, message, Instant.now(), null, null);
    }

    /** @param topic what the message is about, and {@code category} its bell category, when known (analytics only) */
    public boolean email(String tenantId, String organizationId, Long notificationId, Kind kind,
                         NotificationMessage message, NotificationTopic topic, String category) {
        return email(tenantId, organizationId, notificationId, kind, message, Instant.now(), topic, category);
    }

    /** @param at when the delivery row counts as written (a digest passes its load bound) */
    public boolean email(String tenantId, String organizationId, Long notificationId, Kind kind,
                         NotificationMessage message, Instant at) {
        return email(tenantId, organizationId, notificationId, kind, message, at, null, null);
    }

    private boolean email(String tenantId, String organizationId, Long notificationId, Kind kind,
                          NotificationMessage message, Instant at, NotificationTopic topic, String category) {
        AuthClient.NotificationMailResult result = authClient.sendNotificationMail(
                tenantId, message.subject(), message.lines(), message.actionPath(), message.actionLabel());
        boolean sent = result != null && result.sent();
        String detail = result == null ? "no result" : sent ? null : result.status()
                + (result.detail() != null ? ": " + result.detail() : "");
        deliveryLog.record(tenantId, organizationId, notificationId, kind, Medium.EMAIL,
                sent ? Status.SENT : Status.FAILED, detail, at);
        count(Medium.EMAIL, sent ? Status.SENT : Status.FAILED);
        if (!sent) {
            logger.warn("[notification-delivery] email {} for tenant {} not sent: {}", kind, tenantId, detail);
        }
        track(tenantId, organizationId, kind, Medium.EMAIL, emailStatus(result), topic, category, null);
        return sent;
    }

    /** auth-service answers SENT, NO_ADDRESS or FAILED; anything else (or no answer) is a failure. */
    static SendStatus emailStatus(AuthClient.NotificationMailResult result) {
        if (result == null) return SendStatus.FAILED;
        if (result.sent()) return SendStatus.SENT;
        return "NO_ADDRESS".equals(result.status()) ? SendStatus.NO_ADDRESS : SendStatus.FAILED;
    }

    /**
     * @return true when delivered. A workspace with no channel connected records
     *         nothing: that is a choice the workspace made, not a failed delivery,
     *         and logging it on every alert would bury the real failures.
     */
    public boolean channel(String tenantId, String organizationId, Long notificationId, Kind kind,
                           NotificationMessage message) {
        return channel(tenantId, organizationId, notificationId, kind, message, Instant.now(), null, null);
    }

    /** @param topic what the message is about, and {@code category} its bell category, when known (analytics only) */
    public boolean channel(String tenantId, String organizationId, Long notificationId, Kind kind,
                           NotificationMessage message, NotificationTopic topic, String category) {
        return channel(tenantId, organizationId, notificationId, kind, message, Instant.now(), topic, category);
    }

    /** @param at when the delivery row counts as written (a digest passes its load bound) */
    public boolean channel(String tenantId, String organizationId, Long notificationId, Kind kind,
                           NotificationMessage message, Instant at) {
        return channel(tenantId, organizationId, notificationId, kind, message, at, null, null);
    }

    private boolean channel(String tenantId, String organizationId, Long notificationId, Kind kind,
                            NotificationMessage message, Instant at, NotificationTopic topic, String category) {
        ChatChannelService.NoticeResult result =
                chatChannelService.sendNotice(organizationId, tenantId, message.toChannelText(publicBaseUrl));
        if (result.channel() == null) {
            track(tenantId, organizationId, kind, Medium.CHANNEL, SendStatus.NO_CHANNEL, topic, category, null);
            return false;
        }
        deliveryLog.record(tenantId, organizationId, notificationId, kind, Medium.CHANNEL,
                result.delivered() ? Status.SENT : Status.FAILED,
                result.delivered() ? result.channel() : result.channel() + ": " + result.error(), at);
        count(Medium.CHANNEL, result.delivered() ? Status.SENT : Status.FAILED);
        if (!result.delivered()) {
            logger.warn("[notification-delivery] {} notice for org {} not delivered on {}: {}",
                    kind, organizationId, result.channel(), result.error());
        }
        track(tenantId, organizationId, kind, Medium.CHANNEL,
                result.delivered() ? SendStatus.SENT : SendStatus.FAILED, topic, category, result.channel());
        return result.delivered();
    }

    public void deferred(String tenantId, String organizationId, Long notificationId, Kind kind, Medium medium) {
        deferred(tenantId, organizationId, notificationId, kind, medium, null, null);
    }

    public void deferred(String tenantId, String organizationId, Long notificationId, Kind kind, Medium medium,
                         NotificationTopic topic, String category) {
        deliveryLog.record(tenantId, organizationId, notificationId, kind, medium, Status.DEFERRED,
                "daily cap reached, moved to the next summary");
        count(medium, Status.DEFERRED);
        track(tenantId, organizationId, kind, medium, SendStatus.DEFERRED, topic, category, null);
    }

    private void track(String tenantId, String organizationId, Kind kind, Medium medium, SendStatus status,
                       NotificationTopic topic, String category, String channel) {
        if (analytics != null) {
            analytics.notificationSent(tenantId, organizationId, kind, medium, status, topic, category, channel);
        }
    }

    private void count(Medium medium, Status status) {
        meterRegistry.counter("notification.delivery", "medium", medium.name(), "status", status.name())
                .increment();
    }
}
