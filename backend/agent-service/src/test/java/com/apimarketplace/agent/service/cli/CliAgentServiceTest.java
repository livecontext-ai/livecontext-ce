package com.apimarketplace.agent.service.cli;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.dto.cli.CliSessionStartRequest;
import com.apimarketplace.agent.dto.cli.CliSessionResponse;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.execution.AgentToolsConfigCredentials;
import com.apimarketplace.agent.service.execution.CoreToolsCache;
import com.apimarketplace.agent.service.execution.RemoteToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CliAgentService}, focused on credential injection
 * (especially __agentId__ for sub-agent budget chain tracking).
 */
class CliAgentServiceTest {

    private CoreToolsCache coreToolsCache;
    private AgentService agentService;
    private CliAgentService service;

    @BeforeEach
    void setUp() {
        coreToolsCache = mock(CoreToolsCache.class);
        when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());
        agentService = mock(AgentService.class);

        service = new CliAgentService(
            coreToolsCache,
            mock(RemoteToolExecutionService.class),
            mock(AgentObservabilityService.class),
            agentService,
            new ObjectMapper()
        );
    }

    /** Stub the bound agent so startSession resolves its toolsConfig (workspace-scoped getAgent). */
    private void stubBoundAgent(String agentId, Map<String, Object> toolsConfig) {
        AgentEntity agent = mock(AgentEntity.class);
        when(agent.getToolsConfig()).thenReturn(toolsConfig);
        when(agentService.getAgent(eq(UUID.fromString(agentId)), any(), any(), any()))
            .thenReturn(Optional.of(agent));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSessionCredentials(String sessionId) throws Exception {
        Field sessionsField = CliAgentService.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<String, Object> sessions =
            (ConcurrentHashMap<String, Object>) sessionsField.get(service);
        Object session = sessions.get(sessionId);
        Field credField = session.getClass().getDeclaredField("credentials");
        credField.setAccessible(true);
        return (Map<String, Object>) credField.get(session);
    }

    @Nested
    @DisplayName("AgentId credential injection")
    class AgentIdInjection {

        @Test
        @DisplayName("should inject __agentId__ when agentId is provided in request")
        void shouldInjectAgentIdWhenPresent() throws Exception {
            String agentId = UUID.randomUUID().toString();
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, agentId, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");
            assertThat(response).isNotNull();
            assertThat(response.sessionId()).isNotBlank();

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).containsEntry("__agentId__", agentId);
        }

        @Test
        @DisplayName("startSession scopes core tool schemas to the bridged enabledModules - catalog-free set → FILTERED getCoreTools, never the unfiltered overload (the bridge billing point)")
        void startSessionScopesCoreToolsByEnabledModules() throws Exception {
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            // The bridge forwards the agent's mode-derived module keys (enabledModules = 1st arg).
            // "table"+"agent" granted, catalog NOT - every bridge agent (claude-code/codex/…) bills
            // exactly the schemas this Set yields. A revert of CliAgentService to the unfiltered
            // getCoreTools(), or resolveModules() returning all keys, reddens this test.
            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of("table", "agent"), null, "test-model", null, null, null, null, null, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .as("CLI session must scope core tool schemas to the bridged modules (catalog dropped)")
                .contains("table", "agent")
                .doesNotContain("catalog");
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("startSession with an EMPTY enabledModules → ZERO core tools (mode=off / tool-less agent), never the table-only fallback or the unfiltered overload")
        void startSessionWithEmptyEnabledModulesAdvertisesNoTools() throws Exception {
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            // enabledModules=[] (a mode=off agent) → resolveModules([]) → no modules → build([]) →
            // no core tool names. Pre-fix resolveModules returned Set.of("table") for [] → reddens.
            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of(), null, "test-model", null, null, null, null, null, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .as("an empty module set must advertise ZERO core tools, not the legacy table-only set")
                .isEmpty();
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("should inject __approvedToolActions__ so the bridge gate skips on resume")
        void shouldInjectApprovedToolActions() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null,
                List.of("application:acquire", "catalog:execute"));

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).containsEntry("__approvedToolActions__",
                List.of("application:acquire", "catalog:execute"));
        }

        @Test
        @DisplayName("should NOT inject __approvedToolActions__ when none provided")
        void shouldNotInjectApprovedToolActionsWhenEmpty() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__approvedToolActions__");
        }

        @Test
        @DisplayName("should NOT inject __agentId__ when agentId is null")
        void shouldNotInjectAgentIdWhenNull() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__agentId__");
        }

        @Test
        @DisplayName("should NOT inject __agentId__ when agentId is blank")
        void shouldNotInjectAgentIdWhenBlank() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, "  ", null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__agentId__");
        }

        @Test
        @DisplayName("should NOT inject __agentId__ when request is null (backward compat)")
        void shouldNotInjectAgentIdWhenRequestNull() throws Exception {
            CliSessionResponse response = service.startSession(null, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__agentId__");
        }

        @Test
        @DisplayName("should always include __agent_depth__ and turnId in credentials")
        void shouldAlwaysIncludeBaseCredentials() throws Exception {
            String agentId = UUID.randomUUID().toString();
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, agentId, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).containsEntry("__agent_depth__", 0);
            assertThat(creds).containsKey("turnId");
            assertThat(creds).containsKey("conversationId");
        }
    }

    @Nested
    @DisplayName("UserRoles credential injection (admin gate on bridge/CLI path)")
    class UserRolesInjection {

        // The agent-cli MCP bridge bypasses the gateway, so the caller's platform
        // roles are resolved server-side (CliAgentController → AuthClient.getUserRoles)
        // and threaded into the session credentials as __userRoles__. Admin-gated
        // tool modules (SkillCrudModule.callerIsAdmin → editing a GLOBAL skill) read
        // this. Without it, an admin on the bridge path was rejected with "Only
        // admins can modify global skills". This regression pins the injection.

        @Test
        @DisplayName("should inject __userRoles__ when roles CSV is provided")
        void shouldInjectUserRolesWhenPresent() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(
                request, "tenant-1", "org-test", "OWNER", "USER,ADMIN");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).containsEntry("__userRoles__", "USER,ADMIN");
        }

        @Test
        @DisplayName("should NOT inject __userRoles__ when roles are null (pre-fix behavior path)")
        void shouldNotInjectUserRolesWhenNull() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(
                request, "tenant-1", "org-test", "OWNER", null);

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__userRoles__");
        }

        @Test
        @DisplayName("should NOT inject __userRoles__ when roles are blank")
        void shouldNotInjectUserRolesWhenBlank() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(
                request, "tenant-1", "org-test", "OWNER", "  ");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__userRoles__");
        }

        @Test
        @DisplayName("legacy 4-arg startSession overload leaves __userRoles__ unset (backward compat)")
        void legacyOverloadLeavesUserRolesUnset() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            CliSessionResponse response = service.startSession(
                request, "tenant-1", "org-test", "OWNER");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).doesNotContainKey("__userRoles__");
        }
    }

    @Nested
    @DisplayName("Conversation title tool gating")
    class ConversationTitleToolGating {

        // set_conversation_title lets the LLM rename the conversation. Agent-bound
        // chats already inherit their title from the agent entity at creation time,
        // so exposing the tool here would let the agent override the user's agent
        // name on the first turn. These tests pin the suppression rule at the
        // bridge/CLI entry point.

        private List<String> toolNames(String sessionId) throws Exception {
            Field sessionsField = CliAgentService.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Object> sessions =
                (ConcurrentHashMap<String, Object>) sessionsField.get(service);
            Object session = sessions.get(sessionId);
            Field toolsField = session.getClass().getDeclaredField("tools");
            toolsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ToolDefinition> tools = (List<ToolDefinition>) toolsField.get(session);
            return tools.stream().map(ToolDefinition::name).toList();
        }

        @Test
        @DisplayName("new general-chat conversation (no agentId) exposes set_conversation_title")
        void exposesTitleToolOnGeneralChat() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, null, Boolean.TRUE, null, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            assertThat(toolNames(response.sessionId())).contains("set_conversation_title");
        }

        @Test
        @DisplayName("new agent-bound conversation suppresses set_conversation_title")
        void suppressesTitleToolForAgentBoundConversation() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, null, Boolean.TRUE,
                UUID.randomUUID().toString(), null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            List<String> names = toolNames(response.sessionId());
            assertThat(names).doesNotContain("set_conversation_title");
            // Other conversation tools remain available.
            assertThat(names).contains("get_tool_result", "request_credential");
        }

        @Test
        @DisplayName("blank agentId behaves like no agent (title tool exposed)")
        void blankAgentIdBehavesLikeNoAgent() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, null, Boolean.TRUE, "  ", null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            assertThat(toolNames(response.sessionId())).contains("set_conversation_title");
        }

        @Test
        @DisplayName("follow-up general chat never exposes set_conversation_title")
        void followUpGeneralChatSuppresses() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, null, Boolean.FALSE, null, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            assertThat(toolNames(response.sessionId())).doesNotContain("set_conversation_title");
        }
    }

    @Nested
    @DisplayName("Resource allow-list scope (bridge parity with the direct-API path)")
    class ResourceAllowlistScope {

        // Regression for the CLI-bridge allow-list bypass: a top-level bridge agent ran
        // UNRESTRICTED because CliSessionStartRequest carries no allow-list, so the session
        // credentials never held allowedTableIds → ToolAccessControl treated null as "no
        // restriction" (the agent could read every table in its tenant). startSession now
        // re-resolves the bound agent's toolsConfig and emits the SAME allow-list/access-mode
        // credentials the direct-API path produces.

        @Test
        @DisplayName("bound agent with a NUMERIC custom table allow-list → session credentials carry allowedTableIds as STRINGS (the exact bridge leak)")
        void numericTableAllowlistIsScopedIntoSessionCredentials() throws Exception {
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, Map.of(
                "tablesGrant", "custom",
                "tables", List.of(222, 42)));
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, agentId, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            // Stringified so DataSourceTableModule's `.contains(String.valueOf(id))` matches,
            // identical to the direct-API path - a table NOT in this list stays blocked.
            assertThat(creds).containsEntry("allowedTableIds", List.of("222", "42"));
        }

        @Test
        @DisplayName("session credentials EQUAL the shared builder output for the same toolsConfig (full parity: resources + grant semantics + access modes + catalog tools)")
        void sessionCredentialsMatchDirectApiBuilder() throws Exception {
            String agentId = UUID.randomUUID().toString();
            Map<String, Object> toolsConfig = Map.of(
                "mode", "custom", "tools", List.of("gmail_send"),
                "tablesGrant", "custom", "tables", List.of(222),
                "workflowsGrant", "none",
                "agentsGrant", "all",
                "tableAccessMode", "read");
            stubBoundAgent(agentId, toolsConfig);
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, agentId, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");
            Map<String, Object> creds = getSessionCredentials(response.sessionId());

            // The SAME builders the direct path uses, run on the same config = reference.
            Map<String, Object> reference = new HashMap<>();
            AgentToolsConfigCredentials.apply(reference, toolsConfig);
            AgentToolsConfigCredentials.applyCatalogToolsMode(reference, toolsConfig);

            assertThat(creds).containsEntry("allowedTableIds", reference.get("allowedTableIds")) // ["222"]
                .containsEntry("allowedWorkflowIds", reference.get("allowedWorkflowIds"))         // [] (none)
                .containsEntry("allowedToolIds", reference.get("allowedToolIds"))                 // ["gmail_send"]
                .containsEntry("tableAccessMode", "read");
            assertThat(creds).doesNotContainKey("allowedAgentIds");                               // all → omitted
            assertThat(reference).doesNotContainKey("allowedAgentIds");
        }

        @Test
        @DisplayName("bound agent mode=custom → session scopes catalog tools (allowedToolIds); mode=none denies all catalog tools")
        void catalogToolsModeScopedIntoSessionCredentials() throws Exception {
            String customAgent = UUID.randomUUID().toString();
            stubBoundAgent(customAgent, Map.of("mode", "custom", "tools", List.of("gmail_send", "slack_post")));
            Map<String, Object> customCreds = getSessionCredentials(service.startSession(
                new CliSessionStartRequest(null, null, "claude-code", null, null, null, null, customAgent, null, null),
                "tenant-1", "org-test").sessionId());
            assertThat(customCreds).containsEntry("allowedToolIds", List.of("gmail_send", "slack_post"));

            String noneAgent = UUID.randomUUID().toString();
            stubBoundAgent(noneAgent, Map.of("mode", "none"));
            Map<String, Object> noneCreds = getSessionCredentials(service.startSession(
                new CliSessionStartRequest(null, null, "claude-code", null, null, null, null, noneAgent, null, null),
                "tenant-1", "org-test").sessionId());
            assertThat(noneCreds).containsEntry("allowedToolIds", List.of());
        }

        @Test
        @DisplayName("grant=none → allowedTableIds=[] (deny-all); grant=all → key omitted (unrestricted)")
        void grantSemanticsArePreserved() throws Exception {
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, Map.of(
                "tablesGrant", "none",
                "workflowsGrant", "all"));
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, agentId, null, null);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("allowedTableIds", List.of()); // none = deny-all
            assertThat(creds).doesNotContainKey("allowedWorkflowIds");      // all = unrestricted
        }

        @Test
        @DisplayName("general chat (no agentId) stays UNRESTRICTED - no allow-list injected, agent never loaded")
        void generalChatStaysUnrestricted() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, null, null, null);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).doesNotContainKey("allowedTableIds");
            verify(agentService, never()).getAgent(any(), any(), any(), any());
        }

        @Test
        @DisplayName("bound agent with NULL toolsConfig stays unrestricted (parity with conversation's `if toolsConfig != null` guard)")
        void nullToolsConfigStaysUnrestricted() throws Exception {
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, null); // agent exists but has no toolsConfig
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, agentId, null, null);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).doesNotContainKey("allowedTableIds");
        }

        @Test
        @DisplayName("agent not found in the session's workspace → unrestricted, session still starts")
        void agentNotFoundStaysUnrestricted() throws Exception {
            String agentId = UUID.randomUUID().toString();
            when(agentService.getAgent(eq(UUID.fromString(agentId)), any(), any(), any()))
                .thenReturn(Optional.empty());
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, agentId, null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            assertThat(response).isNotNull();
            assertThat(getSessionCredentials(response.sessionId())).doesNotContainKey("allowedTableIds");
        }

        @Test
        @DisplayName("non-UUID agentId is tolerated - session starts unrestricted, agent never loaded")
        void nonUuidAgentIdIsTolerated() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "claude-code", null, null, null, null, "not-a-uuid", null, null);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            assertThat(response).isNotNull();
            assertThat(getSessionCredentials(response.sessionId())).doesNotContainKey("allowedTableIds");
            verify(agentService, never()).getAgent(any(), any(), any(), any());
        }
    }
}
