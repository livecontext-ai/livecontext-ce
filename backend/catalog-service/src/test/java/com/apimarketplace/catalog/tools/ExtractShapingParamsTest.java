package com.apimarketplace.catalog.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CatalogExecuteModule#extractShapingParams(Map, Map)}.
 *
 * <p>Locks the cache-strip site: {@code expand} and {@code max_items} must
 * NEVER survive in the {@code remainingParams} Map fed to
 * {@link com.apimarketplace.catalog.service.ResponseCache#buildKey(String, Map)}.
 * Two calls with the same call-params and different shaping params hit the
 * cache; shaping then runs fresh per call on the cached tree.
 */
@DisplayName("CatalogExecuteModule.extractShapingParams")
class ExtractShapingParamsTest {

    @Test
    @DisplayName("extractShapingParamsStripsFromTopLevel - top-level expand/max_items pulled out, input strip is mirrored")
    void extractShapingParamsStripsFromTopLevel() {
        // `parameters` is the agent's tool-call wrapper. `input` is the inner
        // `params` map forwarded as the catalog HTTP body. Shaping params at
        // the TOP LEVEL still trigger a strip from `input` to defend against
        // duplication.
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("expand", List.of("items[].about"));
        parameters.put("max_items", 5);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataset_id", "X");
        input.put("expand", List.of("ignored-by-top-level"));
        input.put("max_items", 99);

        CatalogExecuteModule.ShapingParams result =
                CatalogExecuteModule.extractShapingParams(parameters, input);

        assertFalse(result.remainingParams().containsKey("expand"),
                "top-level shaping params strip duplicates from input");
        assertFalse(result.remainingParams().containsKey("max_items"));
        assertEquals("X", result.remainingParams().get("dataset_id"));
        assertEquals(List.of("items[].about"), result.expand(), "top-level expand wins");
        assertEquals(5, result.maxItems(), "top-level max_items wins");
    }

    @Test
    @DisplayName("extractShapingParamsStripsFromInputNestedLocation - LLM placed shaping params inside `input`")
    void extractShapingParamsStripsFromInputNestedLocation() {
        Map<String, Object> parameters = Map.of();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataset_id", "X");
        input.put("expand", List.of("body"));
        input.put("max_items", 3);

        CatalogExecuteModule.ShapingParams result =
                CatalogExecuteModule.extractShapingParams(parameters, input);

        assertFalse(result.remainingParams().containsKey("expand"));
        assertFalse(result.remainingParams().containsKey("max_items"));
        assertEquals("X", result.remainingParams().get("dataset_id"));
        assertEquals(List.of("body"), result.expand());
        assertEquals(3, result.maxItems());
    }

    @Test
    @DisplayName("extractShapingParamsNoShapingParams - nothing extracted, input passed through")
    void extractShapingParamsNoShapingParams() {
        Map<String, Object> parameters = Map.of();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataset_id", "X");

        CatalogExecuteModule.ShapingParams result =
                CatalogExecuteModule.extractShapingParams(parameters, input);

        assertEquals(1, result.remainingParams().size());
        assertEquals("X", result.remainingParams().get("dataset_id"));
        assertNull(result.expand());
        assertNull(result.maxItems());
    }

    @Test
    @DisplayName("extractShapingParamsTopLevelTakesPriorityOverInput - both locations strip from input either way")
    void extractShapingParamsTopLevelTakesPriorityOverInput() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("expand", List.of("a"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dataset_id", "X");
        input.put("expand", List.of("b"));   // duplicated; should be stripped from remaining

        CatalogExecuteModule.ShapingParams result =
                CatalogExecuteModule.extractShapingParams(parameters, input);

        assertEquals(List.of("a"), result.expand(), "top-level expand wins");
        assertFalse(result.remainingParams().containsKey("expand"),
                "duplicate expand key inside input must also be stripped");
    }
}
