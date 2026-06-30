package com.apimarketplace.orchestrator.services.completion;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkipContext")
class SkipContextTest {

    @Mock
    private WorkflowExecution mockExecution;

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create SkipContext with all fields")
        void shouldCreateWithAllFields() {
            SkipContext ctx = new SkipContext(
                mockExecution, "mcp:step1", "step1",
                "Decision took else branch", "core:decision1", 5, 0
            );

            assertSame(mockExecution, ctx.execution());
            assertEquals("mcp:step1", ctx.nodeId());
            assertEquals("step1", ctx.nodeLabel());
            assertEquals("Decision took else branch", ctx.skipReason());
            assertEquals("core:decision1", ctx.skipSourceNode());
            assertEquals(5, ctx.itemIndex());
        }

        @Test
        @DisplayName("Should allow null skipSourceNode")
        void shouldAllowNullSkipSourceNode() {
            SkipContext ctx = new SkipContext(
                mockExecution, "mcp:step1", "step1",
                "reason", null, 0, 0
            );
            assertNull(ctx.skipSourceNode());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when execution is null")
        void shouldThrowWhenExecutionIsNull() {
            assertThrows(NullPointerException.class,
                () -> new SkipContext(null, "id", "label", "reason", null, 0, 0));
        }

        @Test
        @DisplayName("Should throw when nodeId is null")
        void shouldThrowWhenNodeIdIsNull() {
            assertThrows(NullPointerException.class,
                () -> new SkipContext(mockExecution, null, "label", "reason", null, 0, 0));
        }

        @Test
        @DisplayName("Should throw when nodeLabel is null")
        void shouldThrowWhenNodeLabelIsNull() {
            assertThrows(NullPointerException.class,
                () -> new SkipContext(mockExecution, "id", null, "reason", null, 0, 0));
        }

        @Test
        @DisplayName("Should throw when skipReason is null")
        void shouldThrowWhenSkipReasonIsNull() {
            assertThrows(NullPointerException.class,
                () -> new SkipContext(mockExecution, "id", "label", null, null, 0, 0));
        }

        @Test
        @DisplayName("Should throw when itemIndex is negative")
        void shouldThrowWhenItemIndexIsNegative() {
            assertThrows(IllegalArgumentException.class,
                () -> new SkipContext(mockExecution, "id", "label", "reason", null, -1, 0));
        }
    }

    @Nested
    @DisplayName("Factory method")
    class FactoryMethodTests {

        @Test
        @DisplayName("of() should create equivalent instance")
        void ofShouldCreateEquivalent() {
            SkipContext ctx = SkipContext.of(
                mockExecution, "mcp:step1", "step1", "reason", "src", 3
            );

            assertEquals("mcp:step1", ctx.nodeId());
            assertEquals(3, ctx.itemIndex());
        }
    }

    @Nested
    @DisplayName("runId()")
    class RunIdTests {

        @Test
        @DisplayName("Should delegate to execution.getRunId()")
        void shouldDelegateToExecution() {
            when(mockExecution.getRunId()).thenReturn("run-abc");

            SkipContext ctx = new SkipContext(
                mockExecution, "id", "label", "reason", null, 0, 0
            );

            assertEquals("run-abc", ctx.runId());
        }
    }
}
