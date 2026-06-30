package com.apimarketplace.trigger.service;

import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4 - verifies TriggerLifecycleManager publishes WEBHOOK_TRIGGER_DISABLED
 * via NotificationClient on user-actionable suspend (USER_DISABLED,
 * NO_PRODUCTION_RUN, WORKFLOW_UNPINNED, TTL_EXPIRED, MAX_EXEC_REACHED,
 * PLAN_TRIGGER_REMOVED) and DOES NOT emit for filtered reasons
 * (WORKFLOW_DELETED, LEGACY_DISABLED, null) or non-suspend transitions.
 *
 * <p>Note: tests run outside a Spring tx (no synchronization active), so
 * the manager falls back to immediate emit (see {@code emitTriggerDisabledAfterCommit}).
 * AFTER_COMMIT path is exercised by integration tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerLifecycleManager - P4 notification emission")
class TriggerLifecycleManagerNotificationTest {

    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatRepository;
    @Mock private StandaloneFormEndpointRepository formRepository;
    @Mock private WebhookTokenRepository tokenRepository;
    @Mock private NotificationClient notificationClient;

    private TriggerLifecycleManager manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = new TriggerLifecycleManager(scheduleRepository, webhookRepository,
                chatRepository, formRepository, tokenRepository);
        Field f = TriggerLifecycleManager.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(manager, notificationClient);
    }

    private ScheduledExecutionEntity schedule(UUID id) {
        ScheduledExecutionEntity s = new ScheduledExecutionEntity();
        s.setId(id);
        s.setName("Daily Schedule");
        s.setTenantId("user-1");
        s.setState(TriggerState.ACTIVE);
        s.setEnabled(true);
        return s;
    }

    private StandaloneWebhookEntity webhook(UUID id) {
        StandaloneWebhookEntity w = new StandaloneWebhookEntity();
        w.setId(id);
        w.setName("Slack Webhook");
        w.setTenantId("user-1");
        w.setState(TriggerState.ACTIVE);
        w.setIsActive(true);
        return w;
    }

    @Test
    @DisplayName("suspendSchedule with USER_DISABLED → emits WEBHOOK_TRIGGER_DISABLED")
    void suspendScheduleUserDisabledEmits() {
        UUID id = UUID.randomUUID();
        when(scheduleRepository.findById(id)).thenReturn(Optional.of(schedule(id)));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        manager.suspendSchedule(id, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        NotificationEmitRequest req = captor.getValue();
        assertThat(req.getCategory()).isEqualTo("WEBHOOK_TRIGGER_DISABLED");
        assertThat(req.getSeverity()).isEqualTo("warning");
        assertThat(req.getSubjectType()).isEqualTo("TRIGGER");
        assertThat(req.getSubjectId()).isEqualTo(id);
        assertThat(req.getTenantId()).isEqualTo("user-1");
        assertThat(req.getSourceId()).startsWith(id.toString() + ":");
        assertThat(req.getPayload()).containsEntry("status", "disabled");
        assertThat(req.getPayload()).containsEntry("reason", "USER_DISABLED");
        assertThat(req.getPayload()).containsEntry("triggerKind", "schedule");
        assertThat(req.getPayload()).containsEntry("subjectName", "Daily Schedule");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_DISABLED", "NO_PRODUCTION_RUN", "WORKFLOW_UNPINNED",
            "TTL_EXPIRED", "MAX_EXEC_REACHED", "PLAN_TRIGGER_REMOVED"})
    @DisplayName("All 6 notifiable reasons trigger emit")
    void allNotifiableReasonsEmit(String reason) {
        UUID id = UUID.randomUUID();
        when(webhookRepository.findById(id)).thenReturn(Optional.of(webhook(id)));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        manager.suspendWebhook(id, reason, TriggerLifecycleManager.Source.ADMIN);

        verify(notificationClient).emit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKFLOW_DELETED", "LEGACY_DISABLED", "TENANT_SUSPENDED"})
    @DisplayName("Filtered reasons (deleted / legacy / tenant-suspended) DO NOT emit")
    void filteredReasonsDoNotEmit(String reason) {
        UUID id = UUID.randomUUID();
        when(webhookRepository.findById(id)).thenReturn(Optional.of(webhook(id)));

        manager.suspendWebhook(id, reason, TriggerLifecycleManager.Source.DELETION);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("Null reason → no emit (defensive guard)")
    void nullReasonNoEmit() {
        UUID id = UUID.randomUUID();
        when(webhookRepository.findById(id)).thenReturn(Optional.of(webhook(id)));

        manager.suspendWebhook(id, null, TriggerLifecycleManager.Source.ADMIN);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("archiveWebhook does NOT emit (only suspend paths emit)")
    void archiveDoesNotEmit() {
        UUID id = UUID.randomUUID();
        when(webhookRepository.findById(id)).thenReturn(Optional.of(webhook(id)));

        manager.archiveWebhook(id, TriggerLifecycleManager.Reason.WORKFLOW_DELETED,
                TriggerLifecycleManager.Source.DELETION);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("source_id format: triggerId + ':' + epoch_day (deterministic per-day dedup)")
    void sourceIdFormat() {
        UUID id = UUID.randomUUID();
        StandaloneWebhookEntity w = webhook(id);
        // The pre-set lastDisabledAt is OVERWRITTEN by transitionWebhook to
        // Instant.now() before emitTriggerDisabledAfterCommit reads it
        // (TriggerLifecycleManager.java:406). The hardcoded "20586" expectation
        // only worked on the test-author's calendar day (2026-05-08); on any
        // other CI run it produced off-by-N (CI 2026-05-10: actual=20583).
        // Capture "now" before/after suspendWebhook to allow either side of a
        // UTC-midnight crossover (vanishingly rare but pinned).
        w.setLastDisabledAt(Instant.parse("2026-05-08T10:00:00Z")); // setup noise, will be overwritten
        when(webhookRepository.findById(id)).thenReturn(Optional.of(w));
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        Instant before = Instant.now();
        manager.suspendWebhook(id, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);
        Instant after = Instant.now();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        long expectedDayBefore = before.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .getEpochSecond() / 86_400L;
        long expectedDayAfter = after.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .getEpochSecond() / 86_400L;
        assertThat(captor.getValue().getSourceId())
                .isIn(id.toString() + ":" + expectedDayBefore,
                      id.toString() + ":" + expectedDayAfter);
    }

    @Test
    @DisplayName("Null tenantId → no emit (defensive guard)")
    void nullTenantIdNoEmit() {
        UUID id = UUID.randomUUID();
        StandaloneWebhookEntity w = webhook(id);
        w.setTenantId(null);
        when(webhookRepository.findById(id)).thenReturn(Optional.of(w));

        manager.suspendWebhook(id, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("All 4 trigger kinds (schedule/webhook/chat/form) emit with correct triggerKind in payload")
    void allTriggerKindsEmit() {
        lenient().when(notificationClient.emit(any())).thenReturn(true);

        UUID schedId = UUID.randomUUID();
        when(scheduleRepository.findById(schedId)).thenReturn(Optional.of(schedule(schedId)));
        manager.suspendSchedule(schedId, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        UUID webId = UUID.randomUUID();
        when(webhookRepository.findById(webId)).thenReturn(Optional.of(webhook(webId)));
        manager.suspendWebhook(webId, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        UUID chatId = UUID.randomUUID();
        StandaloneChatEndpointEntity c = new StandaloneChatEndpointEntity();
        c.setId(chatId); c.setName("Chat A"); c.setTenantId("user-1");
        c.setState(TriggerState.ACTIVE); c.setIsActive(true);
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(c));
        manager.suspendChat(chatId, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        UUID formId = UUID.randomUUID();
        StandaloneFormEndpointEntity f = new StandaloneFormEndpointEntity();
        f.setId(formId); f.setName("Form A"); f.setTenantId("user-1");
        f.setState(TriggerState.ACTIVE); f.setIsActive(true);
        when(formRepository.findById(formId)).thenReturn(Optional.of(f));
        manager.suspendForm(formId, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient, org.mockito.Mockito.times(4)).emit(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(r -> r.getPayload().get("triggerKind"))
                .containsExactlyInAnyOrder("schedule", "webhook", "chat", "form");
    }

    @Test
    @DisplayName("NotificationClient unwired (null) → suspend still works, no NPE")
    void unwiredClientIsNoOp() throws Exception {
        TriggerLifecycleManager bareManager = new TriggerLifecycleManager(
                scheduleRepository, webhookRepository, chatRepository, formRepository, tokenRepository);
        // notificationClient field stays null - no reflection set.
        UUID id = UUID.randomUUID();
        when(webhookRepository.findById(id)).thenReturn(Optional.of(webhook(id)));

        // Must not throw
        bareManager.suspendWebhook(id, TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);
    }
}
