package com.apimarketplace.agent.service.cli;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.dto.cli.CliSessionStartRequest;
import com.apimarketplace.agent.dto.cli.CliSessionResponse;
import com.apimarketplace.agent.dto.cli.CliToolRequest;
import com.apimarketplace.agent.dto.cli.CliToolResponse;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.execution.AgentToolsConfigCredentials;
import com.apimarketplace.agent.service.execution.CoreToolsCache;
import com.apimarketplace.agent.service.execution.RemoteToolExecutionService;
import com.apimarketplace.agent.tool.ToolExecutionService;
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
    private AgentObservabilityService observabilityService;
    private CliAgentService service;

    @BeforeEach
    void setUp() {
        coreToolsCache = mock(CoreToolsCache.class);
        when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());
        agentService = mock(AgentService.class);
        observabilityService = mock(AgentObservabilityService.class);

        service = new CliAgentService(
            coreToolsCache,
            mock(RemoteToolExecutionService.class),
            observabilityService,
            agentService,
            new ObjectMapper()
        );
    }

    @Nested
    @DisplayName("Observability stop reason (regression: CLI rows had none)")
    class ObservabilityStopReason {

        @Test
        @DisplayName("an explicitly ended session is recorded as a CLI run stopped with COMPLETED")
        void endSessionRecordsCompletedStopReason() {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);
            CliSessionResponse started = service.startSession(request, "tenant-1", "org-test");

            service.endSession(started.sessionId(), "tenant-1");

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(captor.capture());
            assertThat(captor.getValue().getAgentType()).isEqualTo("CLI");
            assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
            assertThat(captor.getValue().getStopReason()).isEqualTo("COMPLETED");
            // A CLI holds no API key: the row is pinned PLATFORM, never left for the debit to guess.
            assertThat(captor.getValue().getKeyRoute()).isEqualTo("PLATFORM");
        }

        /**
         * Prod 2026-09-23: every bridge turn showed twice in the run history, the real run and a
         * phantom WORKFLOW run with provider "external" and no conversation, written here.
         */
        @Test
        @DisplayName("a session serving a dispatched run records nothing: its dispatcher records the run")
        void dispatchedRunIsNotRecordedTwice() {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, null,
                UUID.randomUUID().toString(), null);
            CliSessionResponse started = service.startSession(request, "tenant-1", "org-test");

            service.endSession(started.sessionId(), "tenant-1");

            verify(observabilityService, never()).recordFromRequest(any());
        }

        @Test
        @DisplayName("ending a session under the wrong tenant records nothing")
        void wrongTenantRecordsNothing() {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);
            CliSessionResponse started = service.startSession(request, "tenant-1", "org-test");

            service.endSession(started.sessionId(), "tenant-2");

            verify(observabilityService, never()).recordFromRequest(any());
        }
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
        @DisplayName("startSession with NO enabledModules falls back to the no-config set, so a bridge session nobody scoped never gets the credit-spending tools")
        void startSessionWithNullEnabledModulesFallsBackToTheNoConfigSet() {
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            // A caller that sends no module list (e.g. a sub-agent whose entity carries no
            // toolsConfig) used to get KNOWN_MODULE_KEYS = EVERY module, so the CLI advertised
            // generation / image_generation and the agent could spend the customer's credits
            // with no grant set anywhere. null now means "nobody decided", not "everything".
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .as("an unscoped bridge session must not be advertised the credit-spending tools")
                .doesNotContain("generation", "image_generation")
                .contains("catalog", "table", "workflow", "files");
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("startSession with the generation module explicitly granted DOES advertise it (the ON direction survives the fallback change)")
        void startSessionWithGenerationModuleGrantedAdvertisesIt() {
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of("table", "generation"), null, "test-model", null, null, null, null, null, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .contains("generation")
                .doesNotContain("image_generation");
        }

        @Test
        @DisplayName("a session cannot ASK for a module its bound agent does not grant")
        void theWireCannotWidenTheAgentGrant() {
            // The module list arrives in the request body, and the body is a
            // claim about the grant, not the grant. It used to be checked only
            // for spelling, so a session could name `generation` and receive it
            // whatever the agent's owner had configured. For the two modules
            // that spend the customer's credits, that check WAS the gate.
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, Map.of("mode", "custom"));   // no generation grant
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of("table", "generation", "image_generation"), null, "test-model",
                null, null, null, null, agentId, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .doesNotContain("generation")
                .doesNotContain("image_generation");
        }

        @Test
        @DisplayName("and it still RECEIVES the module once the agent actually grants it")
        void aRealGrantStillReachesTheSession() {
            // The cap must narrow, never block: if this failed, granting the
            // module in the agent's config would stop working and the gate
            // would be a wall.
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, Map.of("generation", true));
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of("table", "generation"), null, "test-model",
                null, null, null, null, agentId, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue()).contains("generation");
        }

        @Test
        @DisplayName("a bound agent with NO config cannot be talked into the credit-spending modules")
        void anAgentWithNoConfigCannotBeWidenedEither() {
            // An agent row that never got a config is the "nobody decided"
            // case, and this platform answers that with the set that leaves the
            // opt-ins out. Reading the wire instead would make a missing config
            // more permissive than a real one.
            String agentId = UUID.randomUUID().toString();
            stubBoundAgent(agentId, null);
            ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.forClass(Set.class);
            when(coreToolsCache.getCoreTools(namesCaptor.capture())).thenReturn(List.of());

            CliSessionStartRequest request = new CliSessionStartRequest(
                List.of("table", "generation"), null, "test-model",
                null, null, null, null, agentId, null, null);

            service.startSession(request, "tenant-1", "org-test");

            assertThat(namesCaptor.getValue())
                .contains("table")
                .doesNotContain("generation");
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

        /**
         * Prod 2026-09-23: a task run on the CLI bridge asked its question with ask_user. The
         * session's credentials had no task id, so the call looked like a person watching the chat:
         * it waited 150 s on a screen nobody had open and never reached the connected Telegram.
         */
        @Test
        @DisplayName("a task's session knows it is a task, so its questions go to the chat channel")
        void taskSessionIsNotPromptable() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, null, null, null, null, null,
                "task-9", null, null);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("__taskId__", "task-9");
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.isUserPromptable(creds)).isFalse();
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.questionReach(creds))
                .isEqualTo(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach.CHANNEL);
        }

        @Test
        @DisplayName("an unattended run and an armed agent keep their markers on the bridge session")
        void unattendedAndArmedMarkersReachTheSession() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, null, null, null, null, null,
                null, true, true);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("__unattendedRun__", true)
                .containsEntry("__requireToolAuthorization__", true);
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.questionReach(creds))
                .isEqualTo(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach.CHANNEL);
        }

        /**
         * Prod 2026-09-23: agent(action='execute') ran a sub-agent on the bridge; its session said
         * depth 0, so its ask_user parked a card for 112 s that nobody could see while the parent
         * chat waited on it, and the person saw an agent that never answered.
         */
        @Test
        @DisplayName("a sub-agent's session keeps its depth, so it never parks a card for a person")
        void subAgentSessionKeepsItsDepth() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-child", null, "stream-1", null, null, null, null, null, null,
                null, null, null, 1);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("__agent_depth__", 1);
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.isUserPromptable(creds)).isFalse();
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.questionReach(creds))
                .isEqualTo(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach.NONE);
        }

        @Test
        @DisplayName("a workflow agent node's session never asks through a channel: no reply can re-enter it")
        void workflowNodeSessionHasNoQuestionReach() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, null, null, null, null, null,
                null, null, null, null, "run-4");

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("__workflowRunId__", "run-4");
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.questionReach(creds))
                .isEqualTo(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach.NONE);
        }

        @Test
        @DisplayName("an armed agent bound to the session asks permission even if the bridge did not say so")
        void armedBoundAgentIsArmedFromItsOwnRow() throws Exception {
            String agentId = UUID.randomUUID().toString();
            AgentEntity agent = mock(AgentEntity.class);
            when(agent.getRequireToolAuthorization()).thenReturn(true);
            when(agentService.getAgent(eq(UUID.fromString(agentId)), any(), any(), any()))
                .thenReturn(java.util.Optional.of(agent));
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, agentId, null, null);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).containsEntry("__requireToolAuthorization__", true);
        }

        @Test
        @DisplayName("a chat session carries none of the run markers, so it stays promptable")
        void chatSessionHasNoRunMarkers() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", "conv-1", null, "stream-1", null, null, null, null, null, null,
                " ", false, false);

            Map<String, Object> creds = getSessionCredentials(
                service.startSession(request, "tenant-1", "org-test").sessionId());

            assertThat(creds).doesNotContainKeys("__taskId__", "__unattendedRun__", "__requireToolAuthorization__");
            assertThat(creds).containsEntry("__agent_depth__", 0);
            assertThat(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.questionReach(creds))
                .isEqualTo(com.apimarketplace.agent.tools.authz.ToolAuthorizationScope.QuestionReach.IN_APP);
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
    @DisplayName("Approval-gate budget credentials (how long a tool call may be held)")
    class ApprovalGateBudget {

        @Test
        @DisplayName("should declare the session as CLI-bridge, so a held tool call is bounded")
        void shouldMarkTheSessionAsCliBridge() throws Exception {
            // No start request at all - the controller accepts a body-less call, and the
            // marker must not hang off any request field: it describes the ROUTE, which is
            // the same whatever the caller chose to send.
            CliSessionResponse response = service.startSession(null, "tenant-1", "org-test");

            // Every tool call on this session is held open by a CLI at the other end of an
            // MCP request. The approval gate caps how long it may hold one, and it must know
            // that from the session rather than infer it: inferring from the watchdog window
            // was wrong in both directions - the window is absent when the watchdog is
            // disabled, and present on the direct route when an agent configures one.
            Map<String, Object> creds = getSessionCredentials(response.sessionId());
            assertThat(creds).containsEntry("__cliBridgeSession__", true);
        }

        @Test
        @DisplayName("should carry the run's watchdog window when the bridge sends one")
        void shouldCarryTheInactivityWindow() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null, 300);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            // Separate from the marker above: this bounds the hold so the tool still has
            // time to run before the watchdog kills a silent run. Nothing asserted this hop
            // before, so removing it would have been invisible.
            assertThat(getSessionCredentials(response.sessionId()))
                .containsEntry("__inactivityTimeoutSeconds__", 300);
        }

        @Test
        @DisplayName("should carry the hold the bridge granted on its CLI, in ms, so a card can wait past the floor")
        void shouldCarryTheCliMaxHold() throws Exception {
            CliSessionStartRequest request = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null, 300, 300);

            CliSessionResponse response = service.startSession(request, "tenant-1", "org-test");

            // The bridge writes the CLI's per-call timeout, so it is the one place that
            // knows how long a parked call may wait there. Without this hop the gate falls
            // back to a 25 s floor and a question card expires mid-read on every CLI.
            assertThat(getSessionCredentials(response.sessionId()))
                .containsEntry("__cliMaxParkMs__", 300_000L);
        }

        @Test
        @DisplayName("should not invent a hold when the bridge sends none or a non-positive one")
        void shouldNotInventACliMaxHold() throws Exception {
            CliSessionStartRequest absent = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null, 300);
            CliSessionStartRequest zero = new CliSessionStartRequest(
                null, null, "test-model", null, null, null, null, null, null, null, 300, 0);

            assertThat(getSessionCredentials(service.startSession(absent, "tenant-1", "org-test").sessionId()))
                .as("an older bridge says nothing: the gate keeps its floor")
                .doesNotContainKey("__cliMaxParkMs__");
            assertThat(getSessionCredentials(service.startSession(zero, "tenant-1", "org-test").sessionId()))
                .as("0 is not a wait a person can answer in; it must not shadow the floor")
                .doesNotContainKey("__cliMaxParkMs__");
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
            assertThat(names).contains("get_tool_result", "credential");
            // Legacy routing alias is never advertised in session tool definitions.
            assertThat(names).doesNotContain("request_credential");
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

    @Nested
    @DisplayName("ToolExecutionService injection seam (CE monolith adapter)")
    class ToolExecutionServiceInjectionSeam {

        // REGRESSION: the CE bridge 401 bug. CliAgentService used to inject the
        // CONCRETE RemoteToolExecutionService, bypassing the CE monolith's
        // @Primary MonolithToolExecutionService adapter - so every bridge-session
        // conversation tool (credential, get_tool_result, set_conversation_title)
        // routed to the JWT-protected /api/internal/conversation/tools/execute
        // callback WITHOUT a JWT and died with 401. The dependency must stay the
        // com.apimarketplace.agent.tool.ToolExecutionService INTERFACE so the
        // monolith's @Primary adapter intercepts in-process.

        @Test
        @DisplayName("executeTool dispatches through ANY ToolExecutionService impl - a non-Remote stub receives the call and its result is relayed")
        void executeToolDispatchesThroughInterfaceImpl() {
            // A plain interface impl that is NOT RemoteToolExecutionService: this
            // is exactly the shape of the CE monolith's @Primary adapter. If the
            // constructor parameter reverts to the concrete class, this stops
            // compiling - the build itself pins the seam.
            List<ToolCall> received = new java.util.ArrayList<>();
            ToolExecutionService nonRemoteStub = new ToolExecutionService() {
                @Override
                public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                              String tenantId, Map<String, Object> credentials) {
                    received.add(toolCall);
                    return ToolResult.builder()
                        .toolCall(toolCall)
                        .success(true)
                        .content("in-process adapter result")
                        .build();
                }

                @Override
                public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                    return true;
                }
            };
            CliAgentService inProcessService = new CliAgentService(
                coreToolsCache, nonRemoteStub,
                mock(AgentObservabilityService.class), agentService, new ObjectMapper());

            // conversationId present → the session advertises the conversation
            // tools (credential/get_tool_result) - the exact tools the CE bug hit.
            CliSessionResponse session = inProcessService.startSession(
                new CliSessionStartRequest(null, null, "claude-code", "conv-1",
                    null, null, Boolean.TRUE, null, null, null),
                "tenant-1", "org-test");

            CliToolResponse response = inProcessService.executeTool(
                new CliToolRequest(session.sessionId(), "credential", Map.of("action", "list")),
                "tenant-1");

            assertThat(response.success()).isTrue();
            assertThat(response.result()).isEqualTo("in-process adapter result");
            assertThat(received).hasSize(1);
            assertThat(received.get(0).toolName()).isEqualTo("credential");
        }

        @Test
        @DisplayName("the injected dependency is declared as the ToolExecutionService INTERFACE, not RemoteToolExecutionService")
        void dependencyIsDeclaredAsInterface() throws Exception {
            Field field = CliAgentService.class.getDeclaredField("remoteToolExecutionService");

            assertThat(field.getType())
                .as("CliAgentService must depend on the interface so the CE monolith's "
                    + "@Primary MonolithToolExecutionService intercepts conversation tools "
                    + "in-process (concrete injection = bridge 401 on every conversation tool)")
                .isEqualTo(ToolExecutionService.class)
                .isNotEqualTo(RemoteToolExecutionService.class);
        }
    }
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an array parameter tells a CLI agent what it holds")
    void arraysDeclareTheirItemTypeToCliAgents() {
        // A CLI agent reads this schema to decide what to send. Arrays used to carry no items key
        // at all here, so an array of objects was filled in as an array of strings with nothing
        // to say otherwise, and the two other schema emitters disagreed with this one.
        com.apimarketplace.agent.domain.ToolDefinition td =
            com.apimarketplace.agent.domain.ToolDefinition.builder()
                .name("t").description("T")
                .parameters(java.util.List.of(
                    com.apimarketplace.agent.domain.ToolParameter.builder()
                        .name("rows").type("array").description("Rows").required(false)
                        .itemType("object").build(),
                    com.apimarketplace.agent.domain.ToolParameter.builder()
                        .name("tags").type("array").description("Tags").required(false).build()))
                .build();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props =
            (java.util.Map<String, Object>) service.buildInputSchema(td).get("properties");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> rows = (java.util.Map<String, Object>) props.get("rows");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tags = (java.util.Map<String, Object>) props.get("tags");
        assertThat(rows.get("items")).isEqualTo(java.util.Map.of("type", "object"));
        assertThat(tags.get("items")).isEqualTo(java.util.Map.of("type", "string"));
    }

}
