package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CHAIN from a catalog refusal to the node's retry: the catalog envelope is flattened by the
 * REAL {@link CatalogResultFlattener} (the one the gateway uses), wrapped the way the step node
 * fails, and handed to the REAL {@link NodePolicyRunner}. A test of the runner alone feeds it a
 * shape it was written against; this one fails if the flattener stops carrying the status or the
 * provider's Retry-After where the runner reads them.
 */
@DisplayName("Catalog refusal -> flattened step output -> node retry (chain)")
class CatalogFailureRetryChainTest {

    private static NodeExecutionResult stepFailureFrom(Map<String, Object> catalogMetadata) {
        // The gateway resolves the status from metadata.status before flattening.
        Integer httpStatus = catalogMetadata.get("status") instanceof Number n ? n.intValue() : null;
        Map<String, Object> result = Map.of("httpStatus", catalogMetadata.get("httpStatus"));
        Map<String, Object> output = new HashMap<>(CatalogResultFlattener.flatten(
                "tool-1", result, catalogMetadata, httpStatus, "Catalog service executed the tool"));
        output.put("error", "Too many requests");
        return NodeExecutionResult.failureWithOutput("mcp:post", "Too many requests", output, 3);
    }

    @Test
    @DisplayName("a 429 with Retry-After 7 s is waited out for 7000 ms and retried")
    void retryAfterTravelsFromCatalogToTheWait() throws Exception {
        Map<String, Object> catalogMetadata = new HashMap<>();
        catalogMetadata.put("status", 429);
        catalogMetadata.put("retryAfterSeconds", 7L);
        catalogMetadata.put("httpStatus", Map.of("code", 429, "error", "Too many requests"));
        NodeExecutionResult failure = stepFailureFrom(catalogMetadata);

        List<Long> sleeps = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        new NodePolicyRunner(sleeps::add).run(new NodePolicy(1, 1000L, false), "mcp:post", () -> {
            attempts.incrementAndGet();
            return failure;
        }, null);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(7_000L);
    }

    @Test
    @DisplayName("a plain 404 through the same chain is attempted once")
    void permanentRefusalThroughTheChainIsNotRetried() throws Exception {
        Map<String, Object> catalogMetadata = new HashMap<>();
        catalogMetadata.put("status", 404);
        catalogMetadata.put("httpStatus", Map.of("code", 404, "error", "Not found"));
        NodeExecutionResult base = stepFailureFrom(catalogMetadata);
        Map<String, Object> output = new HashMap<>(base.output());
        output.put("error", "Not found");
        NodeExecutionResult failure = NodeExecutionResult.failureWithOutput("mcp:post", "Not found", output, 3);

        AtomicInteger attempts = new AtomicInteger();
        new NodePolicyRunner(millis -> { }).run(new NodePolicy(3, 0L, false), "mcp:post", () -> {
            attempts.incrementAndGet();
            return failure;
        }, null);

        assertThat(attempts.get()).isEqualTo(1);
    }
}
