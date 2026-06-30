package com.apimarketplace.orchestrator.execution.v2.cache;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecutionCacheManager.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCacheManager")
class ExecutionCacheManagerTest {

    @Mock private WorkflowResumeService mockResumeService;
    @Mock private ExecutionContextManager mockContextManager;
    @Mock private ExecutionTreeBuilder mockTreeBuilder;
    @Mock private WorkflowRunRepository mockRunRepository;
    @Mock private WorkflowExecution mockExecution;
    @Mock private WorkflowPlan mockPlan;
    @Mock private ExecutionTree mockTree;
    @Mock private WorkflowRunState mockState;

    private ExecutionCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ExecutionCacheManager(
            mockResumeService, mockContextManager, mockTreeBuilder, mockRunRepository
        );
    }

    @Nested
    @DisplayName("getExecution")
    class GetExecution {

        @Test
        @DisplayName("should return null when state not found")
        void shouldReturnNullWhenStateNotFound() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(null);

            assertNull(cacheManager.getExecution("run-1"));
        }

        @Test
        @DisplayName("should return execution when state found")
        void shouldReturnExecutionWhenFound() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);

            WorkflowExecution result = cacheManager.getExecution("run-1");
            assertSame(mockExecution, result);
        }

        @Test
        @DisplayName("should return null on exception")
        void shouldReturnNullOnException() {
            when(mockResumeService.reconstructState("run-1")).thenThrow(new RuntimeException("DB error"));

            assertNull(cacheManager.getExecution("run-1"));
        }
    }

    @Nested
    @DisplayName("hasExecution")
    class HasExecution {

        @Test
        @DisplayName("should return true when state exists")
        void shouldReturnTrueWhenExists() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            assertTrue(cacheManager.hasExecution("run-1"));
        }

        @Test
        @DisplayName("should return false when state is null")
        void shouldReturnFalseWhenNull() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(null);
            assertFalse(cacheManager.hasExecution("run-1"));
        }
    }

    @Nested
    @DisplayName("hasTree")
    class HasTree {

        @Test
        @DisplayName("should return true when execution exists")
        void shouldReturnTrueWhenExecutionExists() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);

            assertTrue(cacheManager.hasTree("run-1"));
        }

        @Test
        @DisplayName("should return false when no execution")
        void shouldReturnFalseWhenNoExecution() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(null);

            assertFalse(cacheManager.hasTree("run-1"));
        }
    }

    @Nested
    @DisplayName("ensureLoaded")
    class EnsureLoaded {

        @Test
        @DisplayName("should throw when execution not found")
        void shouldThrowWhenExecutionNotFound() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> cacheManager.ensureLoaded("run-1"));
        }

        @Test
        @DisplayName("should throw when plan is null")
        void shouldThrowWhenPlanIsNull() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> cacheManager.ensureLoaded("run-1"));
        }

        @Test
        @DisplayName("should not throw when both execution and plan exist")
        void shouldNotThrowWhenBothExist() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(mockPlan);

            assertDoesNotThrow(() -> cacheManager.ensureLoaded("run-1"));
        }
    }

    @Nested
    @DisplayName("loadTreeAndExecution")
    class LoadTreeAndExecution {

        @Test
        @DisplayName("should return null when execution not found")
        void shouldReturnNullWhenNoExecution() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(null);

            assertNull(cacheManager.loadTreeAndExecution("run-1"));
        }

        @Test
        @DisplayName("should return null when plan is null")
        void shouldReturnNullWhenNoPlan() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(null);

            assertNull(cacheManager.loadTreeAndExecution("run-1"));
        }

        @Test
        @DisplayName("should return LoadedExecution when all data available")
        void shouldReturnLoadedExecution() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a"));
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            when(mockPlan.getMcps()).thenReturn(List.of());
            when(mockPlan.getCores()).thenReturn(List.of());
            when(mockTreeBuilder.build(eq("run-1"), eq("00000000-0000-0000-0000-00000000002a"), eq("tenant-1"), eq(mockPlan), isNull(), isNull())).thenReturn(mockTree);
            when(mockTree.withExecutionMode(any())).thenReturn(mockTree);

            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            ExecutionCacheManager.LoadedExecution loaded = cacheManager.loadTreeAndExecution("run-1");

            assertNotNull(loaded);
            assertSame(mockTree, loaded.tree());
            assertSame(mockExecution, loaded.execution());
        }

        @Test
        @DisplayName("should pass workflow run org scope to the execution tree")
        void shouldPassWorkflowRunOrgScopeToTreeBuilder() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a"));
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            when(mockPlan.getMcps()).thenReturn(List.of());
            when(mockPlan.getCores()).thenReturn(List.of());
            when(mockTreeBuilder.build(
                eq("run-1"),
                eq("00000000-0000-0000-0000-00000000002a"),
                eq("tenant-1"),
                eq(mockPlan),
                eq("org-acme"),
                eq("OWNER"))).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.STEP_BY_STEP)).thenReturn(mockTree);

            WorkflowRunEntity runEntity = mock(WorkflowRunEntity.class);
            when(runEntity.getOrgId()).thenReturn("org-acme");
            when(runEntity.getOrgRole()).thenReturn("OWNER");
            when(runEntity.getExecutionMode()).thenReturn(ExecutionMode.STEP_BY_STEP);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(runEntity));

            ExecutionCacheManager.LoadedExecution loaded = cacheManager.loadTreeAndExecution("run-1");

            assertNotNull(loaded);
            verify(mockTreeBuilder).build(
                eq("run-1"),
                eq("00000000-0000-0000-0000-00000000002a"),
                eq("tenant-1"),
                eq(mockPlan),
                eq("org-acme"),
                eq("OWNER"));
        }

        @Test
        @DisplayName("should return null when tree builder throws")
        void shouldReturnNullOnTreeBuilderError() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a"));
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(), isNull(), isNull()))
                .thenThrow(new RuntimeException("Build error"));

            assertNull(cacheManager.loadTreeAndExecution("run-1"));
        }

        @Test
        @DisplayName("should default to AUTOMATIC mode when run entity not found")
        void shouldDefaultToAutomatic() {
            when(mockResumeService.reconstructState("run-1")).thenReturn(mockState);
            when(mockContextManager.rebuildExecutionContext("run-1", mockState)).thenReturn(mockExecution);
            when(mockExecution.getPlan()).thenReturn(mockPlan);
            when(mockExecution.getWorkflowRunId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a"));
            when(mockPlan.getTenantId()).thenReturn("tenant-1");
            when(mockPlan.getMcps()).thenReturn(List.of());
            when(mockPlan.getCores()).thenReturn(List.of());
            when(mockTreeBuilder.build(anyString(), anyString(), anyString(), any(), isNull(), isNull())).thenReturn(mockTree);
            when(mockTree.withExecutionMode(ExecutionMode.AUTOMATIC)).thenReturn(mockTree);
            when(mockRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            ExecutionCacheManager.LoadedExecution loaded = cacheManager.loadTreeAndExecution("run-1");

            assertNotNull(loaded);
            verify(mockTree).withExecutionMode(ExecutionMode.AUTOMATIC);
        }
    }
}
