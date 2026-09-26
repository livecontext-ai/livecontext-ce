package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The settings screen's view of delivery: what the person chose per topic in the
 * active workspace, and the two facts that decide whether a choice can actually
 * deliver (does the plan include email, is a chat channel connected). Both facts
 * are computed here, the same way the delivery path computes them, so the screen
 * can never promise a medium the sender would then skip.
 */
@Service
public class NotificationPreferencesService {

    /**
     * @param personScoped the choice applies in every workspace of the person (credits: the alert
     *                     always lands in their personal workspace, whose channel it uses)
     */
    public record TopicView(String topic, String delivery, String defaultDelivery, boolean emailAvailable,
                            boolean personScoped) {}

    /** The workspace default destination, or connected=false. {@code title} is the chat's name when known. */
    public record ChannelView(boolean connected, String channel, String title) {}

    public record PreferencesView(List<TopicView> topics, String emailRequiredPlan, ChannelView channel) {}

    private final NotificationPreferenceStore store;
    private final NotificationEmailEntitlement emailEntitlement;
    private final ChatChannelService chatChannelService;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public NotificationPreferencesService(NotificationPreferenceStore store,
                                          NotificationEmailEntitlement emailEntitlement,
                                          ChatChannelService chatChannelService) {
        this.store = store;
        this.emailEntitlement = emailEntitlement;
        this.chatChannelService = chatChannelService;
    }

    public PreferencesView view(String tenantId, String organizationId) {
        Map<NotificationTopic, DeliveryMode> modes = store.resolveAll(tenantId, organizationId);
        String requiredPlan = emailEntitlement.requiredPlan(tenantId);
        List<TopicView> topics = new ArrayList<>();
        for (NotificationTopic topic : NotificationTopic.values()) {
            topics.add(new TopicView(topic.name(), modes.get(topic).name(), topic.defaultDelivery().name(),
                    topic == NotificationTopic.CREDITS || requiredPlan == null, topic.isPersonScoped()));
        }
        return new PreferencesView(topics, requiredPlan, channel(organizationId));
    }

    /**
     * @throws IllegalArgumentException with a sentence the screen can show, for an
     *         unknown topic or delivery
     */
    public PreferencesView update(String tenantId, String organizationId, String topicValue, String deliveryValue) {
        NotificationTopic topic = NotificationTopic.parse(topicValue)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification topic: " + topicValue));
        DeliveryMode mode = DeliveryMode.parse(deliveryValue)
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery: " + deliveryValue
                        + ". Use OFF, EMAIL, CHANNEL or BOTH."));
        DeliveryMode previous = previousChoice(tenantId, organizationId, topic);
        store.save(tenantId, organizationId, topic, mode);
        PreferencesView view = view(tenantId, organizationId);
        // Only a real change is an event: re-saving the choice in force changes nothing. An
        // unreadable previous choice (null) is still reported, without previous_delivery.
        if (analytics != null && previous != mode) {
            boolean emailAvailable = view.topics().stream()
                    .filter(t -> t.topic().equals(topic.name()))
                    .findFirst().map(TopicView::emailAvailable).orElse(false);
            analytics.notificationPreferenceChanged(tenantId, organizationId, topic, mode, previous,
                    topic.isPersonScoped(), emailAvailable);
        }
        return view;
    }

    /**
     * The choice in force before this save, read only when analytics would report it.
     * Null when it cannot be read: the save must never depend on a read made for a chart.
     */
    private DeliveryMode previousChoice(String tenantId, String organizationId, NotificationTopic topic) {
        if (analytics == null || !analytics.isActive()) return null;
        try {
            return store.resolve(tenantId, organizationId, topic);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ChannelView channel(String organizationId) {
        return chatChannelService.list(organizationId).stream()
                .filter(s -> s.isDefault() && s.active())
                .findFirst()
                .map(s -> new ChannelView(true, s.channel(), s.chatTitle() != null ? s.chatTitle() : s.chatId()))
                .orElse(new ChannelView(false, null, null));
    }
}
