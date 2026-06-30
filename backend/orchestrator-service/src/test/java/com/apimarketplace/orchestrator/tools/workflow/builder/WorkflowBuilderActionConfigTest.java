package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowBuilderActionConfig")
class WorkflowBuilderActionConfigTest {

    @Test
    @DisplayName("get_node_output is in ALL_ACTIONS (regression guard)")
    void getNodeOutput_isAllowed() {
        assertThat(WorkflowBuilderActionConfig.ALL_ACTIONS).contains("get_node_output");
        assertThat(WorkflowBuilderActionConfig.HIDDEN_ACTIONS).contains("get_node_output");
    }

    @Test
    @DisplayName("ALL_ACTIONS contains every primary action")
    void allActions_containsPrimaries() {
        assertThat(WorkflowBuilderActionConfig.ALL_ACTIONS)
                .containsAll(WorkflowBuilderActionConfig.PRIMARY_ACTIONS);
    }

    @Test
    @DisplayName("ALL_ACTIONS contains every alias")
    void allActions_containsAliases() {
        assertThat(WorkflowBuilderActionConfig.ALL_ACTIONS)
                .containsAll(WorkflowBuilderActionConfig.ACTION_ALIASES.keySet());
    }

    // ===== READ_ONLY_ACTIONS - side-panel focus suppression =====

    @Test
    @DisplayName("READ_ONLY_ACTIONS contains the 10 canonical read actions")
    void readOnlyActions_canonicalSet() {
        assertThat(WorkflowBuilderActionConfig.READ_ONLY_ACTIONS)
                .containsExactlyInAnyOrder(
                        "get", "list", "runs", "get_run", "get_node_output",
                        "describe", "validate", "search", "help", "get_plan");
    }

    @Test
    @DisplayName("isReadOnlyAction returns true for every entry in READ_ONLY_ACTIONS")
    void isReadOnlyAction_truePositive() {
        for (String action : WorkflowBuilderActionConfig.READ_ONLY_ACTIONS) {
            assertThat(WorkflowBuilderActionConfig.isReadOnlyAction(action))
                    .as("isReadOnlyAction(%s)", action)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("isReadOnlyAction returns false for write actions (regression: load/execute/add_node MUST keep their focus)")
    void isReadOnlyAction_falseForWrites() {
        // These actions DO inject visualization on purpose - the side panel SHOULD focus.
        // Putting any of them into READ_ONLY_ACTIONS by mistake would silently break the
        // intended UX where the user expects the panel to switch.
        for (String action : new String[]{
                "load", "execute", "finish", "add_node", "connect", "disconnect",
                "modify", "remove", "undo", "save", "init", "set_plan",
                "pin", "unpin", "publish", "unpublish"
        }) {
            assertThat(WorkflowBuilderActionConfig.isReadOnlyAction(action))
                    .as("isReadOnlyAction(%s) must be FALSE - this action triggers focus by design", action)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("isReadOnlyAction null-safe: returns false (so unknown actions default to focus-triggering)")
    void isReadOnlyAction_nullSafe() {
        assertThat(WorkflowBuilderActionConfig.isReadOnlyAction(null)).isFalse();
        assertThat(WorkflowBuilderActionConfig.isReadOnlyAction("")).isFalse();
        assertThat(WorkflowBuilderActionConfig.isReadOnlyAction("nonexistent_action")).isFalse();
    }

    @Test
    @DisplayName("READ_ONLY_ACTIONS and MODIFYING_ACTIONS are disjoint (action can't be both)")
    void readOnly_and_modifying_disjoint() {
        for (String readAction : WorkflowBuilderActionConfig.READ_ONLY_ACTIONS) {
            assertThat(WorkflowBuilderActionConfig.MODIFYING_ACTIONS)
                    .as("Action '%s' is in BOTH READ_ONLY and MODIFYING - contradictory classification", readAction)
                    .doesNotContain(readAction);
        }
    }
}
