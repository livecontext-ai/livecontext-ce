package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("NotificationDigestScheduler - reminders and the daily summary")
class NotificationDigestSchedulerTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";

    private NotificationIncidentStore incidents;
    private NotificationDeliveryLog deliveryLog;
    private NotificationPreferenceStore preferences;
    private NotificationEmailEntitlement entitlement;
    private NotificationMessageComposer composer;
    private NotificationSender sender;
    private NotificationDeliveryService deliveryService;
    private NotificationDigestScheduler scheduler;

    private final NotificationMessage message = new NotificationMessage("s", List.of("l"), "/app", "Open");
    private final NotificationIncidentStore.Incident incident = new NotificationIncidentStore.Incident(
            1L, TENANT, ORG, UUID.randomUUID(), Instant.now(), Instant.now(), 9, Instant.now(), 1);

    @BeforeEach
    void setUp() {
        incidents = mock(NotificationIncidentStore.class);
        deliveryLog = mock(NotificationDeliveryLog.class);
        preferences = mock(NotificationPreferenceStore.class);
        entitlement = mock(NotificationEmailEntitlement.class);
        composer = mock(NotificationMessageComposer.class);
        sender = mock(NotificationSender.class);
        deliveryService = mock(NotificationDeliveryService.class);
        scheduler = spy(new NotificationDigestScheduler(incidents, deliveryLog, preferences, entitlement, composer,
                sender, deliveryService, mock(JdbcTemplate.class), new ObjectMapper(), true));
        when(composer.reminder(any())).thenReturn(message);
        when(composer.digest(any(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).isEmpty() ? null : message);
    }

    @Test
    @DisplayName("A due reminder is claimed, then sent once on each open medium")
    void reminderSent() {
        when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.BOTH);
        when(deliveryService.openMediums(TENANT, DeliveryMode.BOTH)).thenReturn(List.of(Medium.EMAIL, Medium.CHANNEL));
        when(incidents.claimReminder(eq(incident), any())).thenReturn(true);

        scheduler.remind(incident, Instant.now());

        verify(sender).email(TENANT, ORG, null, Kind.REMINDER, message, NotificationTopic.FAILURES, "RUN_FAILED");
        verify(sender).channel(TENANT, ORG, null, Kind.REMINDER, message, NotificationTopic.FAILURES, "RUN_FAILED");
    }

    @Test
    @DisplayName("Regression (duplicate reminder): a claim lost to another pass sends nothing")
    void lostClaimSendsNothing() {
        when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.BOTH);
        when(deliveryService.openMediums(TENANT, DeliveryMode.BOTH)).thenReturn(List.of(Medium.EMAIL));
        when(incidents.claimReminder(eq(incident), any())).thenReturn(false);

        scheduler.remind(incident, Instant.now());

        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("Regression (reminder lost for a day): a message that cannot be built is never claimed")
    void unbuildableReminderNotClaimed() {
        when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.BOTH);
        when(deliveryService.openMediums(TENANT, DeliveryMode.BOTH)).thenReturn(List.of(Medium.EMAIL));
        when(composer.reminder(any())).thenThrow(new IllegalStateException("name lookup failed"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.remind(incident, Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        verify(incidents, never()).claimReminder(any(), any());
    }

    @Test
    @DisplayName("Capped on every medium: the reminder is NOT claimed, so the next hourly pass retries it")
    void cappedIsNotClaimed() {
        when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.BOTH);
        when(deliveryService.openMediums(TENANT, DeliveryMode.BOTH)).thenReturn(List.of());

        scheduler.remind(incident, Instant.now());

        verify(incidents, never()).claimReminder(any(), any());
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("Failures switched OFF: no reminder, and the incident is left alone")
    void offNoReminder() {
        when(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.OFF);

        scheduler.remind(incident, Instant.now());

        verify(incidents, never()).claimReminder(any(), any());
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("The email digest carries only topics the plan emails; the channel digest carries what it wants")
    void digestSplitsByMediumAndPlan() {
        Map<NotificationTopic, DeliveryMode> modes = new EnumMap<>(NotificationTopic.class);
        for (NotificationTopic t : NotificationTopic.values()) modes.put(t, t.defaultDelivery());
        modes.put(NotificationTopic.ACCOUNT, DeliveryMode.BOTH);
        modes.put(NotificationTopic.TASKS, DeliveryMode.EMAIL);
        when(preferences.resolveAll(TENANT, ORG)).thenReturn(modes);
        when(entitlement.allows(TENANT, NotificationTopic.ACCOUNT)).thenReturn(false);
        when(entitlement.allows(TENANT, NotificationTopic.TASKS)).thenReturn(false);
        when(deliveryLog.deferredBetween(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        doReturn(List.of()).when(scheduler).load(eq(TENANT), eq(ORG), eq(Set.of()), eq(List.of()), any(), any());
        doReturn(List.of(new NotificationMessageComposer.DigestItem("CRED_EXPIRED", "CREDENTIAL", UUID.randomUUID(),
                Map.of(), Instant.now())))
                .when(scheduler).load(eq(TENANT), eq(ORG),
                        eq(Set.of("CRED_EXPIRED", "WEBHOOK_TRIGGER_DISABLED")), eq(List.of()), any(), any());

        scheduler.digest(TENANT, ORG, Instant.now());

        verify(sender, never()).email(any(), any(), any(), any(), any(), any());
        verify(sender).channel(eq(TENANT), eq(ORG), isNull(), eq(Kind.DIGEST), eq(message), any());
        // Worded for the person the summary goes to (their language and zone), not a workspace.
        verify(composer).digest(eq(TENANT), org.mockito.ArgumentMatchers.argThat(items -> items.size() == 1));
    }

    @Test
    @DisplayName("The summary starts at the medium's last summary, never more than 48h back")
    void digestWindow() {
        Instant now = Instant.parse("2026-09-24T07:00:00Z");

        org.assertj.core.api.Assertions.assertThat(NotificationDigestScheduler.digestSince(null, now))
                .isEqualTo(Instant.parse("2026-09-22T07:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(
                NotificationDigestScheduler.digestSince(Instant.parse("2026-09-23T07:00:05Z"), now))
                .isEqualTo(Instant.parse("2026-09-23T07:00:05Z"));
        org.assertj.core.api.Assertions.assertThat(
                NotificationDigestScheduler.digestSince(Instant.parse("2026-09-01T07:00:00Z"), now))
                .as("a long outage does not replay a month").isEqualTo(Instant.parse("2026-09-22T07:00:00Z"));
    }

    @Test
    @DisplayName("Regression (shared cursor): each medium's summary starts at ITS OWN last summary")
    void cursorIsPerMedium() {
        Instant now = Instant.parse("2026-09-24T07:00:00Z");
        Instant lastChannel = Instant.parse("2026-09-24T06:59:00Z");
        Map<NotificationTopic, DeliveryMode> modes = new EnumMap<>(NotificationTopic.class);
        for (NotificationTopic t : NotificationTopic.values()) modes.put(t, DeliveryMode.BOTH);
        when(preferences.resolveAll(TENANT, ORG)).thenReturn(modes);
        when(entitlement.allows(anyString(), any())).thenReturn(true);
        when(deliveryLog.lastDigestAt(TENANT, ORG, Medium.EMAIL)).thenReturn(null);
        when(deliveryLog.lastDigestAt(TENANT, ORG, Medium.CHANNEL)).thenReturn(lastChannel);
        when(deliveryLog.deferredBetween(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        doReturn(List.of()).when(scheduler).load(any(), any(), any(), any(), any(), any());

        scheduler.digest(TENANT, ORG, now);

        Instant bound = now.minus(NotificationDigestScheduler.COMMIT_GRACE);
        verify(scheduler).load(eq(TENANT), eq(ORG), any(), any(),
                eq(bound.minus(NotificationDigestScheduler.MAX_DIGEST_WINDOW)), eq(bound));
        verify(scheduler).load(eq(TENANT), eq(ORG), any(), any(), eq(lastChannel), eq(bound));
    }

    @Test
    @DisplayName("Nothing to say: no digest on either medium")
    void emptyDigestSendsNothing() {
        Map<NotificationTopic, DeliveryMode> modes = new EnumMap<>(NotificationTopic.class);
        for (NotificationTopic t : NotificationTopic.values()) modes.put(t, DeliveryMode.OFF);
        when(preferences.resolveAll(TENANT, ORG)).thenReturn(modes);
        when(deliveryLog.deferredBetween(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());

        scheduler.digest(TENANT, ORG, Instant.now());

        verifyNoInteractions(sender);
    }
}
