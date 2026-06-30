package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ToolContextService.ToolContext;
import com.apimarketplace.catalog.service.ToolContextService.ToolContext.ParamMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug / wire-shape inspection tests.
 *
 * <p>Purpose: print the EXACT JSON that the LLM agent sees when calling a
 * tool through the catalog. No Redis, no Spring, no HTTP. Pure pipeline:
 * {@link ResponseShaper} → {@link NextActionBuilder} → assembled
 * {@code result + metadata} block → JSON pretty-print.
 *
 * <p>Assertions are minimal - these are diagnostic tests. Run with
 * {@code mvn test -Dtest=AgentWireShapeDebugTest -Dsurefire.useFile=false}
 * and read the {@code System.out.println} output to inspect the payload an
 * LLM would receive in its {@code tool_result.content}.
 */
@DisplayName("Agent wire-shape debug")
class AgentWireShapeDebugTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseShaper shaper = new ResponseShaper();
    private final NextActionBuilder nextActionBuilder = new NextActionBuilder(new PaginationParamResolver());

    @Nested
    @DisplayName("Apify get_dataset_items - 10 LinkedIn-like profiles, 147 KB raw")
    class ApifyDatasetScenario {

        @Test
        @DisplayName("AGENT mode → digest payload + nextAction copy-pastable for offset=3, limit=1")
        void agentModeApifyDigest() throws Exception {
            // Build a synthetic 10-profile payload mimicking the 2026-05-04 prod
            // incident shape (~147 KB raw).
            List<Map<String, Object>> profiles = buildLinkedInPayload(10);

            // Agent's call params (what they'd pass to catalog(action='execute',
            // tool_id=apify_dataset_items_uuid, params={...}))
            Map<String, Object> originalParams = new LinkedHashMap<>();
            originalParams.put("dataset_id", "I6AGg6mVOEezjNlWb");
            originalParams.put("clean", true);

            // Pipeline
            ResponseShaper.ShapingResult shaping =
                    shaper.shape(profiles, null, null, ResponseShaper.Mode.AGENT);

            ToolContext context = apifyDatasetItemsContext();
            Map<String, Object> nextAction = nextActionBuilder
                    .build(context, shaping, originalParams)
                    .orElseThrow();

            // Assemble the wire response (same structure as ToolExecutionManager)
            Map<String, Object> wire = assembleWireResponse(shaping, nextAction);

            // === PRETTY PRINT ===
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wire);
            System.out.println("\n========== AGENT MODE - Apify dataset (10 profiles) ==========");
            System.out.println(prettyJson);
            System.out.println("========== Raw bytes: " + shaping.rawBytes()
                    + " | Shaped bytes: " + shaping.shapedBytes()
                    + " | Action: " + shaping.action() + " ==========\n");

            // Sanity assertions
            assertTrue(shaping.shapedBytes() <= ResponseShaper.MAX_TOTAL_RESPONSE_SIZE);
            assertEquals(ResponseShaper.Action.ARRAY_DIGESTED, shaping.action());

            // Parse the wire JSON to confirm what the LLM would see
            var root = mapper.readTree(prettyJson);
            // result is a list with one digest map
            assertEquals("array_digest", root.at("/result/0/_shape").asText());
            assertEquals(10, root.at("/result/0/total_items").asInt());
            assertEquals(3, root.at("/result/0/preview_items").asInt());
            // nextAction.params.parameters has dataset_id + offset + limit
            assertEquals("I6AGg6mVOEezjNlWb",
                    root.at("/metadata/nextAction/params/parameters/dataset_id").asText());
            assertEquals(3, root.at("/metadata/nextAction/params/parameters/offset").asInt());
            assertEquals(1, root.at("/metadata/nextAction/params/parameters/limit").asInt());
            assertTrue(root.at("/metadata/nextAction/hint").asText()
                    .toLowerCase().contains("zero-based"));
        }

        @Test
        @DisplayName("WORKFLOW mode → array preserved, NO digest markers, SpEL-safe")
        void workflowModeApifyPreservedShape() throws Exception {
            List<Map<String, Object>> profiles = buildLinkedInPayload(10);

            ResponseShaper.ShapingResult shaping =
                    shaper.shape(profiles, null, null, ResponseShaper.Mode.WORKFLOW);

            // No nextAction wired by the manager in WORKFLOW path of step.output;
            // we still compute it for visibility. The workflow projector reads
            // the data tree only - array shape preserved.
            Map<String, Object> wire = assembleWireResponse(shaping, /* no nextAction */ null);

            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wire);
            System.out.println("\n========== WORKFLOW MODE - Apify dataset (10 profiles) ==========");
            System.out.println(prettyJson);
            System.out.println("========== Raw bytes: " + shaping.rawBytes()
                    + " | Shaped bytes: " + shaping.shapedBytes()
                    + " | Action: " + shaping.action() + " ==========\n");

            // Workflow keeps the array as a List of 10
            assertTrue(shaping.data() instanceof List);
            assertEquals(10, ((List<?>) shaping.data()).size());
            // No digest action
            assertNotEquals(ResponseShaper.Action.ARRAY_DIGESTED, shaping.action());
            assertNotEquals(ResponseShaper.Action.OVERSIZE_FALLBACK, shaping.action());
        }

        @Test
        @DisplayName("max_items=2 → explicit cap, 2 preview items, skipped_from=2")
        void agentModeWithMaxItemsCap() throws Exception {
            List<Map<String, Object>> profiles = buildLinkedInPayload(10);

            ResponseShaper.ShapingResult shaping =
                    shaper.shape(profiles, null, /* maxItems */ 2, ResponseShaper.Mode.AGENT);

            ToolContext context = apifyDatasetItemsContext();
            Map<String, Object> nextAction = nextActionBuilder
                    .build(context, shaping, Map.of("dataset_id", "X"))
                    .orElseThrow();

            Map<String, Object> wire = assembleWireResponse(shaping, nextAction);

            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wire);
            System.out.println("\n========== AGENT MODE - max_items=2 ==========");
            System.out.println(prettyJson);
            System.out.println("===========================================================\n");

            var root = mapper.readTree(prettyJson);
            assertEquals(10, root.at("/result/0/total_items").asInt());
            assertEquals(2, root.at("/result/0/preview_items").asInt());
            assertEquals(2, root.at("/result/0/skipped_from").asInt());
            assertEquals(9, root.at("/result/0/skipped_to").asInt());
        }
    }

    @Nested
    @DisplayName("No-cursor API (prose-only nextAction)")
    class NoCursorScenario {

        @Test
        @DisplayName("Tool without offset/cursor in inputSchema → prose hint + response_schema fallback")
        void agentModeNoCursorResolvesProse() throws Exception {
            List<Map<String, Object>> items = buildLinkedInPayload(10);

            ResponseShaper.ShapingResult shaping =
                    shaper.shape(items, null, null, ResponseShaper.Mode.AGENT);

            // Context with NO cursor candidate (only filter/q params)
            ToolContext context = new ToolContext();
            context.setToolId("opaque-tool-uuid");
            context.setToolName("search_articles");
            context.setIconSlug("custom");
            context.setParameters(List.of(
                    new ParamMeta("query", "Search query"),
                    new ParamMeta("filter", "Filter expression")));

            Map<String, Object> nextAction = nextActionBuilder
                    .build(context, shaping, Map.of("query", "foo"))
                    .orElseThrow();

            Map<String, Object> wire = assembleWireResponse(shaping, nextAction);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wire);
            System.out.println("\n========== AGENT MODE - Tool without cursor ==========");
            System.out.println(prettyJson);
            System.out.println("======================================================\n");

            var root = mapper.readTree(prettyJson);
            // No params block - prose only
            assertTrue(root.at("/metadata/nextAction/params").isMissingNode());
            assertTrue(root.at("/metadata/nextAction/hint").asText()
                    .contains("response_schema"));
        }
    }

    @Nested
    @DisplayName("Wide-object oversize fallback (skeleton)")
    class OversizeFallbackScenario {

        @Test
        @DisplayName("350 wide non-b64 fields → skeleton fallback, max_items=1 nextAction")
        void agentModeOversizeFallback() throws Exception {
            // Wide flat object: 350 fields × ~5.7 KB each, no array
            String wideText = "Sentence with words and spaces. ".repeat(180);
            Map<String, Object> input = new LinkedHashMap<>();
            for (int i = 0; i < 350; i++) input.put("f" + i, wideText);

            ResponseShaper.ShapingResult shaping =
                    shaper.shape(input, null, null, ResponseShaper.Mode.AGENT);

            ToolContext context = apifyDatasetItemsContext();
            Map<String, Object> nextAction = nextActionBuilder
                    .build(context, shaping, Map.of()).orElseThrow();
            Map<String, Object> wire = assembleWireResponse(shaping, nextAction);

            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wire);
            System.out.println("\n========== AGENT MODE - Oversize fallback ==========");
            System.out.println(prettyJson);
            System.out.println("====================================================\n");

            var root = mapper.readTree(prettyJson);
            assertEquals("oversize", root.at("/result/_shape").asText());
            assertEquals(1, root.at("/metadata/nextAction/params/max_items").asInt());
        }
    }

    // ---- helpers --------------------------------------------------------------

    /** Build a synthetic LinkedIn-shaped profile array, ~147 KB at N=10. */
    private static List<Map<String, Object>> buildLinkedInPayload(int n) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", "Engineer #" + i);
            p.put("headline", "CTO at Company " + i);
            p.put("location", "Paris, France");
            p.put("about", "About paragraph. ".repeat(220));       // ~3.7 KB
            p.put("experience", "Worked at place. ".repeat(220));  // ~3.7 KB
            p.put("manifesto", "X".repeat(5000));                  // > 4 KB → clipped, surfaces pattern
            profiles.add(p);
        }
        return profiles;
    }

    /** Apify-shaped tool context (offset + limit + dataset_id params). */
    private static ToolContext apifyDatasetItemsContext() {
        ToolContext c = new ToolContext();
        c.setToolId("01d07247-02fe-4272-a7f0-f2c0d06638b4");
        c.setToolName("get_dataset_items");
        c.setIconSlug("apify");
        c.setEndpoint("/datasets/{dataset_id}/items");
        c.setHttpMethod("GET");
        c.setParameters(List.of(
                new ParamMeta("dataset_id", "Dataset identifier"),
                new ParamMeta("offset", "Items to skip from the start"),
                new ParamMeta("limit", "Maximum items to return"),
                new ParamMeta("clean", "Whether to omit empty fields"),
                new ParamMeta("desc", "Reverse order")));
        return c;
    }

    /** Mirror what {@code ToolExecutionManager} writes into the response body. */
    private static Map<String, Object> assembleWireResponse(
            ResponseShaper.ShapingResult shaping, Map<String, Object> nextAction) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", "get_dataset_items");
        metadata.put("endpoint", "/datasets/{dataset_id}/items");
        metadata.put("method", "GET");
        metadata.put("iconSlug", "apify");
        metadata.put("httpStatus", Map.of("code", 200));
        if (shaping.hasTruncatedPatterns()) {
            List<Map<String, Object>> patterns = new ArrayList<>();
            for (ResponseShaper.TruncationPattern p : shaping.truncatedPatterns()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("path", p.path());
                e.put("count", p.count());
                e.put("bytes", p.bytes());
                patterns.add(e);
            }
            metadata.put("truncatedFields", patterns);
        }
        if (nextAction != null) {
            metadata.put("nextAction", nextAction);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", shaping.data());
        response.put("metadata", metadata);
        response.put("success", true);
        return response;
    }
}
