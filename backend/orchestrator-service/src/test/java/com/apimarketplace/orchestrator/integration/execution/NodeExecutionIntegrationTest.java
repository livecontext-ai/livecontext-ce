package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.nodes.AggregateNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.DecisionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.EndNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.HttpRequestNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ResponseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.StepNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExitNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.TransformNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.TriggerNode;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for individual node executors with Spring context.
 *
 * <p>Tests each node type's execution logic with real Spring beans
 * and injected services. Only external HTTP calls are mocked.
 */
@IntegrationTest
@DisplayName("Node Execution Integration Tests")
class NodeExecutionIntegrationTest {

    @Autowired(required = false)
    private V2TemplateAdapter templateAdapter;

    @Autowired(required = false)
    private TemplateEngine templateEngine;

    @Autowired(required = false)
    private SplitContextManager splitContextManager;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private ToolsGateway toolsGateway;

    private ExecutionContext baseContext;

    @BeforeEach
    void setUp() {
        // Create base execution context for tests
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "TestUser");
        triggerData.put("value", 42);
        triggerData.put("items", List.of("a", "b", "c"));

        WorkflowPlan plan = buildMinimalPlan();
        baseContext = ExecutionContext.create(
            "run-" + UUID.randomUUID().toString().substring(0, 8),
            "wfr-test",
            "tenant-test",
            "item-1",
            0,
            triggerData,
            plan);
    }

    // =========================================================================
    // TRIGGER NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("TriggerNode execution")
    class TriggerNodeTests {

        @Test
        @DisplayName("Should execute trigger and produce output with trigger data")
        void shouldExecuteTrigger() {
            // Given
            TriggerNode triggerNode = new TriggerNode("trigger:start", "t1");

            // When
            NodeExecutionResult result = triggerNode.execute(baseContext);

            // Then
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertFalse(result.output().isEmpty(), "Trigger output should not be empty");
            assertEquals("t1", result.output().get("trigger_id"));
            assertEquals("item-1", result.output().get("item_id"));
            assertEquals(0, result.output().get("item_index"));
        }

        @Test
        @DisplayName("Should always be executable (canExecute returns true)")
        void shouldAlwaysBeExecutable() {
            TriggerNode triggerNode = new TriggerNode("trigger:start", "t1");
            assertTrue(triggerNode.canExecute(baseContext));
        }

        @Test
        @DisplayName("Should include trigger data fields in output")
        void shouldIncludeTriggerDataInOutput() {
            TriggerNode triggerNode = new TriggerNode("trigger:start", "t1");
            NodeExecutionResult result = triggerNode.execute(baseContext);

            assertEquals("TestUser", result.output().get("name"));
            assertEquals(42, result.output().get("value"));
        }
    }

    // =========================================================================
    // HTTP REQUEST NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("HttpRequestNode execution")
    class HttpRequestNodeTests {

        @Test
        @DisplayName("Should execute GET request with mocked RestTemplate")
        void shouldExecuteGetRequest() {
            // Given
            HttpRequestNode httpNode = HttpRequestNode.builder()
                .nodeId("core:http_call")
                .method("GET")
                .urlExpression("https://example.com/data")
                .build();
            httpNode.setRestTemplate(restTemplate);

            when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"result\":\"ok\"}", HttpStatus.OK));

            // When
            NodeExecutionResult result = httpNode.execute(baseContext);

            // Then
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertTrue((Boolean) result.output().get("success"));
            assertEquals(200, result.output().get("status"));
        }

        @Test
        @DisplayName("Should return failure when RestTemplate is not configured")
        void shouldReturnFailureWithoutRestTemplate() {
            // Given: Node without RestTemplate
            HttpRequestNode httpNode = HttpRequestNode.builder()
                .nodeId("core:http_call")
                .method("GET")
                .urlExpression("https://example.com/data")
                .build();

            // When
            NodeExecutionResult result = httpNode.execute(baseContext);

            // Then
            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("RestTemplate not configured"));
        }

        @Test
        @DisplayName("Should return failure when URL is missing")
        void shouldReturnFailureWithMissingUrl() {
            HttpRequestNode httpNode = HttpRequestNode.builder()
                .nodeId("core:http_call")
                .method("GET")
                .urlExpression("")
                .build();
            httpNode.setRestTemplate(restTemplate);

            NodeExecutionResult result = httpNode.execute(baseContext);

            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.errorMessage().isPresent());
        }

        @Test
        @DisplayName("Should execute POST request with JSON body")
        void shouldExecutePostRequest() {
            HttpRequestNode httpNode = HttpRequestNode.builder()
                .nodeId("core:http_post")
                .method("POST")
                .urlExpression("https://example.com/items")
                .bodyType("json")
                .bodyExpression("{\"name\":\"test\"}")
                .build();
            httpNode.setRestTemplate(restTemplate);

            when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"id\":1}", HttpStatus.CREATED));

            NodeExecutionResult result = httpNode.execute(baseContext);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(201, result.output().get("status"));
        }

        @Test
        @DisplayName("Should accept services from ServiceRegistry")
        void shouldAcceptServicesFromRegistry() {
            HttpRequestNode httpNode = HttpRequestNode.builder()
                .nodeId("core:http_call")
                .method("GET")
                .urlExpression("https://example.com/data")
                .build();

            ServiceRegistry registry = ServiceRegistry.builder()
                .restTemplate(restTemplate)
                .build();

            httpNode.acceptServices(registry);

            when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

            NodeExecutionResult result = httpNode.execute(baseContext);
            assertEquals(NodeStatus.COMPLETED, result.status());
        }
    }

    // =========================================================================
    // AGGREGATE NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("AggregateNode execution")
    class AggregateNodeTests {

        @Test
        @DisplayName("Should collect single item and produce aggregated output")
        void shouldCollectAndAggregate() {
            // Given: Aggregate with one field, expecting 1 item
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .addField("names", "{{name}}")
                .build();

            // When: Execute (single item, totalItems defaults to 1)
            NodeExecutionResult result = aggregateNode.execute(baseContext);

            // Then
            assertNotNull(result);
            // With 1 item and 1 expected, should complete immediately
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(1, result.output().get("aggregated_count"));
        }

        @Test
        @DisplayName("Should always be executable")
        void shouldAlwaysBeExecutable() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .addField("values", "{{value}}")
                .build();

            assertTrue(aggregateNode.canExecute(baseContext));
            assertTrue(aggregateNode.isAggregateNode());
        }
    }

    // =========================================================================
    // TRANSFORM NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("TransformNode execution")
    class TransformNodeTests {

        @Test
        @DisplayName("Should execute transform and produce canonical {transformed, evaluations} output")
        void shouldExecuteTransform() {
            // Given: Transform with mappings (expressions stored as-is without templateAdapter)
            TransformNode transformNode = TransformNode.builder()
                .nodeId("core:map_data")
                .mappings(List.of(
                    new Core.TransformMapping("greeting", "Hello World")))
                .build();

            // When
            NodeExecutionResult result = transformNode.execute(baseContext);

            // Then
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("TRANSFORM", result.output().get("node_type"));
            // Mapping value lives under 'transformed' (canonical runtime == DB == doc shape).
            // Without templateAdapter, expression is returned as-is.
            @SuppressWarnings("unchecked")
            Map<String, Object> transformed = (Map<String, Object>) result.output().get("transformed");
            assertNotNull(transformed, "Output must contain 'transformed' map");
            assertEquals("Hello World", transformed.get("greeting"));
        }

        @Test
        @DisplayName("Should handle empty mappings gracefully")
        void shouldHandleEmptyMappings() {
            TransformNode transformNode = TransformNode.builder()
                .nodeId("core:empty_transform")
                .mappings(List.of())
                .build();

            NodeExecutionResult result = transformNode.execute(baseContext);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("TRANSFORM", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should handle null mapping label gracefully")
        void shouldHandleNullLabel() {
            TransformNode transformNode = TransformNode.builder()
                .nodeId("core:null_label")
                .mappings(List.of(
                    new Core.TransformMapping(null, "some_value")))
                .build();

            NodeExecutionResult result = transformNode.execute(baseContext);

            // Should not fail - null labels are skipped
            assertEquals(NodeStatus.COMPLETED, result.status());
        }
    }

    // =========================================================================
    // RESPONSE NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("ResponseNode execution")
    class ResponseNodeTests {

        @Test
        @DisplayName("Should execute response and produce message output")
        void shouldExecuteResponse() {
            // Given
            ResponseNode responseNode = ResponseNode.builder()
                .nodeId("core:send_reply")
                .messageTemplate("Processing complete for item")
                .build();

            // When
            NodeExecutionResult result = responseNode.execute(baseContext);

            // Then
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("RESPONSE", result.output().get("node_type"));
            assertEquals("Processing complete for item", result.output().get("message"));
            assertNotNull(result.output().get("sent_at"));
        }

        @Test
        @DisplayName("Should handle empty message template")
        void shouldHandleEmptyMessage() {
            ResponseNode responseNode = ResponseNode.builder()
                .nodeId("core:empty_response")
                .messageTemplate("")
                .build();

            NodeExecutionResult result = responseNode.execute(baseContext);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("", result.output().get("message"));
        }
    }

    // =========================================================================
    // EXIT NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("ExitNode execution")
    class ExitNodeTests {

        @Test
        @DisplayName("Should execute exit and produce termination output")
        void shouldExecuteExit() {
            // Given
            ExitNode exitNode = ExitNode.builder()
                .nodeId("core:halt")
                .reason("User requested cancellation")
                .build();

            // When
            NodeExecutionResult result = exitNode.execute(baseContext);

            // Then
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(true, result.output().get("exited"));
            assertEquals("User requested cancellation", result.output().get("reason"));
            assertEquals("EXIT", result.output().get("node_type"));
        }

        @Test
        @DisplayName("Should return no successors")
        void shouldReturnNoSuccessors() {
            ExitNode exitNode = ExitNode.builder()
                .nodeId("core:halt")
                .reason("Done")
                .build();

            NodeExecutionResult result = exitNode.execute(baseContext);
            assertTrue(exitNode.getNextNodes(result).isEmpty(),
                "Exit node should have no successors");
        }

        @Test
        @DisplayName("Should report as exit node")
        void shouldReportAsExitNode() {
            ExitNode exitNode = ExitNode.builder()
                .nodeId("core:halt")
                .reason("Done")
                .build();

            assertTrue(exitNode.isExitNode());
        }
    }

    // =========================================================================
    // END NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("EndNode execution")
    class EndNodeTests {

        @Test
        @DisplayName("Should execute end node successfully")
        void shouldExecuteEndNode() {
            EndNode endNode = new EndNode("end:workflow");

            NodeExecutionResult result = endNode.execute(baseContext);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals("completed", result.output().get("status"));
        }

        @Test
        @DisplayName("Should have no successors")
        void shouldHaveNoSuccessors() {
            EndNode endNode = new EndNode("end:workflow");
            NodeExecutionResult result = endNode.execute(baseContext);

            assertTrue(endNode.getNextNodes(result).isEmpty());
            assertTrue(endNode.isEndNode());
        }
    }

    // =========================================================================
    // STEP NODE TESTS
    // =========================================================================

    @Nested
    @DisplayName("StepNode execution")
    class StepNodeTests {

        @Test
        @DisplayName("Should execute in passthrough mode without ToolsGateway")
        void shouldExecutePassthroughWithoutGateway() {
            // Given: StepNode without ToolsGateway
            Step stepConfig = new Step("tool-id-1", "mcp", "Process Data", null, Map.of(), null, null, null);
            StepNode stepNode = new StepNode("mcp:process_data", stepConfig);

            // When
            NodeExecutionResult result = stepNode.execute(baseContext);

            // Then: Should succeed in passthrough mode
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertTrue((Boolean) result.output().get("passthrough"),
                "Should be in passthrough mode");
        }

        /**
         * E2E regression for the originating "Gemini generationConfig" bug. Builds an MCP step
         * whose params contain {@code "generationConfig": "{{json('{...}')}}"} (the user-visible
         * fix), runs the step against the real Spring-loaded {@code V2TemplateAdapter} and
         * {@code TemplateEngine}, and asserts that the {@code ToolsGateway} mock receives a
         * REAL Map (not the JSON string, not Java toString {a=1}). This exercises:
         *   - Real SpEL parser dispatch to the registered {@code json()} function
         *   - Pure-expression short-circuit returning a typed Map
         *   - V2TemplateAdapter wiring the typed value into {@code inputData}
         *   - StepNode forwarding {@code inputData} verbatim to the gateway
         */
        @Test
        @DisplayName("E2E: {{json('{...}')}} in params delivers a typed Map to ToolsGateway")
        void e2eJsonFunctionDeliversTypedMapToGateway() {
            // Step with the canonical user-visible workaround: wrap a JSON literal in json().
            Map<String, Object> params = new HashMap<>();
            params.put("generationConfig", "{{json('{\"responseModalities\":[\"IMAGE\"]}')}}");
            params.put("model", "gemini-2.0-flash");

            Step stepConfig = new Step("gemini-tool", "mcp", "Generate Image", null,
                params, null, null, null);
            StepNode stepNode = new StepNode("mcp:gemini_image", stepConfig);

            // Inject the real Spring-loaded V2TemplateAdapter via ServiceRegistry
            ServiceRegistry registry = ServiceRegistry.builder()
                .templateAdapter(templateAdapter)
                .toolsGateway(toolsGateway)
                .build();
            stepNode.acceptServices(registry);

            // Mock gateway: capture what StepNode actually sent
            when(toolsGateway.executeTool(any(), any(), anyString(), any()))
                .thenReturn(new com.apimarketplace.orchestrator.services.interfaces.ExecutionResult(
                    true, Map.of("image_url", "https://example.com/img.png"), null, null));

            // When
            NodeExecutionResult result = stepNode.execute(baseContext);

            // Then: gateway received a typed Map for generationConfig, not a String
            ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
            verify(toolsGateway).executeTool(any(), inputCaptor.capture(), anyString(), any());

            Map<String, Object> sentInput = inputCaptor.getValue();
            Object generationConfig = sentInput.get("generationConfig");

            assertInstanceOf(Map.class, generationConfig,
                "generationConfig must be a typed Map, not a JSON String - was " +
                (generationConfig == null ? "null" : generationConfig.getClass().getSimpleName()));

            @SuppressWarnings("unchecked")
            Map<String, Object> cfgMap = (Map<String, Object>) generationConfig;
            assertEquals(List.of("IMAGE"), cfgMap.get("responseModalities"),
                "Nested array inside the JSON must round-trip as a typed List");

            // Scalar param stays a String
            assertEquals("gemini-2.0-flash", sentInput.get("model"));

            assertEquals(NodeStatus.COMPLETED, result.status());
        }

        /**
         * Companion to the e2e test above: when the JSON inside json(...) is malformed, the
         * step must FAIL with a {@link com.apimarketplace.orchestrator.services.expression.JsonParseException}
         * whose message names the field. This validates the rethrow path through SpelEvaluator
         * → TemplateEngine → V2TemplateAdapter → StepNode.
         */
        @Test
        @DisplayName("E2E: malformed json('...') fails the step with field-name in error")
        void e2eMalformedJsonFailsWithFieldName() {
            Map<String, Object> params = new HashMap<>();
            params.put("generationConfig", "{{json('{not json')}}");

            Step stepConfig = new Step("gemini-tool", "mcp", "Generate Image", null,
                params, null, null, null);
            StepNode stepNode = new StepNode("mcp:gemini_image", stepConfig);

            ServiceRegistry registry = ServiceRegistry.builder()
                .templateAdapter(templateAdapter)
                .toolsGateway(toolsGateway)
                .build();
            stepNode.acceptServices(registry);

            // When
            NodeExecutionResult result = stepNode.execute(baseContext);

            // Then: step FAILED, error message names the field, gateway never called
            assertEquals(NodeStatus.FAILED, result.status(),
                "Malformed JSON in json() must fail the step, not silently null");
            assertTrue(result.errorMessage().isPresent());
            String err = result.errorMessage().get();
            assertTrue(err.contains("generationConfig"),
                "Error message must name the failing field, was: " + err);

            verify(toolsGateway, never())
                .executeTool(any(), any(), anyString(), any());
        }
    }

    // =========================================================================
    // TEMPLATE ADAPTER TESTS
    // =========================================================================

    @Nested
    @DisplayName("V2TemplateAdapter split context")
    class V2TemplateAdapterSplitContextTests {

        @Test
        @DisplayName("Resolves documented item aliases from split global data")
        void resolvesDocumentedItemAliasesFromSplitGlobalData() {
            Map<String, Object> item = Map.of(
                "name", "Ada",
                "records", List.of(Map.of("id", "a1", "score", 4))
            );

            ExecutionContext splitContext = baseContext
                .withGlobalData("item", item)
                .withGlobalData("index", 2)
                .withGlobalData("current_split_id", "core:split_people:0")
                .withGlobalData("core:split_people:0.current_item", item)
                .withGlobalData("core:split_people:0.current_index", 2);

            Map<String, Object> resolved = templateAdapter.resolveTemplates(Map.of(
                "itemName", "{{item.name}}",
                "currentItemName", "{{current_item.name}}",
                "itemRecords", "{{item.records}}",
                "index", "{{index}}",
                "currentIndex", "{{current_index}}"
            ), splitContext);

            assertEquals("Ada", resolved.get("itemName"),
                "{{item.name}} must resolve inside split body nodes");
            assertEquals("Ada", resolved.get("currentItemName"),
                "{{current_item.name}} must resolve to the same split item");
            assertEquals(item.get("records"), resolved.get("itemRecords"),
                "{{item.records}} must preserve the typed list value");
            assertEquals(2, resolved.get("index"));
            assertEquals(2, resolved.get("currentIndex"));
        }

        @Test
        @DisplayName("Resolves documented set output fields from wrapped core step outputs")
        void resolvesDocumentedSetOutputFieldsFromWrappedCoreStepOutputs() {
            Map<String, Object> fields = Map.of(
                "scenario", "auto-sbs-contract",
                "threshold", 2
            );
            ExecutionContext contextWithSetOutput = baseContext.withResult(
                "core:prepare_params",
                NodeExecutionResult.success("core:prepare_params", Map.of(
                    "fields", fields,
                    "output", fields,
                    "keep_only_set", true,
                    "count", 2
                ))
            );

            Map<String, Object> resolved = templateAdapter.resolveTemplates(Map.of(
                "scenario", "{{core:prepare_params.output.fields.scenario}}",
                "threshold", "{{core:prepare_params.output.fields.threshold}}",
                "shorthandScenario", "{{core:prepare_params.fields.scenario}}",
                "outputScenario", "{{core:prepare_params.output.scenario}}"
            ), contextWithSetOutput);

            assertEquals("auto-sbs-contract", resolved.get("scenario"),
                "{{core:set.output.fields.*}} must resolve through the runtime output wrapper");
            assertEquals(2, resolved.get("threshold"));
            assertEquals("auto-sbs-contract", resolved.get("shorthandScenario"));
            assertEquals("auto-sbs-contract", resolved.get("outputScenario"));
        }

        @Test
        @DisplayName("Resolves documented set output fields from persisted flat core step outputs")
        void resolvesDocumentedSetOutputFieldsFromPersistedFlatCoreStepOutputs() {
            Map<String, Object> fields = Map.of(
                "scenario", "auto-sbs-contract",
                "threshold", 2
            );
            ExecutionContext contextWithPersistedSetOutput = baseContext.withStepOutput(
                "core:prepare_params",
                Map.of(
                    "fields", fields,
                    "output", fields,
                    "keep_only_set", true,
                    "count", 2
                )
            );

            Map<String, Object> resolved = templateAdapter.resolveTemplates(Map.of(
                "scenario", "{{core:prepare_params.output.fields.scenario}}",
                "threshold", "{{core:prepare_params.output.fields.threshold}}"
            ), contextWithPersistedSetOutput);

            assertEquals("auto-sbs-contract", resolved.get("scenario"),
                "{{core:set.output.fields.*}} must resolve after step outputs are reloaded from persistence");
            assertEquals(2, resolved.get("threshold"));
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private WorkflowPlan buildMinimalPlan() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "test-plan");
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));
        data.put("mcps", List.of(
            Map.of("id", "s1", "label", "Step", "alias", "step", "tool_name", "mock")));
        data.put("edges", List.of(
            Map.of("from", "trigger:start", "to", "mcp:step")));
        data.put("agents", List.of());
        data.put("cores", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());
        return WorkflowPlan.fromMap(data);
    }
}
