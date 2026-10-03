package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-key scope enforcement on the MCP path (LC-054 and LC-055, security audit
 * 2026-08-13).
 *
 * <p>LC-054: a scope was one entry per TOP-LEVEL tool, and a top-level tool is not a
 * permission. {@code catalog} covers {@code search} and {@code execute}, and
 * {@code execute} spends the owner's stored OAuth credentials against a third party, so a
 * key created to search the catalog could call any API the owner had ever connected.
 *
 * <p>LC-055: the execution context was built with {@code Map.of()} for credentials, and
 * every tool module reads an absent access mode as UNRESTRICTED, so per-agent read-only
 * modes did not exist for an external MCP client.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpProtocolService - API key scopes")
class McpProtocolServiceScopeTest {

    @Mock private AgentToolRegistry registry;
    @Mock private ToolsRegistrationService registrationService;
    @Mock private ObjectProvider<AggregatedToolCatalog> aggregatedCatalogProvider;
    @Mock private ObjectProvider<RemoteToolGateway> remoteToolGatewayProvider;

    private McpProtocolService service;

    @BeforeEach
    void setUp() {
        service = new McpProtocolService(registry, registrationService, new ObjectMapper(),
                aggregatedCatalogProvider, remoteToolGatewayProvider);
        when(registry.hasTool("catalog")).thenReturn(true);
        when(registrationService.executeTool(anyString(), any(), any()))
                .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));
    }

    private Map<String, Object> call(String action) {
        return Map.of("action", action);
    }

    @Nested
    @DisplayName("tool.action scopes (LC-054)")
    class ActionScopes {

        @Test
        @DisplayName("a catalog.search key cannot run catalog(action='execute')")
        void searchScopeCannotExecute() throws Exception {
            Map<String, Object> result = service.callTool(
                    "catalog", call("execute"), "tenant-1", "org-1", "OWNER", Set.of("catalog.search"));

            assertThat(result.get("isError")).isEqualTo(true);
            verify(registrationService, never()).executeTool(anyString(), any(), any());
        }

        @Test
        @DisplayName("the refusal names the actions the key DOES allow, so the agent can retry")
        void refusalNamesAllowedActions() throws Exception {
            Map<String, Object> result = service.callTool(
                    "catalog", call("execute"), "tenant-1", "org-1", "OWNER",
                    Set.of("catalog.search", "catalog.response_schema"));

            assertThat(text(result))
                    .contains("not allowed")
                    .contains("search")
                    .contains("response_schema");
        }

        @Test
        @DisplayName("the same key still runs the action it was granted")
        void grantedActionStillRuns() throws Exception {
            Map<String, Object> result = service.callTool(
                    "catalog", call("search"), "tenant-1", "org-1", "OWNER", Set.of("catalog.search"));

            assertThat(result.get("isError")).isEqualTo(false);
            verify(registrationService).executeTool(anyString(), any(), any());
        }

        @Test
        @DisplayName("a bare tool scope keeps its old meaning - every action")
        void bareToolScopeGrantsEveryAction() throws Exception {
            // Back-compat: keys created before action scopes existed carry bare names and
            // must not start failing.
            Map<String, Object> result = service.callTool(
                    "catalog", call("execute"), "tenant-1", "org-1", "OWNER", Set.of("catalog"));

            assertThat(result.get("isError")).isEqualTo(false);
        }

        @Test
        @DisplayName("an action-scoped key that names no action at all is refused")
        void missingActionIsRefused() throws Exception {
            // Fail closed: guessing which action the tool defaults to is the assumption the
            // check exists to remove.
            Map<String, Object> result = service.callTool(
                    "catalog", Map.of("query", "gmail"), "tenant-1", "org-1", "OWNER",
                    Set.of("catalog.search"));

            assertThat(result.get("isError")).isEqualTo(true);
            verify(registrationService, never()).executeTool(anyString(), any(), any());
        }

        @Test
        @DisplayName("a full-access key (no scopes) is unaffected")
        void fullAccessKeyUnaffected() throws Exception {
            Map<String, Object> result = service.callTool(
                    "catalog", call("execute"), "tenant-1", "org-1", "OWNER", null);

            assertThat(result.get("isError")).isEqualTo(false);
        }

        @Test
        @DisplayName("an action-scoped tool is still listed, or the client cannot call its grant")
        void actionScopedToolStaysVisible() {
            when(registry.getToolsInMcpFormat()).thenReturn(List.of(
                    Map.of("name", "catalog", "description", "Catalog"),
                    Map.of("name", "workflow", "description", "Workflow")));

            List<Map<String, Object>> listed = service.listTools(Set.of("catalog.search"));

            assertThat(listed).extracting(t -> t.get("name")).containsExactly("catalog");
            assertThat(service.hasTool("catalog", Set.of("catalog.search"))).isTrue();
            assertThat(service.hasTool("workflow", Set.of("catalog.search"))).isFalse();
        }
    }

    @Nested
    @DisplayName("restriction credentials (LC-055)")
    class RestrictionCredentials {

        @Test
        @DisplayName("a read-only scope reaches the tool as a read access mode, not an empty map")
        void readOnlyScopeBecomesReadMode() throws Exception {
            service.callTool("catalog", call("search"), "tenant-1", "org-1", "OWNER",
                    Set.of("catalog.search"));

            ArgumentCaptor<ToolsProvider.ToolExecutionContext> context =
                    ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
            verify(registrationService).executeTool(anyString(), any(), context.capture());

            assertThat(context.getValue().credentials())
                    .as("pre-fix this was Map.of(), which every module reads as UNRESTRICTED")
                    .containsEntry("catalogAccessMode", "read");
        }

        @Test
        @DisplayName("one write action anywhere in the tool's grants makes the tool writable")
        void writeActionMakesToolWritable() {
            assertThat(McpProtocolService.restrictionCredentials(
                    Set.of("catalog.search", "catalog.execute")))
                    .containsEntry("catalogAccessMode", "write");
        }

        @Test
        @DisplayName("a bare tool scope is full access to that tool")
        void bareToolScopeIsWrite() {
            assertThat(McpProtocolService.restrictionCredentials(Set.of("catalog")))
                    .containsEntry("catalogAccessMode", "write");
        }

        @Test
        @DisplayName("the files tool maps onto the singular category the access control uses")
        void filesToolMapsToFileCategory() {
            assertThat(McpProtocolService.restrictionCredentials(Set.of("files.list")))
                    .containsEntry("fileAccessMode", "read");
        }

        @Test
        @DisplayName("a full-access key keeps an empty map, which is what unrestricted means")
        void fullAccessKeyKeepsEmptyMap() {
            assertThat(McpProtocolService.restrictionCredentials(null)).isEmpty();
        }
    }

    /**
     * The aggregated (cloud) half of LC-055. A tool this process does not host is executed by
     * a sibling service, and {@code RemoteToolGateway} used to forward only the identity
     * headers and the arguments: the receiving {@code /api/agent-tools/execute} rebuilds its
     * credentials map key by key from the request body, and every tool module reads an absent
     * {@code <category>AccessMode} as UNRESTRICTED. So the identical scoped key was restricted
     * on a tool hosted in this process and unrestricted on one hosted next door.
     *
     * <p>The previous pass answered that with a local {@code restrictedWriteAllowed} guard,
     * which could never deny: {@code actionInScope} runs first and only lets through an action
     * the key was granted, and the mode was derived from those same grants - so a read action
     * short-circuits inside {@code ToolAccessControl.checkWriteAccess} and a write action has
     * already set the mode to write. It was dead code that read as a control, and the sibling
     * still received nothing. The modes are now FORWARDED instead, which is the only place the
     * decision can actually be applied.
     */
    @Nested
    @DisplayName("aggregated tools receive the key's derived access modes (LC-055)")
    class RemoteRestrictions {

        @Mock private RemoteToolGateway gateway;
        @Mock private AggregatedToolCatalog aggregatedCatalog;

        @SuppressWarnings("unchecked")
        private Map<String, Object> forwardedRestrictions(Set<String> scopes, String action) {
            when(registry.hasTool("table")).thenReturn(false);
            when(aggregatedCatalogProvider.getIfAvailable()).thenReturn(aggregatedCatalog);
            when(aggregatedCatalog.knows("table")).thenReturn(true);
            when(remoteToolGatewayProvider.getIfAvailable()).thenReturn(gateway);
            when(gateway.execute(anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(ToolsProvider.ToolExecutionResult.success(Map.of("ok", true)));

            assertThatNoRefusal(() -> service.callTool("table", call(action),
                    "tenant-1", "org-1", "MEMBER", scopes));

            ArgumentCaptor<Map<String, Object>> restrictions = ArgumentCaptor.forClass(Map.class);
            verify(gateway).execute(eq("table"), any(), eq("tenant-1"), eq("org-1"), eq("MEMBER"),
                    restrictions.capture());
            return restrictions.getValue();
        }

        @Test
        @DisplayName("a read-only key reaches the sibling as a read access mode")
        void readOnlyKeyIsForwardedAsReadMode() {
            // Pre-fix the sibling received no credentials at all, and absent means UNRESTRICTED
            // to every tool module, so this key had full write authority inside the remote tool.
            assertThat(forwardedRestrictions(Set.of("table.list"), "list"))
                    .containsEntry("tableAccessMode", "read");
        }

        @Test
        @DisplayName("one write grant on the tool is forwarded as write, not silently downgraded")
        void writeGrantIsForwardedAsWriteMode() {
            assertThat(forwardedRestrictions(Set.of("table.list", "table.create"), "create"))
                    .containsEntry("tableAccessMode", "write");
        }

        @Test
        @DisplayName("a full-access key forwards an empty map, which is what unrestricted means")
        void fullAccessKeyForwardsNothing() {
            assertThat(forwardedRestrictions(null, "list")).isEmpty();
        }

        @Test
        @DisplayName("the local and the remote path carry the SAME modes for the same key")
        void localAndRemotePathsAgree() throws Exception {
            // The asymmetry IS the finding: one key must not mean one thing on a tool this
            // process hosts and another on a tool it does not. Same key, one call on each path.
            Set<String> scopes = Set.of("catalog.search", "table.list");

            Map<String, Object> remote = forwardedRestrictions(scopes, "list");

            service.callTool("catalog", call("search"), "tenant-1", "org-1", "MEMBER", scopes);
            ArgumentCaptor<ToolsProvider.ToolExecutionContext> local =
                    ArgumentCaptor.forClass(ToolsProvider.ToolExecutionContext.class);
            verify(registrationService).executeTool(anyString(), any(), local.capture());

            assertThat(remote)
                    .containsEntry("catalogAccessMode", "read")
                    .containsEntry("tableAccessMode", "read")
                    .isEqualTo(local.getValue().credentials());
        }

        private void assertThatNoRefusal(ThrowingCall call) {
            try {
                assertThat(call.get().get("isError")).isEqualTo(false);
            } catch (Exception e) {
                throw new AssertionError("the call must not fail", e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        Map<String, Object> get() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return String.valueOf(content.get(0).get("text"));
    }
}
