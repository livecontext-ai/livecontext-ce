package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guard against the cross-prefix label-coexistence bug: node identity is
 * prefix:normalizedLabel, so two nodes of DIFFERENT types could share a
 * normalized label (e.g. agent:build_report + core:build_report). The label
 * resolver ({@code findNodeByNormalizedLabel}) keys purely by normalized label
 * with a fixed prefix priority, so once two coexist every label reference binds
 * silently to whichever prefix sorts first - the other node becomes
 * unaddressable by label. Creators used to guard only the exact prefixed id
 * ({@code validateNodeNotExists} → {@code nodeExists(nodeId)}); the cross-prefix
 * guard {@code WorkflowBuilderSession.validateUniqueLabel} existed but was never
 * wired. These tests lock the wiring in.
 */
@DisplayName("Cross-prefix label uniqueness guard at node creation")
class CrossPrefixLabelUniquenessTest {

    /** Anonymous concrete CreatorBase to reach the protected validateNodeNotExists. */
    private final CreatorBase creator = new CreatorBase() {};

    @Test
    @DisplayName("Adding a code node under a label already held by a live agent is rejected (no silent coexistence)")
    void rejectsCodeNodeWhenAgentWithSameLabelIsLive() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Build Report"); // agent:build_report live

        ToolExecutionResult r = creator.validateNodeNotExists(s, "core:build_report", "Build Report");

        assertNotNull(r, "must reject: agent 'Build Report' already holds this label");
        assertFalse(r.success());
        assertEquals(ToolErrorCode.RESOURCE_ALREADY_EXISTS, r.errorCode());
        assertTrue(r.error().contains("already exists as agent"),
                "message should name the conflicting node type, got: " + r.error());
    }

    @Test
    @DisplayName("Adding a node under a fresh label is allowed")
    void allowsFreshLabel() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Build Report");

        assertNull(creator.validateNodeNotExists(s, "core:render_report", "Render Report"));
    }

    @Test
    @DisplayName("Exact same-type id collision keeps its original error (unchanged behavior)")
    void rejectsExactSameTypeWithOriginalMessage() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Build Report");

        ToolExecutionResult r = creator.validateNodeNotExists(s, "agent:build_report", "Build Report");

        assertNotNull(r);
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode());
        assertTrue(r.error().contains("already exists"));
    }

    @ParameterizedTest(name = "live {0}:swap_me blocks adding {1}:swap_me")
    @CsvSource({
        "agent,core",
        "mcp,agent",
        "mcp,core",
        "table,core",
        "interface,core",
        "core,mcp",
        "core,interface",
        "trigger,mcp"
    })
    @DisplayName("Any cross-prefix label collision is rejected, regardless of prefix ordering")
    void rejectsCrossPrefixForEveryPair(String existingPrefix, String newPrefix) {
        WorkflowBuilderSession s = newSession();
        seedNode(s, existingPrefix, "Swap Me");

        ToolExecutionResult r = creator.validateNodeNotExists(s, idFor(newPrefix, "Swap Me"), "Swap Me");

        assertNotNull(r, existingPrefix + " label should block a new " + newPrefix + " node of the same label");
        assertEquals(ToolErrorCode.RESOURCE_ALREADY_EXISTS, r.errorCode());
    }

    @Test
    @DisplayName("validateUniqueLabel (the shared engine used by all creators + TriggerCreator) flags clash, passes when unique")
    void validateUniqueLabelEngineBehavior() {
        WorkflowBuilderSession s = newSession();
        seedNode(s, "agent", "Weekly Report");

        assertNotNull(s.validateUniqueLabel("Weekly Report", "core"), "same normalized label under another prefix → clash");
        assertNull(s.validateUniqueLabel("Monthly Report", "core"), "distinct label → ok");
    }

    // ===== helpers =====

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder().sessionId("test-session").tenantId("t1").build();
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

    /** Seed a live node of the given prefix into its collection (mirrors the real session shape). */
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
    }
}
