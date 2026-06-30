package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResolvedEvent;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the P2a APPROVAL_PENDING listeners on {@link NotificationEmitter}:
 * insert via {@link WorkflowApprovalPendingEvent}, delete via
 * {@link SignalResolvedEvent} (USER_APPROVAL only), bulk-delete via
 * {@link SignalsCancelledEvent} (cancel paths bypassing SignalResolvedEvent).
 *
 * <p>Mocks both {@code createNativeQuery} (insert path) and
 * {@code createQuery} (JPQL delete path) so unit tests don't need a real DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEmitter - P2a APPROVAL_PENDING")
class NotificationEmitterApprovalPendingTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;
    @Mock private Query jpqlQuery;

    private MeterRegistry meterRegistry;
    private NotificationEmitter emitter;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Integer PINNED_VERSION = 7;
    private static final String TENANT_ID = "tenant-1";
    private static final String RUN_PUBLIC = "run_pub_abc";
    private static final long SIGNAL_ID = 4242L;
    private static final int EPOCH = 3;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emitter = new NotificationEmitter(workflowRepository, workflowRunRepository,
                redisPublisher, meterRegistry);
        Field emField = NotificationEmitter.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(emitter, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(entityManager.createQuery(anyString())).thenReturn(jpqlQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        lenient().when(jpqlQuery.setParameter(anyInt(), any())).thenReturn(jpqlQuery);
    }

    private WorkflowEntity workflowPinned(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Pinned WF");
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowRunEntity runWithMetadata(Map<String, Object> metadata, Integer planVersion) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        // id is JPA-generated and has no public setter - reflection set it for the test.
        try {
            Field idField = WorkflowRunEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(run, RUN_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        run.setTenantId(TENANT_ID);
        // V261 - deleteApprovalPendingByIds requires a non-null orgId on the
        // run; otherwise the listener no-ops and the test verifies no DB call.
        run.setOrganizationId(TENANT_ID);
        run.setRunIdPublic(RUN_PUBLIC);
        run.setPlanVersion(planVersion);
        run.setMetadata(metadata);
        // simulate the @ManyToOne workflow load - emitter accesses run.getWorkflow().getId()
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        run.setWorkflow(wf);
        return run;
    }

    private WorkflowApprovalPendingEvent event(Instant expiresAt) {
        return new WorkflowApprovalPendingEvent(
                RUN_PUBLIC, SIGNAL_ID, EPOCH,
                Instant.parse("2026-05-08T10:00:00Z"),  // createdAt
                expiresAt);
    }

    // ========================================================================
    // INSERT path - onApprovalPending
    // ========================================================================

    @Test
    @DisplayName("USER_APPROVAL on pinned production run → INSERT + Redis publish")
    void emitsForUserApprovalOnPinnedProductionRun() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        emitter.onApprovalPending(event(Instant.parse("2026-05-09T10:00:00Z")));

        verify(redisPublisher).publishNotification(eq(TENANT_ID),
                eq("notification.created"), any());
    }

    @Test
    @DisplayName("Pinned-version mismatch → no INSERT, no publish")
    void skipsForPinnedVersionMismatch() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), 999)));  // wrong version
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Unpinned workflow → no INSERT")
    void skipsForUnpinnedWorkflow() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(null)));  // unpinned

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Editor run metadata → skip")
    void skipsForEditorRun() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", true);
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(meta, PINNED_VERSION)));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Version replay metadata → skip (Integer or any value)")
    void skipsForVersionReplay() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__versionReplay__", 5);   // Integer value, key presence is what matters
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(meta, PINNED_VERSION)));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Agent-initiated metadata → skip")
    void skipsForAgentInitiated() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__agentInitiated__", true);
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(meta, PINNED_VERSION)));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Showcase run prefix → skip")
    void skipsForShowcaseRun() {
        WorkflowRunEntity run = runWithMetadata(new HashMap<>(), PINNED_VERSION);
        run.setRunIdPublic("showcase_demo_123");
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(run));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Run not found in DB → silent exit")
    void skipsWhenRunNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.empty());

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Tenant ID null → silent exit")
    void skipsWhenTenantNull() {
        WorkflowRunEntity run = runWithMetadata(new HashMap<>(), PINNED_VERSION);
        run.setTenantId(null);
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(run));

        emitter.onApprovalPending(event(null));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("ON CONFLICT empty result → no Redis publish (idempotent retry)")
    void idempotentRetryDoesNotDoubleEmit() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of()); // ON CONFLICT skipped - no row inserted

        emitter.onApprovalPending(event(null));

        // INSERT was attempted but ON CONFLICT no-opped - Redis MUST NOT publish
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Payload shape: status='pending' + expiresAt + runIdPublic; status is V174's only required key")
    void payloadShapeMatchesV174Contract() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onApprovalPending(event(Instant.parse("2026-05-09T10:00:00Z")));

        // V220 INSERT shape: organization_id inserted at parameter index 2;
        // the payload jsonb cast that was previously param 10 is now param 11.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery).setParameter(eq(11), captor.capture());
        String payload = (String) captor.getValue();
        assertThat(payload).contains("\"status\":\"pending\"");
        assertThat(payload).contains("\"expiresAt\":\"2026-05-09T10:00:00.000Z\"");
        assertThat(payload).contains("\"runIdPublic\":\"" + RUN_PUBLIC + "\"");
        assertThat(payload).doesNotContain("\"endedAt\"");  // V174 dropped this requirement
    }

    @Test
    @DisplayName("Payload accepts null expiresAt - emits literal JSON null")
    void payloadAcceptsNullExpiresAt() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onApprovalPending(event(null));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        // V220 - payload is now at index 11 (organization_id consumes index 2).
        verify(nativeQuery).setParameter(eq(11), captor.capture());
        String payload = (String) captor.getValue();
        assertThat(payload).contains("\"expiresAt\":null");
    }

    @Test
    @DisplayName("Severity is 'info' (not 'error') - APPROVAL_PENDING is action-required, not failure")
    void severityIsInfo() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onApprovalPending(event(null));

        // V220 - column shift by 1 (organization_id at index 2):
        // category moved to index 3, severity to index 4.
        verify(nativeQuery).setParameter(eq(4), eq("info"));
        verify(nativeQuery).setParameter(eq(3), eq("APPROVAL_PENDING"));
    }

    @Test
    @DisplayName("Source ID is signalWaitId stringified - stable across re-traversal")
    void sourceIdIsSignalWaitIdStringified() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onApprovalPending(event(null));

        // V220 - source_id moved from index 6 → 7 (column shift by 1).
        verify(nativeQuery).setParameter(eq(7), eq(String.valueOf(SIGNAL_ID)));
    }

    @Test
    @DisplayName("Redis publish failure is counted + swallowed; row stays in DB")
    void swallowsRedisPublishFailure() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("boom"))
                .when(redisPublisher).publishNotification(anyString(), anyString(), any());

        emitter.onApprovalPending(event(null));

        assertThat(meterRegistry.counter("notification.emitter.errors",
                "type", "RedisPublish").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DB exception is counted + swallowed")
    void swallowsDataAccessFailure() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenThrow(new DataAccessResourceFailureException("DB down"));

        emitter.onApprovalPending(event(null));

        assertThat(meterRegistry.counter("notification.emitter.errors",
                "type", "DataAccessResourceFailureException").count()).isEqualTo(1.0);
    }

    // ========================================================================
    // DELETE on resolve - onSignalResolved
    // ========================================================================

    private SignalWaitEntity userApprovalSignal() {
        SignalWaitEntity s = new SignalWaitEntity();
        s.setId(SIGNAL_ID);
        s.setRunId(RUN_PUBLIC);
        s.setSignalType(SignalType.USER_APPROVAL);
        return s;
    }

    @Test
    @DisplayName("USER_APPROVAL resolve → DELETE matching APPROVAL_PENDING + notification.removed WS")
    void deletesApprovalPendingOnUserApprovalResolve() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(jpqlQuery.executeUpdate()).thenReturn(1);

        emitter.onSignalResolved(new SignalResolvedEvent(this, userApprovalSignal()));

        verify(jpqlQuery).executeUpdate();
        verify(redisPublisher).publishNotification(eq(TENANT_ID),
                eq("notification.removed"), any());
    }

    @Test
    @DisplayName("Non-USER_APPROVAL resolve (TIMER, INTERFACE, WEBHOOK_WAIT, AGENT_EXECUTION) → no-op")
    void doesNotDeleteForNonUserApprovalSignal() {
        for (SignalType type : List.of(SignalType.WAIT_TIMER, SignalType.INTERFACE_SIGNAL,
                SignalType.WEBHOOK_WAIT, SignalType.AGENT_EXECUTION)) {
            SignalWaitEntity s = new SignalWaitEntity();
            s.setId(SIGNAL_ID);
            s.setRunId(RUN_PUBLIC);
            s.setSignalType(type);

            emitter.onSignalResolved(new SignalResolvedEvent(this, s));
        }
        verify(entityManager, never()).createQuery(anyString());
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Resolve with run not found → no-op (race: run deleted between commit and listener)")
    void resolveSkipsWhenRunNotFound() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.empty());

        emitter.onSignalResolved(new SignalResolvedEvent(this, userApprovalSignal()));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("DELETE returning 0 rows → no Redis publish (multi-replica race loser)")
    void resolveDoesNotPublishOnZeroDeletedRows() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(jpqlQuery.executeUpdate()).thenReturn(0);

        emitter.onSignalResolved(new SignalResolvedEvent(this, userApprovalSignal()));

        verifyNoInteractions(redisPublisher);
    }

    // ========================================================================
    // BULK DELETE on cancel - onSignalsCancelled
    // ========================================================================

    @Test
    @DisplayName("SignalsCancelledEvent with non-empty list → bulk DELETE + Redis publish")
    void bulkDeletesOnSignalsCancelled() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(jpqlQuery.executeUpdate()).thenReturn(3);

        emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_PUBLIC, List.of(1L, 2L, 3L)));

        ArgumentCaptor<Object> idsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jpqlQuery).setParameter(eq(3), idsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) idsCaptor.getValue();
        assertThat(ids).containsExactly("1", "2", "3");
        verify(redisPublisher).publishNotification(eq(TENANT_ID),
                eq("notification.removed"), any());
    }

    @Test
    @DisplayName("Empty id list → no DB call, no Redis publish")
    void cancelledEmptyListNoOp() {
        emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_PUBLIC, List.of()));

        verify(entityManager, never()).createQuery(anyString());
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Cancel with run not found → no-op")
    void cancelledRunNotFoundNoOp() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC)).thenReturn(Optional.empty());

        emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_PUBLIC, List.of(1L)));

        verify(entityManager, never()).createQuery(anyString());
        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Cancel: DELETE returning 0 rows → no Redis publish")
    void cancelledZeroDeletedRowsNoPublish() {
        when(workflowRunRepository.findByRunIdPublic(RUN_PUBLIC))
                .thenReturn(Optional.of(runWithMetadata(new HashMap<>(), PINNED_VERSION)));
        when(jpqlQuery.executeUpdate()).thenReturn(0);

        emitter.onSignalsCancelled(new SignalsCancelledEvent(RUN_PUBLIC, List.of(1L)));

        verifyNoInteractions(redisPublisher);
    }
}
