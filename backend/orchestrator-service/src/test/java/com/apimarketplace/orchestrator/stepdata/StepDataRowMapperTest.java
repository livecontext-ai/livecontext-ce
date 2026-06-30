package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StepDataRowMapper.
 */
@DisplayName("StepDataRowMapper")
class StepDataRowMapperTest {

    private StepDataRowMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new StepDataRowMapper();
    }

    private WorkflowStepDataEntity createEntity(NodeType nodeType) {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        entity.setId(1L);
        entity.setNodeType(nodeType);
        entity.setStatus("COMPLETED");
        entity.setStartTime(Instant.parse("2025-01-01T10:00:00Z"));
        entity.setEndTime(Instant.parse("2025-01-01T10:00:05Z"));
        entity.setEpoch(1);
        entity.setItemIndex(0);
        entity.setItemId("item-1");
        return entity;
    }

    private WorkflowStepDataEntity createEntityWithEpoch(NodeType nodeType, int epoch) {
        WorkflowStepDataEntity entity = createEntity(nodeType);
        entity.setEpoch(epoch);
        return entity;
    }

    @Nested
    @DisplayName("common fields")
    class CommonFieldTests {

        @Test
        @DisplayName("Should include common fields for all node types")
        void shouldIncludeCommonFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("id", 1);
            assertThat(row).containsEntry("status", "COMPLETED");
            assertThat(row).containsEntry("epoch", 1);
            // itemIndex=0 (default) is not included - only meaningful values (>0)
            assertThat(row).doesNotContainKey("itemIndex");
            assertThat(row).containsEntry("itemId", "item-1");
            assertThat(row).containsKey("startTime");
            assertThat(row).containsKey("endTime");
        }

        @Test
        @DisplayName("Should calculate duration when start and end time present")
        void shouldCalculateDuration() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row.get("durationMs")).isEqualTo(5000L);
        }

        @Test
        @DisplayName("Should set duration to null when times missing")
        void shouldSetDurationNullWhenTimesMissing() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setStartTime(null);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row.get("durationMs")).isNull();
        }

        @Test
        @DisplayName("Should include error message when present")
        void shouldIncludeErrorMessage() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setErrorMessage("Connection timed out");

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("errorMessage", "Connection timed out");
        }

        @Test
        @DisplayName("Should not include error message when null")
        void shouldNotIncludeErrorWhenNull() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).doesNotContainKey("errorMessage");
        }

        @Test
        @DisplayName("Should include epoch 0 (first epoch)")
        void shouldIncludeEpochZero() {
            WorkflowStepDataEntity entity = createEntityWithEpoch(NodeType.MCP, 0);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("epoch", 0);
        }

        @Test
        @DisplayName("Should include epoch when null (defaults to 0)")
        void shouldIncludeEpochWhenNull() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setEpoch(null);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("epoch", 0);
        }
    }

    @Nested
    @DisplayName("TRIGGER fields")
    class TriggerFieldTests {

        @Test
        @DisplayName("Should include trigger fields with metadata")
        void shouldIncludeTriggerFieldsWithMetadata() {
            WorkflowStepDataEntity entity = createEntity(NodeType.TRIGGER);
            entity.setInputData(Map.of("payload", "data"));
            entity.setMetadata(Map.of(
                "trigger_type", "webhook",
                "items_spawned", 5
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("triggerType", "webhook");
            assertThat(row).containsEntry("itemsSpawned", 5);
            assertThat(row).containsEntry("input", Map.of("payload", "data"));
        }

        @Test
        @DisplayName("Should extract trigger type from toolId when no metadata")
        void shouldExtractTriggerTypeFromToolId() {
            WorkflowStepDataEntity entity = createEntity(NodeType.TRIGGER);
            entity.setToolId("webhook-trigger");

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("triggerType", "webhook");
        }

        @Test
        @DisplayName("Should return 'unknown' for null toolId")
        void shouldReturnUnknownForNullToolId() {
            WorkflowStepDataEntity entity = createEntity(NodeType.TRIGGER);
            entity.setToolId(null);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("triggerType", "unknown");
        }

        @Test
        @DisplayName("Should include output when outputData is present")
        void shouldIncludeOutputWhenPresent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.TRIGGER);
            entity.setToolId("webhook-trigger");
            Map<String, Object> outputData = Map.of("output", Map.of("body", "hello", "headers", Map.of("x-key", "val")));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("body", "hello");
        }

        @Test
        @DisplayName("Should not include output when outputData is null")
        void shouldNotIncludeOutputWhenNull() {
            WorkflowStepDataEntity entity = createEntity(NodeType.TRIGGER);
            entity.setToolId("webhook-trigger");

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).doesNotContainKey("output");
        }
    }

    @Nested
    @DisplayName("DECISION fields")
    class DecisionFieldTests {

        @Test
        @DisplayName("Should include decision fields")
        void shouldIncludeDecisionFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.DECISION);
            entity.setSelectedBranch("if");
            entity.setConditionExpression("{{x > 100}}");
            entity.setConditionResult(true);
            entity.setMetadata(Map.of(
                "evaluations", List.of(Map.of("branch", "if", "result", true)),
                "condition_resolved", "150 > 100"
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("selectedBranch", "if");
            assertThat(row).containsEntry("conditionExpression", "{{x > 100}}");
            assertThat(row).containsEntry("conditionResult", true);
            assertThat(row).containsEntry("conditionResolved", "150 > 100");
            assertThat(row).containsKey("evaluations");
        }

        @Test
        @DisplayName("Should include output when outputData is present")
        void shouldIncludeOutputWhenPresent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.DECISION);
            entity.setSelectedBranch("if");
            Map<String, Object> outputData = Map.of("output", Map.of("selected_branch", "if", "result", true));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("selected_branch", "if");
        }
    }

    @Nested
    @DisplayName("SWITCH fields")
    class SwitchFieldTests {

        @Test
        @DisplayName("Should include switch fields from metadata")
        void shouldIncludeSwitchFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.SWITCH);
            entity.setSelectedBranch("case_0");
            entity.setMetadata(Map.of(
                "switch_expression", "{{status}}",
                "switch_value", "active",
                "selected_case", "case_0"
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("selectedBranch", "case_0");
            assertThat(row).containsEntry("switchExpression", "{{status}}");
            assertThat(row).containsEntry("switchValue", "active");
            assertThat(row).containsEntry("selectedCase", "case_0");
        }

        @Test
        @DisplayName("Should include output when outputData is present")
        void shouldIncludeOutputWhenPresent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.SWITCH);
            entity.setSelectedBranch("case_1");
            Map<String, Object> outputData = Map.of("output", Map.of("matched_case", "case_1"));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("matched_case", "case_1");
        }
    }

    @Nested
    @DisplayName("LOOP_CONTROLLER fields")
    class LoopFieldTests {

        @Test
        @DisplayName("Should include loop fields with progress")
        void shouldIncludeLoopFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.LOOP_CONTROLLER);
            entity.setLoopIteration(3);
            entity.setLoopId("loop-1");
            entity.setLoopExitReason("condition_false");
            entity.setConditionExpression("{{i < 10}}");
            entity.setConditionResult(false);
            entity.setMetadata(Map.of(
                "max_iterations", 10,
                "loop_condition", "{{i < 10}}"
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("loopIteration", 3);
            assertThat(row).containsEntry("loopId", "loop-1");
            assertThat(row).containsEntry("loopExitReason", "condition_false");
            assertThat(row).containsEntry("maxIterations", 10);
            assertThat(row).containsKey("loopProgress");
            @SuppressWarnings("unchecked")
            Map<String, Object> progress = (Map<String, Object>) row.get("loopProgress");
            assertThat(progress).containsEntry("current", 3);
            assertThat(progress).containsEntry("max", 10);
        }

        @Test
        @DisplayName("Should include output when outputData is present")
        void shouldIncludeOutputWhenPresent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.LOOP_CONTROLLER);
            entity.setLoopIteration(2);
            entity.setMetadata(Map.of("max_iterations", 5));
            Map<String, Object> outputData = Map.of("output", Map.of("carry", "accumulated"));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("carry", "accumulated");
        }
    }

    @Nested
    @DisplayName("SPLIT_CONTROLLER fields")
    class SplitFieldTests {

        @Test
        @DisplayName("Should include split fields with progress")
        void shouldIncludeSplitFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.SPLIT_CONTROLLER);
            entity.setItemIndex(2);
            entity.setMetadata(Map.of(
                "item_count", 10,
                "processed_items", 3,
                "list_expression", "{{data.items}}",
                "split_strategy", "sequential",
                "spawn_parallel_items", false
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("totalItems", 10);
            assertThat(row).containsEntry("processedItems", 3);
            assertThat(row).containsEntry("strategy", "sequential");
            assertThat(row).containsEntry("parallel", false);
            assertThat(row).containsKey("splitProgress");
        }

        @Test
        @DisplayName("Should include output when outputData is present")
        void shouldIncludeOutputWhenPresent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.SPLIT_CONTROLLER);
            entity.setItemIndex(0);
            entity.setMetadata(Map.of("item_count", 3));
            Map<String, Object> outputData = Map.of("output", Map.of("current_item", "item_0", "items", List.of("a", "b", "c")));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("current_item", "item_0");
            assertThat(output).containsKey("items");
        }
    }

    @Nested
    @DisplayName("MERGE fields")
    class MergeFieldTests {

        @Test
        @DisplayName("Should include merge fields with counts")
        void shouldIncludeMergeFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MERGE);
            entity.setMergeStrategy("combine_all");
            entity.setMergeReceivedBranches(List.of("mcp:step_a", "mcp:step_b"));
            entity.setMergeSkippedBranches(List.of("mcp:step_c"));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("mergeStrategy", "combine_all");
            assertThat(row).containsEntry("predecessorsCompleted", 2);
            assertThat(row).containsEntry("predecessorsSkipped", 1);
            assertThat(row).containsEntry("predecessorsTotal", 3);
        }

        @Test
        @DisplayName("Should handle null branch lists")
        void shouldHandleNullBranchLists() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MERGE);
            entity.setMergeStrategy("combine_all");
            entity.setMergeReceivedBranches(null);
            entity.setMergeSkippedBranches(null);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            // When both branch lists are null, predecessor counts are not included
            assertThat(row).doesNotContainKey("predecessorsCompleted");
            assertThat(row).doesNotContainKey("predecessorsSkipped");
            assertThat(row).doesNotContainKey("predecessorsTotal");
        }

        @Test
        @DisplayName("Should extract waiting-for from merge_states metadata")
        void shouldExtractWaitingFor() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MERGE);
            entity.setMergeReceivedBranches(List.of());
            entity.setMergeSkippedBranches(List.of());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("merge_states", Map.of(
                "mcp:step_a", "COMPLETED",
                "mcp:step_b", "WAITING",
                "mcp:step_c", "PENDING"
            ));
            entity.setMetadata(metadata);

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            @SuppressWarnings("unchecked")
            List<String> waitingFor = (List<String>) row.get("waitingFor");
            assertThat(waitingFor).containsExactlyInAnyOrder("mcp:step_b", "mcp:step_c");
        }
    }

    @Nested
    @DisplayName("FORK fields")
    class ForkFieldTests {

        @Test
        @DisplayName("Should include fork fields from metadata")
        void shouldIncludeForkFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.FORK);
            entity.setMetadata(Map.of(
                "branches", List.of("mcp:task_a", "mcp:task_b")
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("branchesCount", 2);
            assertThat(row).containsKey("branches");
        }
    }

    @Nested
    @DisplayName("AGENT fields")
    class AgentFieldTests {

        @Test
        @DisplayName("Should include agent fields from metadata")
        void shouldIncludeAgentFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.AGENT);
            entity.setInputData(Map.of("prompt", "Classify this"));
            entity.setMetadata(Map.of(
                "model", "gpt-4",
                "provider", "openai",
                "iterations", 2,
                "tool_calls", 3,
                "tokens_used", 1500
            ));

            Map<String, Object> outputData = Map.of(
                "output", Map.of("response", "Classified as: A")
            );

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsEntry("model", "gpt-4");
            assertThat(row).containsEntry("provider", "openai");
            assertThat(row).containsEntry("llmIterations", 2);
            assertThat(row).containsEntry("toolCallsCount", 3);
            assertThat(row).containsEntry("tokensUsed", 1500);
            assertThat(row).containsEntry("response", "Classified as: A");
        }

        @Test
        @DisplayName("Should extract response from content field as fallback")
        void shouldExtractResponseFromContent() {
            WorkflowStepDataEntity entity = createEntity(NodeType.AGENT);
            entity.setMetadata(Map.of());

            Map<String, Object> outputData = Map.of(
                "content", "Direct content response"
            );

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsEntry("response", "Direct content response");
        }

        @Test
        @DisplayName("Should include full output alongside response")
        void shouldIncludeFullOutputAlongsideResponse() {
            WorkflowStepDataEntity entity = createEntity(NodeType.AGENT);
            entity.setMetadata(Map.of("model", "gpt-4"));
            Map<String, Object> innerOutput = new HashMap<>();
            innerOutput.put("response", "classified as A");
            innerOutput.put("category", "A");
            innerOutput.put("confidence", 0.95);
            Map<String, Object> outputData = Map.of("output", innerOutput);

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            // response extracted for convenience
            assertThat(row).containsEntry("response", "classified as A");
            // full output also present for the DataTable output column
            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("category", "A");
            assertThat(output).containsEntry("confidence", 0.95);
        }

        @Test
        @DisplayName("Should not include output when outputData is null")
        void shouldNotIncludeOutputWhenNull() {
            WorkflowStepDataEntity entity = createEntity(NodeType.AGENT);
            entity.setMetadata(Map.of());

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).doesNotContainKey("output");
            assertThat(row).doesNotContainKey("response");
        }
    }

    @Nested
    @DisplayName("MCP fields")
    class McpFieldTests {

        @Test
        @DisplayName("Should include default MCP fields")
        void shouldIncludeDefaultMcpFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setHttpStatus(200);
            entity.setToolId("my-api/search");
            entity.setInputData(Map.of("query", "test"));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("httpStatus", 200);
            assertThat(row).containsEntry("toolName", "search");
            assertThat(row).containsEntry("apiName", "my-api");
        }

        @Test
        @DisplayName("Should derive toolName from toolId without slash")
        void shouldDeriveToolNameWithoutSlash() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("simple-tool");

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("toolName", "simple-tool");
            assertThat(row.get("apiName")).isNull();
        }

        @Test
        @DisplayName("Should use metadata for toolName and apiName when available")
        void shouldUseMetadataForToolInfo() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("my-api/search");
            entity.setMetadata(Map.of(
                "toolName", "Custom Tool",
                "apiName", "Custom API"
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("toolName", "Custom Tool");
            assertThat(row).containsEntry("apiName", "Custom API");
        }

        @Test
        @DisplayName("Should include transform fields for transform toolId")
        void shouldIncludeTransformFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("core__transform__1");
            entity.setInputData(Map.of(
                "mappings", List.of(
                    Map.of("from", "a", "to", "b")
                )
            ));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("mappingsCount", 1);
            assertThat(row).containsKey("mappings");
        }

        @Test
        @DisplayName("Should include wait fields for wait toolId")
        void shouldIncludeWaitFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("core__wait__5s");
            entity.setInputData(Map.of("duration", 5000));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("waitDuration", 5000);
            assertThat(row).containsEntry("actualWait", 5000L);
        }

        @Test
        @DisplayName("Should include CRUD fields for crud toolId")
        void shouldIncludeCrudFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("crud/create-row");
            entity.setMetadata(Map.of("dataSourceName", "my_table"));

            Map<String, Object> outputData = Map.of(
                "output", Map.of("rows_affected", 5)
            );

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsEntry("operation", "INSERT");
            assertThat(row).containsEntry("dataSourceName", "my_table");
            assertThat(row).containsEntry("rowsAffected", 5);
        }

        @Test
        @DisplayName("Should include HTTP request fields for http-request toolId")
        void shouldIncludeHttpRequestFields() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setToolId("http-request");
            entity.setHttpStatus(200);
            entity.setInputData(Map.of("method", "GET", "url", "https://api.example.com"));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("method", "GET");
            assertThat(row).containsEntry("url", "https://api.example.com");
            assertThat(row).containsEntry("httpStatus", 200);
            assertThat(row).containsEntry("responseTime", 5000L);
        }
    }

    @Nested
    @DisplayName("null nodeType")
    class NullNodeTypeTests {

        @Test
        @DisplayName("Should use generic fields for null nodeType")
        void shouldUseGenericFields() {
            WorkflowStepDataEntity entity = createEntity(null);
            entity.setInputData(Map.of("key", "value"));

            Map<String, Object> row = mapper.mapToRow(entity, null, 1);

            assertThat(row).containsEntry("input", Map.of("key", "value"));
        }

        @Test
        @DisplayName("Should include output for null nodeType")
        void shouldIncludeOutputForNullNodeType() {
            WorkflowStepDataEntity entity = createEntity(null);
            Map<String, Object> outputData = Map.of("output", Map.of("result", "done"));

            Map<String, Object> row = mapper.mapToRow(entity, outputData, 1);

            assertThat(row).containsKey("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) row.get("output");
            assertThat(output).containsEntry("result", "done");
        }
    }

    @Nested
    @DisplayName("output field presence across all node types")
    class OutputFieldPresenceTests {

        private final Map<String, Object> standardOutputData = Map.of(
            "output", Map.of("result", "test_value", "count", 42)
        );

        @Test
        @DisplayName("Every node type should include output when outputData is provided")
        void everyNodeTypeShouldIncludeOutput() {
            // All NodeType values that go through the switch in mapToRow
            NodeType[] allTypes = {
                NodeType.TRIGGER,
                NodeType.DECISION,
                NodeType.SWITCH,
                NodeType.LOOP_CONTROLLER,
                NodeType.SPLIT_CONTROLLER,
                NodeType.MERGE,
                NodeType.FORK,
                NodeType.AGENT,
                NodeType.MCP,
                NodeType.TRANSFORM,
                NodeType.WAIT,
                NodeType.HTTP_REQUEST,
                NodeType.INSERT_ROW,
                NodeType.GET_ROWS,
                NodeType.UPDATE_ROW,
                NodeType.DELETE_ROW,
                NodeType.CREATE_COLUMN,
            };

            for (NodeType nodeType : allTypes) {
                WorkflowStepDataEntity entity = createEntity(nodeType);
                // MCP sub-types need a toolId to route correctly
                if (nodeType == NodeType.MCP) {
                    entity.setToolId("api/tool");
                }
                // CRUD types need a toolId
                if (nodeType == NodeType.INSERT_ROW || nodeType == NodeType.GET_ROWS
                    || nodeType == NodeType.UPDATE_ROW || nodeType == NodeType.DELETE_ROW
                    || nodeType == NodeType.CREATE_COLUMN) {
                    entity.setToolId("crud/op");
                }

                Map<String, Object> row = mapper.mapToRow(entity, standardOutputData, 1);

                assertThat(row)
                    .as("NodeType %s should include 'output' key", nodeType)
                    .containsKey("output");
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) row.get("output");
                assertThat(output)
                    .as("NodeType %s output should contain 'result'", nodeType)
                    .containsEntry("result", "test_value");
            }
        }

        @Test
        @DisplayName("No node type should include output when outputData is null")
        void noNodeTypeShouldIncludeOutputWhenNull() {
            NodeType[] allTypes = {
                NodeType.TRIGGER, NodeType.DECISION, NodeType.SWITCH,
                NodeType.LOOP_CONTROLLER, NodeType.SPLIT_CONTROLLER,
                NodeType.MERGE, NodeType.FORK, NodeType.AGENT, NodeType.MCP,
            };

            for (NodeType nodeType : allTypes) {
                WorkflowStepDataEntity entity = createEntity(nodeType);
                if (nodeType == NodeType.MCP) entity.setToolId("api/tool");

                Map<String, Object> row = mapper.mapToRow(entity, null, 1);

                assertThat(row)
                    .as("NodeType %s should NOT include 'output' key when outputData is null", nodeType)
                    .doesNotContainKey("output");
            }
        }
    }

    @Nested
    @DisplayName("legacy mapToRow(entity, outputData)")
    class LegacyMapToRowTests {

        @Test
        @DisplayName("Should use itemNumber from entity as row id when available")
        void shouldUseItemNumberFromEntity() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setItemNumber(42);

            Map<String, Object> row = mapper.mapToRow(entity, null);

            assertThat(row).containsEntry("id", 42);
        }

        @Test
        @DisplayName("Should fall back to entity id as row id when itemNumber is null")
        void shouldFallBackToEntityId() {
            WorkflowStepDataEntity entity = createEntity(NodeType.MCP);
            entity.setItemNumber(null);

            Map<String, Object> row = mapper.mapToRow(entity, null);

            assertThat(row).containsEntry("id", 1); // id is 1L -> intValue() = 1
        }
    }
}
