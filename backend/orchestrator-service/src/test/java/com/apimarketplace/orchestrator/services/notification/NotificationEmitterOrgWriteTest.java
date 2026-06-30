package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
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

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V220 regression: verifies the {@link NotificationEmitter}'s native INSERT
 * threads {@code organization_id} from the workflow run's {@code orgId} so
 * the bell's org-scope read predicate ({@code organization_id = :orgId})
 * surfaces the row to org teammates.
 *
 * <p>Pre-V220 the INSERT had 11 columns and tenant_id-only scope. The bug:
 * a teammate opening the org workspace saw zero bell rows for run failures
 * that happened in that workspace because the read filtered tenant_id =
 * userId and the emitter wrote run-owner's user-id, never the workspace-id.
 * This test pins the 12-column INSERT (organization_id at parameter index 2)
 * and the fact that it carries {@code WorkflowRunEntity.getOrgId()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEmitter - V220 organization_id INSERT contract")
class NotificationEmitterOrgWriteTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowRedisPublisher redisPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private MeterRegistry meterRegistry;
    private NotificationEmitter emitter;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000aa1");
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000aa2");
    private static final Integer PINNED_VERSION = 7;
    private static final String TENANT_ID = "tenant-org-write";
    private static final String RUN_PUBLIC = "run_pub_org_write";
    private static final String ORG_ID = "org-99";

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

    private WorkflowEntity pinnedWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("Pinned WF");
        wf.setPinnedVersion(PINNED_VERSION);
        return wf;
    }

    private WorkflowRunEntity runWithOrg(String orgId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(TENANT_ID);
        run.setRunIdPublic(RUN_PUBLIC);
        run.setStatus(RunStatus.FAILED);
        run.setEndedAt(Instant.now());
        run.setMetadata(new HashMap<>());
        run.setOrganizationId(orgId);
        return run;
    }

    @Test
    @DisplayName("Run with organization_id → INSERT binds orgId at parameter index 2 (workspace fan-out load-bearing)")
    void orgRunInsertBindsOrganizationId() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(pinnedWorkflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithOrg(ORG_ID)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onRunTerminated(new WorkflowRunTerminatedEvent(RUN_ID, WORKFLOW_ID, RunStatus.FAILED, PINNED_VERSION));

        // V220 INSERT shape: organization_id is the 2nd column → parameter index 2.
        // Capturing the SQL pins the column ordering so a future refactor that
        // re-orders the bind sites surfaces here (regression caught at unit-time,
        // not at first prod query).
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("organization_id")
                .contains("(tenant_id, organization_id, category");
        verify(nativeQuery).setParameter(1, TENANT_ID);
        verify(nativeQuery).setParameter(2, ORG_ID);
    }

    @Test
    @DisplayName("Personal-scope run (orgId=null) → INSERT binds null at index 2 so the read's IS NULL predicate matches")
    void personalRunInsertBindsNullOrgId() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(pinnedWorkflow()));
        when(workflowRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runWithOrg(null)));
        when(nativeQuery.getResultList()).thenReturn(List.of(1L));

        emitter.onRunTerminated(new WorkflowRunTerminatedEvent(RUN_ID, WORKFLOW_ID, RunStatus.FAILED, PINNED_VERSION));

        // Personal-scope contract: organization_id MUST be NULL in the DB so the
        // bell's personal-scope read (organization_id IS NULL AND tenant_id = …)
        // catches the row. Binding the empty string instead would silently break
        // personal-scope visibility.
        verify(nativeQuery).setParameter(2, (Object) null);
    }
}
