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

    // ===== present - the agent chooses the user's view =====

    @Test
    @DisplayName("present is a PRIMARY action and never read-only: read-only actions have their visualization stripped, and the visualization IS what present produces")
    void present_isPrimaryAndNotReadOnly() {
        assertThat(WorkflowBuilderActionConfig.PRIMARY_ACTIONS).contains("present");
        assertThat(WorkflowBuilderActionConfig.isReadOnlyAction("present")).isFalse();
        // It changes nothing, so it must not trigger an auto-save or the application immutability gate.
        assertThat(WorkflowBuilderActionConfig.isModifyingAction("present")).isFalse();
        assertThat(WorkflowBuilderActionConfig.PLAN_MUTATING_ACTIONS).doesNotContain("present");
    }

    // ===== stop_run - the counterpart of execute =====

    @Test
    @DisplayName("stop_run is a PRIMARY action, so the LLM sees it next to execute")
    void stopRun_isPrimary() {
        assertThat(WorkflowBuilderActionConfig.PRIMARY_ACTIONS).contains("stop_run");
        assertThat(WorkflowBuilderActionConfig.isValidAction("stop_run")).isTrue();
    }

    @Test
    @DisplayName("the unambiguous phrasings of 'end this run' resolve to stop_run")
    void stopRun_aliasesResolve() {
        assertThat(WorkflowBuilderActionConfig.resolveAlias("stop")).isEqualTo("stop_run");
        assertThat(WorkflowBuilderActionConfig.resolveAlias("cancel_run")).isEqualTo("stop_run");
        assertThat(WorkflowBuilderActionConfig.resolveAlias("stop_run")).isEqualTo("stop_run");
    }

    /**
     * In THIS tool "cancel" is just as plausibly "abandon the draft I am building" (the
     * real action is `discard`), and stop_run without a run_id self-aborts the caller's
     * run. Aliasing it would turn that slip into a terminal stop plus suspended schedules,
     * so the agent gets an "unknown action" it can recover from instead.
     */
    @Test
    @DisplayName("bare 'cancel' is deliberately NOT an alias for stop_run")
    void bareCancelIsNotAStopAlias() {
        assertThat(WorkflowBuilderActionConfig.ACTION_ALIASES).doesNotContainKey("cancel");
        assertThat(WorkflowBuilderActionConfig.isValidAction("cancel")).isFalse();
    }

    @Test
    @DisplayName("stop_run does not mutate the plan, so it is neither auto-saved nor blocked on an application")
    void stopRun_isNotAPlanMutation() {
        assertThat(WorkflowBuilderActionConfig.MODIFYING_ACTIONS).doesNotContain("stop_run");
        assertThat(WorkflowBuilderActionConfig.PLAN_MUTATING_ACTIONS).doesNotContain("stop_run");
    }

    @Test
    @DisplayName("read_rows and find_rows are auto-saved and re-synced: they add a table node to the workflow")
    void rowReadNodes_areSessionWrites() {
        assertThat(WorkflowBuilderActionConfig.MODIFYING_ACTIONS).contains("read_rows", "find_rows");
        assertThat(WorkflowBuilderActionConfig.RESYNC_BEFORE_WRITE_ACTIONS).contains("read_rows", "find_rows");
    }

    @Test
    @DisplayName("every action that writes the session's copy re-syncs from the stored plan first")
    void everySessionWrite_resyncsFirst() {
        assertThat(WorkflowBuilderActionConfig.RESYNC_BEFORE_WRITE_ACTIONS)
                .containsAll(WorkflowBuilderActionConfig.MODIFYING_ACTIONS)
                .contains("save", "finish", "create");
    }

    // ===== READ_ONLY_ACTIONS - side-panel focus suppression =====

    @Test
    @DisplayName("READ_ONLY_ACTIONS contains the 11 canonical read actions")
    void readOnlyActions_canonicalSet() {
        assertThat(WorkflowBuilderActionConfig.READ_ONLY_ACTIONS)
                .containsExactlyInAnyOrder(
                        "get", "list", "runs", "get_run", "wait_run", "get_node_output",
                        "describe", "validate", "search", "help", "get_plan");
    }

    @Test
    @DisplayName("wait_run is a valid hidden read action (regression guard)")
    void waitRun_isAllowed() {
        assertThat(WorkflowBuilderActionConfig.ALL_ACTIONS).contains("wait_run");
        assertThat(WorkflowBuilderActionConfig.HIDDEN_ACTIONS).contains("wait_run");
        assertThat(WorkflowBuilderActionConfig.PLAN_MUTATING_ACTIONS).doesNotContain("wait_run");
        assertThat(WorkflowBuilderActionConfig.MODIFYING_ACTIONS).doesNotContain("wait_run");
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
