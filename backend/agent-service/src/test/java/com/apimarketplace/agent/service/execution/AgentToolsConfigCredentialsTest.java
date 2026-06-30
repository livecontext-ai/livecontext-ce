package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentToolsConfigCredentials} - the shared write-side builder that
 * turns an agent's {@code toolsConfig} into allow-list + access-mode credentials. This is the
 * single source of truth used by BOTH the sub-agent cascade ({@code SubAgentExecutionHandler})
 * and the CLI-bridge session ({@code CliAgentService}); these tests pin the grant semantics and
 * the numeric-id stringify so the two paths enforce resource scope identically.
 */
class AgentToolsConfigCredentialsTest {

    private Map<String, Object> apply(Map<String, Object> toolsConfig) {
        Map<String, Object> creds = new HashMap<>();
        AgentToolsConfigCredentials.apply(creds, toolsConfig);
        return creds;
    }

    @Test
    @DisplayName("custom grant with NUMERIC ids stringifies them (tables:[222] from MCP arrives as List<Integer>)")
    void customGrantStringifiesNumericIds() {
        Map<String, Object> creds = apply(Map.of("tablesGrant", "custom", "tables", List.of(222, 42)));
        assertThat(creds).containsEntry("allowedTableIds", List.of("222", "42"));
    }

    @Test
    @DisplayName("all grant OMITS the key → unrestricted for that resource")
    void allGrantOmitsKey() {
        Map<String, Object> creds = apply(Map.of("tablesGrant", "all", "tables", List.of(1, 2)));
        assertThat(creds).doesNotContainKey("allowedTableIds");
    }

    @Test
    @DisplayName("none grant emits [] (deny-all), never trusting a stale id list behind it")
    void noneGrantEmitsEmptyDeny() {
        Map<String, Object> creds = apply(Map.of("tablesGrant", "none", "tables", List.of(99)));
        assertThat(creds).containsEntry("allowedTableIds", List.of());
    }

    @Test
    @DisplayName("unrecognised grant fails CLOSED → [] (deny-all)")
    void unknownGrantFailsClosed() {
        Map<String, Object> creds = apply(Map.of("tablesGrant", "bogus", "tables", List.of(7)));
        assertThat(creds).containsEntry("allowedTableIds", List.of());
    }

    @Test
    @DisplayName("no grant axis but a list present → emit the (stringified) list")
    void noGrantAxisEmitsList() {
        Map<String, Object> creds = apply(Map.of("tables", List.of(5, 6)));
        assertThat(creds).containsEntry("allowedTableIds", List.of("5", "6"));
    }

    @Test
    @DisplayName("no grant axis and absent list → [] so a missing list never bypasses the allow-list")
    void absentListBecomesEmpty() {
        Map<String, Object> creds = apply(Map.of("mode", "custom"));
        assertThat(creds).containsEntry("allowedTableIds", List.of())
            .containsEntry("allowedWorkflowIds", List.of())
            .containsEntry("allowedInterfaceIds", List.of())
            .containsEntry("allowedAgentIds", List.of())
            .containsEntry("allowedApplicationIds", List.of());
    }

    @Test
    @DisplayName("null toolsConfig denies the 5 internal resources (never falls through to full tenant access)")
    void nullToolsConfigDeniesInternalResources() {
        Map<String, Object> creds = apply(null);
        assertThat(creds).containsEntry("allowedTableIds", List.of())
            .containsEntry("allowedWorkflowIds", List.of())
            .containsEntry("allowedInterfaceIds", List.of())
            .containsEntry("allowedAgentIds", List.of())
            .containsEntry("allowedApplicationIds", List.of());
        // Files are OPT-IN, so a null config must NOT write allowedFileIds (absent = full file access).
        assertThat(creds).doesNotContainKey("allowedFileIds");
    }

    @Test
    @DisplayName("files are OPT-IN: a non-empty list scopes (stringified), empty/absent stays unset")
    void filesAreOptIn() {
        assertThat(apply(Map.of("files", List.of("f-1", "f-2"))))
            .containsEntry("allowedFileIds", List.of("f-1", "f-2"));
        assertThat(apply(Map.of("files", List.of()))).doesNotContainKey("allowedFileIds");
        assertThat(apply(Map.of("mode", "custom"))).doesNotContainKey("allowedFileIds");
    }

    @Test
    @DisplayName("per-resource access modes are forwarded verbatim")
    void accessModesForwarded() {
        Map<String, Object> creds = apply(Map.of(
            "tableAccessMode", "read",
            "workflowAccessMode", "write",
            "fileAccessMode", "read"));
        assertThat(creds).containsEntry("tableAccessMode", "read")
            .containsEntry("workflowAccessMode", "write")
            .containsEntry("fileAccessMode", "read");
    }

    @Test
    @DisplayName("apply() does NOT emit allowedToolIds - the catalog axis is a separate method")
    void applyDoesNotTouchCatalogTools() {
        assertThat(apply(Map.of("mode", "custom", "tools", List.of("t-1"))))
            .doesNotContainKey("allowedToolIds");
    }

    private Map<String, Object> catalog(Map<String, Object> toolsConfig) {
        Map<String, Object> creds = new HashMap<>();
        AgentToolsConfigCredentials.applyCatalogToolsMode(creds, toolsConfig);
        return creds;
    }

    @Test
    @DisplayName("applyCatalogToolsMode: mode=none → allowedToolIds=[] (deny all catalog tools)")
    void catalogNoneDeniesAll() {
        assertThat(catalog(Map.of("mode", "none"))).containsEntry("allowedToolIds", List.of());
    }

    @Test
    @DisplayName("applyCatalogToolsMode: mode=custom → the configured tool list (stringified)")
    void catalogCustomScopesToList() {
        assertThat(catalog(Map.of("mode", "custom", "tools", List.of("gmail_send", "slack_post"))))
            .containsEntry("allowedToolIds", List.of("gmail_send", "slack_post"));
    }

    @Test
    @DisplayName("applyCatalogToolsMode: mode=custom with NO tools list → [] (restricted to nothing)")
    void catalogCustomNoToolsDeniesAll() {
        assertThat(catalog(Map.of("mode", "custom"))).containsEntry("allowedToolIds", List.of());
    }

    @Test
    @DisplayName("applyCatalogToolsMode: mode=all / absent → omitted (unrestricted)")
    void catalogAllOrAbsentIsUnrestricted() {
        assertThat(catalog(Map.of("mode", "all", "tools", List.of("x")))).doesNotContainKey("allowedToolIds");
        assertThat(catalog(Map.of("tables", List.of(1)))).doesNotContainKey("allowedToolIds");
        assertThat(catalog(null)).doesNotContainKey("allowedToolIds");
    }
}
