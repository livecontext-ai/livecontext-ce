package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TriggerLifecycleManager} - the sole authority for trigger config-row
 * state transitions across the 5 trigger types.
 *
 * <p>The PR2 contract under test:
 * <ol>
 *   <li>Every {@code arm/suspend/archive} method correctly mutates the new {@code state}
 *       column AND the legacy {@code enabled}/{@code is_active} boolean.</li>
 *   <li>Reason text is persisted (or cleared on {@code arm}).</li>
 *   <li>The {@code last_disabled_at} timestamp is set on suspend/archive, cleared on arm.</li>
 *   <li>{@code WORKFLOW_UNPINNED} reason routes to {@code SUSPENDED_UNPINNED}; everything
 *       else routes to {@code SUSPENDED_NO_RUN}.</li>
 *   <li>Missing entity → no-op (no NPE).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerLifecycleManager - PR2 state transitions")
class TriggerLifecycleManagerTest {

    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatRepository;
    @Mock private StandaloneFormEndpointRepository formRepository;
    @Mock private WebhookTokenRepository tokenRepository;

    @InjectMocks
    private TriggerLifecycleManager manager;

    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final UUID WEBHOOK_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID FORM_ID = UUID.randomUUID();
    private static final Long TOKEN_ID = 42L;

    private ScheduledExecutionEntity newSchedule(TriggerState state, boolean enabled) {
        ScheduledExecutionEntity s = new ScheduledExecutionEntity();
        s.setId(SCHEDULE_ID);
        s.setState(state);
        s.setEnabled(enabled);
        s.setIsActive(enabled);
        return s;
    }

    private StandaloneWebhookEntity newWebhook(TriggerState state, boolean isActive) {
        StandaloneWebhookEntity w = new StandaloneWebhookEntity();
        w.setId(WEBHOOK_ID);
        w.setState(state);
        w.setIsActive(isActive);
        return w;
    }

    private StandaloneChatEndpointEntity newChat(TriggerState state, boolean isActive) {
        StandaloneChatEndpointEntity c = new StandaloneChatEndpointEntity();
        c.setId(CHAT_ID);
        c.setState(state);
        c.setIsActive(isActive);
        return c;
    }

    private StandaloneFormEndpointEntity newForm(TriggerState state, boolean isActive) {
        StandaloneFormEndpointEntity f = new StandaloneFormEndpointEntity();
        f.setId(FORM_ID);
        f.setState(state);
        f.setIsActive(isActive);
        return f;
    }

    private WebhookTokenEntity newToken(TriggerState state) {
        WebhookTokenEntity t = new WebhookTokenEntity();
        t.setId(TOKEN_ID);
        t.setState(state);
        return t;
    }

    // ============================================================================
    // Schedule
    // ============================================================================

    @Nested
    @DisplayName("Schedule transitions")
    class ScheduleTests {

        @Test
        @DisplayName("arm: SUSPENDED_NO_RUN → ACTIVE clears reason + sets enabled/isActive=true + returns true")
        void armSchedule() {
            // RC2 (audit round 4): the prior version of this test exercised
            // ARCHIVED → ACTIVE, which is now refused by the source-state guard
            // (ARCHIVED is documented as permanent, manual recovery only). The
            // canonical recoverable transition is SUSPENDED_NO_RUN → ACTIVE.
            ScheduledExecutionEntity s = newSchedule(TriggerState.SUSPENDED_NO_RUN, false);
            s.setLastDisabledReason("NO_PRODUCTION_RUN");
            s.setLastDisabledAt(java.time.Instant.now());
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            boolean armed = manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.PIN);

            assertThat(armed).isTrue();
            assertThat(s.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(s.getLastDisabledReason()).isNull();
            assertThat(s.getLastDisabledAt()).isNull();
            assertThat(s.isEnabled()).isTrue();
            assertThat(s.getIsActive()).isTrue();
            verify(scheduleRepository).save(s);
        }

        @Test
        @DisplayName("arm: ARCHIVED → REFUSED (returns false, no save, no transition)")
        void armRefusesArchived() {
            // RC2 root-cause fix: ARCHIVED is documented as permanent. A row
            // archived for MAX_EXEC_REACHED would otherwise be silently
            // re-armed by any reactivate, only to skip dispatch (because
            // executionCount >= maxExecutions still holds). Refusing the
            // transition keeps the count semantics honest for callers and
            // surfaces the situation via WARN log.
            ScheduledExecutionEntity s = newSchedule(TriggerState.ARCHIVED, false);
            s.setIsActive(false);
            s.setLastDisabledReason(TriggerLifecycleManager.Reason.MAX_EXEC_REACHED);
            s.setLastDisabledAt(java.time.Instant.now());
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            boolean armed = manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.SYNC);

            assertThat(armed).isFalse();
            // State, enabled, isActive all preserved - refusal is a no-op.
            assertThat(s.getState()).isEqualTo(TriggerState.ARCHIVED);
            assertThat(s.isEnabled()).isFalse();
            assertThat(s.getIsActive()).isFalse();
            assertThat(s.getLastDisabledReason()).isEqualTo(TriggerLifecycleManager.Reason.MAX_EXEC_REACHED);
            verify(scheduleRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("suspend NO_PRODUCTION_RUN: ACTIVE → SUSPENDED_NO_RUN, enabled=false")
        void suspendScheduleNoRun() {
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.suspendSchedule(SCHEDULE_ID,
                TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN,
                TriggerLifecycleManager.Source.SYNC);

            assertThat(s.getState()).isEqualTo(TriggerState.SUSPENDED_NO_RUN);
            assertThat(s.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN);
            assertThat(s.getLastDisabledAt()).isNotNull();
            assertThat(s.isEnabled()).isFalse();
            verify(scheduleRepository).save(s);
        }

        @Test
        @DisplayName("suspend WORKFLOW_UNPINNED: routes to SUSPENDED_UNPINNED, not SUSPENDED_NO_RUN")
        void suspendScheduleUnpinned() {
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.suspendSchedule(SCHEDULE_ID,
                TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED,
                TriggerLifecycleManager.Source.PIN);

            assertThat(s.getState()).isEqualTo(TriggerState.SUSPENDED_UNPINNED);
            assertThat(s.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED);
        }

        @Test
        @DisplayName("archive: ACTIVE → ARCHIVED, enabled=false AND isActive=false")
        void archiveSchedule() {
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.archiveSchedule(SCHEDULE_ID,
                TriggerLifecycleManager.Reason.MAX_EXEC_REACHED,
                TriggerLifecycleManager.Source.QUOTA);

            assertThat(s.getState()).isEqualTo(TriggerState.ARCHIVED);
            assertThat(s.isEnabled()).isFalse();
            assertThat(s.getIsActive()).isFalse();
            assertThat(s.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.MAX_EXEC_REACHED);
        }

        @Test
        @DisplayName("Missing schedule → no-op (no save, no exception)")
        void missingScheduleIsNoop() {
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.empty());

            manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.PIN);

            verify(scheduleRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("arm: legacy drift state=ACTIVE+enabled=true+isActive=false → guard falls through, heals isActive")
        void armHealsLegacyIsActiveDrift() {
            // Round-2 audit (audit #1/#2/#3): the guard MUST consider all
            // three booleans, not just (state, enabled). Pre-PR2 rows or
            // any code path that touches `enabled` without `isActive` can
            // produce drift like (ACTIVE, true, false). If the guard
            // skipped on (state, enabled) alone, the drift would be silently
            // preserved through every arm - and dispatch paths still
            // gating on `getIsActive()` would skip the row indefinitely.
            // The fix: include isActive in the guard so drift falls through
            // to the transition path, which calls setIsActive(true) and
            // saves.
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            s.setIsActive(false); // simulate drift

            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.SYNC);

            // The guard should NOT short-circuit - it sees the drift and
            // routes through the transition + save path.
            assertThat(s.getIsActive()).isTrue();
            assertThat(s.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(s.isEnabled()).isTrue();
            verify(scheduleRepository).save(s);
        }

        @Test
        @DisplayName("arm: already-ACTIVE+enabled+isActive row → idempotent no-op (no save, no log churn)")
        void armAlreadyActiveIsIdempotentNoop() {
            // Reactivate's helper now arms BEFORE and AFTER syncAllTriggersFromPinnedVersion,
            // and the inner ScheduleSyncService.syncFromPinnedVersion arms a third time.
            // Without an idempotency guard, every already-armed row received 3 redundant
            // ACTIVE→ACTIVE row writes + lifecycle log lines per reactivate. The guard
            // collapses those to zero while keeping meaningful transitions
            // (SUSPENDED_*→ACTIVE, ARCHIVED→ACTIVE) intact (covered by armSchedule above).
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.SYNC);

            // No save call → no row UPDATE, no JPA dirty-flush bump on updated_at,
            // no lifecycle log entry. Other observable state must stay untouched.
            verify(scheduleRepository, never()).save(org.mockito.ArgumentMatchers.any());
            assertThat(s.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(s.isEnabled()).isTrue();
        }
    }

    // ============================================================================
    // Webhook
    // ============================================================================

    @Nested
    @DisplayName("Webhook transitions")
    class WebhookTests {

        @Test
        @DisplayName("arm: SUSPENDED_NO_RUN → ACTIVE clears reason + sets isActive=true")
        void armWebhook() {
            StandaloneWebhookEntity w = newWebhook(TriggerState.SUSPENDED_NO_RUN, false);
            w.setLastDisabledReason(TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN);
            when(webhookRepository.findById(WEBHOOK_ID)).thenReturn(Optional.of(w));

            manager.armWebhook(WEBHOOK_ID, TriggerLifecycleManager.Source.PIN);

            assertThat(w.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(w.getLastDisabledReason()).isNull();
            assertThat(w.getIsActive()).isTrue();
            verify(webhookRepository).save(w);
        }

        @Test
        @DisplayName("archive: ACTIVE → ARCHIVED, isActive=false, reason persisted")
        void archiveWebhook() {
            StandaloneWebhookEntity w = newWebhook(TriggerState.ACTIVE, true);
            when(webhookRepository.findById(WEBHOOK_ID)).thenReturn(Optional.of(w));

            manager.archiveWebhook(WEBHOOK_ID,
                TriggerLifecycleManager.Reason.WORKFLOW_DELETED,
                TriggerLifecycleManager.Source.DELETION);

            assertThat(w.getState()).isEqualTo(TriggerState.ARCHIVED);
            assertThat(w.getIsActive()).isFalse();
            assertThat(w.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.WORKFLOW_DELETED);
        }
    }

    // ============================================================================
    // Chat / Form / Token - smoke tests (logic identical to schedule/webhook above)
    // ============================================================================

    @Nested
    @DisplayName("Chat/Form/Token smoke tests")
    class OtherTypesTests {

        @Test
        @DisplayName("Chat arm sets state=ACTIVE and isActive=true")
        void armChat() {
            StandaloneChatEndpointEntity c = newChat(TriggerState.ARCHIVED, false);
            when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(c));

            manager.armChat(CHAT_ID, TriggerLifecycleManager.Source.ADMIN);

            assertThat(c.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(c.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("Form suspend WORKFLOW_UNPINNED routes to SUSPENDED_UNPINNED")
        void suspendFormUnpinned() {
            StandaloneFormEndpointEntity f = newForm(TriggerState.ACTIVE, true);
            when(formRepository.findById(FORM_ID)).thenReturn(Optional.of(f));

            manager.suspendForm(FORM_ID,
                TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED,
                TriggerLifecycleManager.Source.PIN);

            assertThat(f.getState()).isEqualTo(TriggerState.SUSPENDED_UNPINNED);
            assertThat(f.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("Token archive sets state=ARCHIVED (no legacy boolean to sync)")
        void archiveToken() {
            WebhookTokenEntity t = newToken(TriggerState.ACTIVE);
            when(tokenRepository.findById(TOKEN_ID)).thenReturn(Optional.of(t));

            manager.archiveToken(TOKEN_ID,
                TriggerLifecycleManager.Reason.WORKFLOW_DELETED,
                TriggerLifecycleManager.Source.DELETION);

            assertThat(t.getState()).isEqualTo(TriggerState.ARCHIVED);
            assertThat(t.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.WORKFLOW_DELETED);
            assertThat(t.getLastDisabledAt()).isNotNull();
        }

        @Test
        @DisplayName("Token arm clears reason + last_disabled_at")
        void armTokenClearsReason() {
            WebhookTokenEntity t = newToken(TriggerState.SUSPENDED_NO_RUN);
            t.setLastDisabledReason("WAS_SUSPENDED");
            t.setLastDisabledAt(java.time.Instant.now());
            when(tokenRepository.findById(TOKEN_ID)).thenReturn(Optional.of(t));

            manager.armToken(TOKEN_ID, TriggerLifecycleManager.Source.PIN);

            assertThat(t.getState()).isEqualTo(TriggerState.ACTIVE);
            assertThat(t.getLastDisabledReason()).isNull();
            assertThat(t.getLastDisabledAt()).isNull();
        }
    }

    // ============================================================================
    // TriggerState predicate
    // ============================================================================

    @Nested
    @DisplayName("TriggerState.isDispatchable")
    class StatePredicateTests {

        @Test
        @DisplayName("Only ACTIVE is dispatchable")
        void onlyActiveIsDispatchable() {
            assertThat(TriggerState.ACTIVE.isDispatchable()).isTrue();
            assertThat(TriggerState.SUSPENDED_NO_RUN.isDispatchable()).isFalse();
            assertThat(TriggerState.SUSPENDED_UNPINNED.isDispatchable()).isFalse();
            assertThat(TriggerState.ARCHIVED.isDispatchable()).isFalse();
        }
    }

    @Nested
    @DisplayName("V169 audit-log wire (TriggerStateAuditLogRepository optional dep)")
    class AuditLogTests {

        @org.mockito.Mock
        private com.apimarketplace.trigger.repository.TriggerStateAuditLogRepository auditLogRepository;

        @org.junit.jupiter.api.BeforeEach
        void wireAuditRepo() throws Exception {
            org.mockito.MockitoAnnotations.openMocks(this);
            java.lang.reflect.Field f =
                    TriggerLifecycleManager.class.getDeclaredField("auditLogRepository");
            f.setAccessible(true);
            f.set(manager, auditLogRepository);
        }

        @Test
        @DisplayName("Arming a suspended schedule writes one audit row with from/to/source")
        void armSuspendedScheduleWritesAuditRow() {
            ScheduledExecutionEntity s = newSchedule(TriggerState.SUSPENDED_NO_RUN, false);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.PIN);

            org.mockito.ArgumentCaptor<com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity.class);
            verify(auditLogRepository).save(captor.capture());
            com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity row = captor.getValue();
            assertThat(row.getFromState()).isEqualTo("SUSPENDED_NO_RUN");
            assertThat(row.getToState()).isEqualTo("ACTIVE");
            assertThat(row.getTriggerType()).isEqualTo("schedule");
            assertThat(row.getTriggerId()).isEqualTo(SCHEDULE_ID.toString());
            assertThat(row.getSource()).isEqualTo("PIN");
        }

        @Test
        @DisplayName("Self-loop arm (already ACTIVE) writes NO audit row")
        void selfLoopArmWritesNoAuditRow() {
            // Idempotency guard: ACTIVE+enabled+isActive returns true without
            // calling transitionSchedule, so log() never fires.
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));

            manager.armSchedule(SCHEDULE_ID, TriggerLifecycleManager.Source.SYNC);

            verify(auditLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Suspending a webhook writes audit row with reason populated")
        void suspendWebhookWritesAuditRowWithReason() {
            StandaloneWebhookEntity w = newWebhook(TriggerState.ACTIVE, true);
            when(webhookRepository.findById(WEBHOOK_ID)).thenReturn(Optional.of(w));

            manager.suspendWebhook(WEBHOOK_ID,
                    TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED,
                    TriggerLifecycleManager.Source.PIN);

            org.mockito.ArgumentCaptor<com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity.class);
            verify(auditLogRepository).save(captor.capture());
            com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity row = captor.getValue();
            assertThat(row.getFromState()).isEqualTo("ACTIVE");
            assertThat(row.getToState()).isEqualTo("SUSPENDED_UNPINNED");
            assertThat(row.getReason()).isEqualTo(TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED);
            assertThat(row.getTriggerType()).isEqualTo("webhook");
        }

        @Test
        @DisplayName("Audit-log persistence failure does NOT abort the lifecycle transition (fail-open)")
        void auditPersistenceFailureIsFailOpen() {
            ScheduledExecutionEntity s = newSchedule(TriggerState.ACTIVE, true);
            when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(s));
            when(auditLogRepository.save(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("audit_log table missing"));

            // The state mutation still completes - the row is saved to schedule
            // repository despite the audit-log save throwing.
            manager.suspendSchedule(SCHEDULE_ID,
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Source.ADMIN);

            verify(scheduleRepository).save(s);
            assertThat(s.getState()).isEqualTo(TriggerState.SUSPENDED_NO_RUN);
        }
    }

    @Nested
    @DisplayName("Reason.precedence + canReplace")
    class ReasonPrecedenceTests {

        @Test
        @DisplayName("Precedence values follow the documented bands")
        void precedenceValues() {
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.WORKFLOW_DELETED)).isEqualTo(100);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.TENANT_SUSPENDED)).isEqualTo(80);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.USER_DISABLED)).isEqualTo(70);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.TTL_EXPIRED)).isEqualTo(65);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.MAX_EXEC_REACHED)).isEqualTo(60);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED)).isEqualTo(50);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED)).isEqualTo(30);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN)).isEqualTo(20);
            assertThat(TriggerLifecycleManager.Reason.precedence(TriggerLifecycleManager.Reason.LEGACY_DISABLED)).isEqualTo(0);
        }

        @Test
        @DisplayName("Null and unknown reasons resolve to legacy precedence (0)")
        void unknownAndNullAreLegacy() {
            assertThat(TriggerLifecycleManager.Reason.precedence(null)).isZero();
            assertThat(TriggerLifecycleManager.Reason.precedence("NOT_A_REASON")).isZero();
            assertThat(TriggerLifecycleManager.Reason.precedence("")).isZero();
        }

        @Test
        @DisplayName("Stronger reason replaces weaker; equal precedence is allowed (idempotent)")
        void canReplaceFollowsPrecedence() {
            // Strong replaces weak
            assertThat(TriggerLifecycleManager.Reason.canReplace(
                    TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN,
                    TriggerLifecycleManager.Reason.USER_DISABLED)).isTrue();
            assertThat(TriggerLifecycleManager.Reason.canReplace(
                    TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED,
                    TriggerLifecycleManager.Reason.WORKFLOW_DELETED)).isTrue();
            // Weak does NOT replace strong
            assertThat(TriggerLifecycleManager.Reason.canReplace(
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN)).isFalse();
            assertThat(TriggerLifecycleManager.Reason.canReplace(
                    TriggerLifecycleManager.Reason.WORKFLOW_DELETED,
                    TriggerLifecycleManager.Reason.MAX_EXEC_REACHED)).isFalse();
            // Equal precedence allowed (e.g. re-applying same reason)
            assertThat(TriggerLifecycleManager.Reason.canReplace(
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Reason.USER_DISABLED)).isTrue();
        }

        @Test
        @DisplayName("Every Reason.* constant has an explicit precedence mapping (reflection-driven exhaustiveness)")
        void everyReasonConstantHasExplicitPrecedence() throws Exception {
            // Guard against the silent-default landmine: Reason.precedence is a
            // String switch with `default → 0`. Adding a new Reason.* constant
            // without updating the switch would compile, get precedence 0
            // (legacy), and quietly let any other reason clobber it.
            //
            // This test enumerates every public-static-final-String field on
            // Reason and asserts each is in the explicitly-mapped set. If a
            // new constant is added, this test FAILS - the developer must
            // either add a precedence band or explicitly accept "legacy 0"
            // by adding it to LEGACY_ALLOWLIST below.
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (java.lang.reflect.Field field : TriggerLifecycleManager.Reason.class.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)
                        && java.lang.reflect.Modifier.isFinal(mods)
                        && java.lang.reflect.Modifier.isPublic(mods)
                        && field.getType() == String.class) {
                    declared.add((String) field.get(null));
                }
            }
            // Constants that intentionally map to precedence 0 (the legacy
            // sentinel - explicit, NOT silent-default).
            java.util.Set<String> legacyAllowlist =
                    java.util.Set.of(TriggerLifecycleManager.Reason.LEGACY_DISABLED);
            for (String reason : declared) {
                int p = TriggerLifecycleManager.Reason.precedence(reason);
                if (legacyAllowlist.contains(reason)) {
                    assertThat(p).as("%s expected legacy precedence 0", reason).isZero();
                } else {
                    assertThat(p)
                            .as("%s has no explicit precedence mapping - adding a new Reason.* requires updating Reason.precedence()", reason)
                            .isPositive();
                }
            }
        }

        @Test
        @DisplayName("WORKFLOW_DELETED is terminal - nothing else can replace it")
        void workflowDeletedIsTerminal() {
            String[] allOthers = new String[] {
                    TriggerLifecycleManager.Reason.LEGACY_DISABLED,
                    TriggerLifecycleManager.Reason.USER_DISABLED,
                    TriggerLifecycleManager.Reason.NO_PRODUCTION_RUN,
                    TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED,
                    TriggerLifecycleManager.Reason.TTL_EXPIRED,
                    TriggerLifecycleManager.Reason.MAX_EXEC_REACHED,
                    TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED,
                    TriggerLifecycleManager.Reason.TENANT_SUSPENDED
            };
            for (String r : allOthers) {
                assertThat(TriggerLifecycleManager.Reason.canReplace(
                        TriggerLifecycleManager.Reason.WORKFLOW_DELETED, r))
                        .as("Replacing WORKFLOW_DELETED with %s must be refused", r)
                        .isFalse();
            }
        }
    }
}
