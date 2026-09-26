package com.apimarketplace.orchestrator.services.analytics;

import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Decision;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Input;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SendStatus;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SuppressionReason;
import com.apimarketplace.orchestrator.services.badge.BadgeCatalog;
import com.apimarketplace.orchestrator.services.badge.BadgeDefinition;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChangeSource;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.WebhookOutcome;
import com.apimarketplace.orchestrator.services.notification.delivery.DeliveryMode;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The property contracts of the engagement events (notifications, chat channel, channel
 * requests, trophies), and the gating that keeps the emitter silent and harmless without a
 * configured client.
 */
class EngagementAnalyticsEmitterTest {

    private static final String ORG = "org-1";
    /** Keys that would carry PII if they ever appeared: none of these may be sent. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("email", "chat_id", "chat_title", "title",
            "name", "message", "text", "answer", "question", "subject", "error", "webhook_url");

    private static void assertCommon(Map<String, Object> p) {
        assertThat(p.get("surface")).isEqualTo("backend");
        assertThat(p.get("organization_id")).isEqualTo(ORG);
        assertThat(p.get("$groups")).isEqualTo(Map.of("organization", ORG));
        assertThat(p.keySet()).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
    }

    @Nested
    @DisplayName("notification_sent")
    class Sent {
        @Test
        @DisplayName("a channel alert carries kind, medium, status, topic, category and the provider, all lowercase")
        void channelProps() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildNotificationSentProps(ORG, Kind.ALERT,
                    Medium.CHANNEL, SendStatus.SENT, NotificationTopic.FAILURES, "RUN_FAILED", "Telegram");
            assertCommon(p);
            assertThat(p).containsEntry("kind", "alert").containsEntry("medium", "channel")
                    .containsEntry("status", "sent").containsEntry("topic", "failures")
                    .containsEntry("category", "run_failed").containsEntry("channel", "telegram");
        }

        @Test
        @DisplayName("an email never names a channel, and a digest omits topic and category it does not know")
        void emailDigestProps() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildNotificationSentProps(ORG, Kind.DIGEST,
                    Medium.EMAIL, SendStatus.NO_ADDRESS, null, null, "slack");
            assertThat(p).containsEntry("kind", "digest").containsEntry("status", "no_address")
                    .doesNotContainKeys("channel", "topic", "category");
        }
    }

    @Test
    @DisplayName("notification_suppressed: reason, medium, topic and category")
    void suppressedProps() {
        Map<String, Object> p = EngagementAnalyticsEmitter.buildNotificationSuppressedProps(ORG,
                SuppressionReason.EMAIL_NOT_ON_PLAN, Medium.EMAIL, NotificationTopic.CREDITS, "CREDIT_LOW");
        assertCommon(p);
        assertThat(p).containsEntry("reason", "email_not_on_plan").containsEntry("medium", "email")
                .containsEntry("topic", "credits").containsEntry("category", "credit_low");
    }

    @Test
    @DisplayName("notification_suppressed: a reason that stops every medium carries no medium")
    void suppressedPropsWithoutMedium() {
        Map<String, Object> p = EngagementAnalyticsEmitter.buildNotificationSuppressedProps(ORG,
                SuppressionReason.PREFERENCE_OFF, null, NotificationTopic.FAILURES, "RUN_FAILED");
        assertThat(p).containsEntry("reason", "preference_off").doesNotContainKey("medium");
    }

    @Test
    @DisplayName("notification_preference_changed: new and previous choice, scope, email availability")
    void preferenceProps() {
        Map<String, Object> p = EngagementAnalyticsEmitter.buildPreferenceChangedProps(ORG,
                NotificationTopic.CREDITS, DeliveryMode.BOTH, DeliveryMode.EMAIL, true, false);
        assertCommon(p);
        assertThat(p).containsEntry("topic", "credits").containsEntry("delivery", "both")
                .containsEntry("previous_delivery", "email").containsEntry("person_scoped", true)
                .containsEntry("email_available", false);

        assertThat(EngagementAnalyticsEmitter.buildPreferenceChangedProps(ORG, NotificationTopic.TASKS,
                DeliveryMode.OFF, null, false, true)).doesNotContainKey("previous_delivery");
    }

    @Nested
    @DisplayName("chat channel")
    class Channel {
        @Test
        @DisplayName("channel_connected: provider, source, delivered, default and the webhook outcome")
        void connected() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildChannelConnectedProps(ORG, "slack",
                    ChangeSource.ASSISTANT, false, true, WebhookOutcome.NOT_OURS_TO_SET, false);
            assertCommon(p);
            assertThat(p).containsEntry("channel", "slack").containsEntry("source", "assistant")
                    .containsEntry("delivered", false).containsEntry("is_default", true)
                    .containsEntry("webhook_outcome", "not_ours_to_set").containsEntry("is_new_link", false);
            assertThat(EngagementAnalyticsEmitter.buildChannelConnectedProps(ORG, "slack",
                    ChangeSource.MANUAL, true, false, WebhookOutcome.POINTED, true)).containsEntry("is_new_link", true);
        }

        @Test
        @DisplayName("channel_disconnected: provider, source and whether it was the default")
        void disconnected() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildChannelDisconnectedProps(ORG, "telegram",
                    ChangeSource.MANUAL, true);
            assertCommon(p);
            assertThat(p).containsEntry("channel", "telegram").containsEntry("source", "manual")
                    .containsEntry("was_default", true);
        }

        @Test
        @DisplayName("channel_default_set: the provider only")
        void defaultSet() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildChannelDefaultSetProps(ORG, "discord");
            assertCommon(p);
            assertThat(p).containsEntry("channel", "discord");
        }
    }

    @Nested
    @DisplayName("channel requests")
    class Requests {
        @Test
        @DisplayName("channel_request_delivered: request type, provider and status")
        void delivered() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildRequestDeliveredProps(ORG,
                    RequestType.WORKFLOW_APPROVAL, "teams", RequestStatus.ALREADY_PENDING);
            assertCommon(p);
            assertThat(p).containsEntry("request_type", "workflow_approval").containsEntry("channel", "teams")
                    .containsEntry("status", "already_pending");
        }

        @Test
        @DisplayName("channel_request_answered: request type, provider, decision and input when known")
        void answered() {
            Map<String, Object> p = EngagementAnalyticsEmitter.buildRequestAnsweredProps(ORG,
                    RequestType.ASK_USER, "whatsapp", Decision.ANSWERED, Input.TEXT);
            assertCommon(p);
            assertThat(p).containsEntry("request_type", "ask_user").containsEntry("decision", "answered")
                    .containsEntry("input", "text");
            assertThat(EngagementAnalyticsEmitter.buildRequestAnsweredProps(ORG, RequestType.AGENT_PERMISSION,
                    null, Decision.REJECTED, null)).doesNotContainKeys("input", "channel");
        }

        @Test
        @DisplayName("a service status maps by name, and an unknown one maps to nothing instead of throwing")
        void statusMapping() {
            assertThat(RequestStatus.of(Thread.State.NEW)).isNull();
            assertThat(RequestStatus.of(null)).isNull();
            assertThat(RequestStatus.of(RequestStatus.NO_CHANNEL)).isEqualTo(RequestStatus.NO_CHANNEL);
        }
    }

    @Test
    @DisplayName("badge_unlocked: code, family and tier lowercased, backfill flag")
    void badgeProps() {
        BadgeDefinition badge = BadgeCatalog.all().get(0);
        Map<String, Object> p = EngagementAnalyticsEmitter.buildBadgeUnlockedProps(ORG, badge, true);
        assertCommon(p);
        assertThat(p).containsEntry("badge_code", badge.code())
                .containsEntry("badge_family", badge.family().name().toLowerCase())
                .containsEntry("badge_tier", badge.tier().name().toLowerCase())
                .containsEntry("backfill", true);
    }

    @Test
    @DisplayName("an unknown workspace sends neither organization_id nor a group")
    void noOrg() {
        assertThat(EngagementAnalyticsEmitter.buildChannelDefaultSetProps(null, "slack"))
                .doesNotContainKeys("organization_id", "$groups").containsEntry("surface", "backend");
    }

    @Nested
    @DisplayName("gating")
    class Gating {
        @Test
        @DisplayName("active: captured under the user id as distinct_id, with the built props")
        void capturesUnderUserId() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            EngagementAnalyticsEmitter emitter = new EngagementAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);

            emitter.channelDefaultSet("42", ORG, "slack");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("42"), eq(EngagementAnalyticsEmitter.CHANNEL_DEFAULT_SET), props.capture());
            assertThat(props.getValue()).containsEntry("channel", "slack");
            assertThat(emitter.isActive()).isTrue();
        }

        @Test
        @DisplayName("no client, inactive client, blank user or null status/badge: nothing captured, nothing thrown")
        void silentWhenInactive() {
            EngagementAnalyticsEmitter none = new EngagementAnalyticsEmitter();
            assertThat(none.isActive()).isFalse();
            assertThatCode(() -> none.channelDefaultSet("42", ORG, "slack")).doesNotThrowAnyException();

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(false);
            EngagementAnalyticsEmitter inactive = new EngagementAnalyticsEmitter();
            ReflectionTestUtils.setField(inactive, "postHog", client);
            inactive.notificationSent("42", ORG, Kind.ALERT, Medium.EMAIL, SendStatus.SENT, null, null, null);
            verify(client, never()).capture(anyString(), anyString(), anyMap());

            when(client.isActive()).thenReturn(true);
            inactive.channelDefaultSet(" ", ORG, "slack");
            inactive.channelDefaultSet(null, ORG, "slack");
            inactive.channelRequestDelivered("42", ORG, RequestType.ASK_USER, "slack", null);
            inactive.badgeUnlocked("42", ORG, null, false);
            verify(client, never()).capture(any(), any(), any());
        }

        @Test
        @DisplayName("a client that throws never reaches the caller")
        void swallowsClientFailure() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(true);
            doThrow(new IllegalStateException("boom")).when(client).capture(any(), any(), any());
            EngagementAnalyticsEmitter emitter = new EngagementAnalyticsEmitter();
            ReflectionTestUtils.setField(emitter, "postHog", client);

            assertThatCode(() -> emitter.notificationSuppressed("42", ORG, SuppressionReason.DAILY_CAP,
                    Medium.CHANNEL, NotificationTopic.FAILURES, "RUN_FAILED")).doesNotThrowAnyException();
        }
    }
}
