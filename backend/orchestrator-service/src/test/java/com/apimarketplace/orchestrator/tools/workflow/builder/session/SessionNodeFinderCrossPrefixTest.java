package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionNodeFinder cross-prefix resolution.
 *
 * When the LLM provides a wrong prefix (e.g., mcp:download_image instead of core:download_image),
 * resolveNodeReference should still find the correct node by trying all known prefixes.
 */
@DisplayName("SessionNodeFinder - Cross-Prefix Resolution")
class SessionNodeFinderCrossPrefixTest {

    @Test
    @DisplayName("Should resolve reference with correct prefix directly")
    void shouldResolveDirectReference() {
        SessionNodeFinder finder = createFinderWithCoreNode("Download Image");

        String resolved = finder.resolveNodeReference("core:download_image");

        assertEquals("core:download_image", resolved);
    }

    @Test
    @DisplayName("Should resolve mcp: prefix to core: when node is a core node")
    void shouldResolveMcpPrefixToCorePrefix() {
        SessionNodeFinder finder = createFinderWithCoreNode("Download Image");

        String resolved = finder.resolveNodeReference("mcp:download_image");

        assertEquals("core:download_image", resolved);
    }

    @Test
    @DisplayName("Should resolve core: prefix to mcp: when node is an mcp node")
    void shouldResolveCorePrefixToMcpPrefix() {
        SessionNodeFinder finder = createFinderWithMcpNode("API Call");

        String resolved = finder.resolveNodeReference("core:api_call");

        assertEquals("mcp:api_call", resolved);
    }

    @Test
    @DisplayName("Should resolve by label when no prefix matches")
    void shouldResolveByLabel() {
        SessionNodeFinder finder = createFinderWithCoreNode("Download Image");

        String resolved = finder.resolveNodeReference("Download Image");

        assertEquals("core:download_image", resolved);
    }

    @Test
    @DisplayName("Should resolve table: prefix to core: when node is core")
    void shouldResolveTablePrefixToCore() {
        SessionNodeFinder finder = createFinderWithCoreNode("Wait All");

        String resolved = finder.resolveNodeReference("table:wait_all");

        assertEquals("core:wait_all", resolved);
    }

    @Test
    @DisplayName("Should return as-is when no node found with any prefix")
    void shouldReturnAsIsWhenNotFound() {
        SessionNodeFinder finder = createFinderWithCoreNode("Download Image");

        String resolved = finder.resolveNodeReference("mcp:nonexistent_node");

        assertEquals("mcp:nonexistent_node", resolved);
    }

    @Test
    @DisplayName("Should handle null reference")
    void shouldHandleNullReference() {
        SessionNodeFinder finder = createFinderWithCoreNode("Test");

        String resolved = finder.resolveNodeReference(null);

        assertNull(resolved);
    }

    @Test
    @DisplayName("Should resolve cross-prefix with port suffix preserved")
    void shouldResolveCrossPrefixWithPortSuffix() {
        SessionNodeFinder finder = createFinderWithCoreNode("My Fork");

        // Reference with wrong prefix + port suffix
        String resolved = finder.resolveNodeReference("mcp:my_fork:branch_0");

        assertEquals("core:my_fork:branch_0", resolved);
    }

    // ===== Helper methods =====

    private SessionNodeFinder createFinderWithCoreNode(String label) {
        List<Map<String, Object>> cores = new ArrayList<>();
        Map<String, Object> coreNode = new LinkedHashMap<>();
        coreNode.put("id", "core:" + normalizeLabel(label));
        coreNode.put("label", label);
        coreNode.put("type", "download_file");
        cores.add(coreNode);

        return new SessionNodeFinder(
            List.of(),  // triggers
            List.of(),  // mcps
            cores,
            Map.of()    // nodeSchemas
        );
    }

    private SessionNodeFinder createFinderWithMcpNode(String label) {
        List<Map<String, Object>> mcps = new ArrayList<>();
        Map<String, Object> mcpNode = new LinkedHashMap<>();
        mcpNode.put("id", "mcp:" + normalizeLabel(label));
        mcpNode.put("label", label);
        mcps.add(mcpNode);

        return new SessionNodeFinder(
            List.of(),  // triggers
            mcps,
            List.of(),  // cores
            Map.of()    // nodeSchemas
        );
    }

    private String normalizeLabel(String label) {
        return label.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
