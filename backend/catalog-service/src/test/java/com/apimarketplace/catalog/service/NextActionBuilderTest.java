package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ResponseShaper.Action;
import com.apimarketplace.catalog.service.ResponseShaper.ShapingResult;
import com.apimarketplace.catalog.service.ResponseShaper.TruncationPattern;
import com.apimarketplace.catalog.service.ToolContextService.ToolContext;
import com.apimarketplace.catalog.service.ToolContextService.ToolContext.ParamMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NextActionBuilder}.
 *
 * <p>Locks the agent-facing contract: hint always names the resolved cursor,
 * params block merges with the agent's original parameters, hint mentions
 * "zero-based" semantics, fallback prose points at {@code response_schema}.
 */
@DisplayName("NextActionBuilder")
class NextActionBuilderTest {

    private NextActionBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new NextActionBuilder(new PaginationParamResolver());
    }

    private ToolContext context(String... paramNames) {
        ToolContext c = new ToolContext();
        c.setToolId("tool-uuid-123");
        c.setToolName("get_dataset_items");
        c.setIconSlug("apify");
        c.setParameters(java.util.Arrays.stream(paramNames)
                .map(n -> new ParamMeta(n, ""))
                .toList());
        return c;
    }

    private ShapingResult digestResult(int total, int previewItems) {
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("_shape", "array_digest");
        digest.put("total_items", total);
        digest.put("preview_items", previewItems);
        digest.put("items", List.of());
        if (previewItems < total) {
            digest.put("skipped_from", previewItems);
            digest.put("skipped_to", total - 1);
        }
        Object data = List.of(digest);
        return new ShapingResult(data, List.of(), Action.ARRAY_DIGESTED, 200_000, 12_000);
    }

    // ---- branch A: digest -----------------------------------------------------

    @Test
    @DisplayName("nextActionDigestResolvesCursorFromInputSchema - Apify offset+limit")
    void nextActionDigestResolvesCursorFromInputSchema() {
        ToolContext ctx = context("dataset_id", "offset", "limit", "clean");
        Map<String, Object> originalParams = Map.of("dataset_id", "X", "clean", true);

        Map<String, Object> action = builder.build(ctx, digestResult(10, 3), originalParams).orElseThrow();

        assertEquals("catalog", action.get("tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) action.get("params");
        assertEquals("tool-uuid-123", params.get("tool_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedParams = (Map<String, Object>) params.get("parameters");
        assertEquals("X", mergedParams.get("dataset_id"));
        assertEquals(true, mergedParams.get("clean"));
        assertEquals(3, mergedParams.get("offset"));
        assertEquals(1, mergedParams.get("limit"));
    }

    @Test
    @DisplayName("nextActionDigestMergesWithOriginalParams - all original keys preserved")
    void nextActionDigestMergesWithOriginalParams() {
        ToolContext ctx = context("dataset_id", "offset", "limit");
        Map<String, Object> originalParams = new LinkedHashMap<>();
        originalParams.put("dataset_id", "I6AGg6mVOEezjNlWb");
        originalParams.put("query", "foo");

        Map<String, Object> action = builder.build(ctx, digestResult(10, 3), originalParams).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) action.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedParams = (Map<String, Object>) params.get("parameters");
        assertEquals("I6AGg6mVOEezjNlWb", mergedParams.get("dataset_id"));
        assertEquals("foo", mergedParams.get("query"));
        assertEquals(3, mergedParams.get("offset"));
        assertEquals(1, mergedParams.get("limit"));
    }

    @Test
    @DisplayName("nextActionDigestProseOnlyWhenNoCursor - hint points at response_schema")
    void nextActionDigestProseOnlyWhenNoCursor() {
        ToolContext ctx = context("foo", "bar"); // no cursor candidate
        Map<String, Object> action = builder.build(ctx, digestResult(10, 3), Map.of()).orElseThrow();

        assertNull(action.get("params"), "no params block when no cursor resolved");
        String hint = (String) action.get("hint");
        assertTrue(hint.contains("response_schema"));
        assertTrue(hint.contains("max_items=1"));
    }

    @Test
    @DisplayName("nextActionDigestZeroBasedSemantics - hint contains 'zero-based'")
    void nextActionDigestZeroBasedSemantics() {
        ToolContext ctx = context("offset", "limit");
        Map<String, Object> action = builder.build(ctx, digestResult(10, 3), Map.of()).orElseThrow();
        assertTrue(((String) action.get("hint")).toLowerCase().contains("zero-based"));
    }

    @Test
    @DisplayName("nextActionDigestNotionCursorOnlyNoSize - emits cursor without size")
    void nextActionDigestNotionCursorOnlyNoSize() {
        // start_cursor is the cursor; page_size IS the size in real Notion API,
        // BUT this test simulates a tool where ONLY cursor was registered.
        ToolContext ctx = context("database_id", "start_cursor");
        Map<String, Object> action = builder.build(ctx, digestResult(10, 3), Map.of("database_id", "X")).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) action.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) params.get("parameters");
        assertEquals("X", merged.get("database_id"));
        assertEquals(3, merged.get("start_cursor"));
        assertNull(merged.get("limit"), "no size param injected when none resolved");
    }

    // ---- branch B: leaves only ------------------------------------------------

    @Test
    @DisplayName("nextActionLeafTopThreePatternsByBytes")
    void nextActionLeafTopThreePatternsByBytes() {
        ToolContext ctx = context("dataset_id");
        List<TruncationPattern> patterns = List.of(
                new TruncationPattern("items[].experience", 10, 8214),
                new TruncationPattern("items[].about", 10, 5132),
                new TruncationPattern("items[].headline", 4, 2680),
                new TruncationPattern("items[].location", 5, 1500)
        );
        ShapingResult shaping = new ShapingResult(Map.of(), patterns, Action.LEAVES_ONLY, 80_000, 60_000);
        Map<String, Object> originalParams = Map.of("dataset_id", "X");

        Map<String, Object> action = builder.build(ctx, shaping, originalParams).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) action.get("params");
        @SuppressWarnings("unchecked")
        List<String> expand = (List<String>) params.get("expand");
        assertEquals(3, expand.size());
        // Patterns come pre-sorted by bytes desc; top 3 are experience > about > headline.
        assertEquals("items[].experience", expand.get(0));
        assertEquals("items[].about", expand.get(1));
        assertEquals("items[].headline", expand.get(2));
        // Original params merged.
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) params.get("parameters");
        assertEquals("X", merged.get("dataset_id"));
    }

    // ---- branch C: oversize ---------------------------------------------------

    @Test
    @DisplayName("nextActionFallbackSuggestsMaxItems - max_items at top of params, not inside parameters")
    void nextActionFallbackSuggestsMaxItems() {
        ToolContext ctx = context("dataset_id");
        ShapingResult shaping = new ShapingResult(
                Map.of("_shape", "oversize", "total_size_bytes", 1_000_000),
                List.of(), Action.OVERSIZE_FALLBACK, 1_000_000, 5_000);
        Map<String, Object> action = builder.build(ctx, shaping, Map.of("dataset_id", "X")).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) action.get("params");
        // max_items must be at the top level of `params`, NOT inside `parameters`.
        assertEquals(1, params.get("max_items"));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) params.get("parameters");
        assertNull(merged.get("max_items"), "max_items must not leak into parameters");
        assertEquals("X", merged.get("dataset_id"));
    }

    // ---- branch D: untouched falls through -----------------------------------

    @Test
    @DisplayName("untouchedShapingFallsThrough - returns Optional.empty")
    void untouchedShapingFallsThrough() {
        ToolContext ctx = context("foo");
        ShapingResult untouched = new ShapingResult(Map.of("a", 1), List.of(), Action.UNTOUCHED, 100, 100);
        Optional<Map<String, Object>> action = builder.build(ctx, untouched, Map.of());
        assertTrue(action.isEmpty());
    }
}
