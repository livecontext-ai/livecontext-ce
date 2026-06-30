package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.ColumnType;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.RenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ColumnDefinitionService.
 */
@DisplayName("ColumnDefinitionService")
class ColumnDefinitionServiceTest {

    private ColumnDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new ColumnDefinitionService();
    }

    @Nested
    @DisplayName("common columns")
    class CommonColumnsTests {

        @ParameterizedTest
        @EnumSource(NodeType.class)
        @DisplayName("Should always include common columns for all node types")
        void shouldIncludeCommonColumns(NodeType nodeType) {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(nodeType, null);

            assertThat(columns).isNotEmpty();
            // First common column should be itemNumber
            assertThat(columns.get(0).field()).isEqualTo("itemNumber");
            assertThat(columns.get(0).header()).isEqualTo("#");
            // Status column
            assertThat(columns.get(1).field()).isEqualTo("status");
            assertThat(columns.get(1).renderType()).isEqualTo(RenderType.STATUS_BADGE);
        }

        @Test
        @DisplayName("Should include common columns when nodeType is null")
        void shouldIncludeCommonColumnsForNull() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(null, null);

            assertThat(columns).isNotEmpty();
            assertThat(columns.get(0).field()).isEqualTo("itemNumber");
        }

        @ParameterizedTest
        @EnumSource(NodeType.class)
        @DisplayName("Should always include error column as last column")
        void shouldIncludeErrorColumnLast(NodeType nodeType) {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(nodeType, null);

            ColumnDefinition lastColumn = columns.get(columns.size() - 1);
            assertThat(lastColumn.field()).isEqualTo("errorMessage");
            assertThat(lastColumn.header()).isEqualTo("Error");
            assertThat(lastColumn.renderType()).isEqualTo(RenderType.TEXT_PREVIEW);
        }
    }

    @Nested
    @DisplayName("TRIGGER columns")
    class TriggerColumnsTests {

        @Test
        @DisplayName("Should include trigger-specific columns")
        void shouldIncludeTriggerColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.TRIGGER, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("triggerType", "input", "itemsSpawned");
        }

        @Test
        @DisplayName("Should have triggerType with BADGE render")
        void shouldHaveTriggerTypeBadge() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.TRIGGER, null);

            ColumnDefinition triggerType = columns.stream()
                .filter(c -> "triggerType".equals(c.field())).findFirst().orElseThrow();
            assertThat(triggerType.renderType()).isEqualTo(RenderType.BADGE);
        }
    }

    @Nested
    @DisplayName("DECISION columns")
    class DecisionColumnsTests {

        @Test
        @DisplayName("Should include decision-specific columns")
        void shouldIncludeDecisionColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.DECISION, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("selectedBranch", "conditionExpression",
                "conditionResolved", "conditionResult", "evaluations");
        }

        @Test
        @DisplayName("Should have selectedBranch with BRANCH_BADGE render")
        void shouldHaveBranchBadge() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.DECISION, null);

            ColumnDefinition selectedBranch = columns.stream()
                .filter(c -> "selectedBranch".equals(c.field())).findFirst().orElseThrow();
            assertThat(selectedBranch.renderType()).isEqualTo(RenderType.BRANCH_BADGE);
        }
    }

    @Nested
    @DisplayName("SWITCH columns")
    class SwitchColumnsTests {

        @Test
        @DisplayName("Should include switch-specific columns")
        void shouldIncludeSwitchColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.SWITCH, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("switchExpression", "switchValue", "selectedCase", "cases");
        }
    }

    @Nested
    @DisplayName("LOOP_CONTROLLER columns")
    class LoopColumnsTests {

        @Test
        @DisplayName("Should include loop-specific columns")
        void shouldIncludeLoopColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.LOOP_CONTROLLER, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("loopProgress", "loopIteration",
                "maxIterations", "loopCondition", "conditionResult", "loopExitReason", "carryValue");
        }

        @Test
        @DisplayName("Should have loopProgress with LOOP_PROGRESS render")
        void shouldHaveLoopProgress() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.LOOP_CONTROLLER, null);

            ColumnDefinition progress = columns.stream()
                .filter(c -> "loopProgress".equals(c.field())).findFirst().orElseThrow();
            assertThat(progress.renderType()).isEqualTo(RenderType.LOOP_PROGRESS);
        }
    }

    @Nested
    @DisplayName("SPLIT_CONTROLLER columns")
    class SplitColumnsTests {

        @Test
        @DisplayName("Should include split-specific columns")
        void shouldIncludeSplitColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.SPLIT_CONTROLLER, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("splitProgress", "totalItems", "processedItems",
                "list", "strategy", "parallel", "currentItem", "currentIndex");
        }
    }

    @Nested
    @DisplayName("MERGE columns")
    class MergeColumnsTests {

        @Test
        @DisplayName("Should include merge-specific columns")
        void shouldIncludeMergeColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MERGE, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("mergeStrategy", "predecessorsTotal",
                "predecessorsCompleted", "predecessorsSkipped", "waitingFor", "receivedBranches");
        }
    }

    @Nested
    @DisplayName("FORK columns")
    class ForkColumnsTests {

        @Test
        @DisplayName("Should include fork-specific columns")
        void shouldIncludeForkColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.FORK, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("branchesCount", "branches", "output");
        }
    }

    @Nested
    @DisplayName("AGENT columns")
    class AgentColumnsTests {

        @Test
        @DisplayName("Should include agent-specific columns")
        void shouldIncludeAgentColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.AGENT, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("model", "provider", "llmIterations",
                "toolCallsCount", "tokensUsed", "promptTokens", "completionTokens",
                "response", "input");
        }
    }

    @Nested
    @DisplayName("MCP columns")
    class McpColumnsTests {

        @Test
        @DisplayName("Should include default MCP columns when stepId is null")
        void shouldIncludeDefaultMcpColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MCP, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("httpStatus", "toolName", "apiName", "input", "output");
        }

        @Test
        @DisplayName("Should include transform columns for transform stepId")
        void shouldIncludeTransformColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MCP, "core__transform__1");

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("mappingsCount", "mappings", "input", "output");
            assertThat(fieldNames).doesNotContain("httpStatus", "toolName");
        }

        @Test
        @DisplayName("Should include wait columns for wait stepId")
        void shouldIncludeWaitColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MCP, "core__wait__5s");

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("waitDuration", "actualWait");
            assertThat(fieldNames).doesNotContain("httpStatus", "toolName");
        }

        @Test
        @DisplayName("Should include CRUD columns for crud stepId")
        void shouldIncludeCrudColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MCP, "crud/create-row");

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("operation", "dataSourceName", "rowsAffected",
                "whereClause", "output");
        }

        @Test
        @DisplayName("Should include HTTP request columns for http-request stepId")
        void shouldIncludeHttpRequestColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(NodeType.MCP, "http-request");

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("method", "url", "httpStatus", "responseTime",
                "input", "output");
        }
    }

    @Nested
    @DisplayName("null nodeType")
    class NullNodeTypeTests {

        @Test
        @DisplayName("Should include generic columns for null nodeType")
        void shouldIncludeGenericColumns() {
            List<ColumnDefinition> columns = service.getColumnsForNodeType(null, null);

            List<String> fieldNames = columns.stream().map(ColumnDefinition::field).toList();
            assertThat(fieldNames).contains("input", "output");
        }
    }
}
