package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for get_node_output field-expand - offset-based paging of a large text output
 * field, using the same content/offset/returned_bytes/original_length/truncated/NEXT vocabulary
 * as the files tool. Targets the pure helpers windowOutputField/addExpandHints/expandCall
 * directly (package-private for testability); the full buildNodeOutputReport path is exercised
 * by the live agent-cli bridge e2e.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWorkflowFireService - get_node_output field expand")
class AgentWorkflowFireServiceExpandTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private ReusableTriggerService reusableTriggerService;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private EditorRunResolver editorRunResolver;
    @Mock private StepOutputService stepOutputService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private WorkflowEpochService epochService;

    private AgentWorkflowFireService service;

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowFireService(
                runRepository, executionService, reusableTriggerService,
                signalWaitRepository, editorRunResolver, new ObjectMapper(),
                stepOutputService, stepDataRepository, epochService);
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("run_id", "run-1");
        r.put("epoch", 1);
        r.put("node_id", "agent:writer");
        return r;
    }

    private static WorkflowStepDataEntity step(Integer itemIndex, Integer iteration, Integer spawn) {
        WorkflowStepDataEntity s = new WorkflowStepDataEntity();
        s.setItemIndex(itemIndex);
        s.setIteration(iteration);
        s.setSpawn(spawn);
        return s;
    }

    @Test
    @DisplayName("windows a text field, truncates at max_bytes, offers NEXT with the next offset")
    void windowTruncatesAndOffersNext() {
        Map<String, Object> raw = Map.of("agent_response", "X".repeat(200));
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "agent_response", 0, 50);
        assertThat(w).containsEntry("field", "agent_response");
        assertThat((String) w.get("content")).hasSize(50);
        assertThat(w).containsEntry("returned_bytes", 50).containsEntry("original_length", 200).containsEntry("offset", 0);
        assertThat(w).containsEntry("truncated", true);
        assertThat((String) w.get("NEXT")).contains("field='agent_response'").contains("offset=50");
    }

    @Test
    @DisplayName("expands from a byte offset (contiguous next window)")
    void expandsFromOffset() {
        Map<String, Object> raw = Map.of("agent_response", "0123456789".repeat(20)); // 200
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "agent_response", 50, 50);
        assertThat(w).containsEntry("offset", 50).containsEntry("returned_bytes", 50).containsEntry("truncated", true);
        assertThat((String) w.get("NEXT")).contains("offset=100");
    }

    @Test
    @DisplayName("final window has truncated=false and no NEXT")
    void finalWindowNoNext() {
        Map<String, Object> raw = Map.of("agent_response", "Y".repeat(200));
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "agent_response", 150, 100);
        assertThat(w).containsEntry("offset", 150).containsEntry("returned_bytes", 50).containsEntry("truncated", false);
        assertThat(w).doesNotContainKey("NEXT");
    }

    @Test
    @DisplayName("unknown field returns an error + the available field names")
    void unknownFieldListsAvailable() {
        Map<String, Object> raw = Map.of("agent_response", "hi", "tokens", "5");
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "nope", 0, 50);
        assertThat(w).containsKey("error");
        assertThat(w.get("available_fields").toString()).contains("agent_response").contains("tokens");
        assertThat(w).doesNotContainKey("content");
    }

    @Test
    @DisplayName("non-text field returns its (capped) structured value, not a text window")
    void nonTextFieldReturnsStructuredValue() {
        Map<String, Object> raw = Map.of("rows", List.of("a", "b", "c"));
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "rows", 0, 50);
        assertThat(w).containsKey("value").containsKey("note");
        assertThat(w).doesNotContainKey("content");
    }

    @Test
    @DisplayName("max_bytes is clamped to >= 1")
    void maxBytesClampedToAtLeastOne() {
        Map<String, Object> raw = Map.of("agent_response", "abcdef");
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "agent_response", 0, 0);
        assertThat((String) w.get("content")).hasSize(1);
        assertThat(w).containsEntry("truncated", true);
    }

    @Test
    @DisplayName("expandCall carries the row's identity filters (item_index/iteration/spawn) + field + offset")
    void expandCallCarriesFilters() {
        String call = service.expandCall(ctx(), step(2, 3, 1), "agent_response", 131072);
        assertThat(call).contains("run_id='run-1'").contains("epoch=1").contains("node_id='agent:writer'")
                .contains("item_index=2").contains("iteration=3").contains("spawn=1")
                .contains("field='agent_response'").contains("offset=131072");
    }

    @Test
    @DisplayName("addExpandHints adds NEXT to truncated TEXT stubs only (not list stubs, not plain values)")
    void addExpandHintsTargetsTextStubsOnly() {
        Map<String, Object> bigStub = new LinkedHashMap<>();
        bigStub.put("truncated", true);
        bigStub.put("original_length", 500000);
        bigStub.put("preview", "...");
        Map<String, Object> listStub = new LinkedHashMap<>();
        listStub.put("row_count", 50);
        listStub.put("truncated", true);
        listStub.put("preview", List.of());
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("agent_response", bigStub);
        capped.put("rows", listStub);
        capped.put("tokens", "42");

        service.addExpandHints(capped, ctx(), step(null, null, null));

        assertThat(bigStub).containsKey("NEXT");
        assertThat((String) bigStub.get("NEXT")).contains("field='agent_response'").contains("offset=0");
        assertThat(listStub).doesNotContainKey("NEXT");   // list stub (row_count, no original_length) → no expand
        assertThat(capped.get("tokens")).isEqualTo("42"); // plain value untouched
    }

    @Test
    @DisplayName("addExpandHints recurses into nested maps and points NEXT at the dot-path")
    void addExpandHintsRecursesIntoNestedMaps() {
        Map<String, Object> nestedStub = new LinkedHashMap<>();
        nestedStub.put("truncated", true);
        nestedStub.put("original_length", 300000);
        nestedStub.put("preview", "iVBOR...");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("image", nestedStub);
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("output", inner);

        service.addExpandHints(capped, ctx(), step(0, null, null));

        assertThat(nestedStub).containsKey("NEXT");
        assertThat((String) nestedStub.get("NEXT")).contains("field='output.image'").contains("item_index=0");
    }

    @Test
    @DisplayName("windowOutputField resolves a dot-path into a nested text field")
    void windowOutputFieldResolvesDotPath() {
        Map<String, Object> raw = Map.of("output", Map.of("image", "Z".repeat(200)));
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "output.image", 0, 50);
        assertThat(w).containsEntry("field", "output.image");
        assertThat((String) w.get("content")).hasSize(50);
        assertThat(w).containsEntry("truncated", true);
        assertThat((String) w.get("NEXT")).contains("field='output.image'").contains("offset=50");
    }

    @Test
    @DisplayName("addExpandHints recurses into arrays - points NEXT at the indexed dot-path (e.g. output.data.0.b64_json)")
    void addExpandHintsRecursesIntoArrays() {
        Map<String, Object> nestedStub = new LinkedHashMap<>();
        nestedStub.put("truncated", true);
        nestedStub.put("original_length", 2256258);
        nestedStub.put("preview", "iVBOR...");
        Map<String, Object> elem = new LinkedHashMap<>();
        elem.put("b64_json", nestedStub);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("data", List.of(elem));
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("output", inner);

        service.addExpandHints(capped, ctx(), step(0, null, null));

        assertThat(nestedStub).containsKey("NEXT");
        assertThat((String) nestedStub.get("NEXT")).contains("field='output.data.0.b64_json'");
    }

    @Test
    @DisplayName("windowOutputField resolves a dot-path with an array index")
    void windowOutputFieldResolvesArrayIndexPath() {
        Map<String, Object> raw = Map.of("output", Map.of("data", List.of(Map.of("b64_json", "Z".repeat(200)))));
        Map<String, Object> w = service.windowOutputField(ctx(), step(null, null, null), raw, "output.data.0.b64_json", 0, 50);
        assertThat(w).containsEntry("field", "output.data.0.b64_json");
        assertThat((String) w.get("content")).hasSize(50);
        assertThat(w).containsEntry("truncated", true);
        assertThat((String) w.get("NEXT")).contains("field='output.data.0.b64_json'").contains("offset=50");
    }

    @Test
    @DisplayName("addExpandHints does NOT offer expand for binary-shaped (base64) stubs - only genuine text")
    void addExpandHintsSkipsBinaryStubs() {
        // ToolResultSizeCap tags base64-shaped strings with a 'binary' note; paging raw image bytes is useless.
        Map<String, Object> binStub = new LinkedHashMap<>();
        binStub.put("truncated", true);
        binStub.put("original_length", 2256256);
        binStub.put("preview", "iVBOR...");
        binStub.put("note", "string-shaped binary elided from agent context - fetch via a typed tool if you need the bytes");
        Map<String, Object> textStub = new LinkedHashMap<>();
        textStub.put("truncated", true);
        textStub.put("original_length", 500000);
        textStub.put("preview", "Lorem ipsum...");
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("image_b64", binStub);
        capped.put("article", textStub);

        service.addExpandHints(capped, ctx(), step(null, null, null));

        assertThat(binStub).doesNotContainKey("NEXT");   // binary → not proactively offered for paging
        assertThat(textStub).containsKey("NEXT");         // genuine text → pageable
    }

    @Test
    @DisplayName("expandCall includes item_index=0 (not omitted) but omits zero iteration/spawn")
    void expandCallIncludesItemIndexZero() {
        String call = service.expandCall(ctx(), step(0, 0, 0), "agent_response", 0);
        assertThat(call).contains("item_index=0");
        assertThat(call).doesNotContain("iteration=");
        assertThat(call).doesNotContain("spawn=");
    }
}
