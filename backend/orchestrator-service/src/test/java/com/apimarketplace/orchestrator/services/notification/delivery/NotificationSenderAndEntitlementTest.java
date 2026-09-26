package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SendStatus;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Notification sender and email entitlement")
class NotificationSenderAndEntitlementTest {

    @Nested
    @DisplayName("NotificationSender - records what really happened")
    class Sender {

        private AuthClient authClient;
        private ChatChannelService channels;
        private NotificationDeliveryLog log;
        private NotificationSender sender;
        private final NotificationMessage message = new NotificationMessage("Subject", List.of("line"), "/app", "Open");

        @BeforeEach
        void setUp() {
            authClient = mock(AuthClient.class);
            channels = mock(ChatChannelService.class);
            log = mock(NotificationDeliveryLog.class);
            sender = new NotificationSender(authClient, channels, log, new SimpleMeterRegistry(), "https://app.test");
        }

        @Test
        @DisplayName("An email auth-service could not send is recorded FAILED with its reason, never SENT")
        void emailFailureRecorded() {
            when(authClient.sendNotificationMail(any(), any(), any(), any(), any()))
                    .thenReturn(new AuthClient.NotificationMailResult("NO_ADDRESS", "email not verified"));

            assertThat(sender.email("42", "org", 7L, Kind.ALERT, message)).isFalse();

            verify(log).record(eq("42"), eq("org"), eq(7L), eq(Kind.ALERT), eq(Medium.EMAIL), eq(Status.FAILED),
                    eq("NO_ADDRESS: email not verified"), any());
        }

        @Test
        @DisplayName("A sent email is recorded SENT (what the daily cap counts)")
        void emailSentRecorded() {
            when(authClient.sendNotificationMail("42", "Subject", List.of("line"), "/app", "Open"))
                    .thenReturn(new AuthClient.NotificationMailResult("SENT", null));

            assertThat(sender.email("42", "org", 7L, Kind.ALERT, message)).isTrue();

            verify(log).record(eq("42"), eq("org"), eq(7L), eq(Kind.ALERT), eq(Medium.EMAIL), eq(Status.SENT), isNull(), any());
        }

        @Test
        @DisplayName("The chat text links to the public origin")
        void channelTextUsesPublicOrigin() {
            when(channels.sendNotice(eq("org"), eq("42"), anyString()))
                    .thenReturn(new ChatChannelService.NoticeResult(true, "slack", null));

            sender.channel("42", "org", 7L, Kind.ALERT, message);

            verify(channels).sendNotice(eq("org"), eq("42"), eq("Subject\nline\n\nOpen: https://app.test/app"));
            verify(log).record(eq("42"), eq("org"), eq(7L), eq(Kind.ALERT), eq(Medium.CHANNEL), eq(Status.SENT), eq("slack"), any());
        }

        @Test
        @DisplayName("A digest's delivery row is stamped with the instant its reads were bounded by")
        void digestRowStampedWithLoadBound() {
            java.time.Instant bound = java.time.Instant.parse("2026-09-24T07:00:00Z");
            when(authClient.sendNotificationMail(any(), any(), any(), any(), any()))
                    .thenReturn(new AuthClient.NotificationMailResult("SENT", null));

            sender.email("42", "org", null, Kind.DIGEST, message, bound);

            verify(log).record(eq("42"), eq("org"), isNull(), eq(Kind.DIGEST), eq(Medium.EMAIL), eq(Status.SENT),
                    isNull(), eq(bound));
        }

        @Test
        @DisplayName("No channel connected is a choice, not a failure: nothing is recorded")
        void noChannelRecordsNothing() {
            when(channels.sendNotice(any(), any(), any()))
                    .thenReturn(new ChatChannelService.NoticeResult(false, null, "No chat channel"));

            assertThat(sender.channel("42", "org", 7L, Kind.ALERT, message)).isFalse();

            verifyNoInteractions(log);
        }

        @Test
        @DisplayName("A provider refusal is recorded FAILED with the channel and the reason")
        void channelFailureRecorded() {
            when(channels.sendNotice(any(), any(), any()))
                    .thenReturn(new ChatChannelService.NoticeResult(false, "telegram", "chat not found"));

            sender.channel("42", "org", 7L, Kind.ALERT, message);

            verify(log).record(eq("42"), eq("org"), eq(7L), eq(Kind.ALERT), eq(Medium.CHANNEL), eq(Status.FAILED),
                    startsWith("telegram: chat not found"), any());
        }

        @Nested
        @DisplayName("notification_sent reports the outcome of each medium")
        class Analytics {

            private EngagementAnalyticsEmitter analytics;

            @BeforeEach
            void wire() {
                analytics = mock(EngagementAnalyticsEmitter.class);
                org.springframework.test.util.ReflectionTestUtils.setField(sender, "analytics", analytics);
            }

            @Test
            @DisplayName("an email with no usable address reports no_address, with the caller's topic and category")
            void emailNoAddress() {
                when(authClient.sendNotificationMail(any(), any(), any(), any(), any()))
                        .thenReturn(new AuthClient.NotificationMailResult("NO_ADDRESS", "email not verified"));

                sender.email("42", "org", 7L, Kind.ALERT, message, NotificationTopic.CREDITS, "CREDIT_LOW");

                verify(analytics).notificationSent("42", "org", Kind.ALERT, Medium.EMAIL, SendStatus.NO_ADDRESS,
                        NotificationTopic.CREDITS, "CREDIT_LOW", null);
            }

            @Test
            @DisplayName("an unreachable auth-service reports failed; a digest reports no topic")
            void emailFailedAndDigest() {
                when(authClient.sendNotificationMail(any(), any(), any(), any(), any()))
                        .thenReturn(new AuthClient.NotificationMailResult("FAILED", "ResourceAccessException"));

                sender.email("42", "org", null, Kind.DIGEST, message, java.time.Instant.now());

                verify(analytics).notificationSent("42", "org", Kind.DIGEST, Medium.EMAIL, SendStatus.FAILED,
                        null, null, null);
            }

            @Test
            @DisplayName("a delivered chat notice names its provider; no channel connected reports no_channel")
            void channelOutcomes() {
                when(channels.sendNotice(any(), any(), any()))
                        .thenReturn(new ChatChannelService.NoticeResult(true, "slack", null))
                        .thenReturn(new ChatChannelService.NoticeResult(false, null, "No chat channel"));

                sender.channel("42", "org", 7L, Kind.REMINDER, message, NotificationTopic.FAILURES, "RUN_FAILED");
                sender.channel("42", "org", 7L, Kind.ALERT, message);

                verify(analytics).notificationSent("42", "org", Kind.REMINDER, Medium.CHANNEL, SendStatus.SENT,
                        NotificationTopic.FAILURES, "RUN_FAILED", "slack");
                verify(analytics).notificationSent("42", "org", Kind.ALERT, Medium.CHANNEL, SendStatus.NO_CHANNEL,
                        null, null, null);
            }

            @Test
            @DisplayName("a capped message reports deferred on its medium")
            void deferred() {
                sender.deferred("42", "org", 7L, Kind.ALERT, Medium.EMAIL, NotificationTopic.FAILURES, "RUN_FAILED");

                verify(analytics).notificationSent("42", "org", Kind.ALERT, Medium.EMAIL, SendStatus.DEFERRED,
                        NotificationTopic.FAILURES, "RUN_FAILED", null);
            }
        }
    }

    @Nested
    @DisplayName("NotificationEmailEntitlement - email is a paid capability, credits excepted")
    class Entitlement {

        private final NotificationEmailEntitlement entitlement = new NotificationEmailEntitlement();

        @Test
        @DisplayName("No gate (self-hosted, tests): email is allowed for every topic")
        void noGateAllows() {
            assertThat(entitlement.allows("42", NotificationTopic.FAILURES)).isTrue();
            assertThat(entitlement.requiredPlan("42")).isNull();
        }

        @Test
        @DisplayName("A plan below the bar gets no failure email, but still gets the credit email")
        void belowBar() {
            PlanFeatureGate gate = mock(PlanFeatureGate.class);
            when(gate.isEnabled()).thenReturn(true);
            when(gate.upgradeRequiredFor("42", List.of(NotificationEmailEntitlement.FEATURE_KEY))).thenReturn("STARTER");
            entitlement.setPlanFeatureGate(gate);

            assertThat(entitlement.allows("42", NotificationTopic.FAILURES)).isFalse();
            assertThat(entitlement.allows("42", NotificationTopic.CREDITS)).isTrue();
            assertThat(entitlement.requiredPlan("42")).isEqualTo("STARTER");
        }

        @Test
        @DisplayName("The key the gate asks for is, string for string, the one V528 seeds at STARTER")
        void featureKeyMatchesSeed() throws Exception {
            String v525 = java.nio.file.Files.readString(java.nio.file.Path.of("..", "migration-service", "src",
                    "main", "resources", "db", "migration", "V528__notification_delivery.sql"));

            assertThat(v525).contains("VALUES ('" + NotificationEmailEntitlement.FEATURE_KEY + "', 'STARTER',");
        }

        @Test
        @DisplayName("A disabled gate (non-shared-cloud edition) gates nothing")
        void disabledGate() {
            PlanFeatureGate gate = mock(PlanFeatureGate.class);
            when(gate.isEnabled()).thenReturn(false);
            entitlement.setPlanFeatureGate(gate);

            assertThat(entitlement.allows("42", NotificationTopic.TASKS)).isTrue();
            verify(gate, never()).upgradeRequiredFor(any(), any());
        }
    }
}
