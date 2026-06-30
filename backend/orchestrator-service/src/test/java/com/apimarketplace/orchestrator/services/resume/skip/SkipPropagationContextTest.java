package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkipPropagationContext")
class SkipPropagationContextTest {

    @Mock
    private WorkflowExecution mockExecution;

    @Mock
    private WorkflowPlan mockPlan;

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            Set<String> visited = new HashSet<>();
            SkipPropagationContext context = new SkipPropagationContext(
                mockExecution, mockPlan, "mcp:step1", "core:decision", visited
            );

            assertEquals(mockExecution, context.execution());
            assertEquals(mockPlan, context.plan());
            assertEquals("mcp:step1", context.skippedNodeId());
            assertEquals("core:decision", context.skipSourceId());
            assertSame(visited, context.visited());
        }
    }

    @Nested
    @DisplayName("forSuccessor()")
    class ForSuccessorTests {

        @Test
        @DisplayName("Should create new context with different skippedNodeId")
        void shouldCreateNewContextWithDifferentNode() {
            Set<String> visited = new HashSet<>();
            SkipPropagationContext original = new SkipPropagationContext(
                mockExecution, mockPlan, "mcp:step1", "core:decision", visited
            );

            SkipPropagationContext successor = original.forSuccessor("mcp:step2");

            assertEquals("mcp:step2", successor.skippedNodeId());
            assertEquals("core:decision", successor.skipSourceId());
            assertSame(mockExecution, successor.execution());
            assertSame(mockPlan, successor.plan());
        }

        @Test
        @DisplayName("Should share the same visited set (mutable)")
        void shouldShareVisitedSet() {
            Set<String> visited = new HashSet<>();
            SkipPropagationContext original = new SkipPropagationContext(
                mockExecution, mockPlan, "mcp:step1", "core:decision", visited
            );

            SkipPropagationContext successor = original.forSuccessor("mcp:step2");
            visited.add("mcp:step1");

            assertTrue(successor.visited().contains("mcp:step1"));
            assertSame(original.visited(), successor.visited());
        }
    }

    @Nested
    @DisplayName("runId()")
    class RunIdTests {

        @Test
        @DisplayName("Should return run ID from execution")
        void shouldReturnRunIdFromExecution() {
            when(mockExecution.getRunId()).thenReturn("run-123");

            SkipPropagationContext context = new SkipPropagationContext(
                mockExecution, mockPlan, "mcp:step1", "core:decision", new HashSet<>()
            );

            assertEquals("run-123", context.runId());
        }

        @Test
        @DisplayName("Should return null when execution is null")
        void shouldReturnNullWhenExecutionIsNull() {
            SkipPropagationContext context = new SkipPropagationContext(
                null, mockPlan, "mcp:step1", "core:decision", new HashSet<>()
            );

            assertNull(context.runId());
        }
    }
}
