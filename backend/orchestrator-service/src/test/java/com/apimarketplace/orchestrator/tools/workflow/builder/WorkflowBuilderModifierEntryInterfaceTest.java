package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single-entry invariant on the agent write path: an application has ONE entry page.
 * The canvas builder enforces it on edit, but {@code workflow(action='modify')} used
 * to persist a plan with several {@code isEntryInterface=true} interfaces - the
 * author's intent then silently lost to the showcase resolver's findFirst().
 *
 * <p>IMPORTANT SHAPE: a plan interface entry's {@code id} is the interface ENTITY
 * UUID, while node references resolve to {@code interface:<label>} keys. The nodes
 * here mirror that (UUID ids + labels) because an id-based keep-check demoted the
 * very node that was just flagged - caught live in e2e, invisible to a test that
 * used {@code interface:<label>} ids.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - single entry interface invariant")
class WorkflowBuilderModifierEntryInterfaceTest {

    private static final String HOME_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String RESULTS_UUID = "22222222-2222-2222-2222-222222222222";

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession sessionWithTwoInterfaces(boolean firstIsEntry) {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Map<String, Object> home = new LinkedHashMap<>();
        home.put("id", HOME_UUID);
        home.put("type", "interface");
        home.put("label", "Home");
        home.put("isEntryInterface", firstIsEntry);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("id", RESULTS_UUID);
        results.put("type", "interface");
        results.put("label", "Results");
        results.put("isEntryInterface", false);
        session.getInterfaces().add(home);
        session.getInterfaces().add(results);
        return session;
    }

    private Map<String, Object> iface(WorkflowBuilderSession session, String label) {
        return session.getInterfaces().stream()
                .filter(i -> label.equals(i.get("label")))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("setting is_entry_interface=true on one interface demotes the previously flagged one - and NEVER the node itself")
    void settingEntryDemotesTheOtherInterface() {
        WorkflowBuilderSession session = sessionWithTwoInterfaces(true);

        ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                "node", "Results",
                "params", Map.of("is_entry_interface", true)
        ));

        assertThat(result.success()).isTrue();
        assertThat(iface(session, "Results").get("isEntryInterface")).isEqualTo(true);
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(String.valueOf(data.get("entry_interface_moved"))).contains("Home");
        assertThat(String.valueOf(data.get("entry_interface_moved"))).doesNotContain("Results");
    }

    @Test
    @DisplayName("re-flagging the CURRENT entry is a no-op on siblings and never demotes itself")
    void reflaggingCurrentEntryIsStable() {
        // The live bug: the keep-check compared node ids, but plan interface ids are
        // entity UUIDs, so the just-flagged node failed its own keep-check and was
        // demoted - the app ended with ZERO entry pages.
        WorkflowBuilderSession session = sessionWithTwoInterfaces(true);

        ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                "node", "Home",
                "params", Map.of("is_entry_interface", true)
        ));

        assertThat(result.success()).isTrue();
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(true);
        assertThat(iface(session, "Results").get("isEntryInterface")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).doesNotContainKey("entry_interface_moved");
    }

    @Test
    @DisplayName("modifying OTHER params never touches a sibling's entry flag")
    void unrelatedModifyLeavesSiblingEntryAlone() {
        WorkflowBuilderSession session = sessionWithTwoInterfaces(true);

        ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                "node", "Results",
                "params", Map.of("variable_mapping", Map.of("title", "{{trigger:t.output.q}}"))
        ));

        assertThat(result.success()).isTrue();
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(true);
    }

    @Test
    @DisplayName("setting is_entry_interface=false does not demote anyone")
    void settingEntryFalseDoesNotDemote() {
        WorkflowBuilderSession session = sessionWithTwoInterfaces(true);

        ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                "node", "Results",
                "params", Map.of("is_entry_interface", false)
        ));

        assertThat(result.success()).isTrue();
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).doesNotContainKey("entry_interface_moved");
    }

    @Test
    @DisplayName("undo of an entry move restores BOTH sides - the node's old flag AND the demoted sibling")
    void undoRestoresTheDemotedSibling() {
        // Without this, "undo to revert this change" left the plan with ZERO entry
        // pages: old_values only covers the modified node, not the sibling the
        // single-entry invariant demoted.
        WorkflowBuilderSession session = sessionWithTwoInterfaces(true);
        ToolExecutionResult moved = modifier.executeModifyNode(session, Map.of(
                "node", "Results",
                "params", Map.of("is_entry_interface", true)
        ));
        assertThat(moved.success()).isTrue();
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(false);

        ToolExecutionResult undone = modifier.executeUndo(session);

        assertThat(undone.success()).isTrue();
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(true);
        assertThat(iface(session, "Results").get("isEntryInterface")).isEqualTo(false);
    }

    @Test
    @DisplayName("enforceSingleEntryInterface also clears a legacy snake_case flag remnant")
    void enforceClearsLegacySnakeCaseFlag() {
        WorkflowBuilderSession session = sessionWithTwoInterfaces(false);
        // A plan written by an older agent path can carry the raw snake_case key.
        iface(session, "Home").remove("isEntryInterface");
        iface(session, "Home").put("is_entry_interface", true);

        List<String> cleared = session.enforceSingleEntryInterface(iface(session, "Results"));

        assertThat(cleared).containsExactly("Home");
        assertThat(iface(session, "Home").get("isEntryInterface")).isEqualTo(false);
        assertThat(iface(session, "Home")).doesNotContainKey("is_entry_interface");
    }
}
