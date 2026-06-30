package com.apimarketplace.orchestrator.services.persistence.schema;

import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that GenericOutputSchemaMapper correctly transforms output for all node types.
 * Covers generic field mapping, customTransform, aliases, defaults, sentinels, and coercion.
 */
@DisplayName("GenericOutputSchemaMapper")
class GenericOutputSchemaMapperTest {

    private GenericOutputSchemaMapper genericMapper;

    private static final List<NodeSpec> ALL_SPECS = List.of(
        new ExitNodeSpec(), new SortNodeSpec(), new MergeNodeSpec(),
        new RemoveDuplicatesNodeSpec(), new FilterNodeSpec(),
        new DecisionNodeSpec(), new SwitchNodeSpec(), new ForkNodeSpec(),
        new SplitNodeSpec(), new LoopNodeSpec(), new WaitNodeSpec(),
        new FindNodeSpec(), new AgentNodeSpec(), new GuardrailNodeSpec(),
        new ClassifyNodeSpec(), new HttpRequestNodeSpec(),
        new RespondToWebhookNodeSpec(), new ManualTriggerNodeSpec(),
        new ChatTriggerNodeSpec(), new WebhookTriggerNodeSpec(),
        new ScheduleTriggerNodeSpec(), new GetRowsNodeSpec(),
        new InsertRowNodeSpec(), new UpdateRowNodeSpec(), new DeleteRowNodeSpec(),
        new CreateColumnNodeSpec(), new SendEmailNodeSpec(),
        new SubWorkflowNodeSpec(), new ResponseNodeSpec(),
        new CompressionNodeSpec(), new DateTimeNodeSpec(), new CryptoJwtNodeSpec(),
        new XmlNodeSpec(), new RssNodeSpec(), new CodeNodeSpec(),
        new CompareDatasetsNodeSpec(), new ExtractFromFileNodeSpec(),
        new ConvertToFileNodeSpec(), new ApprovalNodeSpec(),
        new OptionNodeSpec(), new LimitNodeSpec(), new SummarizeNodeSpec(),
        new FormTriggerNodeSpec(), new TableTriggerNodeSpec(),
        new WorkflowTriggerNodeSpec(), new DownloadFileNodeSpec(),
        new TransformNodeSpec(), new AggregateNodeSpec(),
        new InterfaceNodeSpec(), new HtmlExtractNodeSpec()
    );

    @BeforeEach
    void setUp() {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(ALL_SPECS);
        registry.init();
        genericMapper = new GenericOutputSchemaMapper(registry);
    }

    @Nested
    @DisplayName("canHandle()")
    class CanHandle {

        @Test
        @DisplayName("Should return true for all registered types")
        void shouldReturnTrueForAllRegistered() {
            for (NodeSpec spec : ALL_SPECS) {
                assertTrue(genericMapper.canHandle(spec.definition().nodeType()),
                    "Should handle " + spec.definition().nodeType());
            }
        }

        @Test
        @DisplayName("Should return true case-insensitively")
        void shouldBeCaseInsensitive() {
            assertTrue(genericMapper.canHandle("exit"));
            assertTrue(genericMapper.canHandle("Sort"));
            assertTrue(genericMapper.canHandle("decision"));
            assertTrue(genericMapper.canHandle("AGENT"));
        }

        @Test
        @DisplayName("Should return false for unknown type")
        void shouldReturnFalseForUnknown() {
            assertFalse(genericMapper.canHandle("UNKNOWN_NODE"));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(genericMapper.canHandle(null));
        }
    }

    @Nested
    @DisplayName("transform() - null/edge cases")
    class EdgeCases {

        @Test
        void shouldReturnNullForNullType() {
            assertNull(genericMapper.transform(null, Map.of()));
        }

        @Test
        void shouldReturnNullForNullOutput() {
            assertNull(genericMapper.transform("STOP", null));
        }

        @Test
        void shouldReturnNullForUnregistered() {
            assertNull(genericMapper.transform("UNKNOWN", Map.of()));
        }
    }

    // ======= Simple nodes - generic field mapping =======

    @Nested
    @DisplayName("EXIT node")
    class ExitTests {

        @Test
        void shouldTransformCompleteInput() {
            Map<String, Object> input = new HashMap<>();
            input.put("exited_at", "2024-01-01T12:00:00Z");
            input.put("reason", "user_cancelled");
            input.put("status", "cancelled");

            Map<String, Object> result = genericMapper.transform("EXIT", input);

            assertEquals("2024-01-01T12:00:00Z", result.get("exited_at"));
            assertEquals("user_cancelled", result.get("reason"));
            assertEquals("cancelled", result.get("status"));
        }

        @Test
        void shouldApplyDefaults() {
            Map<String, Object> result = genericMapper.transform("EXIT", new HashMap<>());

            assertNotNull(result.get("exited_at")); // __NOW__
            assertEquals("branch_exited", result.get("reason"));
            assertEquals("exited", result.get("status"));
        }
    }

    @Nested
    @DisplayName("DECISION node")
    class DecisionTests {

        @Test
        void shouldTransformComplete() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_branch", "if");
            input.put("skipped_branches", List.of("else"));
            input.put("evaluations", List.of(Map.of("result", true)));

            Map<String, Object> result = genericMapper.transform("DECISION", input);

            assertEquals("if", result.get("selected_branch"));
            assertEquals(List.of("else"), result.get("skipped_branches"));
            assertEquals(1, ((List<?>) result.get("evaluations")).size());
        }

        @Test
        void shouldApplyDefaults() {
            Map<String, Object> result = genericMapper.transform("DECISION", new HashMap<>());

            assertFalse(result.containsKey("selected_branch")); // conditional
            assertEquals(List.of(), result.get("skipped_branches"));
            assertEquals(List.of(), result.get("evaluations"));
        }
    }

    @Nested
    @DisplayName("AGENT node - alias resolution")
    class ForkTests {

        @Test
        @DisplayName("Should persist contract keys for branch metadata")
        void shouldPersistContractKeysForBranchMetadata() {
            List<Map<String, Object>> branches = List.of(
                Map.of("index", 0, "id", "branch_0", "label", "A", "target_count", 1),
                Map.of("index", 1, "id", "branch_1", "label", "B", "target_count", 1)
            );
            Map<String, Object> input = new HashMap<>();
            input.put("branch_count", 2);
            input.put("branches", branches);

            Map<String, Object> result = genericMapper.transform("FORK", input);

            assertEquals(2, result.get("branch_count"));
            assertEquals(branches, result.get("branches"));
            assertFalse(result.containsKey("forked_branches"));
        }
    }

    @Nested
    @DisplayName("SWITCH node")
    class SwitchTests {

        @Test
        @DisplayName("Aliases resolve to canonical keys + non-spec fields stripped")
        void shouldPersistSwitchContractFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_case_label", "Gold");
            input.put("selected_case_index", 1);
            input.put("skipped_cases", List.of("case_0", "default"));
            input.put("evaluations", List.of(Map.of("result", true)));
            input.put("switch_value", "gold");
            input.put("matched_value", "gold");

            Map<String, Object> result = genericMapper.transform("SWITCH", input);

            assertEquals("Gold", result.get("selected_branches"));
            assertEquals(1, result.get("selected_case_index"));
            assertEquals(List.of("case_0", "default"), result.get("skipped_branches"));
            assertEquals(1, ((List<?>) result.get("evaluations")).size());

            assertFalse(result.containsKey("selected_case"));
            assertFalse(result.containsKey("selected_case_label"));
            assertFalse(result.containsKey("skipped_cases"));
            assertFalse(result.containsKey("switch_value"));
            assertFalse(result.containsKey("matched_value"));
        }
    }

    @Nested
    @DisplayName("AGENT node alias resolution")
    class AgentTests {

        @Test
        void shouldKeepToolCallCountAndDetailSeparate() {
            Map<String, Object> input = new HashMap<>();
            input.put("response", "Hello");
            input.put("tool_calls", 1);
            input.put("tool_calls_detail", List.of("call1"));
            input.put("iterations", 3);

            Map<String, Object> result = genericMapper.transform("AGENT", input);

            assertEquals("Hello", result.get("response"));
            assertEquals(1, result.get("tool_calls"));
            assertEquals(List.of("call1"), result.get("tool_calls_detail"));
            assertEquals(3, result.get("iterations_used"));
        }
    }

    // split_item_count is injected by SplitAwareNodeExecutor on every node in a split
    // context. It must survive persistence on CLASSIFY/DECISION/SWITCH because
    // ReadyNodeCalculator reads the persisted output to decide whether to traverse
    // ALL branches (vs only the last item's selected branch). Dropping it broke
    // parallel branch execution for async classify in production (2026-04-14).
    @Nested
    @DisplayName("split_item_count preservation across branching specs")
    class SplitItemCountPreservation {

        @Test
        void classifyShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_category", "finance");
            input.put("selected_category_index", 0);
            input.put("split_item_count", 10);

            Map<String, Object> result = genericMapper.transform("CLASSIFY", input);

            assertEquals(10, result.get("split_item_count"));
            assertEquals("finance", result.get("selected_category"));
            assertEquals(0, result.get("selected_category_index"));
        }

        @Test
        void decisionShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_branch", "if");
            input.put("split_item_count", 5);

            Map<String, Object> result = genericMapper.transform("DECISION", input);

            assertEquals(5, result.get("split_item_count"));
        }

        // selected_branch_index is read by DecisionNode.getNextNodes() and
        // getSkippedChildNodes() from the persisted output to determine which
        // branch was selected. Dropping it breaks resume/restart/run-clone and
        // step-by-step paths (ALL branches treated as skipped).
        @Test
        void decisionShouldPreserveSelectedBranchIndex() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_branch", "elsif_0");
            input.put("selected_branch_index", 1);

            Map<String, Object> result = genericMapper.transform("DECISION", input);

            assertEquals(1, result.get("selected_branch_index"));
            assertEquals("elsif_0", result.get("selected_branch"));
        }

        @Test
        void decisionShouldOmitSelectedBranchIndexWhenAbsent() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_branch", "if");

            Map<String, Object> result = genericMapper.transform("DECISION", input);

            assertFalse(result.containsKey("selected_branch_index"));
        }

        @Test
        void switchShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_case_label", "case_a");
            input.put("split_item_count", 7);

            Map<String, Object> result = genericMapper.transform("SWITCH", input);

            assertEquals(7, result.get("split_item_count"));
        }

        @Test
        void optionShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_choice", "choice-a");
            input.put("selected_choice_index", 0);
            input.put("split_item_count", 6);

            Map<String, Object> result = genericMapper.transform("OPTION", input);

            assertEquals(6, result.get("split_item_count"));
            assertEquals(0, result.get("selected_choice_index"));
        }

        @Test
        void agentShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("response", "ok");
            input.put("split_item_count", 3);

            Map<String, Object> result = genericMapper.transform("AGENT", input);

            assertEquals(3, result.get("split_item_count"));
        }

        @Test
        void guardrailShouldPreserveSplitItemCount() {
            Map<String, Object> input = new HashMap<>();
            input.put("passed", true);
            input.put("split_item_count", 4);

            Map<String, Object> result = genericMapper.transform("GUARDRAIL", input);

            assertEquals(4, result.get("split_item_count"));
        }

        @Test
        void shouldOmitSplitItemCountWhenAbsent() {
            Map<String, Object> input = new HashMap<>();
            input.put("selected_category", "x");

            Map<String, Object> result = genericMapper.transform("CLASSIFY", input);

            // Conditional field (no default) → absent when not supplied
            assertFalse(result.containsKey("split_item_count"));
        }
    }

    @Nested
    @DisplayName("HTTP_REQUEST node")
    class HttpRequestTests {

        @Test
        void shouldTransformCompleteHttpResponse() {
            Map<String, Object> input = new HashMap<>();
            input.put("success", true);
            input.put("statusCode", 200);
            input.put("status_text", "OK");
            input.put("body", Map.of("key", "value"));
            input.put("headers", Map.of("Content-Type", "application/json"));

            Map<String, Object> result = genericMapper.transform("HTTP_REQUEST", input);

            assertEquals(true, result.get("success"));
            assertEquals(200, result.get("status"));
            assertEquals("OK", result.get("statusText"));
            assertEquals(Map.of("key", "value"), result.get("data"));
        }

        @Test
        void shouldResolveErrorAlias() {
            Map<String, Object> input = new HashMap<>();
            input.put("errorMessage", "Connection refused");

            Map<String, Object> result = genericMapper.transform("HTTP_REQUEST", input);

            assertEquals("Connection refused", result.get("error"));
        }
    }

    @Nested
    @DisplayName("GET_ROWS node")
    class GetRowsTests {

        @Test
        void shouldTransformComplete() {
            Map<String, Object> input = new HashMap<>();
            input.put("operation", "read-row");
            input.put("success", true);
            input.put("message", "Read 1 row");
            input.put("rows", List.of(Map.of("id", 1)));
            input.put("row_count", 1);
            input.put("rowCount", 1);
            input.put("has_more", true);
            input.put("offset", 5);

            Map<String, Object> result = genericMapper.transform("GET_ROWS", input);

            assertEquals("read-row", result.get("operation"));
            assertEquals(true, result.get("success"));
            assertEquals("Read 1 row", result.get("message"));
            assertEquals(List.of(Map.of("id", 1)), result.get("rows"));
            assertEquals(1, result.get("row_count"));
            assertEquals(1, result.get("rowCount"));
            assertEquals(true, result.get("has_more"));
            assertEquals(5, result.get("offset"));
        }
    }

    @Nested
    @DisplayName("Table CRUD nodes")
    class TableCrudTests {

        @Test
        @DisplayName("Should persist insert-row fields from the shared node contract")
        void shouldPersistInsertRowContractFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("operation", "create-row");
            input.put("success", true);
            input.put("message", "Inserted 2 rows");
            input.put("row_id", "42");
            input.put("created_at", "2026-05-16T10:00:00Z");
            input.put("inserted_count", 2);
            input.put("inserted_values", Map.of("title", "Ada"));

            Map<String, Object> result = genericMapper.transform("INSERT_ROW", input);

            assertEquals("create-row", result.get("operation"));
            assertEquals(true, result.get("success"));
            assertEquals("Inserted 2 rows", result.get("message"));
            assertEquals("42", result.get("row_id"));
            assertEquals("2026-05-16T10:00:00Z", result.get("created_at"));
            assertEquals(2, result.get("inserted_count"));
            assertEquals(Map.of("title", "Ada"), result.get("inserted_values"));
        }

        @Test
        @DisplayName("Should persist update-row fields from the shared node contract")
        void shouldPersistUpdateRowContractFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("operation", "update-row");
            input.put("success", true);
            input.put("message", "Updated 2 rows");
            input.put("updated_count", 2);
            input.put("rows_affected", 2);
            input.put("updated_at", "2026-05-16T10:00:00Z");

            Map<String, Object> result = genericMapper.transform("UPDATE_ROW", input);

            assertEquals("update-row", result.get("operation"));
            assertEquals(true, result.get("success"));
            assertEquals("Updated 2 rows", result.get("message"));
            assertEquals(2, result.get("updated_count"));
            assertEquals(2, result.get("rows_affected"));
            assertEquals("2026-05-16T10:00:00Z", result.get("updated_at"));
        }

        @Test
        @DisplayName("Should persist delete-row fields from the shared node contract")
        void shouldPersistDeleteRowContractFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("operation", "delete-row");
            input.put("success", true);
            input.put("message", "Deleted 2 rows");
            input.put("deleted_count", 2);
            input.put("rows_affected", 2);
            input.put("deleted_at", "2026-05-16T10:00:00Z");

            Map<String, Object> result = genericMapper.transform("DELETE_ROW", input);

            assertEquals("delete-row", result.get("operation"));
            assertEquals(true, result.get("success"));
            assertEquals("Deleted 2 rows", result.get("message"));
            assertEquals(2, result.get("deleted_count"));
            assertEquals(2, result.get("rows_affected"));
            assertEquals("2026-05-16T10:00:00Z", result.get("deleted_at"));
        }

        @Test
        @DisplayName("Should persist create-column fields from the shared node contract")
        void shouldPersistCreateColumnContractFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("operation", "create-column");
            input.put("success", true);
            input.put("message", "Created 3 columns");
            input.put("createdColumns", List.of("status", "score", "note"));

            Map<String, Object> result = genericMapper.transform("CREATE_COLUMN", input);

            assertEquals("create-column", result.get("operation"));
            assertEquals(true, result.get("success"));
            assertEquals("Created 3 columns", result.get("message"));
            assertEquals(List.of("status", "score", "note"), result.get("createdColumns"));
            assertFalse(result.containsKey("created_columns"));
            assertFalse(result.containsKey("column_count"));
        }
    }

    @Nested
    @DisplayName("WEBHOOK_TRIGGER node")
    class WebhookTriggerTests {

        @Test
        void shouldTransformComplete() {
            Map<String, Object> input = new HashMap<>();
            input.put("payload", Map.of("event", "push"));
            input.put("queryParams", Map.of("token", "abc"));
            input.put("method", "POST");

            Map<String, Object> result = genericMapper.transform("WEBHOOK_TRIGGER", input);

            assertEquals(Map.of("event", "push"), result.get("payload"));
            assertEquals(Map.of("token", "abc"), result.get("query")); // alias
            assertEquals("POST", result.get("method"));
            assertNotNull(result.get("triggered_at")); // __NOW__ default
        }
    }

    @Nested
    @DisplayName("COMPARE_DATASETS node - number coercion")
    class CompareDatasetsTests {

        @Test
        void shouldCoerceNumbersToInt() {
            Map<String, Object> input = new HashMap<>();
            input.put("matched", List.of());
            input.put("onlyInA", List.of());
            input.put("onlyInB", List.of());
            input.put("matchedCount", 5L);     // Long
            input.put("onlyInACount", 3.0);    // Double
            input.put("onlyInBCount", 2);      // Integer
            input.put("totalA", 8L);
            input.put("totalB", 5L);

            Map<String, Object> result = genericMapper.transform("COMPARE_DATASETS", input);

            assertEquals(5, result.get("matchedCount"));
            assertInstanceOf(Integer.class, result.get("matchedCount"));
            assertEquals(3, result.get("onlyInACount"));
            assertEquals(2, result.get("onlyInBCount"));
        }
    }

    @Nested
    @DisplayName("CODE node - all defaults")
    class CodeTests {

        @Test
        void shouldApplyAllDefaults() {
            Map<String, Object> result = genericMapper.transform("CODE", new HashMap<>());

            assertEquals("", result.get("stdout"));
            assertEquals("", result.get("stderr"));
            assertEquals(0, result.get("exitCode"));
            assertEquals("javascript", result.get("language"));
            assertEquals(0, result.get("executionTime"));
            assertEquals(false, result.get("success"));
            assertFalse(result.containsKey("result")); // conditional
        }
    }

    @Nested
    @DisplayName("WAIT node - canonical output contract")
    class WaitTests {

        @Test
        void shouldPreserveWaitedMsCanonicalField() {
            Map<String, Object> input = new HashMap<>();
            input.put("waited_ms", 5000);
            input.put("status", "completed");
            input.put("started_at", "2024-01-01T12:00:00Z");
            input.put("completed_at", "2024-01-01T12:00:05Z");

            Map<String, Object> result = genericMapper.transform("WAIT", input);

            assertEquals(5000, result.get("waited_ms"));
            assertEquals("completed", result.get("status"));
            assertEquals("2024-01-01T12:00:00Z", result.get("started_at"));
            assertEquals("2024-01-01T12:00:05Z", result.get("completed_at"));
        }

        @Test
        void shouldResolveLegacyWaitedAliasToCanonicalWaitedMs() {
            Map<String, Object> input = new HashMap<>();
            input.put("waited", 5000);

            Map<String, Object> result = genericMapper.transform("WAIT", input);

            assertEquals(5000, result.get("waited_ms"));
        }

        @Test
        void shouldResolveResumedAtAlias() {
            Map<String, Object> input = new HashMap<>();
            input.put("resumed_at", "2024-01-01T12:01:00Z");

            Map<String, Object> result = genericMapper.transform("WAIT", input);

            assertEquals("2024-01-01T12:01:00Z", result.get("completed_at"));
        }

        @Test
        void shouldPreserveAwaitingSignalFieldsWithoutSynthesizingCompletion() {
            Map<String, Object> input = new HashMap<>();
            input.put("duration_ms", 5000);
            input.put("started_at", "2024-01-01T12:00:00Z");
            input.put("expires_at", "2024-01-01T12:00:05Z");

            Map<String, Object> result = genericMapper.transform("WAIT", input);

            assertEquals(5000, result.get("duration_ms"));
            assertEquals("2024-01-01T12:00:00Z", result.get("started_at"));
            assertEquals("2024-01-01T12:00:05Z", result.get("expires_at"));
            assertFalse(result.containsKey("completed_at"));
            assertFalse(result.containsKey("status"));
        }
    }

    // ======= Complex nodes - customTransform =======

    @Nested
    @DisplayName("TRANSFORM node - customTransform (identity-with-envelope-strip)")
    class TransformTests {

        @Test
        @DisplayName("Preserves canonical {transformed, evaluations} produced by TransformNode.execute()")
        void preservesCanonicalShape() {
            Map<String, Object> input = new HashMap<>();
            input.put("transformed", Map.of("firstName", "John", "lastName", "Doe"));
            input.put("evaluations", List.of(Map.of("field", "firstName", "value", "John")));

            Map<String, Object> result = genericMapper.transform("TRANSFORM", input);

            assertEquals(Map.of("firstName", "John", "lastName", "Doe"), result.get("transformed"));
            assertEquals(List.of(Map.of("field", "firstName", "value", "John")), result.get("evaluations"));
        }

        @Test
        @DisplayName("Strips engine-envelope keys (node_type/item_index/itemIndex/item_id/resolved_params)")
        void stripsEngineEnvelopeKeys() {
            Map<String, Object> input = new HashMap<>();
            input.put("transformed", Map.of("greeting", "hello"));
            input.put("evaluations", List.of());
            input.put("node_type", "TRANSFORM");
            input.put("item_index", 3);
            input.put("itemIndex", 3);
            input.put("item_id", "msg-42");
            input.put("resolved_params", Map.of("greeting", "'hello'"));

            Map<String, Object> result = genericMapper.transform("TRANSFORM", input);

            assertEquals(Map.of("greeting", "hello"), result.get("transformed"));
            assertEquals(List.of(), result.get("evaluations"));
            assertFalse(result.containsKey("node_type"), "node_type must be stripped");
            assertFalse(result.containsKey("item_index"), "item_index must be stripped");
            assertFalse(result.containsKey("itemIndex"), "itemIndex must be stripped");
            assertFalse(result.containsKey("item_id"), "item_id must be stripped");
            assertFalse(result.containsKey("resolved_params"), "resolved_params must be stripped");
        }
    }

    @Nested
    @DisplayName("HTML_EXTRACT node - customTransform")
    class HtmlExtractTests {

        @Test
        @DisplayName("Preserves empty errors while stripping execution envelope")
        void preservesEmptyErrorsWhileStrippingEnvelope() {
            Map<String, Object> input = new HashMap<>();
            input.put("items", List.of(Map.of("title", "Ada")));
            input.put("count", 1);
            input.put("matched_root", 1);
            input.put("errors", List.of());
            input.put("node_type", "HTML_EXTRACT");
            input.put("item_index", 0);
            input.put("itemIndex", 0);
            input.put("item_id", "item-1");
            input.put("resolved_params", Map.of("root_selector", ".card"));

            Map<String, Object> result = genericMapper.transform("HTML_EXTRACT", input);

            assertEquals(List.of(Map.of("title", "Ada")), result.get("items"));
            assertEquals(1, result.get("count"));
            assertEquals(1, result.get("matched_root"));
            assertEquals(List.of(), result.get("errors"));
            assertFalse(result.containsKey("node_type"));
            assertFalse(result.containsKey("item_index"));
            assertFalse(result.containsKey("itemIndex"));
            assertFalse(result.containsKey("item_id"));
            assertFalse(result.containsKey("resolved_params"));
        }

        @Test
        @DisplayName("Defaults errors to an empty list when raw output omits it")
        void defaultsErrorsToEmptyList() {
            Map<String, Object> result = genericMapper.transform("HTML_EXTRACT", Map.of("items", List.of()));

            assertEquals(List.of(), result.get("errors"));
            assertEquals(0, result.get("count"));
            assertEquals(0, result.get("matched_root"));
        }
    }

    @Nested
    @DisplayName("FORM_TRIGGER node - customTransform with FileRef")
    class FormTriggerTests {

        @Test
        void shouldCopyDynamicFormFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("submission_id", "abc-123");
            input.put("submitted_at", "2024-01-01T12:00:00Z");
            input.put("form_data", Map.of("email", "test@test.com"));
            input.put("email", "test@test.com");
            input.put("name", "John");

            Map<String, Object> result = genericMapper.transform("FORM_TRIGGER", input);

            assertEquals("abc-123", result.get("submission_id"));
            assertEquals("test@test.com", result.get("email"));
            assertEquals("John", result.get("name"));
        }

        @Test
        void shouldPreserveFlattenedFileRefKeys() {
            // Post-refactor: FileRef flattening lives in FormDispatchService.buildFormPayload
            // (upstream of the mapper). customTransform is now identity-with-envelope-strip,
            // so it must preserve already-flattened <field>_file_* keys verbatim.
            Map<String, Object> input = new HashMap<>();
            input.put("submission_id", "abc");
            input.put("avatar_file_url", "/api/files/proxy?key=uploads%2Fphoto.jpg");
            input.put("avatar_file_name", "photo.jpg");
            input.put("avatar_file_size", 12345);
            input.put("avatar_content_type", "image/jpeg");
            input.put("node_type", "FORM_TRIGGER");      // envelope - must be stripped
            input.put("resolved_params", Map.of("a", 1)); // envelope - must be stripped

            Map<String, Object> result = genericMapper.transform("FORM_TRIGGER", input);

            assertEquals("abc", result.get("submission_id"));
            assertTrue(((String) result.get("avatar_file_url")).contains("uploads"));
            assertEquals("photo.jpg", result.get("avatar_file_name"));
            assertEquals(12345, result.get("avatar_file_size"));
            assertEquals("image/jpeg", result.get("avatar_content_type"));
            assertNull(result.get("node_type"));
            assertNull(result.get("resolved_params"));
        }
    }

    @Nested
    @DisplayName("AGGREGATE node - customTransform")
    class AggregateTests {

        @Test
        void shouldStoreAggregatedFieldsAtTopLevel() {
            Map<String, Object> input = new HashMap<>();
            input.put("aggregated_count", 3);
            input.put("names", List.of("A", "B", "C"));
            input.put("emails", List.of("a@x", "b@x", "c@x"));
            input.put("node_type", "AGGREGATE"); // metadata, should be excluded

            Map<String, Object> result = genericMapper.transform("AGGREGATE", input);

            assertEquals(3, result.get("aggregated_count"));
            // Aggregated fields are stored at top level (not wrapped under "fields")
            // so expressions like {{core:aggregate.output.names}} resolve directly.
            assertEquals(List.of("A", "B", "C"), result.get("names"));
            assertEquals(List.of("a@x", "b@x", "c@x"), result.get("emails"));
            assertNull(result.get("node_type")); // metadata excluded
        }
    }

    @Nested
    @DisplayName("OPTION node - customTransform")
    class OptionTests {

        @Test
        void shouldPreserveSelectedBranchesAndStripLegacy() {
            // Post-refactor: selected_branches is computed in OptionNode.execute().
            // customTransform now strips engine envelope + Option-legacy keys
            // (option_node, choices_evaluated) and preserves canonical declared keys.
            Map<String, Object> input = new HashMap<>();
            input.put("selected_choice", "choice_1");
            input.put("selected_label", "Option A");
            input.put("selected_choice_index", 0);
            input.put("selected_branches", List.of("Option A"));
            input.put("skipped_branches", List.of("Option B"));
            input.put("evaluations", List.of(Map.of("label", "Option A", "result", true)));
            input.put("option_node", "core:option");      // legacy - must be stripped
            input.put("choices_evaluated", 2);              // legacy - must be stripped
            input.put("node_type", "OPTION");               // envelope - must be stripped
            input.put("resolved_params", Map.of("x", 1)); // envelope - must be stripped

            Map<String, Object> result = genericMapper.transform("OPTION", input);

            assertEquals("choice_1", result.get("selected_choice"));
            assertEquals("Option A", result.get("selected_label"));
            assertEquals(List.of("Option A"), result.get("selected_branches"));
            assertEquals(List.of("Option B"), result.get("skipped_branches"));
            assertEquals(0, result.get("selected_choice_index"));
            assertNotNull(result.get("evaluations"));
            assertNull(result.get("option_node"));
            assertNull(result.get("choices_evaluated"));
            assertNull(result.get("node_type"));
            assertNull(result.get("resolved_params"));
        }
    }

    @Nested
    @DisplayName("DOWNLOAD_FILE node - customTransform with FileRef map")
    class DownloadFileTests {

        @Test
        void shouldPreserveFlattenedFileKeys() {
            // Post-refactor: FileRef flattening lives in DownloadFileNode.execute().
            // customTransform is identity-with-envelope-strip and must preserve
            // already-flattened canonical keys verbatim.
            Map<String, Object> input = new HashMap<>();
            input.put("file_url", "/api/files/proxy?key=files%2Fdoc.pdf&disposition=inline");
            input.put("file_name", "doc.pdf");
            input.put("file_size", 5000);
            input.put("content_type", "application/pdf");
            input.put("source_url", "https://example.com/doc.pdf");
            input.put("node_type", "DOWNLOAD_FILE");        // envelope - stripped
            input.put("resolved_params", Map.of("u", "x")); // envelope - stripped

            Map<String, Object> result = genericMapper.transform("DOWNLOAD_FILE", input);

            assertTrue(((String) result.get("file_url")).contains("files"));
            assertEquals("doc.pdf", result.get("file_name"));
            assertEquals(5000, result.get("file_size"));
            assertEquals("application/pdf", result.get("content_type"));
            assertEquals("https://example.com/doc.pdf", result.get("source_url"));
            assertNull(result.get("node_type"));
            assertNull(result.get("resolved_params"));
        }
    }

    @Nested
    @DisplayName("LIMIT node - customTransform")
    class LimitTests {

        @Test
        void shouldPreserveConfigAndStripResolvedParams() {
            // Post-refactor: LimitNode.execute() emits both `config` and `resolved_params`.
            // customTransform strips `resolved_params` (envelope) and preserves `config`.
            Map<String, Object> input = new HashMap<>();
            input.put("items", List.of(1, 2, 3));
            input.put("count", 3);
            input.put("original_count", 10);
            input.put("config", Map.of("limit", 3, "offset", 0));
            input.put("resolved_params", Map.of("limit", 3, "offset", 0)); // envelope - stripped
            input.put("node_type", "LIMIT");                                // envelope - stripped

            Map<String, Object> result = genericMapper.transform("LIMIT", input);

            assertEquals(List.of(1, 2, 3), result.get("items"));
            assertEquals(3, result.get("count"));
            assertEquals(10, result.get("original_count"));
            assertEquals(Map.of("limit", 3, "offset", 0), result.get("config"));
            assertNull(result.get("resolved_params"));
            assertNull(result.get("node_type"));
        }
    }

    @Nested
    @DisplayName("SUMMARIZE node - customTransform")
    class SummarizeTests {

        @Test
        void shouldCopyDynamicFields() {
            Map<String, Object> input = new HashMap<>();
            input.put("groups", List.of());
            input.put("total_groups", 0);
            input.put("total_items", 5);
            input.put("aggregation_count", 2);
            input.put("avg_price", 42.5);

            Map<String, Object> result = genericMapper.transform("SUMMARIZE", input);

            assertEquals(0, result.get("total_groups"));
            assertEquals(5, result.get("total_items"));
            assertEquals(42.5, result.get("avg_price")); // dynamic field preserved
        }
    }

    @Nested
    @DisplayName("WORKFLOW_TRIGGER node - customTransform")
    class WorkflowTriggerTests {

        @Test
        void shouldPreserveFlattenedParentKeysAndStripReserved() {
            // Post-refactor: WorkflowTriggerResolver emits snake_case `triggered_at` and
            // flattens parent outputs to root. customTransform now strips RESERVED_KEYS
            // (triggerId, type, source, status) + engine envelope and preserves the rest.
            Map<String, Object> input = new HashMap<>();
            input.put("triggered_at", "2024-01-01T12:00:00Z");
            input.put("triggered_by", "alice");
            input.put("parentWorkflowId", "wf-1");
            input.put("parentRunId", "run-1");
            input.put("parentStatus", "COMPLETED");
            input.put("result", 42);                       // flattened parent key
            input.put("triggerId", "trg-1");               // RESERVED - stripped
            input.put("type", "workflow");                  // RESERVED - stripped
            input.put("source", "internal");                // RESERVED - stripped
            input.put("my_parent_field", "value");        // dynamic parent key - flattened
            input.put("node_type", "WORKFLOW_TRIGGER"); // envelope - stripped
            input.put("resolved_params", Map.of());      // envelope - stripped

            Map<String, Object> result = genericMapper.transform("WORKFLOW_TRIGGER", input);

            assertEquals("2024-01-01T12:00:00Z", result.get("triggered_at"));
            assertEquals("alice", result.get("triggered_by"));
            assertEquals("wf-1", result.get("parentWorkflowId"));
            assertEquals(42, result.get("result"));
            assertEquals("value", result.get("my_parent_field"));
            assertNull(result.get("triggerId"));
            assertNull(result.get("type"));
            assertNull(result.get("source"));
            assertNull(result.get("node_type"));
            assertNull(result.get("resolved_params"));
        }
    }

    @Nested
    @DisplayName("TABLE_TRIGGER node - customTransform")
    class TableTriggerTests {

        @Test
        void shouldCopyDynamicColumns() {
            Map<String, Object> input = new HashMap<>();
            input.put("data", List.of(Map.of("name", "A")));
            input.put("totalCount", 10);
            input.put("hasMore", true);
            input.put("name", "A");
            input.put("age", 30);

            Map<String, Object> result = genericMapper.transform("TABLE_TRIGGER", input);

            assertEquals(List.of(Map.of("name", "A")), result.get("data"));
            assertEquals(10, result.get("totalCount"));
            assertEquals(true, result.get("hasMore"));
            assertEquals("A", result.get("name")); // dynamic column
            assertEquals(30, result.get("age"));   // dynamic column
        }
    }

    @Nested
    @DisplayName("String conversion utilities")
    class StringConversion {

        @Test
        void snakeToCamel() {
            assertEquals("sortedItems", GenericOutputSchemaMapper.snakeToCamel("sorted_items"));
            assertEquals("originalCount", GenericOutputSchemaMapper.snakeToCamel("original_count"));
            assertEquals("simple", GenericOutputSchemaMapper.snakeToCamel("simple"));
            assertNull(GenericOutputSchemaMapper.snakeToCamel(null));
        }

        @Test
        void camelToSnake() {
            assertEquals("sorted_items", GenericOutputSchemaMapper.camelToSnake("sortedItems"));
            assertEquals("original_count", GenericOutputSchemaMapper.camelToSnake("originalCount"));
            assertEquals("simple", GenericOutputSchemaMapper.camelToSnake("simple"));
            assertNull(GenericOutputSchemaMapper.camelToSnake(null));
        }
    }

    // FilterNode emits items, rejected_items, count, rejected_count, original_count
    // in addition to matched/filter_mode/conditions_evaluated/data. V11 only declared
    // the latter four in node_type_documentation and FilterNodeSpec followed suit, so
    // the mapper stripped the former five on persistence and LLMs never learned they
    // existed. The spec now declares all nine; these tests enforce that the mapper
    // preserves the full set (#D3).
    @Nested
    @DisplayName("FILTER node - preserves full output set (#D3)")
    class FilterTests {

        @Test
        void shouldPreserveItemsAndRejectedItems() {
            Map<String, Object> input = new HashMap<>();
            input.put("matched", true);
            input.put("items", List.of(Map.of("id", 1), Map.of("id", 2)));
            input.put("rejected_items", List.of(Map.of("id", 3)));
            input.put("count", 2);
            input.put("rejected_count", 1);
            input.put("original_count", 3);
            input.put("filter_mode", "and");
            input.put("conditions_evaluated", 2);

            Map<String, Object> result = genericMapper.transform("FILTER", input);

            assertEquals(true, result.get("matched"));
            assertEquals(List.of(Map.of("id", 1), Map.of("id", 2)), result.get("items"));
            assertEquals(List.of(Map.of("id", 3)), result.get("rejected_items"));
            assertEquals(2, result.get("count"));
            assertEquals(1, result.get("rejected_count"));
            assertEquals(3, result.get("original_count"));
            assertEquals("and", result.get("filter_mode"));
            assertEquals(2, result.get("conditions_evaluated"));
        }

        @Test
        void shouldApplyDefaultsWhenFieldsAbsent() {
            Map<String, Object> result = genericMapper.transform("FILTER", new HashMap<>());

            assertEquals(false, result.get("matched"));
            assertEquals(List.of(), result.get("items"));
            assertEquals(List.of(), result.get("rejected_items"));
            assertEquals(0, result.get("count"));
            assertEquals(0, result.get("rejected_count"));
            assertEquals(0, result.get("original_count"));
            assertEquals("and", result.get("filter_mode"));
            assertEquals(0, result.get("conditions_evaluated"));
            // data is conditional (no default) and must be absent
            assertFalse(result.containsKey("data"));
        }

        @Test
        void shouldPreserveDataOnlyWhenSupplied() {
            Map<String, Object> input = new HashMap<>();
            input.put("matched", true);
            input.put("data", Map.of("id", 42));

            Map<String, Object> result = genericMapper.transform("FILTER", input);

            assertEquals(Map.of("id", 42), result.get("data"));
        }
    }

    @Nested
    @DisplayName("INTERFACE node")
    class InterfaceTests {

        @Test
        @DisplayName("Persists interface_id, action_mapping and is_entry_interface as declared by InterfaceNodeSpec")
        void shouldTransformCompleteInput() {
            Map<String, Object> input = new HashMap<>();
            input.put("interface_id", "iface-uuid-123");
            input.put("action_mapping", Map.of("#btn", "__continue"));
            input.put("is_entry_interface", true);

            Map<String, Object> result = genericMapper.transform("INTERFACE", input);

            assertEquals("iface-uuid-123", result.get("interface_id"));
            assertEquals(Map.of("#btn", "__continue"), result.get("action_mapping"));
            assertEquals(true, result.get("is_entry_interface"));
        }

        @Test
        @DisplayName("Defaults is_entry_interface to false when missing; interface_id/action_mapping are conditional and stay absent")
        void shouldApplyDefaultForEntryFlagAndKeepConditionalsAbsent() {
            Map<String, Object> result = genericMapper.transform("INTERFACE", new HashMap<>());

            assertEquals(false, result.get("is_entry_interface"));
            assertFalse(result.containsKey("interface_id"));
            assertFalse(result.containsKey("action_mapping"));
        }

        @Test
        @DisplayName("resolved_params is an inspector-only engine envelope key - never persisted to the DB JSONB")
        void shouldNotPersistResolvedParamsEvenWhenSupplied() {
            // InterfaceNode.execute() emits resolved_params at runtime for the inspector panel.
            // GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS filters it out at persistence time.
            // Regression guard: declaring resolved_params in NodeSpec.outputs() would break this contract.
            Map<String, Object> input = new HashMap<>();
            input.put("interface_id", "iface-uuid-123");
            input.put("resolved_params", Map.of("interfaceId", "iface-uuid-123", "actions", 2));

            Map<String, Object> result = genericMapper.transform("INTERFACE", input);

            assertEquals("iface-uuid-123", result.get("interface_id"));
            assertFalse(result.containsKey("resolved_params"),
                "resolved_params must stay out of the persisted output (ENGINE_ENVELOPE_KEYS)");
        }

        @Test
        @DisplayName("rendered_html / rendered_css / rendered_js round-trip through the mapper when present")
        void shouldPersistRenderedSourceTripleWhenPresent() {
            Map<String, Object> input = new HashMap<>();
            input.put("interface_id", "iface-uuid-123");
            input.put("rendered_html", "<h1>hi</h1>");
            input.put("rendered_css", "h1{color:red}");
            input.put("rendered_js", "console.log('hi')");

            Map<String, Object> result = genericMapper.transform("INTERFACE", input);

            assertEquals("<h1>hi</h1>", result.get("rendered_html"));
            assertEquals("h1{color:red}", result.get("rendered_css"));
            assertEquals("console.log('hi')", result.get("rendered_js"));
        }

        @Test
        @DisplayName("rendered_html / rendered_css / rendered_js are conditional - stay absent when InterfaceNode did not emit them (toggle off)")
        void shouldKeepRenderedSourceAbsentWhenNotEmitted() {
            // When exposeRenderedSource=false on the node, the InterfaceNode does not populate these
            // fields. NodeSpec declares them as conditional (no default), so the mapper must NOT
            // synthesize empty values - they must stay absent from the persisted JSONB.
            Map<String, Object> input = new HashMap<>();
            input.put("interface_id", "iface-uuid-123");

            Map<String, Object> result = genericMapper.transform("INTERFACE", input);

            assertFalse(result.containsKey("rendered_html"));
            assertFalse(result.containsKey("rendered_css"));
            assertFalse(result.containsKey("rendered_js"));
        }
    }
}
