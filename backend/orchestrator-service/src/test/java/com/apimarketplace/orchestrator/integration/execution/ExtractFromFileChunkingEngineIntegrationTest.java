package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * End-to-end (in-process, real Spring wiring) integration test for token-based chunking on the
 * extract_from_file node. Unlike {@code ExtractFromFileNodeTest} (which calls node.execute()
 * directly), this runs a full WorkflowPlan through the real {@link UnifiedExecutionEngine}:
 * a trigger feeds a {@code core:extract_from_file} node configured with {@code chunkUnit=token},
 * proving the plan parses chunkUnit, the engine wires the node, and the run output carries
 * {@code chunk_unit} + token-sized chunks.
 */
@IntegrationTest
@DisplayName("Extract-from-file token chunking - engine integration")
class ExtractFromFileChunkingEngineIntegrationTest {

    @Autowired
    private UnifiedExecutionEngine executionEngine;

    @Autowired
    private ExecutionTreeBuilder treeBuilder;

    @MockitoBean
    private V2ExecutionEventService eventService;

    private WorkflowExecution workflowExecution;

    // ASCII English prose: ~4 chars/token, so token-sized chunks are much longer than chunkSize chars.
    private static final String PROSE =
        "Retrieval augmented generation pipelines split documents into chunks. "
        + "Each chunk is embedded into a vector and stored for similarity search. "
        + "Token based chunking keeps every chunk within the embedding context window.";

    private static final int CHUNK_SIZE = 8;

    @BeforeEach
    void setUp() {
        workflowExecution = new WorkflowExecution("run-extract-itest", buildPlan("char"), Map.of());
        lenient().doNothing().when(eventService).initializeTotalItems(any(), anyInt());
        lenient().doNothing().when(eventService).emitNodeStart(any(), any(), any(), anyInt(), anyInt());
        lenient().doReturn(null).when(eventService).emitNodeComplete(any(), any(), any(), any(), anyInt(), any()); // emitNodeComplete now RETURNS the completion result (payload-loss fix); null = no payload-lost rewrite
        lenient().doNothing().when(eventService).emitNodeAwaitingSignal(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Should run a workflow whose extract_from_file node chunks by tokens (chunk_unit=token, chunks exceed chunkSize chars)")
    void shouldChunkByTokensThroughEngine() throws Exception {
        WorkflowPlan plan = buildPlan("token");
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionTree tree = treeBuilder.build(runId, "wfr-extract-itest", "tenant-test", plan);

        List<TriggerItem> items = List.of(new TriggerItem("item-1", 0, Map.of()));
        WorkflowResult result = executionEngine.executeWorkflow(tree, items, workflowExecution, eventService)
            .get(30, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, result.totalItems());

        Map<String, Object> output = captureNodeOutput("core:chunk");
        assertNotNull(output, "extract node should have completed with an output");
        assertEquals("token", output.get("chunk_unit"));
        assertEquals("fixed_size", output.get("chunking_strategy"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) output.get("items");
        assertNotNull(chunks, "output should carry chunk items");
        assertTrue(chunks.size() > 1, "long prose should yield multiple token chunks, was " + chunks.size());

        // Token discriminator: a full 8-token chunk of English prose is far longer than 8 chars,
        // which is impossible under character metering. This is what makes the assertion meaningful.
        String first = (String) chunks.get(0).get("content");
        assertTrue(first.length() > CHUNK_SIZE,
            "token-sized chunk must exceed chunkSize chars, was " + first.length());
    }

    @Test
    @DisplayName("Should default to char chunking through the engine when chunkUnit is omitted")
    void shouldDefaultToCharThroughEngine() throws Exception {
        WorkflowPlan plan = buildPlan(null); // no chunkUnit -> defaults to char
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionTree tree = treeBuilder.build(runId, "wfr-extract-itest", "tenant-test", plan);

        WorkflowResult result = executionEngine.executeWorkflow(
                tree, List.of(new TriggerItem("item-1", 0, Map.of())), workflowExecution, eventService)
            .get(30, TimeUnit.SECONDS);
        assertEquals(1, result.totalItems());

        Map<String, Object> output = captureNodeOutput("core:chunk");
        assertNotNull(output);
        assertEquals("char", output.get("chunk_unit"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) output.get("items");
        assertNotNull(chunks);
        // char mode with chunkSize=8 -> every chunk is at most 8 characters.
        for (Map<String, Object> chunk : chunks) {
            String content = (String) chunk.get("content");
            assertTrue(content.length() <= CHUNK_SIZE, "char chunk must be <= 8 chars, was " + content.length());
        }
    }

    /** Captures the output Map of the most recent emitNodeComplete for the given node id. */
    private Map<String, Object> captureNodeOutput(String nodeId) {
        ArgumentCaptor<ExecutionNode> nodeCaptor = ArgumentCaptor.forClass(ExecutionNode.class);
        ArgumentCaptor<NodeExecutionResult> resultCaptor = ArgumentCaptor.forClass(NodeExecutionResult.class);
        verify(eventService, atLeastOnce()).emitNodeComplete(
            any(), nodeCaptor.capture(), resultCaptor.capture(), any(), anyInt(), any());
        List<ExecutionNode> nodes = nodeCaptor.getAllValues();
        List<NodeExecutionResult> results = resultCaptor.getAllValues();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodeId.equals(nodes.get(i).getNodeId())) {
                return results.get(i).output();
            }
        }
        return null;
    }

    /** trigger(webhook) -> core:extract_from_file(text mode, fixed_size, chunkSize tokens/chars). */
    private WorkflowPlan buildPlan(String chunkUnit) {
        Map<String, Object> extract = new HashMap<>();
        extract.put("format", "txt");
        extract.put("mode", "text");
        extract.put("value", PROSE);
        extract.put("chunking", true);
        extract.put("chunkSize", CHUNK_SIZE);
        extract.put("overlap", 0);
        extract.put("chunkingStrategy", "fixed_size");
        if (chunkUnit != null) {
            extract.put("chunkUnit", chunkUnit);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", "extract-itest-" + UUID.randomUUID().toString().substring(0, 8));
        data.put("tenant_id", "test-tenant");
        data.put("triggers", List.of(
            Map.of("id", "t1", "label", "Start", "type", "webhook", "strategy", "single")));
        data.put("mcps", List.of());
        data.put("cores", List.of(
            Map.of("id", "c1", "label", "Chunk", "type", "extract_from_file", "extractFromFile", extract)));
        data.put("edges", List.of(Map.of("from", "trigger:start", "to", "core:chunk")));
        data.put("agents", List.of());
        data.put("tables", List.of());
        data.put("notes", List.of());
        return WorkflowPlan.fromMap(data);
    }
}
