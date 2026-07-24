package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochFailedEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

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
 * Unit tests for {@link NotificationEmitter}. Each test exercises one filter
 * branch or one failure mode of the AFTER_COMMIT pipeline. The native
 * INSERT...RETURNING is mocked; database-level idempotency is exercised in
 * integration tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEmitter")
class NotificationEmitterTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private MeterRegistry meterRegistry;
    private NotificationEmitter emitter;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Integer PINNED_VERSION = 7;
    private static final String TENANT_ID = "tenant-1";
    private static final String RUN_PUBLIC = "run_pub_abc";

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emitter = new NotificationEmitter(workflowRepository, workflowRunRepository,
                redisPublisher, meterRegistry);
        Field emField = NotificationEmitter.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(emitter, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
    }

    private WorkflowEntity workflowPinned(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Pinned WF");
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowRunEntity runWithMetadata(Map<String, Object> metadata) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(TENANT_ID);
        run.setRunIdPublic(RUN_PUBLIC);
        run.setStatus(RunStatus.FAILED);
        run.setEndedAt(Instant.now());
        run.setMetadata(metadata);
        return run;
    }

    private WorkflowRunTerminatedEvent event(RunStatus status, Integer planVersion) {
        return new WorkflowRunTerminatedEvent(RUN_ID, WORKFLOW_ID, status, planVersion);
    }

    private WorkflowEpochFailedEvent epochEvent(int epoch, Integer planVersion) {
        return new WorkflowEpochFailedEvent(
                RUN_ID, WORKFLOW_ID, epoch, planVersion,
                TENANT_ID, RUN_PUBLIC, Instant.now());
    }

    @Test
    @DisplayName("Pinned-version match + FAILED status + clean metadata → INSERT and Redis publish")
    void pinnedFailedFiresAndPublishes() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L)); // RETURNING id

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("CANCELLED and TIMEOUT also fire (RunStatus.isFailure semantics)")
    void cancelledTimeoutFire() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onRunTerminated(event(RunStatus.CANCELLED, PINNED_VERSION));
        emitter.onRunTerminated(event(RunStatus.TIMEOUT, PINNED_VERSION));

        verify(redisPublisher, org.mockito.Mockito.times(2))
                .publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("PARTIAL_SUCCESS does NOT fire (V1 documented exclusion)")
    void partialSuccessDoesNotFire() {
        emitter.onRunTerminated(event(RunStatus.PARTIAL_SUCCESS, PINNED_VERSION));
        verifyNoInteractions(workflowRepository, workflowRunRepository, redisPublisher, entityManager);
    }

    @Test
    @DisplayName("SKIPPED does NOT fire (terminal but not failure)")
    void skippedDoesNotFire() {
        emitter.onRunTerminated(event(RunStatus.SKIPPED, PINNED_VERSION));
        verifyNoInteractions(workflowRepository, workflowRunRepository, redisPublisher, entityManager);
    }

    @Test
    @DisplayName("COMPLETED does NOT fire")
    void completedDoesNotFire() {
        emitter.onRunTerminated(event(RunStatus.COMPLETED, PINNED_VERSION));
        verifyNoInteractions(workflowRepository, workflowRunRepository, redisPublisher, entityManager);
    }

    @Test
    @DisplayName("Unpinned workflow (pinnedVersion=null) → no notification")
    void unpinnedWorkflowDoesNotFire() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(null)));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Pinned-version mismatch (run is on a non-production version) → no notification")
    void pinnedVersionMismatchDoesNotFire() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(99)));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Editor run (__editorRun__=true) is excluded - set by EditorRunResolver after recordWorkflowStart")
    void editorRunMetadataExcluded() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", Boolean.TRUE);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("REGRESSION: pin-promoted editor run (production_run_id == run id) DOES fire RUN_FAILED - "
            + "the pin never strips __editorRun__, so the flag alone must not silence the production run")
    void editorRunPromotedToProductionFires() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", Boolean.TRUE);
        WorkflowRunEntity run = runWithMetadata(meta);
        Field idField = WorkflowRunEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(run, RUN_ID);
        WorkflowEntity wf = workflowPinned(PINNED_VERSION);
        wf.setProductionRunId(RUN_ID);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("Regression 2026-07-21: production run carrying __agentInitiated__ (or __versionReplay__) DOES fire - "
            + "production identity trumps EVERY metadata flag, not just __editorRun__")
    void agentInitiatedProductionRunFires() throws Exception {
        // Promotion never strips run metadata, so an agent-created run that got
        // pinned carries __agentInitiated__ forever. Pre-fix that flag was checked
        // BEFORE the production carve-out: the workflow failed nightly and the user
        // never heard of it.
        Map<String, Object> meta = new HashMap<>();
        meta.put("__agentInitiated__", Boolean.TRUE);
        meta.put("__versionReplay__", 3);
        WorkflowRunEntity run = runWithMetadata(meta);
        Field idField = WorkflowRunEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(run, RUN_ID);
        WorkflowEntity wf = workflowPinned(PINNED_VERSION);
        wf.setProductionRunId(RUN_ID);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(nativeQuery.getResultList()).thenReturn(List.of(42L));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("Editor run whose id DIFFERS from production_run_id stays excluded on run-terminal")
    void editorRunDifferentFromProductionRunStaysExcluded() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", Boolean.TRUE);
        WorkflowRunEntity run = runWithMetadata(meta);
        Field idField = WorkflowRunEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(run, RUN_ID);
        WorkflowEntity wf = workflowPinned(PINNED_VERSION);
        wf.setProductionRunId(UUID.fromString("00000000-0000-0000-0000-00000000dead"));

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Epoch-failed: pin-promoted editor run DOES fire (filter parity with run-terminal)")
    void epochFailedEditorRunPromotedToProductionFires() throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", Boolean.TRUE);
        WorkflowRunEntity run = runWithMetadata(meta);
        Field idField = WorkflowRunEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(run, RUN_ID);
        WorkflowEntity wf = workflowPinned(PINNED_VERSION);
        wf.setProductionRunId(RUN_ID);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onEpochFailed(epochEvent(2, PINNED_VERSION));

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("Version-replay key existence excludes - works when value is Integer (not boolean)")
    void versionReplayKeyExistenceExcludesIntegerValue() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__versionReplay__", 5); // Integer planVersion, not boolean - verifies key-existence check

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Agent-initiated run (__agentInitiated__=true) is excluded - guards future regression")
    void agentInitiatedExcluded() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__agentInitiated__", Boolean.TRUE);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Showcase run (run_id_public starts with 'showcase_') is excluded")
    void showcaseRunExcluded() {
        WorkflowRunEntity run = runWithMetadata(new HashMap<>());
        run.setRunIdPublic("showcase_demo_1");

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Run deleted between commit and listener fire - graceful no-op (no notification, no NPE)")
    void runDeletedBetweenCommitAndListener() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("ON CONFLICT path (RETURNING empty) does NOT publish - race-safe multi-replica")
    void redisPublishOnlyAfterInsertReturningId() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of()); // ON CONFLICT DO NOTHING

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("DataAccessException is caught + counted; never propagates")
    void narrowCatchSwallowsDataAccess() {
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenThrow(new DataAccessResourceFailureException("DB down"));

        // Must not throw - the originating tx has already committed.
        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        Counter c = meterRegistry.find("notification.emitter.errors").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("IllegalStateException propagates - narrow catch must not mask programmer errors")
    void illegalStateExceptionPropagatesNotSwallowed() {
        // Regression for prior audit: catch was `DataAccessException |
        // PersistenceException | IllegalStateException` which masked NPE-class
        // bugs. Catch is now narrowed; IllegalStateException must surface.
        when(workflowRepository.findById(WORKFLOW_ID))
                .thenThrow(new IllegalStateException("EntityManager closed"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION)));
    }

    @Test
    @DisplayName("ENDED_AT_FORMATTER produces fixed millisecond precision matching V172 backfill")
    void endedAtFormatterMatchesBackfillShape() throws Exception {
        // Pin the wire shape against ISO_INSTANT regression. Whole-second
        // Instants must emit `.000Z` (ISO_INSTANT would omit the fraction);
        // sub-second Instants emit truncated 3-digit `.SSS` regardless of nanos.
        java.lang.reflect.Field f = NotificationEmitter.class
                .getDeclaredField("ENDED_AT_FORMATTER");
        f.setAccessible(true);
        java.time.format.DateTimeFormatter formatter =
                (java.time.format.DateTimeFormatter) f.get(null);

        assertThat(formatter.format(java.time.Instant.parse("2026-05-08T12:34:56Z")))
                .isEqualTo("2026-05-08T12:34:56.000Z");
        assertThat(formatter.format(java.time.Instant.parse("2026-05-08T12:34:56.789Z")))
                .isEqualTo("2026-05-08T12:34:56.789Z");
    }

    @Test
    @DisplayName("payload JSON always carries the 'runIdPublic' key - null when unset, string when set - for V172 backfill parity")
    void payloadAlwaysCarriesRunIdPublicKey() {
        // Regression for prior audit: emitter previously omitted the key when
        // null, while V172 backfill always writes it (jsonb_build_object stores
        // SQL NULL as JSON null). Without this guard the cross-layer payload
        // shape would diverge between historical and forward-emitted rows.
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));

        // Case 1: runIdPublic present → key + string value.
        WorkflowRunEntity runWithPublic = runWithMetadata(new HashMap<>());
        runWithPublic.setRunIdPublic("run_pub_xyz");
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithPublic));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        // V220 - payload param shifted from 10 → 11 (organization_id at index 2).
        org.mockito.ArgumentCaptor<Object> jsonCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery).setParameter(org.mockito.ArgumentMatchers.eq(11), jsonCaptor.capture());
        String json = (String) jsonCaptor.getValue();
        assertThat(json).contains("\"runIdPublic\":\"run_pub_xyz\"");

        // Case 2: runIdPublic null → key still present, value is JSON null.
        org.mockito.Mockito.reset(nativeQuery);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn(List.of(2L));
        WorkflowRunEntity runWithoutPublic = runWithMetadata(new HashMap<>());
        runWithoutPublic.setRunIdPublic(null);
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithoutPublic));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        org.mockito.ArgumentCaptor<Object> jsonCaptor2 = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery).setParameter(org.mockito.ArgumentMatchers.eq(11), jsonCaptor2.capture());
        String jsonNull = (String) jsonCaptor2.getValue();
        assertThat(jsonNull).contains("\"runIdPublic\":null");
    }

    // -------------------------- Epoch-failed (P0) --------------------------

    @Test
    @DisplayName("Epoch-failed event with pinned match writes one row + publishes - closes the reusable-run silent gap")
    void epochFailedFiresAndPublishes() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(123L));

        emitter.onEpochFailed(epochEvent(3, PINNED_VERSION));

        verify(redisPublisher).publishNotification(eq(TENANT_ID), eq("notification.created"), any());
    }

    @Test
    @DisplayName("Epoch-failed source_id = runId + ':' + epoch - different epochs of same run produce distinct rows")
    void epochFailedSourceIdIncludesEpoch() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onEpochFailed(epochEvent(7, PINNED_VERSION));

        // V220 - source_id shifted from index 6 → 7 (organization_id at index 2).
        org.mockito.ArgumentCaptor<Object> sourceCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery).setParameter(eq(7), sourceCaptor.capture());
        assertThat(sourceCaptor.getValue()).isEqualTo(RUN_ID + ":7");
    }

    @Test
    @DisplayName("Epoch-failed payload carries 'epoch' alongside status/endedAt/runIdPublic")
    void epochFailedPayloadIncludesEpoch() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onEpochFailed(epochEvent(12, PINNED_VERSION));

        // V220 - payload jsonb shifted from index 10 → 11 (organization_id at 2).
        org.mockito.ArgumentCaptor<Object> payloadCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(nativeQuery).setParameter(eq(11), payloadCaptor.capture());
        String json = (String) payloadCaptor.getValue();
        assertThat(json).contains("\"epoch\":12");
        assertThat(json).contains("\"status\":\"failed\"");
        assertThat(json).contains("\"runIdPublic\":\"" + RUN_PUBLIC + "\"");
    }

    @Test
    @DisplayName("Epoch-failed unpinned workflow → no notification (same filter as run-terminal path)")
    void epochFailedUnpinnedWorkflowDoesNotFire() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(null)));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Epoch-failed editor-run metadata exclusion (filter parity with run-terminal)")
    void epochFailedEditorRunExcluded() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__editorRun__", Boolean.TRUE);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Epoch-failed pinned-version mismatch → no notification (filter parity with run-terminal)")
    void epochFailedPinnedVersionMismatchDoesNotFire() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(99)));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Epoch-failed __versionReplay__ key existence excludes - Integer value not boolean (V172 cast-bug regression)")
    void epochFailedVersionReplayKeyExistenceExcludes() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__versionReplay__", 5); // Integer planVersion, not boolean

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Epoch-failed __agentInitiated__=true excluded - guards future regression")
    void epochFailedAgentInitiatedExcluded() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("__agentInitiated__", Boolean.TRUE);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(meta)));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Epoch-failed showcase run (run_id_public starts with 'showcase_') excluded")
    void epochFailedShowcaseRunExcluded() {
        WorkflowRunEntity run = runWithMetadata(new HashMap<>());
        run.setRunIdPublic("showcase_demo_1");

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("Epoch-failed run deleted between commit and listener fire → graceful no-op")
    void epochFailedRunDeletedBetweenCommitAndListener() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        emitter.onEpochFailed(epochEvent(1, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Epoch-failed ON CONFLICT path (same epoch retried) does NOT publish - multi-replica race-safe")
    void epochFailedConflictDoesNotPublish() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithMetadata(new HashMap<>())));
        when(nativeQuery.getResultList()).thenReturn(List.of()); // ON CONFLICT DO NOTHING

        emitter.onEpochFailed(epochEvent(2, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
    }

    @Test
    @DisplayName("null tenantId on run row is treated as no-op (defensive)")
    void nullTenantIdNoOp() {
        WorkflowRunEntity run = runWithMetadata(new HashMap<>());
        run.setTenantId(null);

        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflowPinned(PINNED_VERSION)));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        emitter.onRunTerminated(event(RunStatus.FAILED, PINNED_VERSION));

        verifyNoInteractions(redisPublisher);
        verify(entityManager, never()).createNativeQuery(anyString());
    }
}
