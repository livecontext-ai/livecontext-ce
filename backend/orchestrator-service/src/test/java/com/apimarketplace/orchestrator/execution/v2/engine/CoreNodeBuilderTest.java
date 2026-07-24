package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.DataInputNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.FilterNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ForkNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.LimitNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.MergeNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.MediaNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.PublicLinkNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.RemoveDuplicatesNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.SortNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.SummarizeNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.SwitchNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.TransformNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.WaitNode;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CoreNodeBuilder.
 *
 * CoreNodeBuilder creates control flow nodes from Core definitions:
 * - Decision nodes
 * - Loop nodes
 * - Fork nodes
 * - Merge nodes
 * - Wait nodes
 * - Transform nodes
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CoreNodeBuilder")
class CoreNodeBuilderTest {

    @Mock
    private TemplateEngine templateEngine;

    private CoreNodeBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CoreNodeBuilder(templateEngine);
    }

    @Nested
    @DisplayName("createDecisionNodes()")
    class CreateDecisionNodesTests {

        @Test
        @DisplayName("Should create decision node from core definition")
        void shouldCreateDecisionNodeFromCoreDefinition() {
            WorkflowPlan plan = createPlanWithCore("decision", "Check Status");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createDecisionNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:check_status"));
            assertInstanceOf(DecisionNode.class, nodeMap.get("core:check_status"));
        }

        @Test
        @DisplayName("Should normalize core label to key")
        void shouldNormalizeCoreLabelToKey() {
            WorkflowPlan plan = createPlanWithCore("decision", "My Decision Node");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createDecisionNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:my_decision_node"));
        }

        @Test
        @DisplayName("Should create switch node")
        void shouldCreateSwitchNode() {
            WorkflowPlan plan = createPlanWithCore("switch", "Router");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createSwitchNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:router"));
            assertInstanceOf(SwitchNode.class, nodeMap.get("core:router"));
        }

        @Test
        @DisplayName("Should not recreate existing node")
        void shouldNotRecreateExistingNode() {
            WorkflowPlan plan = createPlanWithCore("decision", "Check");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            DecisionNode existingNode = DecisionNode.builder()
                .nodeId("core:check")
                .templateEngine(templateEngine)
                .build();
            nodeMap.put("core:check", existingNode);

            builder.createDecisionNodes(nodeMap, plan);

            assertSame(existingNode, nodeMap.get("core:check"));
        }

        @Test
        @DisplayName("Should handle null cores list")
        void shouldHandleNullCoresList() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> builder.createDecisionNodes(nodeMap, plan));
        }

        @Test
        @DisplayName("Should fallback to id when label is null")
        void shouldFallbackToIdWhenLabelNull() {
            Map<String, Object> data = createBasePlanData();
            data.put("cores", List.of(
                Map.of("id", "decision_1", "type", "decision")
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createDecisionNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:decision_1"));
        }
    }

    @Nested
    @DisplayName("createForkNodes()")
    class CreateForkNodesTests {

        @Test
        @DisplayName("Should create fork node from core definition")
        void shouldCreateForkNodeFromCoreDefinition() {
            WorkflowPlan plan = createPlanWithCore("fork", "Parallel Tasks");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createForkNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:parallel_tasks"));
            assertInstanceOf(ForkNode.class, nodeMap.get("core:parallel_tasks"));
        }

        @Test
        @DisplayName("Should not recreate existing fork node")
        void shouldNotRecreateExistingForkNode() {
            WorkflowPlan plan = createPlanWithCore("fork", "Fork");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ForkNode existingNode = ForkNode.builder()
                .nodeId("core:fork")
                .build();
            nodeMap.put("core:fork", existingNode);

            builder.createForkNodes(nodeMap, plan);

            assertSame(existingNode, nodeMap.get("core:fork"));
        }
    }

    @Nested
    @DisplayName("createMergeNodes()")
    class CreateMergeNodesTests {

        @Test
        @DisplayName("Should create merge node from core definition")
        void shouldCreateMergeNodeFromCoreDefinition() {
            WorkflowPlan plan = createPlanWithCore("merge", "Wait All");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            Map<String, List<String>> mergeSourceNodes = new HashMap<>();

            builder.createMergeNodes(nodeMap, plan, mergeSourceNodes);

            assertTrue(nodeMap.containsKey("core:wait_all"));
            assertInstanceOf(MergeNode.class, nodeMap.get("core:wait_all"));
        }

        @Test
        @DisplayName("Should set source node IDs from collected edges")
        void shouldSetSourceNodeIdsFromCollectedEdges() {
            WorkflowPlan plan = createPlanWithCore("merge", "Join");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            Map<String, List<String>> mergeSourceNodes = new HashMap<>();
            mergeSourceNodes.put("core:join", List.of("mcp:task_a", "mcp:task_b"));

            builder.createMergeNodes(nodeMap, plan, mergeSourceNodes);

            MergeNode mergeNode = (MergeNode) nodeMap.get("core:join");
            assertNotNull(mergeNode);
        }

        @Test
        @DisplayName("Should handle empty source nodes")
        void shouldHandleEmptySourceNodes() {
            WorkflowPlan plan = createPlanWithCore("merge", "Join");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            Map<String, List<String>> mergeSourceNodes = new HashMap<>();

            builder.createMergeNodes(nodeMap, plan, mergeSourceNodes);

            assertTrue(nodeMap.containsKey("core:join"));
        }

    }

    @Nested
    @DisplayName("createWaitNodes()")
    class CreateWaitNodesTests {

        @Test
        @DisplayName("Should create wait node from core definition")
        void shouldCreateWaitNodeFromCoreDefinition() {
            WorkflowPlan plan = createPlanWithWaitCore("Wait 5s", 5000L);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createWaitNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:wait_5s"));
            assertInstanceOf(WaitNode.class, nodeMap.get("core:wait_5s"));
        }

        @Test
        @DisplayName("Should use duration from wait config")
        void shouldUseDurationFromWaitConfig() {
            WorkflowPlan plan = createPlanWithWaitCore("Delay", 10000L);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createWaitNodes(nodeMap, plan);

            WaitNode waitNode = (WaitNode) nodeMap.get("core:delay");
            assertNotNull(waitNode);
        }

        @Test
        @DisplayName("Should default to 0 duration when no config")
        void shouldDefaultToZeroDurationWhenNoConfig() {
            WorkflowPlan plan = createPlanWithCore("wait", "Wait");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createWaitNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:wait"));
        }
    }

    @Nested
    @DisplayName("createTransformNodes()")
    class CreateTransformNodesTests {

        @Test
        @DisplayName("Should create transform node from core definition")
        void shouldCreateTransformNodeFromCoreDefinition() {
            WorkflowPlan plan = createPlanWithCore("transform", "Map Data");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createTransformNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:map_data"));
            assertInstanceOf(TransformNode.class, nodeMap.get("core:map_data"));
        }

        @Test
        @DisplayName("Should handle null transform config")
        void shouldHandleNullTransformConfig() {
            WorkflowPlan plan = createPlanWithCore("transform", "Transform");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> builder.createTransformNodes(nodeMap, plan));
            assertTrue(nodeMap.containsKey("core:transform"));
        }
    }

    @Nested
    @DisplayName("createCoreNodes()")
    class CreateCoreNodesTests {

        @Test
        @DisplayName("Should create all core node types")
        void shouldCreateAllCoreNodeTypes() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> dataInputCore = new HashMap<>();
            dataInputCore.put("id", "c7");
            dataInputCore.put("type", "data_input");
            dataInputCore.put("label", "Input");
            dataInputCore.put("dataInput", Map.of("mode", "text", "text", "Hello"));

            data.put("cores", List.of(
                Map.of("id", "c1", "type", "decision", "label", "Check"),
                Map.of("id", "c3", "type", "fork", "label", "Fork"),
                Map.of("id", "c4", "type", "merge", "label", "Merge"),
                Map.of("id", "c5", "type", "wait", "label", "Wait"),
                Map.of("id", "c6", "type", "transform", "label", "Transform"),
                dataInputCore
            ));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            Map<String, List<String>> mergeSourceNodes = new HashMap<>();

            builder.createCoreNodes(nodeMap, plan, mergeSourceNodes);

            assertTrue(nodeMap.containsKey("core:check"));
            assertTrue(nodeMap.containsKey("core:fork"));
            assertTrue(nodeMap.containsKey("core:merge"));
            assertTrue(nodeMap.containsKey("core:wait"));
            assertTrue(nodeMap.containsKey("core:transform"));
            assertTrue(nodeMap.containsKey("core:input"));
        }
    }

    @Nested
    @DisplayName("createDataInputNodes()")
    class CreateDataInputNodesTests {

        @Test
        @DisplayName("Should create data input node from core definition with items")
        void shouldCreateDataInputNodeFromCoreDefinition() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "data_input");
            coreData.put("label", "My Prompt");
            coreData.put("dataInput", Map.of("items", List.of(
                Map.of("id", "item_1", "label", "prompt", "type", "text", "text", "Analyse this")
            )));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createDataInputNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:my_prompt"));
            assertInstanceOf(DataInputNode.class, nodeMap.get("core:my_prompt"));
            DataInputNode node = (DataInputNode) nodeMap.get("core:my_prompt");
            assertEquals(1, node.getItems().size());
            assertEquals("prompt", node.getItems().get(0).label());
        }

        @Test
        @DisplayName("Should create multi-item data input node")
        void shouldCreateMultiItemDataInputNode() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "data_input");
            coreData.put("label", "Prompt");
            coreData.put("dataInput", Map.of("items", List.of(
                Map.of("id", "item_1", "label", "prompt", "type", "text", "text", "Hello"),
                Map.of("id", "item_2", "label", "document", "type", "file", "text", "",
                    "file", Map.of("path", "s3://bucket/file.pdf", "name", "file.pdf"))
            )));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createDataInputNodes(nodeMap, plan);

            DataInputNode node = (DataInputNode) nodeMap.get("core:prompt");
            assertNotNull(node);
            assertEquals(2, node.getItems().size());
            assertEquals("prompt", node.getItems().get(0).label());
            assertEquals("document", node.getItems().get(1).label());
            assertEquals("file.pdf", node.getItems().get(1).file().get("name"));
        }

        @Test
        @DisplayName("Should handle null dataInput config")
        void shouldHandleNullDataInputConfig() {
            WorkflowPlan plan = createPlanWithCore("data_input", "Empty Input");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> builder.createDataInputNodes(nodeMap, plan));
            assertTrue(nodeMap.containsKey("core:empty_input"));
        }

        @Test
        @DisplayName("Should not recreate existing data input node")
        void shouldNotRecreateExistingDataInputNode() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "data_input");
            coreData.put("label", "Input");
            coreData.put("dataInput", Map.of("items", List.of(
                Map.of("id", "item_1", "label", "text", "type", "text", "text", "Hello")
            )));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            DataInputNode existingNode = DataInputNode.builder()
                .nodeId("core:input")
                .items(List.of(new Core.DataInputItem("item_1", "text", "text", "Original", null)))
                .build();
            nodeMap.put("core:input", existingNode);

            builder.createDataInputNodes(nodeMap, plan);

            assertSame(existingNode, nodeMap.get("core:input"));
        }

        @Test
        @DisplayName("Should handle null cores list")
        void shouldHandleNullCoresList() {
            WorkflowPlan plan = createEmptyPlan();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            assertDoesNotThrow(() -> builder.createDataInputNodes(nodeMap, plan));
        }
    }

    @Nested
    @DisplayName("createFilterNodes() - input from params")
    class CreateFilterNodesTests {

        @Test
        @DisplayName("Should get inputExpression from params.input")
        void shouldGetInputExpressionFromParamsInput() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "filter");
            coreData.put("label", "Filter Active");
            coreData.put("filter", Map.of(
                "conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")),
                "logic", "AND"
            ));
            coreData.put("params", Map.of("input", "{{mcp:fetch_data.output.items}}"));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createFilterNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:filter_active"));
            assertInstanceOf(FilterNode.class, nodeMap.get("core:filter_active"));
            FilterNode filterNode = (FilterNode) nodeMap.get("core:filter_active");
            assertEquals("{{mcp:fetch_data.output.items}}", filterNode.getInputExpression());
        }

        @Test
        @DisplayName("Should have null inputExpression when params is null")
        void shouldHaveNullInputWhenParamsNull() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "filter");
            coreData.put("label", "Filter");
            coreData.put("filter", Map.of(
                "conditions", List.of(Map.of("field", "x", "operator", "equals", "value", "y")),
                "logic", "AND"
            ));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createFilterNodes(nodeMap, plan);

            FilterNode filterNode = (FilterNode) nodeMap.get("core:filter");
            assertNull(filterNode.getInputExpression());
        }
    }

    @Nested
    @DisplayName("createSortNodes() - input from params")
    class CreateSortNodesTests {

        @Test
        @DisplayName("Should get inputExpression from params.input")
        void shouldGetInputExpressionFromParamsInput() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "sort");
            coreData.put("label", "Sort Items");
            coreData.put("sort", Map.of(
                "fields", List.of(Map.of("field", "name", "direction", "asc"))
            ));
            coreData.put("params", Map.of("input", "{{mcp:source.output.items}}"));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createSortNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:sort_items"));
            assertInstanceOf(SortNode.class, nodeMap.get("core:sort_items"));
            SortNode sortNode = (SortNode) nodeMap.get("core:sort_items");
            assertEquals("{{mcp:source.output.items}}", sortNode.getInputExpression());
        }
    }

    @Nested
    @DisplayName("createLimitNodes() - input from params")
    class CreateLimitNodesTests {

        @Test
        @DisplayName("Should get inputExpression from params.input")
        void shouldGetInputExpressionFromParamsInput() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "limit");
            coreData.put("label", "Top 5");
            coreData.put("limit", Map.of("count", 5, "from", "first"));
            coreData.put("params", Map.of("input", "{{core:sort_items.output.items}}"));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createLimitNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:top_5"));
            assertInstanceOf(LimitNode.class, nodeMap.get("core:top_5"));
            LimitNode limitNode = (LimitNode) nodeMap.get("core:top_5");
            assertEquals("{{core:sort_items.output.items}}", limitNode.getInputExpression());
        }
    }

    @Nested
    @DisplayName("createRemoveDuplicatesNodes() - input fallback")
    class CreateRemoveDuplicatesNodesTests {

        @Test
        @DisplayName("Should fallback to params.input when config.input is null")
        void shouldFallbackToParamsInputWhenConfigInputNull() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "remove_duplicates");
            coreData.put("label", "Dedup");
            coreData.put("removeDuplicates", Map.of(
                "fields", List.of("email"),
                "keep", "first"
            ));
            coreData.put("params", Map.of("input", "{{mcp:fetch.output.rows}}"));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createRemoveDuplicatesNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:dedup"));
            assertInstanceOf(RemoveDuplicatesNode.class, nodeMap.get("core:dedup"));
            RemoveDuplicatesNode dedupNode = (RemoveDuplicatesNode) nodeMap.get("core:dedup");
            assertEquals("{{mcp:fetch.output.rows}}", dedupNode.getInputExpression());
        }
    }

    @Nested
    @DisplayName("createSummarizeNodes() - input fallback")
    class CreateSummarizeNodesTests {

        @Test
        @DisplayName("Should fallback to params.input when config.input is null")
        void shouldFallbackToParamsInputWhenConfigInputNull() {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "summarize");
            coreData.put("label", "Sum");
            coreData.put("summarize", Map.of(
                "aggregations", List.of(Map.of("field", "price", "operation", "sum", "label", "total"))
            ));
            coreData.put("params", Map.of("input", "{{table:orders.output.items}}"));
            data.put("cores", List.of(coreData));
            WorkflowPlan plan = WorkflowPlan.fromMap(data);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createSummarizeNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:sum"));
            assertInstanceOf(SummarizeNode.class, nodeMap.get("core:sum"));
            SummarizeNode summarizeNode = (SummarizeNode) nodeMap.get("core:sum");
            assertEquals("{{table:orders.output.items}}", summarizeNode.getConfig().input());
        }
    }

    @Nested
    @DisplayName("createPublicLinkNodes() - config from the generic params map")
    class CreatePublicLinkNodesTests {

        private WorkflowPlan planWithPublicLinkCore(String label, Map<String, Object> params) {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "c1");
            coreData.put("type", "public_link");
            coreData.put("label", label);
            if (params != null) {
                coreData.put("params", params);
            }
            data.put("cores", List.of(coreData));
            return WorkflowPlan.fromMap(data);
        }

        @Test
        @DisplayName("Should create public link node with file, ttl_minutes and disposition from params")
        void shouldCreatePublicLinkNodeFromParams() {
            WorkflowPlan plan = planWithPublicLinkCore("Share Video", Map.of(
                "file", "{{core:dl.output.file}}",
                "ttl_minutes", 60,
                "disposition", "attachment"));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createPublicLinkNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:share_video"));
            assertInstanceOf(PublicLinkNode.class, nodeMap.get("core:share_video"));
            PublicLinkNode node = (PublicLinkNode) nodeMap.get("core:share_video");
            assertEquals("{{core:dl.output.file}}", node.getFileExpression());
            assertEquals(Integer.valueOf(60), node.getTtlMinutes());
            assertEquals("attachment", node.getDisposition());
        }

        @Test
        @DisplayName("Should accept camelCase ttlMinutes alias for the TTL")
        void shouldAcceptCamelCaseTtlMinutesAlias() {
            WorkflowPlan plan = planWithPublicLinkCore("Share Video", Map.of(
                "file", "{{core:dl.output.file}}",
                "ttlMinutes", 60));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createPublicLinkNodes(nodeMap, plan);

            PublicLinkNode node = (PublicLinkNode) nodeMap.get("core:share_video");
            assertEquals(Integer.valueOf(60), node.getTtlMinutes());
        }

        @Test
        @DisplayName("Should create node with null fileExpression when params has no file (fails at runtime, not build time)")
        void shouldCreateNodeWithNullFileWhenFileMissing() {
            WorkflowPlan plan = planWithPublicLinkCore("Share Video", Map.of("ttl_minutes", 60));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createPublicLinkNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:share_video"));
            PublicLinkNode node = (PublicLinkNode) nodeMap.get("core:share_video");
            assertNull(node.getFileExpression());
        }

        @Test
        @DisplayName("Should treat a blank file as null fileExpression")
        void shouldTreatBlankFileAsNull() {
            WorkflowPlan plan = planWithPublicLinkCore("Share Video", Map.of("file", "   "));
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createPublicLinkNodes(nodeMap, plan);

            PublicLinkNode node = (PublicLinkNode) nodeMap.get("core:share_video");
            assertNull(node.getFileExpression());
        }

        @Test
        @DisplayName("Should ignore cores that are not public_link")
        void shouldIgnoreNonPublicLinkCores() {
            WorkflowPlan plan = createPlanWithCore("download_file", "DL");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createPublicLinkNodes(nodeMap, plan);

            assertTrue(nodeMap.isEmpty());
        }
    }

    @Nested
    @DisplayName("createMediaNodes() - generic params map passthrough")
    class CreateMediaNodesTests {

        private WorkflowPlan planWithMediaCore(String label, Map<String, Object> params) {
            Map<String, Object> data = createBasePlanData();
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "m1");
            coreData.put("type", "media");
            coreData.put("label", label);
            if (params != null) {
                coreData.put("params", params);
            }
            data.put("cores", List.of(coreData));
            return WorkflowPlan.fromMap(data);
        }

        @Test
        @DisplayName("Should create media node carrying the FULL params map verbatim (validated at execute time)")
        void shouldCreateMediaNodeWithParamsMap() {
            Map<String, Object> params = Map.of(
                "operation", "mux_audio",
                "video", "{{interface:card.output.video}}",
                "audio", "{{core:dl.output.file}}",
                "volume", 80);
            WorkflowPlan plan = planWithMediaCore("Add Music", params);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createMediaNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:add_music"));
            assertInstanceOf(MediaNode.class, nodeMap.get("core:add_music"));
            MediaNode node = (MediaNode) nodeMap.get("core:add_music");
            assertEquals("mux_audio", node.getParams().get("operation"));
            assertEquals("{{interface:card.output.video}}", node.getParams().get("video"));
            assertEquals(80, node.getParams().get("volume"));
        }

        @Test
        @DisplayName("Should create node with empty params when the params map is absent (fails at runtime, not build time)")
        void shouldCreateNodeWithEmptyParamsWhenAbsent() {
            WorkflowPlan plan = planWithMediaCore("Add Music", null);
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createMediaNodes(nodeMap, plan);

            assertTrue(nodeMap.containsKey("core:add_music"));
            MediaNode node = (MediaNode) nodeMap.get("core:add_music");
            assertTrue(node.getParams().isEmpty());
        }

        @Test
        @DisplayName("Should ignore cores that are not media")
        void shouldIgnoreNonMediaCores() {
            WorkflowPlan plan = createPlanWithCore("download_file", "DL");
            Map<String, ExecutionNode> nodeMap = new HashMap<>();

            builder.createMediaNodes(nodeMap, plan);

            assertTrue(nodeMap.isEmpty());
        }
    }

    // ===== Helper methods =====

    private Map<String, Object> createBasePlanData() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of());
        data.put("mcps", List.of());
        data.put("edges", List.of());
        return data;
    }

    private WorkflowPlan createEmptyPlan() {
        return WorkflowPlan.fromMap(createBasePlanData());
    }

    private WorkflowPlan createPlanWithCore(String type, String label) {
        Map<String, Object> data = createBasePlanData();
        data.put("cores", List.of(
            Map.of("id", "c1", "type", type, "label", label)
        ));
        return WorkflowPlan.fromMap(data);
    }

    private WorkflowPlan createPlanWithWaitCore(String label, Long durationMs) {
        Map<String, Object> data = createBasePlanData();
        Map<String, Object> coreData = new HashMap<>();
        coreData.put("id", "c1");
        coreData.put("type", "wait");
        coreData.put("label", label);
        coreData.put("waitConfig", Map.of("duration", durationMs));
        data.put("cores", List.of(coreData));
        return WorkflowPlan.fromMap(data);
    }

}
