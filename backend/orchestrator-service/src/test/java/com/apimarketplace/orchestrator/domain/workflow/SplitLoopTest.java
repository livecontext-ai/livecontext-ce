package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SplitLoop")
class SplitLoopTest {

    @Nested
    @DisplayName("Record construction")
    class ConstructionTests {
        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 50, "stop-on-error",
                List.of(), Map.of("key", "value"), "core:decision"
            );

            assertEquals("split1", loop.loopId());
            assertEquals("mcp:step1", loop.entryStep());
            assertEquals("{{items}}", loop.list());
            assertEquals(50, loop.maxItems());
            assertEquals("stop-on-error", loop.splitStrategy());
            assertNotNull(loop.steps());
            assertEquals("value", loop.entryScope().get("key"));
            assertEquals("core:decision", loop.decisionNodeId());
        }

        @Test
        @DisplayName("Should create without decisionNodeId (backward compat)")
        void shouldCreateWithoutDecisionNodeId() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, "continue-anyway",
                List.of(), Map.of()
            );

            assertNull(loop.decisionNodeId());
        }
    }

    @Nested
    @DisplayName("isValid()")
    class IsValidTests {
        @Test
        @DisplayName("Should be valid with required fields")
        void shouldBeValidWithRequiredFields() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, null, List.of(), null
            );
            assertTrue(loop.isValid());
        }

        @Test
        @DisplayName("Should be invalid with null loopId")
        void shouldBeInvalidWithNullLoopId() {
            SplitLoop loop = new SplitLoop(
                null, "mcp:step1", "{{items}}", 10, null, List.of(), null
            );
            assertFalse(loop.isValid());
        }

        @Test
        @DisplayName("Should be invalid with null entryStep")
        void shouldBeInvalidWithNullEntryStep() {
            SplitLoop loop = new SplitLoop(
                "split1", null, "{{items}}", 10, null, List.of(), null
            );
            assertFalse(loop.isValid());
        }

        @Test
        @DisplayName("Should be invalid with null list")
        void shouldBeInvalidWithNullListExpression() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", null, 10, null, List.of(), null
            );
            assertFalse(loop.isValid());
        }

        @Test
        @DisplayName("Should be invalid with blank list")
        void shouldBeInvalidWithBlankListExpression() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "   ", 10, null, List.of(), null
            );
            assertFalse(loop.isValid());
        }
    }

    @Nested
    @DisplayName("getStepCount()")
    class GetStepCountTests {
        @Test
        @DisplayName("Should return 0 for null steps")
        void shouldReturnZeroForNullSteps() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, null, null, null
            );
            assertEquals(0, loop.getStepCount());
        }

        @Test
        @DisplayName("Should return correct count")
        void shouldReturnCorrectCount() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, null,
                List.of(mock(SplitStep.class), mock(SplitStep.class)), null
            );
            assertEquals(2, loop.getStepCount());
        }
    }

    @Nested
    @DisplayName("graphNodeId()")
    class GraphNodeIdTests {
        @Test
        @DisplayName("Should return core: prefixed loopId")
        void shouldReturnCorePrefixedLoopId() {
            SplitLoop loop = new SplitLoop(
                "my_split", "mcp:step1", "{{items}}", 10, null, List.of(), null
            );
            assertEquals("core:my_split", loop.graphNodeId());
        }
    }

    @Nested
    @DisplayName("getEffectiveMaxItems()")
    class GetEffectiveMaxItemsTests {
        @Test
        @DisplayName("Should return configured maxItems")
        void shouldReturnConfiguredMaxItems() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 50, null, List.of(), null
            );
            assertEquals(50, loop.getEffectiveMaxItems());
        }

        @Test
        @DisplayName("Should return 100 as default for 0")
        void shouldReturnDefaultForZero() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 0, null, List.of(), null
            );
            assertEquals(100, loop.getEffectiveMaxItems());
        }

        @Test
        @DisplayName("Should return 100 as default for negative")
        void shouldReturnDefaultForNegative() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", -5, null, List.of(), null
            );
            assertEquals(100, loop.getEffectiveMaxItems());
        }
    }

    @Nested
    @DisplayName("getEffectiveStrategy()")
    class GetEffectiveStrategyTests {
        @Test
        @DisplayName("Should return configured strategy")
        void shouldReturnConfiguredStrategy() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, "continue-anyway", List.of(), null
            );
            assertEquals("continue-anyway", loop.getEffectiveStrategy());
        }

        @Test
        @DisplayName("Should return stop-on-error as default for null")
        void shouldReturnDefaultForNull() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, null, List.of(), null
            );
            assertEquals("stop-on-error", loop.getEffectiveStrategy());
        }

        @Test
        @DisplayName("Should return stop-on-error as default for blank")
        void shouldReturnDefaultForBlank() {
            SplitLoop loop = new SplitLoop(
                "split1", "mcp:step1", "{{items}}", 10, "   ", List.of(), null
            );
            assertEquals("stop-on-error", loop.getEffectiveStrategy());
        }
    }

    private SplitStep mock(Class<SplitStep> clazz) {
        return new SplitStep("step1", "mcp:step1");
    }
}
