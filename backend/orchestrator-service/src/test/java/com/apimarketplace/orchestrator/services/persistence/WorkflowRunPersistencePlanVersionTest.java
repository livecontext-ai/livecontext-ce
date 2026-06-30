package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for plan version resolution in WorkflowRunPersistenceService.buildRunEntity().
 *
 * Verifies:
 * - When execution has a planVersion set (from auto-save), it is used directly
 * - When execution has no planVersion, falls back to getMaxVersion
 * - When getMaxVersion throws, planVersion remains null gracefully
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunPersistenceService - Plan Version Resolution")
class WorkflowRunPersistencePlanVersionTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ScheduleSyncService scheduleSyncService;

    @Mock
    private WorkflowPlanVersionRepository planVersionRepository;

    private WorkflowRunPersistenceService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new WorkflowRunPersistenceService(workflowRepository, workflowRunRepository, scheduleSyncService);
        // Inject @Autowired field via reflection
        ReflectionTestUtils.setField(service, "planVersionRepository", planVersionRepository);
    }

    private WorkflowEntity createWorkflowEntity() {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(WORKFLOW_ID);
        entity.setTenantId(TENANT_ID);
        entity.setName("Test Workflow");
        return entity;
    }

    private WorkflowExecution createExecution() {
        Map<String, Object> planData = new HashMap<>();
        planData.put("id", WORKFLOW_ID.toString());
        planData.put("name", "Test");
        planData.put("triggers", List.of(
                Map.of("id", "t1", "label", "Start", "type", "manual", "strategy", "single")
        ));
        planData.put("mcps", List.of(Map.of("id", "s1", "label", "Step")));
        planData.put("edges", List.of(Map.of("from", "trigger:start", "to", "mcp:step")));

        WorkflowPlan plan = WorkflowPlan.fromMap(planData);
        return new WorkflowExecution("run-1", plan, Map.of());
    }

    // =========================================================================
    // buildRunEntity - plan version from execution
    // =========================================================================

    @Nested
    @DisplayName("buildRunEntity plan version")
    class BuildRunEntityPlanVersionTests {

        @Test
        @DisplayName("Should use execution.planVersion when set (auto-save path)")
        void shouldUseExecutionPlanVersion() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();
            execution.setPlanVersion(5);

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now());

            assertThat(runEntity.getPlanVersion()).isEqualTo(5);
            // Should NOT call getMaxVersion since execution already has the version
            verify(planVersionRepository, never()).getMaxVersion(any());
        }

        @Test
        @DisplayName("Should fall back to getMaxVersion when execution.planVersion is null")
        void shouldFallbackToMaxVersion() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();
            // planVersion not set (null)

            when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.of(3));

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now());

            assertThat(runEntity.getPlanVersion()).isEqualTo(3);
            verify(planVersionRepository).getMaxVersion(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Should set null planVersion when no versions exist and execution has none")
        void shouldSetNullWhenNoVersionsExist() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();

            when(planVersionRepository.getMaxVersion(WORKFLOW_ID)).thenReturn(Optional.empty());

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now());

            assertThat(runEntity.getPlanVersion()).isNull();
        }

        @Test
        @DisplayName("Should handle exception from getMaxVersion gracefully")
        void shouldHandleMaxVersionException() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();

            when(planVersionRepository.getMaxVersion(WORKFLOW_ID))
                    .thenThrow(new RuntimeException("DB error"));

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now());

            // planVersion should be null (graceful failure)
            assertThat(runEntity.getPlanVersion()).isNull();
        }

        @Test
        @DisplayName("Should prefer execution planVersion over DB max version")
        void shouldPreferExecutionOverDb() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();
            execution.setPlanVersion(7);

            // Even if DB has version 3, execution's version 7 should win
            // (DB won't be called since execution has it)

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now());

            assertThat(runEntity.getPlanVersion()).isEqualTo(7);
            verify(planVersionRepository, never()).getMaxVersion(any());
        }

        @Test
        @DisplayName("Overload with userPlan should also respect execution planVersion")
        void overloadWithUserPlanShouldRespectPlanVersion() {
            WorkflowEntity entity = createWorkflowEntity();
            WorkflowExecution execution = createExecution();
            execution.setPlanVersion(4);

            WorkflowRunEntity runEntity = service.buildRunEntity(entity, execution, false, Instant.now(), "premium");

            assertThat(runEntity.getPlanVersion()).isEqualTo(4);
            // Also verify userPlan is stored in metadata
            assertThat(runEntity.getMetadata()).containsEntry("userPlan", "premium");
        }
    }
}
