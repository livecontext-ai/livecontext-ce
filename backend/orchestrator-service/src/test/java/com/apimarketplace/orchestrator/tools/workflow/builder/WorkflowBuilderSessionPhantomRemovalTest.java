package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the "phantom node id" bug: removing a node (or renaming one
 * via modify) used to leave its entry behind in {@code nodeSchemas}, which
 * {@link com.apimarketplace.orchestrator.tools.workflow.builder.session.SessionNodeFinder#nodeExists}
 * treats as "the node still exists". Because {@code findNodeByNormalizedLabel}
 * searches prefixes in the order trigger, mcp, agent, core, interface, table, a
 * node re-created under the SAME label but a DIFFERENT prefix resolved back to the
 * removed (dead) id - producing {@code INVALID_EDGE_SOURCE} on connect and
 * "node not found" on a node still listed as Available.
 *
 * <p>Real scenario (prod conv bc903fa4): an {@code agent:build_report} node was
 * removed and replaced by a {@code core:build_report} code node; connect/remove on
 * "Build Report" kept resolving to the phantom {@code agent:build_report}.
 */
@DisplayName("WorkflowBuilderSession - phantom node id purge on remove/rename")
class WorkflowBuilderSessionPhantomRemovalTest {

    @Test
    @DisplayName("Agent replaced by a code node under the same label resolves to the live core node, not the removed agent phantom")
    void agentReplacedByCodeUnderSameLabelResolvesToCoreNotRemovedAgentPhantom() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Build Report");
        seedNode(s, "core", "Render Report");
        s.getEdges().add(edge("agent:build_report", "core:render_report"));

        String toRemove = s.resolveNodeReference("Build Report");
        assertEquals("agent:build_report", toRemove, "precondition: the label first resolves to the agent node");
        assertTrue(s.removeNode(toRemove));

        assertFalse(s.getNodeSchemas().containsKey("agent:build_report"),
                "removeNode must purge the node's schema so it cannot be resolved as a phantom");
        assertFalse(s.nodeExists("agent:build_report"),
                "the removed agent id must no longer be reported as existing");

        // Re-create under the SAME label as a code (core) node - the prod scenario.
        seedNode(s, "core", "Build Report");

        assertEquals("core:build_report", s.resolveNodeReference("Build Report"),
                "the label must resolve to the live core node, not the removed agent:build_report phantom");
    }

    @ParameterizedTest(name = "remove {0}:swap_me then re-add {1}:swap_me -> resolves to {1}:swap_me")
    @CsvSource({
        "mcp,agent",
        "mcp,core",
        "agent,core",
        "mcp,interface",
        "agent,interface",
        "core,interface",
        "trigger,mcp"
    })
    @DisplayName("Re-adding a node under a freed label resolves to the new node for every prefix ordering")
    void reAddingUnderFreedLabelResolvesToNewNodeForEveryType(String removedPrefix, String readdedPrefix) {
        WorkflowBuilderSession s = newSession();
        seedNode(s, removedPrefix, "Swap Me");

        String removedId = idFor(removedPrefix, "Swap Me");
        assertTrue(s.removeNode(removedId));
        assertFalse(s.nodeExists(removedId), removedPrefix + " phantom must not survive removal");
        assertFalse(s.getNodeSchemas().containsKey(removedId), removedPrefix + " schema must be purged");

        seedNode(s, readdedPrefix, "Swap Me");

        assertEquals(idFor(readdedPrefix, "Swap Me"), s.resolveNodeReference("Swap Me"),
                "label must resolve to the freshly re-added node, not the removed " + removedPrefix + " phantom");
    }

    @ParameterizedTest(name = "removeNode purges the schema for a {0} node")
    @ValueSource(strings = {"trigger", "mcp", "agent", "core", "interface", "table"})
    @DisplayName("removeNode purges the cached schema for every node type")
    void removeNodePurgesSchemaForEveryNodeType(String prefix) {
        WorkflowBuilderSession s = newSession();
        seedNode(s, prefix, "Ghost Node");
        String id = idFor(prefix, "Ghost Node");
        assertTrue(s.getNodeSchemas().containsKey(id), "precondition: schema seeded");

        assertTrue(s.removeNode(id));

        assertFalse(s.getNodeSchemas().containsKey(id), prefix + " schema must be purged on removal");
        assertFalse(s.nodeExists(id), prefix + " id must not be reported as existing after removal");
    }

    @Test
    @DisplayName("Renaming a node via updateAllReferences re-keys its schema so the old id stops resolving to a phantom")
    void renamingNodeRekeysSchemaSoOldIdStopsExisting() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Build Report");

        // Mimic modify's label change: the node's label is updated, then references migrated.
        Map<String, Object> node = s.getMcps().get(0);
        node.put("label", "Builder");
        s.updateAllReferences("agent:build_report", "agent:builder");

        assertFalse(s.getNodeSchemas().containsKey("agent:build_report"),
                "old schema key must be dropped on rename");
        assertTrue(s.getNodeSchemas().containsKey("agent:builder"),
                "schema must be re-keyed to the new id");
        assertEquals("agent:builder", s.getNodeSchemas().get("agent:builder").getNodeId());
        assertFalse(s.nodeExists("agent:build_report"),
                "the freed old id must not be reported as existing");
        assertEquals("agent:builder", s.resolveNodeReference("Builder"));

        // Cached output-reference hints must point at the new id, not the dead old one
        // (ResponseContextBuilder surfaces these verbatim to the agent).
        Collection<String> refs = s.getNodeSchemas().get("agent:builder").getReferenceSyntax().values();
        assertTrue(refs.stream().noneMatch(v -> v.contains("agent:build_report")),
                "reference hints must be rewritten off the old id");
        assertTrue(refs.stream().allMatch(v -> v.contains("agent:builder")),
                "reference hints must be rewritten to the new id");
    }

    // ===== helpers =====

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder().sessionId("test-session").tenantId("t1").build();
    }

    private Map<String, Object> edge(String from, String to) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("to", to);
        return e;
    }

    private String idFor(String prefix, String label) {
        return switch (prefix) {
            case "trigger" -> LabelNormalizer.triggerKey(label);
            case "mcp" -> LabelNormalizer.computeStepNodeId(label, false);
            case "agent" -> LabelNormalizer.computeStepNodeId(label, true);
            case "core" -> LabelNormalizer.coreKey(label);
            case "interface" -> LabelNormalizer.interfaceKey(label);
            case "table" -> LabelNormalizer.tableKey(label);
            default -> throw new IllegalArgumentException(prefix);
        };
    }

    /**
     * Seed a node of the given prefix into its collection plus a cached
     * {@code nodeSchemas} entry - mimicking the post-load state where every node
     * type carries a schema (WorkflowBuilderLoader seeds them on load).
     */
    private void seedNode(WorkflowBuilderSession s, String prefix, String label) {
        String id = idFor(prefix, label);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("label", label);
        switch (prefix) {
            case "trigger" -> { n.put("type", "manual"); s.getTriggers().add(n); }
            case "mcp" -> s.getMcps().add(n);
            case "agent" -> { n.put("isAgent", true); s.getMcps().add(n); }
            case "core" -> { n.put("type", "code"); s.getCores().add(n); }
            case "interface" -> s.getInterfaces().add(n);
            case "table" -> { n.put("type", "get_rows"); s.getTables().add(n); }
            default -> throw new IllegalArgumentException(prefix);
        }
        s.getNodeSchemas().put(id, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(id).nodeType(prefix).label(label)
                .outputs(Map.of("x", "string"))
                .referenceSyntax(Map.of("x", "{{" + id + ".output.x}}"))
                .build());
    }
}
