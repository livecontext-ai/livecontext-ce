package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SuppressionReason;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("NotificationDeliveryService - what leaves the platform, and what never does")
class NotificationDeliveryServiceTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final UUID WORKFLOW = UUID.randomUUID();

    private NotificationPreferenceStore preferences;
    private NotificationIncidentStore incidents;
    private NotificationDeliveryLog deliveryLog;
    private NotificationEmailEntitlement entitlement;
    private NotificationMessageComposer composer;
    private NotificationSender sender;
    private NotificationDeliveryService service;

    private final NotificationMessage message =
            new NotificationMessage("subject", List.of("line"), "/app", "Open");

    @BeforeEach
    void setUp() {
        preferences = mock(NotificationPreferenceStore.class);
        incidents = mock(NotificationIncidentStore.class);
        deliveryLog = mock(NotificationDeliveryLog.class);
        entitlement = mock(NotificationEmailEntitlement.class);
        composer = mock(NotificationMessageComposer.class);
        sender = mock(NotificationSender.class);
        service = new NotificationDeliveryService(preferences, incidents, deliveryLog, entitlement, composer,
                sender, new SimpleMeterRegistry(), true, 10);
        when(entitlement.allows(anyString(), any())).thenReturn(true);
        when(composer.alert(any())).thenReturn(message);
        when(composer.recovered(any())).thenReturn(message);
        when(deliveryLog.countImmediateSentSince(anyString(), any(), any())).thenReturn(0);
    }

    private static NotificationCreatedEvent event(String category) {
        return new NotificationCreatedEvent(7L, TENANT, ORG, category, "WORKFLOW", WORKFLOW, "run_1",
                Map.of("status", "failed"), Instant.parse("2026-09-24T03:00:00Z"));
    }

    private void prefer(NotificationTopic topic, DeliveryMode mode) {
        when(preferences.resolve(TENANT, ORG, topic)).thenReturn(mode);
    }

    @Nested
    @DisplayName("A workflow that keeps failing is one incident, not one message per run")
    class Incidents {

        @Test
        @DisplayName("The failure that OPENS the incident is sent on every wanted medium")
        void firstFailureIsSent() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);
            when(incidents.recordFailure(eq(TENANT), eq(ORG), eq(WORKFLOW), any())).thenReturn(NotificationIncidentStore.FailureOutcome.OPENED);

            service.handle(event("RUN_FAILED"));

            verify(sender).email(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.FAILURES, "RUN_FAILED");
            verify(sender).channel(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("Regression (spam): a failure that joins an open incident sends NOTHING")
        void laterFailuresAreSilent() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);
            when(incidents.recordFailure(eq(TENANT), eq(ORG), eq(WORKFLOW), any()))
                    .thenReturn(NotificationIncidentStore.FailureOutcome.JOINED);

            service.handle(event("RUN_FAILED"));

            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("Broken again right after an announced recovery: ONE 'failing again' message")
        void failingAgainIsSent() {
            NotificationMessage again = new NotificationMessage("Failing again", List.of("l"), "/app", "Open");
            prefer(NotificationTopic.FAILURES, DeliveryMode.EMAIL);
            when(incidents.recordFailure(eq(TENANT), eq(ORG), eq(WORKFLOW), any()))
                    .thenReturn(NotificationIncidentStore.FailureOutcome.FAILING_AGAIN);
            when(composer.failingAgain(any())).thenReturn(again);

            service.handle(event("RUN_FAILED"));

            verify(sender).email(TENANT, ORG, 7L, Kind.ALERT, again, NotificationTopic.FAILURES, "RUN_FAILED");
            verify(composer, never()).alert(any());
        }

        @Test
        @DisplayName("OFF records no incident either, so turning alerts back on starts clean")
        void offRecordsNothing() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.OFF);

            service.handle(event("RUN_FAILED"));

            verifyNoInteractions(incidents, sender);
        }

        @Test
        @DisplayName("A spending-cap stop is not an incident: it is sent once, deduplicated by its row")
        void budgetReachedSkipsIncident() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.EMAIL);

            service.handle(event("BUDGET_REACHED"));

            verifyNoInteractions(incidents);
            verify(sender).email(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.FAILURES, "BUDGET_REACHED");
            verify(sender, never()).channel(anyString(), anyString(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Topics")
    class Topics {

        @Test
        @DisplayName("A category outside the four topics (an approval, a trophy) never leaves the bell")
        void unknownCategoryIgnored() {
            service.handle(event("APPROVAL_PENDING"));
            service.handle(event("BADGE_UNLOCKED"));

            verifyNoInteractions(preferences, incidents, sender);
        }

        @Test
        @DisplayName("A digest topic is never sent alone: the daily summary picks it up")
        void digestTopicNotSentImmediately() {
            prefer(NotificationTopic.ACCOUNT, DeliveryMode.BOTH);

            service.handle(event("CRED_EXPIRED"));

            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("A notification with no workspace is not delivered (nowhere to resolve preferences from)")
        void noOrgIgnored() {
            service.handle(new NotificationCreatedEvent(7L, TENANT, null, "CREDIT_LOW", "BILLING", WORKFLOW,
                    null, Map.of(), Instant.now()));

            verifyNoInteractions(preferences, sender);
        }
    }

    @Nested
    @DisplayName("Plan and daily cap")
    class PlanAndCap {

        @Test
        @DisplayName("A plan without email alerts still gets the channel message, and no email")
        void freePlanChannelOnly() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);
            when(incidents.recordFailure(any(), any(), any(), any())).thenReturn(NotificationIncidentStore.FailureOutcome.OPENED);
            when(entitlement.allows(TENANT, NotificationTopic.FAILURES)).thenReturn(false);

            service.handle(event("RUN_FAILED"));

            verify(sender, never()).email(any(), any(), any(), any(), any(), any(), any());
            verify(sender).channel(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("Credit alerts ask the entitlement with their own topic, which is how Free gets them by email")
        void creditTopicAskedAsCredits() {
            prefer(NotificationTopic.CREDITS, DeliveryMode.EMAIL);
            when(entitlement.allows(TENANT, NotificationTopic.CREDITS)).thenReturn(true);

            service.handle(event("CREDIT_EXHAUSTED"));

            verify(entitlement).allows(TENANT, NotificationTopic.CREDITS);
            verify(sender).email(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.CREDITS, "CREDIT_EXHAUSTED");
        }

        @Test
        @DisplayName("At the daily cap the message is DEFERRED to the summary instead of sent")
        void capDefers() {
            prefer(NotificationTopic.CREDITS, DeliveryMode.BOTH);
            when(deliveryLog.countImmediateSentSince(eq(TENANT), eq(Medium.EMAIL), any())).thenReturn(10);
            when(deliveryLog.countImmediateSentSince(eq(TENANT), eq(Medium.CHANNEL), any())).thenReturn(9);

            service.handle(event("CREDIT_LOW"));

            verify(sender).deferred(TENANT, ORG, 7L, Kind.ALERT, Medium.EMAIL, NotificationTopic.CREDITS, "CREDIT_LOW");
            verify(sender, never()).email(any(), any(), any(), any(), any(), any(), any());
            verify(sender).channel(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.CREDITS, "CREDIT_LOW");
        }

        @Test
        @DisplayName("A capped message with no bell row (a recovery) is dropped, never deferred with nothing to carry")
        void capWithoutRowDrops() {
            when(deliveryLog.countImmediateSentSince(eq(TENANT), any(), any())).thenReturn(10);

            service.deliver(TENANT, ORG, null, Kind.RECOVERED, message, DeliveryMode.BOTH, NotificationTopic.FAILURES);

            verifyNoInteractions(sender);
        }
    }

    @Nested
    @DisplayName("Recovery")
    class Recovery {

        private final NotificationIncidentStore.Incident incident = new NotificationIncidentStore.Incident(
                1L, TENANT, ORG, WORKFLOW, Instant.now(), Instant.now(), 5, Instant.now(), 1, true);

        @Test
        @DisplayName("No open incident: a success costs one probe and does nothing else")
        void noOpenIncident() {
            when(incidents.hasOpen(WORKFLOW)).thenReturn(false);

            service.onProductionSuccess(WORKFLOW);

            verify(incidents, never()).resolve(any(), any());
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("An open incident is closed and its person told it recovered")
        void recoveredIsSent() {
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(incident));
            prefer(NotificationTopic.FAILURES, DeliveryMode.CHANNEL);

            service.onProductionSuccess(WORKFLOW);

            verify(sender).channel(eq(TENANT), eq(ORG), isNull(), eq(Kind.RECOVERED), eq(message),
                    eq(NotificationTopic.FAILURES), eq("RUN_FAILED"));
            verify(sender, never()).email(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Regression (flapping): a recovery already announced today closes the incident silently")
        void flappingRecoveryNotAnnounced() {
            NotificationIncidentStore.Incident flap = new NotificationIncidentStore.Incident(
                    1L, TENANT, ORG, WORKFLOW, Instant.now(), Instant.now(), 5, Instant.now(), 1, false);
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(flap));
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);

            service.onProductionSuccess(WORKFLOW);

            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("Regression (lost recoveries): one person's failing message does not cost the others theirs")
        void recoveryIsolatedPerIncident() {
            NotificationIncidentStore.Incident other = new NotificationIncidentStore.Incident(
                    2L, "43", ORG, WORKFLOW, Instant.now(), Instant.now(), 5, Instant.now(), 1, true);
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(incident, other));
            when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenThrow(new IllegalStateException("db"));
            when(preferences.resolve("43", ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.EMAIL);

            service.onProductionSuccess(WORKFLOW);

            verify(sender).email("43", ORG, null, Kind.RECOVERED, message, NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("With failures switched OFF the incident still closes, silently")
        void offClosesSilently() {
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(incident));
            prefer(NotificationTopic.FAILURES, DeliveryMode.OFF);

            service.onProductionSuccess(WORKFLOW);

            verify(incidents).resolve(eq(WORKFLOW), any());
            verifyNoInteractions(sender);
        }
    }

    @Nested
    @DisplayName("notification_suppressed says why a notification did not leave")
    class Suppression {

        private EngagementAnalyticsEmitter analytics;

        @BeforeEach
        void wire() {
            analytics = mock(EngagementAnalyticsEmitter.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        }

        @Test
        @DisplayName("preference OFF")
        void preferenceOff() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.OFF);

            service.handle(event("RUN_FAILED"));

            verify(analytics).notificationSuppressed(TENANT, ORG, SuppressionReason.PREFERENCE_OFF, null,
                    NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("preference OFF on a recovery is reported like on an alert (the incident still closes)")
        void recoveryPreferenceOff() {
            NotificationIncidentStore.Incident incident = new NotificationIncidentStore.Incident(
                    1L, TENANT, ORG, WORKFLOW, Instant.now(), Instant.now(), 5, Instant.now(), 1, true);
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(incident));
            prefer(NotificationTopic.FAILURES, DeliveryMode.OFF);

            service.onProductionSuccess(WORKFLOW);

            verify(analytics).notificationSuppressed(TENANT, ORG, SuppressionReason.PREFERENCE_OFF, null,
                    NotificationTopic.FAILURES, "RUN_FAILED");
            verifyNoInteractions(sender);
        }

        @Test
        @DisplayName("a flapping recovery (not announced) is not a suppression")
        void unannouncedRecoveryIsNotSuppressed() {
            NotificationIncidentStore.Incident flapping = new NotificationIncidentStore.Incident(
                    1L, TENANT, ORG, WORKFLOW, Instant.now(), Instant.now(), 5, Instant.now(), 1, false);
            when(incidents.hasOpen(WORKFLOW)).thenReturn(true);
            when(incidents.resolve(eq(WORKFLOW), any())).thenReturn(List.of(flapping));

            service.onProductionSuccess(WORKFLOW);

            verify(analytics, never()).notificationSuppressed(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a failure joining an open incident")
        void incidentJoined() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);
            when(incidents.recordFailure(eq(TENANT), eq(ORG), eq(WORKFLOW), any()))
                    .thenReturn(NotificationIncidentStore.FailureOutcome.JOINED);

            service.handle(event("RUN_FAILED"));

            verify(analytics).notificationSuppressed(TENANT, ORG, SuppressionReason.INCIDENT_JOINED, null,
                    NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("email wanted but not on the plan; the channel still goes out")
        void emailNotOnPlan() {
            prefer(NotificationTopic.FAILURES, DeliveryMode.BOTH);
            when(incidents.recordFailure(any(), any(), any(), any())).thenReturn(NotificationIncidentStore.FailureOutcome.OPENED);
            when(entitlement.allows(TENANT, NotificationTopic.FAILURES)).thenReturn(false);

            service.handle(event("RUN_FAILED"));

            verify(analytics).notificationSuppressed(TENANT, ORG, SuppressionReason.EMAIL_NOT_ON_PLAN, Medium.EMAIL,
                    NotificationTopic.FAILURES, "RUN_FAILED");
            verify(sender).channel(TENANT, ORG, 7L, Kind.ALERT, message, NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("capped with no bell row to defer: one daily_cap per medium, told apart by medium; a deferral is not a suppression")
        void dailyCap() {
            when(deliveryLog.countImmediateSentSince(eq(TENANT), any(), any())).thenReturn(10);

            service.deliver(TENANT, ORG, null, Kind.RECOVERED, message, DeliveryMode.BOTH,
                    NotificationTopic.FAILURES, "RUN_FAILED");
            service.deliver(TENANT, ORG, 7L, Kind.ALERT, message, DeliveryMode.EMAIL,
                    NotificationTopic.FAILURES, "RUN_FAILED");

            // Two events for the one BOTH message, and they are NOT identical: one per medium.
            verify(analytics).notificationSuppressed(TENANT, ORG,
                    SuppressionReason.DAILY_CAP, Medium.EMAIL, NotificationTopic.FAILURES, "RUN_FAILED");
            verify(analytics).notificationSuppressed(TENANT, ORG,
                    SuppressionReason.DAILY_CAP, Medium.CHANNEL, NotificationTopic.FAILURES, "RUN_FAILED");
            verify(analytics, org.mockito.Mockito.times(2)).notificationSuppressed(any(), any(),
                    eq(SuppressionReason.DAILY_CAP), any(), any(), any());
            verify(sender).deferred(TENANT, ORG, 7L, Kind.ALERT, Medium.EMAIL, NotificationTopic.FAILURES, "RUN_FAILED");
        }

        @Test
        @DisplayName("a message that goes out is never reported as suppressed")
        void sentIsNotSuppressed() {
            prefer(NotificationTopic.CREDITS, DeliveryMode.BOTH);

            service.handle(event("CREDIT_LOW"));

            verify(analytics, never()).notificationSuppressed(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("Switched off by configuration: nothing is read, nothing is sent")
    void disabled() {
        NotificationDeliveryService off = new NotificationDeliveryService(preferences, incidents, deliveryLog,
                entitlement, composer, sender, new SimpleMeterRegistry(), false, 10);

        off.handle(event("RUN_FAILED"));
        off.onProductionSuccess(WORKFLOW);

        assertThat(off.hasOpenIncident(WORKFLOW)).isFalse();
        verifyNoInteractions(preferences, incidents, sender);
    }
}
