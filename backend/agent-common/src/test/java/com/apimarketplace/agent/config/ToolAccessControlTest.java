package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolAccessControl")
class ToolAccessControlTest {

    @Test
    @DisplayName("denies write actions when internal namespaced access mode is read-only")
    void deniesWriteActionFromNamespacedAccessMode() {
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "read"),
            "agent",
            "execute");

        assertThat(denied).isPresent();
        assertThat(denied.orElseThrow()).contains("read-only").contains("execute");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"workflow", "table", "interface", "agent", "file"})
    @DisplayName("present only switches the user's view, so a read-only agent may use it on every tool that has it")
    void allowsPresentWhenReadOnly(String category) {
        assertThat(ToolAccessControl.checkWriteAccess(
            Map.of("__" + category + "AccessMode__", "read"), category, "present")).isEmpty();
    }

    @Test
    @DisplayName("budgets is a READ, so a read-only agent may ask which caps are nearly spent")
    void allowsTheBudgetsAction() {
        // Anything absent from the read set is treated as a WRITE, so an action that reports
        // spend and mutates nothing is refused by default. The tool help advertises budgets to
        // every agent, which makes the failure the worst shape there is: an action a caller can
        // see and cannot call, with a permission error that names no fix.
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "read"),
            "agent",
            "budgets");

        assertThat(denied).isEmpty();
    }

    @Test
    @DisplayName("allows read actions when internal namespaced access mode is read-only")
    void allowsReadActionFromNamespacedAccessMode() {
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "read"),
            "agent",
            "list");

        assertThat(denied).isEmpty();
    }

    @Test
    @DisplayName("allows agent delegation read actions when access mode is read-only")
    void allowsAgentDelegationReadActions() {
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "read"),
            "agent",
            "inbox");

        assertThat(denied).isEmpty();
    }

    // ==================== file read/write actions (fileAccessMode) ====================
    //
    // The files tool (FilesToolsProvider) gates create_folder / move_to_folder on the
    // "file" category mode (fileAccessMode). list/get/view/visualize/help are READS and
    // must pass in read-mode. The category is SINGULAR "file" - matching the allow-list
    // (allowedFileIds) and the "fileAccessMode" credential key.

    @Test
    @DisplayName("file read-mode allows every file read action (list/get/view/visualize/help)")
    void fileReadModeAllowsReadActions() {
        for (String readAction : List.of("list", "get", "view", "visualize", "help")) {
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("__fileAccessMode__", "read"), "file", readAction))
                .as("file read action '%s' must pass in read-mode", readAction)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("file read-mode denies the file write actions (create_folder / move_to_folder)")
    void fileReadModeDeniesWriteActions() {
        for (String writeAction : List.of("create_folder", "move_to_folder")) {
            var denied = ToolAccessControl.checkWriteAccess(
                    Map.of("__fileAccessMode__", "read"), "file", writeAction);
            assertThat(denied)
                .as("file write action '%s' must be denied in read-mode", writeAction)
                .isPresent();
            assertThat(denied.orElseThrow()).contains("read-only").contains(writeAction);
        }
    }

    @Test
    @DisplayName("file write-mode allows the file write actions")
    void fileWriteModeAllowsWriteActions() {
        for (String writeAction : List.of("create_folder", "move_to_folder")) {
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("__fileAccessMode__", "write"), "file", writeAction))
                .as("file write action '%s' must pass in write-mode", writeAction)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("file absent access mode allows the write actions (default = full access, backward compat)")
    void fileAbsentModeAllowsWriteActions() {
        assertThat(ToolAccessControl.checkWriteAccess(
                new HashMap<>(), "file", "move_to_folder")).isEmpty();
        assertThat(ToolAccessControl.checkWriteAccess(
                new HashMap<>(), "file", "create_folder")).isEmpty();
    }

    @Test
    @DisplayName("plain fileAccessMode (tool-controller path) denies writes, not only the namespaced form")
    void filePlainAccessModeDeniesWrites() {
        var denied = ToolAccessControl.checkWriteAccess(
                Map.of("fileAccessMode", "read"), "file", "move_to_folder");
        assertThat(denied).isPresent();
        assertThat(denied.orElseThrow()).contains("read-only").contains("move_to_folder");
    }

    // ==================== table read/write + allow-list (allowedTableIds) ====================
    //
    // The table tool's data/schema/publish modules gate on the "table" mode (tableAccessMode) and
    // the allowedTableIds allow-list - both via the canonical CREDENTIALS channel (so the create
    // auto-grant round-trips, see TableToolAccess). Reads = get/list/query_rows/help; everything
    // else (create/update/delete, insert_rows/update_rows/delete_rows, add_columns, publish,
    // unpublish) is a WRITE.

    @Test
    @DisplayName("table read-mode allows table reads (get/list/query_rows) and denies every table write")
    void tableReadModeReadsPassWritesDenied() {
        for (String read : List.of("get", "list", "query_rows", "help")) {
            assertThat(ToolAccessControl.checkWriteAccess(Map.of("tableAccessMode", "read"), "table", read))
                .as("table read '%s' must pass in read-mode", read).isEmpty();
        }
        for (String write : List.of("create", "update", "delete", "insert_rows", "update_rows",
                "delete_rows", "add_columns", "publish", "unpublish")) {
            assertThat(ToolAccessControl.checkWriteAccess(Map.of("tableAccessMode", "read"), "table", write))
                .as("table write '%s' must be denied in read-mode", write).isPresent();
        }
    }

    @Test
    @DisplayName("table allow-list resolves from allowedTableIds and the create-grant appends into the same list")
    void tableAllowListAndGrantRoundTrip() {
        assertThat(ToolAccessControl.getAllowedIds(Map.of("allowedTableIds", List.of("5")), "table"))
            .containsExactly("5");

        Map<String, Object> creds = new HashMap<>();
        creds.put("allowedTableIds", new ArrayList<>(List.of("5")));
        ToolAccessControl.grantCreatedResource(creds, "table", "7");
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) creds.get("allowedTableIds");
        assertThat(allowed).containsExactly("5", "7");
    }

    @Test
    @DisplayName("denies agent delegation write actions when access mode is read-only")
    void deniesAgentDelegationWriteActions() {
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "read"),
            "agent",
            "assign");

        assertThat(denied).isPresent();
        assertThat(denied.orElseThrow()).contains("read-only").contains("assign");
    }

    @Test
    @DisplayName("denies write actions when access mode is invalid")
    void deniesWriteActionForInvalidAccessMode() {
        var denied = ToolAccessControl.checkWriteAccess(
            Map.of("__agentAccessMode__", "execute"),
            "agent",
            "assign");

        assertThat(denied).isPresent();
        assertThat(denied.orElseThrow()).contains("invalid").contains("read").contains("write");
    }

    @Test
    @DisplayName("reads allowed IDs from internal namespaced credentials")
    void readsAllowedIdsFromNamespacedCredentials() {
        List<String> allowed = ToolAccessControl.getAllowedIds(
            Map.of("__allowedAgentIds__", List.of("child-1")),
            "agent");

        assertThat(allowed).containsExactly("child-1");
    }

    @Test
    @DisplayName("prefers plain allowed IDs over internal namespaced credentials")
    void prefersPlainAllowedIdsOverNamespacedCredentials() {
        List<String> allowed = ToolAccessControl.getAllowedIds(
            Map.of(
                "allowedAgentIds", List.of("plain-child"),
                "__allowedAgentIds__", List.of("namespaced-child")),
            "agent");

        assertThat(allowed).containsExactly("plain-child");
    }

    @Test
    @DisplayName("stringifies a numeric table allow-list so String comparisons at call sites match")
    void stringifiesNumericTableAllowlist() {
        // Regression: an agent created via MCP stores `tables:[209]` as a List<Integer>.
        // Call sites compare with `.contains(String.valueOf(id))`, so getAllowedIds must
        // return the IDs as Strings: a raw List<Integer> would silently never match.
        List<String> allowed = ToolAccessControl.getAllowedIds(
            Map.of("allowedTableIds", List.of(209, 42)),
            "table");

        assertThat(allowed).containsExactly("209", "42");
    }

    @Test
    @DisplayName("grants created resources back into a namespaced restricted list")
    void grantsCreatedResourceIntoNamespacedAllowedIds() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__allowedAgentIds__", List.of("existing-child"));

        ToolAccessControl.grantCreatedResource(credentials, "agent", "new-child");

        assertThat(credentials)
            .containsEntry("__allowedAgentIds__", List.of("existing-child", "new-child"))
            .doesNotContainKey("allowedAgentIds");
    }

    // ==================== getAllowedIds - list convention ====================
    //
    // getAllowedIds is purely list-driven: the per-family GRANT sentinel is
    // translated to absent/[]/[ids] at every credential-emit point (conversation /
    // AgentNode / SubAgentExecutionHandler), so it never reaches this resolver as a
    // grant key. Absent → null (unrestricted), [] → deny-all, [ids] → those ids.

    @Test
    @DisplayName("getAllowedIds: absent key → null (unrestricted)")
    void getAllowedIdsAbsentKeyUnrestricted() {
        assertThat(ToolAccessControl.getAllowedIds(new HashMap<>(), "workflow")).isNull();
    }

    @Test
    @DisplayName("getAllowedIds: empty list → empty list (explicit deny-all)")
    void getAllowedIdsEmptyListDenyAll() {
        assertThat(ToolAccessControl.getAllowedIds(
            Map.of("__allowedWorkflowIds__", List.of()), "workflow"))
            .isEmpty();
    }

    @Test
    @DisplayName("getAllowedIds: populated list → that list verbatim")
    void getAllowedIdsPopulatedList() {
        assertThat(ToolAccessControl.getAllowedIds(
            Map.of("__allowedWorkflowIds__", List.of("wf-1")), "workflow"))
            .containsExactly("wf-1");
    }

    // ==================== workflow BUILDER read/write actions ====================
    //
    // The builder dispatch (WorkflowBuilderProvider.execute) gates every action on
    // this same READ/WRITE split. Inspection actions must pass in read-mode; mutation
    // actions must be denied. get_node_output is the newly-added builder read action.

    @Test
    @DisplayName("workflow read-mode allows every builder inspection action (incl. get_node_output, read_rows, find_rows)")
    void workflowReadModeAllowsBuilderInspectionActions() {
        for (String readAction : List.of(
                "load", "get", "list", "describe", "validate", "get_plan",
                "get_node_output", "runs", "get_run", "wait_run", "search", "help",
                // builder-internal TABLE reads - pure reads, must pass in read-mode
                "read_rows", "find_rows")) {
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("__workflowAccessMode__", "read"), "workflow", readAction))
                .as("read action '%s' must pass in read-mode", readAction)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("workflow read-mode denies builder mutation actions (incl. table row writes)")
    void workflowReadModeDeniesBuilderMutationActions() {
        for (String writeAction : List.of(
                "init", "add_node", "connect", "disconnect", "modify", "remove",
                "save", "finish", "set_plan", "delete",
                // table row WRITES stay WRITE - denied in read-mode (contrast read_rows/find_rows)
                "insert_row", "update_row", "delete_row")) {
            var denied = ToolAccessControl.checkWriteAccess(
                    Map.of("__workflowAccessMode__", "read"), "workflow", writeAction);
            assertThat(denied)
                .as("write action '%s' must be denied in read-mode", writeAction)
                .isPresent();
            assertThat(denied.orElseThrow()).contains("read-only").contains(writeAction);
        }
    }

    @Test
    @DisplayName("workflow write-mode allows builder mutation actions")
    void workflowWriteModeAllowsBuilderMutationActions() {
        for (String writeAction : List.of("add_node", "finish", "set_plan")) {
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("__workflowAccessMode__", "write"), "workflow", writeAction))
                .as("write action '%s' must pass in write-mode", writeAction)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("workflow absent access mode allows builder mutation actions (default = full access)")
    void workflowAbsentModeAllowsBuilderMutationActions() {
        assertThat(ToolAccessControl.checkWriteAccess(
                new HashMap<>(), "workflow", "add_node")).isEmpty();
        assertThat(ToolAccessControl.checkWriteAccess(
                new HashMap<>(), "workflow", "finish")).isEmpty();
    }
    // ==================== memory read/write actions (memoryAccessMode) ====================
    //
    // Memory is the one family where a write reaches every OTHER agent: what one agent
    // saves is injected into every agent's context in the workspace. A read-only agent
    // must therefore keep get/list/search/help and lose save/delete.

    @Test
    @DisplayName("memory read-mode allows recall, including search, which is what the injected index is for")
    void memoryReadModeAllowsEveryRead() {
        for (String readAction : List.of("get", "list", "search", "help")) {
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("__memoryAccessMode__", "read"), "memory", readAction))
                .as("memory read action '%s' must pass in read-mode", readAction)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("memory read-mode denies save and delete, the only two actions that change what other agents see")
    void memoryReadModeDeniesTheWrites() {
        for (String writeAction : List.of("save", "delete")) {
            var denied = ToolAccessControl.checkWriteAccess(
                    Map.of("__memoryAccessMode__", "read"), "memory", writeAction);
            assertThat(denied)
                .as("memory write action '%s' must be denied in read-mode", writeAction)
                .isPresent();
            assertThat(denied.orElseThrow()).contains("read-only").contains(writeAction);
        }
    }

    @Test
    @DisplayName("search is registered as a READ, not merely allowed by accident")
    void searchIsARead() {
        // If search were classed as a write, a recall-only agent would carry a memory
        // index in its context with no way to open anything in it, which is worse than
        // giving it no memory at all.
        assertThat(ToolAccessControl.isReadAction("memory", "search")).isTrue();
        assertThat(ToolAccessControl.isReadAction("memory", "save")).isFalse();
        assertThat(ToolAccessControl.isReadAction("memory", "delete")).isFalse();
    }

    @Test
    @DisplayName("an agent with no memory mode set keeps full access, the default every family has")
    void memoryDefaultsToWrite() {
        assertThat(ToolAccessControl.checkWriteAccess(Map.of(), "memory", "save")).isEmpty();
        assertThat(ToolAccessControl.checkWriteAccess(null, "memory", "save")).isEmpty();
    }

    @Test
    @DisplayName("application read-mode allows runs / get_run / get_node_output (same three reads the workflow entry lists)")
    void applicationReadModeAllowsRunInspection() {
        // Regression: these three were absent from the application READ set while the workflow
        // entry listed them, and ApplicationCrudModule handles all three. A read-mode agent was
        // therefore told it needed WRITE access to look at a run it was allowed to see. This is a
        // fail-CLOSED misclassification, so it produced a support question, not a breach.
        Map<String, Object> readOnly = Map.of("applicationAccessMode", "read");
        for (String action : java.util.List.of("runs", "get_run", "get_node_output")) {
            assertThat(ToolAccessControl.checkWriteAccess(readOnly, "application", action))
                    .as("application read action '%s' must not require write access", action)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("application read-mode still denies the genuine application writes")
    void applicationReadModeStillDeniesWrites() {
        Map<String, Object> readOnly = Map.of("applicationAccessMode", "read");
        assertThat(ToolAccessControl.checkWriteAccess(readOnly, "application", "execute")).isPresent();
        assertThat(ToolAccessControl.checkWriteAccess(readOnly, "application", "delete")).isPresent();
    }

    @Test
    @DisplayName("skill read-mode denies folder writes and publish, and allows list_folders")
    void skillReadModeCoversFolderAndPublishActions() {
        // The skill family has NO grant axis, so this mode is its ONLY gate: an ungated skill
        // write action is unreachable by any configuration.
        Map<String, Object> readOnly = Map.of("skillAccessMode", "read");
        for (String action : java.util.List.of(
                "create_folder", "rename_folder", "move_folder", "delete_folder", "publish", "unpublish")) {
            assertThat(ToolAccessControl.checkWriteAccess(readOnly, "skill", action))
                    .as("skill write action '%s' must be denied in read mode", action)
                    .isPresent();
        }
        assertThat(ToolAccessControl.checkWriteAccess(readOnly, "skill", "list_folders")).isEmpty();
    }

    @Test
    @DisplayName("interface read-mode denies publish / unpublish")
    void interfaceReadModeDeniesPublishing() {
        Map<String, Object> readOnly = Map.of("interfaceAccessMode", "read");
        assertThat(ToolAccessControl.checkWriteAccess(readOnly, "interface", "publish")).isPresent();
        assertThat(ToolAccessControl.checkWriteAccess(readOnly, "interface", "unpublish")).isPresent();
    }

    @Test
    @DisplayName("interface allow-list resolves from allowedInterfaceIds on the credentials channel")
    void interfaceAllowListResolvesFromCredentials() {
        // The interface CRUD module used to resolve this list from context.variables() while the
        // create-grant appended to credentials. getAllowedIds is the single channel both sides
        // now use; [] means explicit no-access and an absent key means unrestricted.
        assertThat(ToolAccessControl.getAllowedIds(
                Map.of("allowedInterfaceIds", java.util.List.of("i-1")), "interface"))
                .containsExactly("i-1");
        assertThat(ToolAccessControl.getAllowedIds(
                Map.of("__allowedInterfaceIds__", java.util.List.of("i-2")), "interface"))
                .containsExactly("i-2");
        assertThat(ToolAccessControl.getAllowedIds(Map.of(), "interface")).isNull();

        Map<String, Object> credentials = new java.util.HashMap<>();
        credentials.put("allowedInterfaceIds", new java.util.ArrayList<>(java.util.List.of("i-1")));
        ToolAccessControl.grantCreatedResource(credentials, "interface", "i-new");
        assertThat(ToolAccessControl.getAllowedIds(credentials, "interface"))
                .as("a freshly created interface must land in the list the module actually reads")
                .containsExactly("i-1", "i-new");
    }

}
