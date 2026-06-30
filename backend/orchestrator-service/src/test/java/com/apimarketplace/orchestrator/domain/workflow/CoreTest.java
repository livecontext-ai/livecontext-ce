package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.domain.workflow.Core.DecisionCondition;
import com.apimarketplace.orchestrator.domain.workflow.Core.ForkOutput;
import com.apimarketplace.orchestrator.domain.workflow.Core.SwitchCase;
import com.apimarketplace.orchestrator.domain.workflow.Core.TransformConfig;
import com.apimarketplace.orchestrator.domain.workflow.Core.TransformMapping;
import com.apimarketplace.orchestrator.domain.workflow.Core.WaitConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Core record.
 *
 * Core represents control flow nodes: decision, switch, loop, split, merge, fork, transform, wait.
 */
@DisplayName("Core")
class CoreTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should throw for null or blank id")
        void shouldThrowForNullOrBlankId(String id) {
            assertThrows(IllegalArgumentException.class,
                () -> new Core(id, "decision", null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should throw for null or blank type")
        void shouldThrowForNullOrBlankType(String type) {
            assertThrows(IllegalArgumentException.class,
                () -> new Core("c1", type, null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should throw for invalid type")
        void shouldThrowForInvalidType() {
            assertThrows(IllegalArgumentException.class,
                () -> new Core("c1", "invalid", null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"decision", "switch", "loop", "split", "merge", "fork", "transform", "wait", "download_file"})
        @DisplayName("Should accept all valid types")
        void shouldAcceptAllValidTypes(String type) {
            Core core = new Core("c1", type, null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertEquals(type, core.type());
        }

        @Test
        @DisplayName("Should normalize type to lowercase")
        void shouldNormalizeTypeToLowercase() {
            Core core = new Core("c1", "DECISION", null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertEquals("decision", core.type());
        }
    }

    @Nested
    @DisplayName("Type detection methods")
    class TypeDetectionTests {

        @Test
        @DisplayName("isDecision() returns true for decision type")
        void isDecisionReturnsTrue() {
            Core core = createCore("decision");
            assertTrue(core.isDecision());
            assertFalse(core.isLoop());
            assertFalse(core.isSplit());
        }

        @Test
        @DisplayName("isSwitch() returns true for switch type")
        void isSwitchReturnsTrue() {
            Core core = createCore("switch");
            assertTrue(core.isSwitch());
        }

        @Test
        @DisplayName("isLoop() returns true for loop type")
        void isLoopReturnsTrue() {
            Core core = createCore("loop");
            assertTrue(core.isLoop());
        }

        @Test
        @DisplayName("isSplit() returns true for split type")
        void isSplitReturnsTrue() {
            Core core = createCore("split");
            assertTrue(core.isSplit());
        }

        @Test
        @DisplayName("isMerge() returns true for merge type")
        void isMergeReturnsTrue() {
            Core core = createCore("merge");
            assertTrue(core.isMerge());
        }

        @Test
        @DisplayName("isFork() returns true for fork type")
        void isForkReturnsTrue() {
            Core core = createCore("fork");
            assertTrue(core.isFork());
        }

        @Test
        @DisplayName("isTransform() returns true for transform type")
        void isTransformReturnsTrue() {
            Core core = createCore("transform");
            assertTrue(core.isTransform());
        }

        @Test
        @DisplayName("isWait() returns true for wait type")
        void isWaitReturnsTrue() {
            Core core = createCore("wait");
            assertTrue(core.isWait());
        }

        @Test
        @DisplayName("isBranching() returns true for decision and switch")
        void isBranchingReturnsTrueForDecisionAndSwitch() {
            assertTrue(createCore("decision").isBranching());
            assertTrue(createCore("switch").isBranching());
            assertFalse(createCore("loop").isBranching());
        }

        @Test
        @DisplayName("isLooping() returns true for loop and split")
        void isLoopingReturnsTrueForLoopAndSplit() {
            assertTrue(createCore("loop").isLooping());
            assertTrue(createCore("split").isLooping());
            assertFalse(createCore("decision").isLooping());
        }
    }

    @Nested
    @DisplayName("getNormalizedKey()")
    class GetNormalizedKeyTests {

        @ParameterizedTest
        @CsvSource({
            "Check Status, core:check_status",
            "While Loop, core:while_loop",
            "For Each Item, core:for_each_item"
        })
        @DisplayName("Should return normalized key with core: prefix")
        void shouldReturnNormalizedKey(String label, String expectedKey) {
            Core core = new Core("c1", "decision", null, label, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertEquals(expectedKey, core.getNormalizedKey());
        }

        @Test
        @DisplayName("Should fallback to id when label is null")
        void shouldFallbackToIdWhenLabelNull() {
            Core core = new Core("decision_1", "decision", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            assertEquals("core:decision_1", core.getNormalizedKey());
        }
    }

    @Nested
    @DisplayName("Decision ports")
    class DecisionPortsTests {

        @Test
        @DisplayName("getDecisionPorts() returns if, elseif_N, else")
        void getDecisionPortsReturnsCorrectPorts() {
            List<DecisionCondition> conditions = List.of(
                new DecisionCondition("c1", "if", "Success", "{{status}} == 200"),
                new DecisionCondition("c2", "elseif", "Redirect", "{{status}} >= 300"),
                new DecisionCondition("c3", "else", "Error", null)
            );
            Core core = new Core("c1", "decision", null, "Check", conditions, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            List<String> ports = core.getDecisionPorts();

            assertTrue(ports.contains("if"));
            assertTrue(ports.contains("else"));
        }

        @Test
        @DisplayName("getDecisionPorts() returns empty for non-decision")
        void getDecisionPortsReturnsEmptyForNonDecision() {
            Core core = createCore("loop");
            assertTrue(core.getDecisionPorts().isEmpty());
        }

        @Test
        @DisplayName("getBranchCount() returns condition count for decision")
        void getBranchCountReturnsConditionCount() {
            List<DecisionCondition> conditions = List.of(
                new DecisionCondition("c1", "if", "A", "expr1"),
                new DecisionCondition("c2", "else", "B", null)
            );
            Core core = new Core("c1", "decision", null, "Check", conditions, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(2, core.getBranchCount());
        }
    }

    @Nested
    @DisplayName("Switch ports")
    class SwitchPortsTests {

        @Test
        @DisplayName("getSwitchPorts() returns case_N and default")
        void getSwitchPortsReturnsCorrectPorts() {
            List<SwitchCase> cases = List.of(
                new SwitchCase("c1", "case", "Created", "created"),
                new SwitchCase("c2", "case", "Updated", "updated"),
                new SwitchCase("c3", "default", "Other", null)
            );
            Core core = new Core("c1", "switch", null, "Router", null, "{{type}}", cases, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            List<String> ports = core.getSwitchPorts();

            assertEquals(3, ports.size());
            assertTrue(ports.contains("case_0"));
            assertTrue(ports.contains("case_1"));
            assertTrue(ports.contains("default"));
        }

        @Test
        @DisplayName("getSwitchPorts() returns empty for non-switch")
        void getSwitchPortsReturnsEmptyForNonSwitch() {
            Core core = createCore("decision");
            assertTrue(core.getSwitchPorts().isEmpty());
        }

        @Test
        @DisplayName("getBranchCount() returns case count for switch")
        void getBranchCountReturnsCaseCount() {
            List<SwitchCase> cases = List.of(
                new SwitchCase("c1", "case", "A", "a"),
                new SwitchCase("c2", "case", "B", "b"),
                new SwitchCase("c3", "default", "C", null)
            );
            Core core = new Core("c1", "switch", null, "Router", null, "{{type}}", cases, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertEquals(3, core.getBranchCount());
        }
    }

    @Nested
    @DisplayName("Fork ports")
    class ForkPortsTests {

        @Test
        @DisplayName("getForkPorts() returns branch_N")
        void getForkPortsReturnsBranchN() {
            List<ForkOutput> outputs = List.of(
                new ForkOutput("b1", "Task A", "mcp:task_a"),
                new ForkOutput("b2", "Task B", "mcp:task_b"),
                new ForkOutput("b3", "Task C", "mcp:task_c")
            );
            Core core = new Core("c1", "fork", null, "Parallel", null, null, null, null, null, null, null, null, null, outputs, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            List<String> ports = core.getForkPorts();

            assertEquals(3, ports.size());
            assertEquals("branch_0", ports.get(0));
            assertEquals("branch_1", ports.get(1));
            assertEquals("branch_2", ports.get(2));
        }

        @Test
        @DisplayName("getForkPorts() returns empty for non-fork")
        void getForkPortsReturnsEmptyForNonFork() {
            Core core = createCore("decision");
            assertTrue(core.getForkPorts().isEmpty());
        }
    }

    @Nested
    @DisplayName("Loop ports")
    class LoopPortsTests {

        @Test
        @DisplayName("getLoopPorts() returns body and exit")
        void getLoopPortsReturnsBodyAndExit() {
            Core core = new Core("c1", "loop", null, "While", null, null, null, "{{i}} < 10", 100, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            List<String> ports = core.getLoopPorts();

            assertEquals(2, ports.size());
            assertTrue(ports.contains("body"));
            assertTrue(ports.contains("exit"));
        }

        @Test
        @DisplayName("getLoopPorts() returns empty for non-loop")
        void getLoopPortsReturnsEmptyForNonLoop() {
            Core core = createCore("decision");
            assertTrue(core.getLoopPorts().isEmpty());
        }
    }

    @Nested
    @DisplayName("Split ports")
    class SplitPortsTests {

        @Test
        @DisplayName("getSplitPorts() returns empty (uses internal spawning)")
        void getSplitPortsReturnsEmpty() {
            Core core = new Core("c1", "split", null, "Each Item", null, null, null, null, null, null, "{{items}}", 100, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            // Split does NOT use edge ports - it uses internal parallel spawning
            assertTrue(core.getSplitPorts().isEmpty());
        }
    }

    @Nested
    @DisplayName("getAllPorts()")
    class GetAllPortsTests {

        @Test
        @DisplayName("Should return decision ports for decision")
        void shouldReturnDecisionPortsForDecision() {
            List<DecisionCondition> conditions = List.of(
                new DecisionCondition("c1", "if", "A", "expr")
            );
            Core core = new Core("c1", "decision", null, "Check", conditions, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertFalse(core.getAllPorts().isEmpty());
        }

        @Test
        @DisplayName("Should return empty for merge")
        void shouldReturnEmptyForMerge() {
            Core core = createCore("merge");
            assertTrue(core.getAllPorts().isEmpty());
        }

        @Test
        @DisplayName("Should return empty for transform")
        void shouldReturnEmptyForTransform() {
            Core core = createCore("transform");
            assertTrue(core.getAllPorts().isEmpty());
        }

        @Test
        @DisplayName("Should return empty for wait")
        void shouldReturnEmptyForWait() {
            Core core = createCore("wait");
            assertTrue(core.getAllPorts().isEmpty());
        }
    }

    @Nested
    @DisplayName("Transform configuration")
    class TransformConfigTests {

        @Test
        @DisplayName("Should create transform with mappings")
        void shouldCreateTransformWithMappings() {
            List<TransformMapping> mappings = List.of(
                new TransformMapping("fullName", "{{first}} {{last}}"),
                new TransformMapping("email", "{{contact.email}}")
            );
            TransformConfig config = new TransformConfig(mappings);
            Core core = new Core("c1", "transform", null, "Transform", null, null, null, null, null, null, null, null, null, null, config, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertTrue(core.isTransform());
            assertNotNull(core.transformConfig());
            assertEquals(2, core.transformConfig().mappings().size());
        }
    }

    @Nested
    @DisplayName("Wait configuration")
    class WaitConfigTests {

        @Test
        @DisplayName("Should create wait with duration")
        void shouldCreateWaitWithDuration() {
            WaitConfig config = new WaitConfig(5000L);
            Core core = new Core("c1", "wait", null, "Wait 5s", null, null, null, null, null, null, null, null, null, null, null, config, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertTrue(core.isWait());
            assertNotNull(core.waitConfig());
            assertEquals(5000L, core.waitConfig().duration());
        }
    }

    @Nested
    @DisplayName("Nested records")
    class NestedRecordsTests {

        @Test
        @DisplayName("DecisionCondition should default type to 'if'")
        void decisionConditionShouldDefaultTypeToIf() {
            DecisionCondition condition = new DecisionCondition("c1", null, "Label", "expr");
            assertEquals("if", condition.type());
        }

        @Test
        @DisplayName("SwitchCase should default type to 'case'")
        void switchCaseShouldDefaultTypeToCase() {
            SwitchCase switchCase = new SwitchCase("c1", null, "Label", "value");
            assertEquals("case", switchCase.type());
        }

        @Test
        @DisplayName("TransformConfig should default null mappings to empty")
        void transformConfigShouldDefaultNullMappingsToEmpty() {
            TransformConfig config = new TransformConfig(null);
            assertNotNull(config.mappings());
            assertTrue(config.mappings().isEmpty());
        }
    }

    // Helper method
    private Core createCore(String type) {
        return new Core("c1", type, null, "Label", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
