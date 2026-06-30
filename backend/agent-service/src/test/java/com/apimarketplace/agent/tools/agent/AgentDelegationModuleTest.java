package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.controller.AgentToolsController;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentDelegationModule}: action dispatch, caller resolution
 * (agent via {@code __agentId__} vs. human fallback using {@code tenantId}),
 * per-turn rate limit, and parameter forwarding to the services.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDelegationModule")
class AgentDelegationModuleTest {

    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-a";

    @Mock private AgentTaskService taskService;
    @Mock private AgentTaskRecurrenceService recurrenceService;
    @Mock private com.apimarketplace.agent.repository.AgentTaskRepository taskRepository;

    private AgentDelegationModule module;

    @BeforeEach
    void setUp() {
        module = new AgentDelegationModule(taskService, recurrenceService, taskRepository);
    }

    @Test
    @DisplayName("Agent tools controller maps reviewerExecutionId into tool credentials")
    void agentToolsControllerMapsReviewerExecutionIdCredential() throws Exception {
        UUID reviewerExecutionId = UUID.randomUUID();
        AgentToolsController controller = new AgentToolsController(null, null);
        Method buildCredentials = AgentToolsController.class
                .getDeclaredMethod("buildCredentials", Map.class);
        buildCredentials.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> credentials = (Map<String, Object>) buildCredentials.invoke(controller,
                Map.of("reviewerExecutionId", reviewerExecutionId.toString()));

        assertThat(credentials).containsEntry("__reviewerExecutionId__", reviewerExecutionId.toString());
    }

    private ToolExecutionContext ctx(UUID callerId, String turnId) {
        return ctx(callerId, turnId, null);
    }

    private ToolExecutionContext ctx(UUID callerId, String turnId, List<String> allowedAgentIds) {
        return context(callerId, turnId, allowedAgentIds, null, null);
    }

    private ToolExecutionContext orgCtx(UUID callerId, String turnId) {
        return orgCtx(callerId, turnId, null);
    }

    private ToolExecutionContext orgCtx(UUID callerId, String turnId, List<String> allowedAgentIds) {
        return context(callerId, turnId, allowedAgentIds, ORG, "owner");
    }

    private ToolExecutionContext context(
            UUID callerId,
            String turnId,
            List<String> allowedAgentIds,
            String orgId,
            String orgRole) {
        Map<String, Object> creds = new HashMap<>();
        if (callerId != null) creds.put("__agentId__", callerId.toString());
        if (turnId != null) creds.put("turnId", turnId);
        if (allowedAgentIds != null) creds.put("allowedAgentIds", allowedAgentIds);
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, orgId, orgRole);
    }

    private ToolExecutionContext reviewCtx(UUID callerId, String turnId, UUID reviewerExecutionId) {
        Map<String, Object> creds = new HashMap<>();
        if (callerId != null) creds.put("__agentId__", callerId.toString());
        if (turnId != null) creds.put("turnId", turnId);
        if (reviewerExecutionId != null) creds.put("__reviewerExecutionId__", reviewerExecutionId.toString());
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, ORG, "owner");
    }

    private AgentTaskEntity sampleTask(UUID id, UUID assignee) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId(TENANT);
        t.setTitle("t");
        t.setInstructions("i");
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        t.setAssignedToAgentId(assignee);
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        return t;
    }

    private void taskVisible(UUID taskId) {
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(sampleTask(taskId, null)));
    }

    // ------------------------------------------------------------------
    // Board extras (F2 labels, F12 estimate/time, F9 blockers, F10 checklist)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("board extras on assign + task_update")
    class TaskExtras {
        private final UUID agentId = UUID.randomUUID();
        private final UUID taskId = UUID.randomUUID();
        private final UUID labelId = UUID.randomUUID();
        private final UUID blockerId = UUID.randomUUID();

        @Test
        @DisplayName("assign creates WITH labels/estimate/time/blockers/checklist in a single call")
        void assignAppliesExtras() {
            AgentTaskEntity backlog = sampleTask(taskId, null); // backlog (assignee null) -> early return, no sync-exec
            when(taskService.assignTask(eq(TENANT), eq(agentId), isNull(), any(CreateTaskRequest.class), anyBoolean()))
                    .thenReturn(backlog);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(backlog);
            when(taskService.setTaskEstimate(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(backlog);
            when(taskService.setTaskBlockers(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(backlog);
            when(taskService.setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(backlog);

            Map<String, Object> params = new HashMap<>();
            params.put("title", "t");
            params.put("instructions", "i");
            params.put("label_ids", List.of(labelId.toString()));
            params.put("estimate_minutes", 120);
            params.put("time_spent_minutes", 30);
            params.put("blocked_by", List.of(blockerId.toString()));
            params.put("checklist", List.of(Map.of("text", "write tests", "done", false)));

            module.execute("assign", params, TENANT, ctx(agentId, null));

            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, List.of(labelId.toString()), null);
            verify(taskService).setTaskEstimate(eq(TENANT), eq(taskId), eq(agentId), isNull(), eq(120), eq(false), eq(30), eq(false));
            verify(taskService).setTaskBlockers(TENANT, taskId, agentId, null, List.of(blockerId.toString()));
            verify(taskService).setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), any());
        }

        @Test
        @DisplayName("assign forwards free-text label NAMES (get-or-create) when label_ids is absent")
        void assignForwardsLabelNames() {
            AgentTaskEntity backlog = sampleTask(taskId, null);
            when(taskService.assignTask(eq(TENANT), eq(agentId), isNull(), any(CreateTaskRequest.class), anyBoolean()))
                    .thenReturn(backlog);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(backlog);

            Map<String, Object> params = new HashMap<>();
            params.put("title", "t");
            params.put("instructions", "i");
            params.put("labels", List.of("urgent", "qa"));

            module.execute("assign", params, TENANT, ctx(agentId, null));

            // label_ids absent -> null; the names list is forwarded as the 6th arg.
            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, null, List.of("urgent", "qa"));
        }

        @Test
        @DisplayName("assign forwards label_ids AND label names together (union)")
        void assignForwardsIdsAndNames() {
            AgentTaskEntity backlog = sampleTask(taskId, null);
            when(taskService.assignTask(eq(TENANT), eq(agentId), isNull(), any(CreateTaskRequest.class), anyBoolean()))
                    .thenReturn(backlog);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(backlog);

            Map<String, Object> params = new HashMap<>();
            params.put("title", "t");
            params.put("instructions", "i");
            params.put("label_ids", List.of(labelId.toString()));
            params.put("labels", List.of("qa"));

            module.execute("assign", params, TENANT, ctx(agentId, null));

            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, List.of(labelId.toString()), List.of("qa"));
        }

        @Test
        @DisplayName("task_update applies the same extras")
        void updateAppliesExtras() {
            taskVisible(taskId);
            AgentTaskEntity t = sampleTask(taskId, agentId);
            when(taskService.updateTask(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(t);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(t);

            Map<String, Object> params = new HashMap<>();
            params.put("task_id", taskId.toString());
            params.put("label_ids", List.of(labelId.toString()));

            module.execute("task_update", params, TENANT, orgCtx(agentId, null));

            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, List.of(labelId.toString()), null);
        }

        @Test
        @DisplayName("task_update applies ALL FOUR extras (labels + estimate/time + blockers + checklist)")
        void updateAppliesAllExtras() {
            taskVisible(taskId);
            AgentTaskEntity t = sampleTask(taskId, agentId);
            when(taskService.updateTask(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(t);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(t);
            when(taskService.setTaskEstimate(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(t);
            when(taskService.setTaskBlockers(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(t);
            when(taskService.setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(t);

            Map<String, Object> params = new HashMap<>();
            params.put("task_id", taskId.toString());
            params.put("label_ids", List.of(labelId.toString()));
            params.put("estimate_minutes", 45);
            params.put("time_spent_minutes", 10);
            params.put("blocked_by", List.of(blockerId.toString()));
            params.put("checklist", List.of(Map.of("text", "review", "done", true)));

            module.execute("task_update", params, TENANT, orgCtx(agentId, null));

            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, List.of(labelId.toString()), null);
            verify(taskService).setTaskEstimate(eq(TENANT), eq(taskId), eq(agentId), isNull(), eq(45), eq(false), eq(10), eq(false));
            verify(taskService).setTaskBlockers(TENANT, taskId, agentId, null, List.of(blockerId.toString()));
            verify(taskService).setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), any());
        }

        @Test
        @DisplayName("empty extra lists are forwarded as clear (empty-list) operations")
        void emptyListsClear() {
            AgentTaskEntity backlog = sampleTask(taskId, null);
            when(taskService.assignTask(eq(TENANT), eq(agentId), isNull(), any(CreateTaskRequest.class), anyBoolean()))
                    .thenReturn(backlog);
            when(taskService.setTaskLabels(eq(TENANT), eq(taskId), eq(agentId), isNull(), any(), any())).thenReturn(backlog);
            when(taskService.setTaskBlockers(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(backlog);
            when(taskService.setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), any())).thenReturn(backlog);

            Map<String, Object> params = new HashMap<>();
            params.put("title", "t");
            params.put("instructions", "i");
            params.put("label_ids", List.of());
            params.put("blocked_by", List.of());
            params.put("checklist", List.of());

            module.execute("assign", params, TENANT, ctx(agentId, null));

            verify(taskService).setTaskLabels(TENANT, taskId, agentId, null, List.of(), null);
            verify(taskService).setTaskBlockers(TENANT, taskId, agentId, null, List.of());
            verify(taskService).setTaskChecklist(eq(TENANT), eq(taskId), eq(agentId), isNull(), eq(List.of()));
        }

        @Test
        @DisplayName("assign without extras touches none of the extra setters")
        void assignNoExtras() {
            when(taskService.assignTask(eq(TENANT), eq(agentId), isNull(), any(CreateTaskRequest.class), anyBoolean()))
                    .thenReturn(sampleTask(taskId, null));

            Map<String, Object> params = new HashMap<>();
            params.put("title", "t");
            params.put("instructions", "i");

            module.execute("assign", params, TENANT, ctx(agentId, null));

            verify(taskService, never()).setTaskLabels(any(), any(), any(), any(), any(), any());
            verify(taskService, never()).setTaskEstimate(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
            verify(taskService, never()).setTaskBlockers(any(), any(), any(), any(), any());
            verify(taskService, never()).setTaskChecklist(any(), any(), any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // Dispatcher
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("canHandle")
    class CanHandle {
        @Test void assign() { assertThat(module.canHandle("assign")).isTrue(); }
        @Test void inbox() { assertThat(module.canHandle("inbox")).isTrue(); }
        @Test void outbox() { assertThat(module.canHandle("outbox")).isTrue(); }
        @Test void taskComplete() { assertThat(module.canHandle("task_complete")).isTrue(); }
        @Test void taskReject() { assertThat(module.canHandle("task_reject")).isTrue(); }
        @Test void taskUpdate() { assertThat(module.canHandle("task_update")).isTrue(); }
        @Test void taskCancel() { assertThat(module.canHandle("task_cancel")).isTrue(); }
        @Test void taskApprove() { assertThat(module.canHandle("task_approve")).isTrue(); }
        @Test void taskRejectReview() { assertThat(module.canHandle("task_reject_review")).isTrue(); }
        @Test void reviewInbox() { assertThat(module.canHandle("review_inbox")).isTrue(); }
        @Test void backlog() { assertThat(module.canHandle("backlog")).isTrue(); }
        @Test void claim() { assertThat(module.canHandle("claim")).isTrue(); }
        @Test void recurrenceCreate() { assertThat(module.canHandle("recurrence_create")).isTrue(); }
        @Test void recurrenceList() { assertThat(module.canHandle("recurrence_list")).isTrue(); }
        @Test void recurrenceUpdate() { assertThat(module.canHandle("recurrence_update")).isTrue(); }
        @Test void recurrenceDelete() { assertThat(module.canHandle("recurrence_delete")).isTrue(); }
        @Test void unrelatedActionRejected() { assertThat(module.canHandle("create")).isFalse(); }
        @Test void nullActionRejected() { assertThat(module.canHandle("bogus")).isFalse(); }

        @Test
        @DisplayName("execute() returns empty for unhandled action")
        void executeEmptyForUnhandled() {
            Optional<ToolExecutionResult> r = module.execute("create", Map.of(), TENANT,
                    ctx(UUID.randomUUID(), "t1"));
            assertThat(r).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Caller resolution (agent vs. human fallback)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Caller resolution")
    class CallerResolution {

        @Test
        @DisplayName("assign without __agentId__ falls back to human caller using tenantId as userId")
        void assignWithoutAgentIdFallsBackToHuman() {
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), assignee);
            ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
            when(taskService.assignTask(eq(TENANT), isNull(), eq(TENANT), captor.capture(), eq(true)))
                    .thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", assignee.toString(), "title", "x", "instructions", "y"),
                    TENANT, ctx(null, "t1"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            verify(taskService).assignTask(eq(TENANT), isNull(), eq(TENANT), any(), eq(true));
        }

        @Test
        @DisplayName("claim still requires agent identity - human callers get a friendly error")
        void claimRejectsHumanCaller() {
            ToolExecutionContext noCreds = new ToolExecutionContext(
                    TENANT, null, Map.of(), Set.of(), null, null, null, null);

            Optional<ToolExecutionResult> r = module.execute("claim",
                    Map.of("task_id", UUID.randomUUID().toString()),
                    TENANT, noCreds);

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("requires an agent identity");
            assertThat(r.get().error()).contains("claim");
        }

        @Test
        @DisplayName("inbox still requires agent identity - direct-chat callers have no inbox")
        void inboxRejectsHumanCaller() {
            Optional<ToolExecutionResult> r = module.execute("inbox", Map.of(),
                    TENANT, ctx(null, "t1"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("requires an agent identity");
        }

        @Test
        @DisplayName("Fails when __agentId__ is not a valid UUID")
        void invalidAgentId() {
            ToolExecutionContext bad = new ToolExecutionContext(
                    TENANT, Map.of("__agentId__", "not-a-uuid"),
                    Map.of(), Set.of(), null, null, null, null);

            Optional<ToolExecutionResult> r = module.execute("inbox", Map.of(), TENANT, bad);

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("invalid __agentId__");
        }
    }

    // ------------------------------------------------------------------
    // assign dispatch + rate limit
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("assign")
    class Assign {

        @Test
        @DisplayName("Forwards request to service with caller as createdBy")
        void assignForwards() {
            UUID caller = UUID.randomUUID();
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), assignee);
            ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), captor.capture(), eq(true)))
                    .thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Map<String, Object> params = Map.of(
                    "agent_id", assignee.toString(),
                    "title", "Do the thing",
                    "instructions", "Details here",
                    "priority", "high"
            );
            Optional<ToolExecutionResult> r = module.execute("assign", params, TENANT,
                    ctx(caller, "t1", List.of(assignee.toString())));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            assertThat(captor.getValue().agentId()).isEqualTo(assignee);
            assertThat(captor.getValue().title()).isEqualTo("Do the thing");
            assertThat(captor.getValue().priority()).isEqualTo("high");
        }

        @Test
        @DisplayName("Rate limit: 6th assign in the same turn is rejected")
        void rateLimit() {
            UUID caller = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), null);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);

            Map<String, Object> params = Map.of("title", "t", "instructions", "i");
            ToolExecutionContext turnCtx = ctx(caller, "turn-rl");

            for (int i = 0; i < AgentDelegationModule.MAX_ASSIGNS_PER_TURN; i++) {
                Optional<ToolExecutionResult> ok = module.execute("assign", params, TENANT, turnCtx);
                assertThat(ok).isPresent();
                assertThat(ok.get().success())
                        .as("assign #%d should succeed", i + 1)
                        .isTrue();
            }

            Optional<ToolExecutionResult> over = module.execute("assign", params, TENANT, turnCtx);
            assertThat(over).isPresent();
            assertThat(over.get().success()).isFalse();
            assertThat(over.get().error()).contains("rate limit");
        }

        @Test
        @DisplayName("Rate limit does not cross turns")
        void rateLimitPerTurn() {
            UUID caller = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), null);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);

            Map<String, Object> params = Map.of("title", "t", "instructions", "i");
            // 5 in turn A
            for (int i = 0; i < AgentDelegationModule.MAX_ASSIGNS_PER_TURN; i++) {
                module.execute("assign", params, TENANT, ctx(caller, "turn-A"));
            }
            // 1 in turn B - new counter, should succeed
            Optional<ToolExecutionResult> r = module.execute("assign", params, TENANT, ctx(caller, "turn-B"));
            assertThat(r.get().success()).isTrue();
        }

        @Test
        @DisplayName("Service-thrown IllegalArgumentException becomes failure")
        void serviceErrorBecomesFailure() {
            UUID caller = UUID.randomUUID();
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new IllegalArgumentException("title is required"));

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("title", " ", "instructions", "i"),
                    TENANT, ctx(caller, "t1"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("title");
        }

        // ==================================================================
        // Regression - null allowedAgentIds is god (unrestricted), [] is denied.
        // Bug pre-fix: AgentDelegationModule rejected callers with null allowedAgentIds,
        // contradicting the convention used by TaskVisibilityResolver / SubAgentExecutionHandler
        // that treats null as "no restriction". Fix: drop the rejection branch.
        // ==================================================================

        @Test
        @DisplayName("null allowedAgentIds (god) can assign to any agent in the tenant")
        void nullAllowedAgentIdsActsAsGod() {
            UUID caller = UUID.randomUUID();
            UUID anyAssignee = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), anyAssignee);
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), eq(true)))
                    .thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            // ctx WITHOUT allowedAgentIds (i.e. null) - caller is the primary chat / god agent.
            ToolExecutionContext godCtx = ctx(caller, "t-god");

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", anyAssignee.toString(),
                           "title", "delegate anywhere",
                           "instructions", "do it"),
                    TENANT, godCtx);

            assertThat(r).isPresent();
            assertThat(r.get().success())
                    .as("god caller (null allowedAgentIds) must NOT be rejected")
                    .isTrue();
            assertThat(r.get().error()).isNull();
        }

        @Test
        @DisplayName("Empty allowedAgentIds [] still rejects assign - explicit 'no children'")
        void emptyAllowedAgentIdsRejectsAssign() {
            UUID caller = UUID.randomUUID();
            UUID anyAssignee = UUID.randomUUID();

            // ctx WITH allowedAgentIds = [] - caller has explicit "no children" config.
            ToolExecutionContext restrictedCtx = ctx(caller, "t-empty", List.of());

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", anyAssignee.toString(),
                           "title", "delegate denied",
                           "instructions", "do it"),
                    TENANT, restrictedCtx);

            assertThat(r).isPresent();
            assertThat(r.get().success())
                    .as("empty [] allowedAgentIds must reject delegation")
                    .isFalse();
            assertThat(r.get().error()).contains("not in your allowed agents list");
        }

        // ==================================================================
        // start_mode matrix - pending / in_progress / execute (default)
        // ==================================================================

        @Test
        @DisplayName("start_mode='pending' calls assignTask with autoTrigger=false, skips executeTaskSync")
        void startModePending() {
            UUID caller = UUID.randomUUID();
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity pending = sampleTask(UUID.randomUUID(), assignee);
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), eq(false)))
                    .thenReturn(pending);

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", assignee.toString(),
                           "title", "wait for pickup",
                           "instructions", "stay pending",
                           "start_mode", "pending"),
                    TENANT, ctx(caller, "t-pend"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            // Critical: the worker must NOT be sync-executed when start_mode='pending'
            verify(taskService, org.mockito.Mockito.never()).executeTaskSync(any());
            // Response should carry start_mode for trace
            assertThat(((Map<?, ?>) r.get().data()).get("start_mode")).isEqualTo("pending");
        }

        @Test
        @DisplayName("start_mode='in_progress' triggers async kickoff (autoTrigger=true) but does NOT sync-wait")
        void startModeInProgress() {
            UUID caller = UUID.randomUUID();
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity pending = sampleTask(UUID.randomUUID(), assignee);
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), eq(true)))
                    .thenReturn(pending);

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", assignee.toString(),
                           "title", "fire and forget",
                           "instructions", "run async",
                           "start_mode", "in_progress"),
                    TENANT, ctx(caller, "t-ip"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            verify(taskService, org.mockito.Mockito.never()).executeTaskSync(any());
            assertThat(((Map<?, ?>) r.get().data()).get("start_mode")).isEqualTo("in_progress");
        }

        @Test
        @DisplayName("start_mode='execute' (and default) triggers kickoff AND sync-waits via executeTaskSync")
        void startModeExecuteDefault() {
            UUID caller = UUID.randomUUID();
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), assignee);
            AgentTaskEntity terminal = sampleTask(created.getId(), assignee);
            terminal.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            terminal.setResult("done");
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), eq(true)))
                    .thenReturn(created);
            when(taskService.executeTaskSync(created)).thenReturn(terminal);

            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", assignee.toString(),
                           "title", "drive to terminal",
                           "instructions", "block until done",
                           "start_mode", "execute"),
                    TENANT, ctx(caller, "t-exec"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            verify(taskService).executeTaskSync(created);
            Map<?, ?> body = (Map<?, ?>) r.get().data();
            assertThat(body.get("status")).isEqualTo(AgentTaskEntity.STATUS_COMPLETED);
            assertThat(body.get("result")).isEqualTo("done");
            // Default mode should also trigger executeTaskSync - no start_mode supplied.
            // Reset and verify.
        }

        @Test
        @DisplayName("Default (no start_mode supplied) behaves like 'execute' - preserves legacy contract")
        void startModeDefaultIsExecute() {
            UUID caller = UUID.randomUUID();
            UUID assignee = UUID.randomUUID();
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), assignee);
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), eq(true)))
                    .thenReturn(created);
            when(taskService.executeTaskSync(created)).thenReturn(created);

            module.execute("assign",
                    Map.of("agent_id", assignee.toString(),
                           "title", "default mode",
                           "instructions", "no start_mode set"),
                    TENANT, ctx(caller, "t-def"));

            verify(taskService).executeTaskSync(created);
        }

        @Test
        @DisplayName("Invalid start_mode value is rejected with the allowed list inline")
        void startModeInvalid() {
            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("agent_id", UUID.randomUUID().toString(),
                           "title", "bogus",
                           "instructions", "x",
                           "start_mode", "bogus_value"),
                    TENANT, ctx(UUID.randomUUID(), "t-bad"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error())
                    .contains("start_mode")
                    .contains("pending")
                    .contains("in_progress")
                    .contains("execute");
        }

        @Test
        @DisplayName("Backlog (no agent_id) ignores start_mode - always pending, never sync-executes")
        void backlogIgnoresStartMode() {
            UUID caller = UUID.randomUUID();
            AgentTaskEntity backlog = sampleTask(UUID.randomUUID(), null);
            // Backlog flow: autoTrigger irrelevant since assignee is null.
            when(taskService.assignTask(eq(TENANT), eq(caller), isNull(), any(), anyBoolean()))
                    .thenReturn(backlog);

            // Even when caller asks for start_mode='execute', backlog cannot be executed
            // (no assignee to dispatch to), so executeTaskSync must NOT be invoked.
            Optional<ToolExecutionResult> r = module.execute("assign",
                    Map.of("title", "open job",
                           "instructions", "any worker",
                           "start_mode", "execute"),
                    TENANT, ctx(caller, "t-bk"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isTrue();
            verify(taskService, org.mockito.Mockito.never()).executeTaskSync(any());
            Map<?, ?> body = (Map<?, ?>) r.get().data();
            assertThat(body.get("assigned_to")).isEqualTo("backlog");
        }
    }

    // ------------------------------------------------------------------
    // inbox / outbox / backlog
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("inbox / outbox / backlog")
    class Queries {

        @Test
        @DisplayName("inbox list uses caller id")
        void inboxList() {
            UUID caller = UUID.randomUUID();
            when(taskService.getInboxList(eq(TENANT), eq(caller), eq(20)))
                    .thenReturn(List.of(sampleTask(UUID.randomUUID(), caller)));

            Optional<ToolExecutionResult> r = module.execute("inbox", Map.of(), TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).getInboxList(TENANT, caller, 20);
        }

        @Test
        @DisplayName("inbox with task_id fetches single task")
        void inboxSingle() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            when(taskService.getInboxTask(eq(TENANT), eq(caller), eq(taskId)))
                    .thenReturn(com.apimarketplace.agent.dto.TaskResponse.from(sampleTask(taskId, caller)));

            Optional<ToolExecutionResult> r = module.execute("inbox",
                    Map.of("task_id", taskId.toString()),
                    TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).getInboxTask(TENANT, caller, taskId);
        }

        @Test
        @DisplayName("outbox with status filter forwards filter")
        void outboxWithStatus() {
            UUID caller = UUID.randomUUID();
            when(taskService.getOutbox(eq(TENANT), eq(caller), eq("completed"), eq(20)))
                    .thenReturn(List.of());

            module.execute("outbox", Map.of("status", "completed"), TENANT, ctx(caller, "t1"));

            verify(taskService).getOutbox(TENANT, caller, "completed", 20);
        }

        @Test
        @DisplayName("backlog forwards tenant only - for a backlog-enabled agent caller (V340)")
        void backlogList() {
            UUID caller = UUID.randomUUID();
            when(taskService.isBacklogEnabled(caller)).thenReturn(true);
            when(taskService.getBacklog(eq(TENANT), eq(20))).thenReturn(List.of());

            module.execute("backlog", Map.of(), TENANT, ctx(caller, "t1"));

            verify(taskService).getBacklog(TENANT, 20);
        }

        @Test
        @DisplayName("V340: backlog browse is denied for an agent that did NOT opt in")
        void backlogDeniedForDisabledAgent() {
            UUID caller = UUID.randomUUID();
            when(taskService.isBacklogEnabled(caller)).thenReturn(false);

            Optional<ToolExecutionResult> r = module.execute("backlog", Map.of(), TENANT, ctx(caller, "t1"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("not enabled to access the shared backlog");
            verify(taskService, never()).getBacklog(anyString(), anyInt());
            verify(taskService, never()).getBacklog(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("V340: a human caller (no agent identity) may always browse the backlog")
        void backlogAllowedForHumanCaller() {
            ToolExecutionContext noAgent = new ToolExecutionContext(
                    TENANT, new HashMap<>(), Map.of(), Set.of(), null, null, null, null);
            when(taskService.getBacklog(eq(TENANT), eq(20))).thenReturn(List.of());

            Optional<ToolExecutionResult> r = module.execute("backlog", Map.of(), TENANT, noAgent);

            assertThat(r.get().success()).isTrue();
            verify(taskService).getBacklog(TENANT, 20);
            // The opt-in flag is never consulted for a human caller.
            verify(taskService, never()).isBacklogEnabled(any());
        }
    }

    // ------------------------------------------------------------------
    // lifecycle actions
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("task_complete requires result param")
        void completeRequiresResult() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            Optional<ToolExecutionResult> r = module.execute("task_complete",
                    Map.of("task_id", taskId.toString()),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("result");
        }

        @Test
        @DisplayName("task_complete forwards to service")
        void completeForwards() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity done = sampleTask(taskId, caller);
            done.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskService.completeTask(TENANT, taskId, caller, "final result", false, null)).thenReturn(done);

            Optional<ToolExecutionResult> r = module.execute("task_complete",
                    Map.of("task_id", taskId.toString(), "result", "final result"),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).completeTask(TENANT, taskId, caller, "final result", false, null);
        }

        @Test
        @DisplayName("task_complete with force=true forwards force flag")
        void completeForceTrue() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity done = sampleTask(taskId, caller);
            done.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskService.completeTask(TENANT, taskId, caller, "forced", true, null)).thenReturn(done);

            Optional<ToolExecutionResult> r = module.execute("task_complete",
                    Map.of("task_id", taskId.toString(), "result", "forced", "force", true),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).completeTask(TENANT, taskId, caller, "forced", true, null);
        }

        @Test
        @DisplayName("task_complete with force as string 'true' is parsed correctly")
        void completeForceStringTrue() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity done = sampleTask(taskId, caller);
            done.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskService.completeTask(TENANT, taskId, caller, "forced", true, null)).thenReturn(done);

            Optional<ToolExecutionResult> r = module.execute("task_complete",
                    Map.of("task_id", taskId.toString(), "result", "forced", "force", "true"),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).completeTask(TENANT, taskId, caller, "forced", true, null);
        }

        @Test
        @DisplayName("task_reject forwards reason")
        void rejectForwards() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity failed = sampleTask(taskId, caller);
            failed.setStatus(AgentTaskEntity.STATUS_FAILED);
            when(taskService.rejectTask(TENANT, taskId, caller, "nope", null)).thenReturn(failed);

            module.execute("task_reject",
                    Map.of("task_id", taskId.toString(), "reason", "nope"),
                    TENANT, orgCtx(caller, "t1"));

            verify(taskService).rejectTask(TENANT, taskId, caller, "nope", null);
        }

        @Test
        @DisplayName("task_cancel forwards to cascading cancel")
        void cancelForwards() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            when(taskService.cancelTask(TENANT, taskId, caller, null, "stop")).thenReturn(3);

            Optional<ToolExecutionResult> r = module.execute("task_cancel",
                    Map.of("task_id", taskId.toString(), "reason", "stop"),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).cancelTask(TENANT, taskId, caller, null, "stop");
        }

        @Test
        @DisplayName("task_update requires task_id")
        void updateRequiresTaskId() {
            UUID caller = UUID.randomUUID();
            Optional<ToolExecutionResult> r = module.execute("task_update", Map.of(), TENANT, ctx(caller, "t1"));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("task_id");
        }
    }

    // ------------------------------------------------------------------
    // review lifecycle (approve / reject-review / review_inbox)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("review lifecycle")
    class ReviewLifecycle {

        @Test
        @DisplayName("task_approve forwards to service")
        void approveForwards() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity approved = sampleTask(taskId, UUID.randomUUID());
            approved.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskService.approveTask(TENANT, taskId, caller, null)).thenReturn(approved);

            Optional<ToolExecutionResult> r = module.execute("task_approve",
                    Map.of("task_id", taskId.toString()),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).approveTask(TENANT, taskId, caller, null);
        }

        @Test
        @DisplayName("task_approve rejects human caller")
        void approveRejectsHuman() {
            Optional<ToolExecutionResult> r = module.execute("task_approve",
                    Map.of("task_id", UUID.randomUUID().toString()),
                    TENANT, ctx(null, "t1"));

            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("requires an agent identity");
        }

        @Test
        @DisplayName("task_approve forwards reviewer execution token")
        void approveForwardsReviewerExecutionToken() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID reviewerExecutionId = UUID.randomUUID();
            AgentTaskEntity approved = sampleTask(taskId, UUID.randomUUID());
            approved.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                    .thenReturn(Optional.of(sampleTask(taskId, null)));
            when(taskService.approveTask(TENANT, taskId, caller, reviewerExecutionId)).thenReturn(approved);

            Optional<ToolExecutionResult> r = module.execute("task_approve",
                    Map.of("task_id", taskId.toString()),
                    TENANT, reviewCtx(caller, "t1", reviewerExecutionId));

            assertThat(r.get().success()).isTrue();
            verify(taskService).approveTask(TENANT, taskId, caller, reviewerExecutionId);
        }

        @Test
        @DisplayName("task_approve forwards reviewer execution token through org-scoped context")
        void approveForwardsReviewerExecutionTokenInOrgScope() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID reviewerExecutionId = UUID.randomUUID();
            AgentTaskEntity approved = sampleTask(taskId, UUID.randomUUID());
            approved.setStatus(AgentTaskEntity.STATUS_COMPLETED);
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                    .thenReturn(Optional.of(sampleTask(taskId, null)));
            when(taskService.approveTask(TENANT, taskId, caller, reviewerExecutionId)).thenReturn(approved);

            Optional<ToolExecutionResult> r = module.execute("task_approve",
                    Map.of("task_id", taskId.toString()),
                    TENANT, reviewCtx(caller, "t1", reviewerExecutionId));

            assertThat(r.get().success()).isTrue();
            verify(taskService).approveTask(TENANT, taskId, caller, reviewerExecutionId);
        }

        @Test
        @DisplayName("task_reject_review forwards reason to service")
        void rejectReviewForwards() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity rejected = sampleTask(taskId, UUID.randomUUID());
            rejected.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
            when(taskService.rejectReview(TENANT, taskId, caller, null, "needs work")).thenReturn(rejected);

            Optional<ToolExecutionResult> r = module.execute("task_reject_review",
                    Map.of("task_id", taskId.toString(), "reason", "needs work"),
                    TENANT, orgCtx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).rejectReview(TENANT, taskId, caller, null, "needs work");
        }

        @Test
        @DisplayName("task_reject_review forwards reviewer execution token through org-scoped context")
        void rejectReviewForwardsReviewerExecutionTokenInOrgScope() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID reviewerExecutionId = UUID.randomUUID();
            AgentTaskEntity rejected = sampleTask(taskId, UUID.randomUUID());
            rejected.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                    .thenReturn(Optional.of(sampleTask(taskId, null)));
            when(taskService.rejectReview(TENANT, taskId, caller, reviewerExecutionId, "needs work"))
                    .thenReturn(rejected);

            Optional<ToolExecutionResult> r = module.execute("task_reject_review",
                    Map.of("task_id", taskId.toString(), "reason", "needs work"),
                    TENANT, reviewCtx(caller, "t1", reviewerExecutionId));

            assertThat(r.get().success()).isTrue();
            verify(taskService).rejectReview(TENANT, taskId, caller, reviewerExecutionId, "needs work");
        }

        @Test
        @DisplayName("task_reject_review rejects human caller")
        void rejectReviewRejectsHuman() {
            Optional<ToolExecutionResult> r = module.execute("task_reject_review",
                    Map.of("task_id", UUID.randomUUID().toString()),
                    TENANT, ctx(null, "t1"));

            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("requires an agent identity");
        }

        @Test
        @DisplayName("review_inbox returns tasks from service")
        void reviewInboxForwards() {
            UUID caller = UUID.randomUUID();
            when(taskService.getReviewInbox(TENANT, caller, 20))
                    .thenReturn(List.of(sampleTask(UUID.randomUUID(), UUID.randomUUID())));

            Optional<ToolExecutionResult> r = module.execute("review_inbox", Map.of(),
                    TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(taskService).getReviewInbox(TENANT, caller, 20);
        }

        @Test
        @DisplayName("review_inbox rejects human caller")
        void reviewInboxRejectsHuman() {
            Optional<ToolExecutionResult> r = module.execute("review_inbox", Map.of(),
                    TENANT, ctx(null, "t1"));

            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("requires an agent identity");
        }
    }

    // ------------------------------------------------------------------
    // claim
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("claim")
    class Claim {
        @Test
        @DisplayName("Returns claimed=false when race loses")
        void raceLost() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            when(taskService.isBacklogEnabled(caller)).thenReturn(true);
            when(taskService.claimTask(TENANT, caller, taskId)).thenReturn(Optional.empty());

            Optional<ToolExecutionResult> r = module.execute("claim",
                    Map.of("task_id", taskId.toString()),
                    TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            assertThat(r.get().data().toString()).contains("claimed=false");
        }

        @Test
        @DisplayName("Returns claimed=true with task data on success")
        void claimSuccess() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            when(taskService.isBacklogEnabled(caller)).thenReturn(true);
            when(taskService.claimTask(TENANT, caller, taskId))
                    .thenReturn(Optional.of(sampleTask(taskId, caller)));

            Optional<ToolExecutionResult> r = module.execute("claim",
                    Map.of("task_id", taskId.toString()),
                    TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            assertThat(r.get().data().toString()).contains("claimed=true");
        }

        @Test
        @DisplayName("V340: claim is denied (and never reaches the service) when the agent did NOT opt in")
        void claimDeniedWhenBacklogDisabled() {
            UUID caller = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            when(taskService.isBacklogEnabled(caller)).thenReturn(false);

            Optional<ToolExecutionResult> r = module.execute("claim",
                    Map.of("task_id", taskId.toString()),
                    TENANT, ctx(caller, "t1"));

            assertThat(r).isPresent();
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("not enabled to pick up shared backlog tasks");
            // The autonomous claim must short-circuit BEFORE the atomic claim CAS runs.
            verify(taskService, never()).claimTask(anyString(), any(UUID.class), any(UUID.class));
            verify(taskService, never()).claimTask(anyString(), anyString(), any(UUID.class), any(UUID.class));
        }
    }

    // ------------------------------------------------------------------
    // recurrences
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("recurrences")
    class Recurrences {

        private AgentTaskRecurrenceEntity sampleRec(UUID id) {
            AgentTaskRecurrenceEntity r = new AgentTaskRecurrenceEntity();
            r.setId(id);
            r.setTenantId(TENANT);
            r.setTitle("t");
            r.setInstructions("i");
            r.setCronExpression("0 0 * * *");
            r.setTimezone("UTC");
            r.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
            r.setEnabled(true);
            return r;
        }

        @Test
        @DisplayName("recurrence_create forwards to service")
        void create() {
            UUID caller = UUID.randomUUID();
            AgentTaskRecurrenceEntity created = sampleRec(UUID.randomUUID());
            when(recurrenceService.create(eq(TENANT), eq(caller), isNull(), any())).thenReturn(created);

            Map<String, Object> params = Map.of(
                    "title", "nightly", "instructions", "do X",
                    "cron", "0 0 * * *", "timezone", "UTC"
            );
            Optional<ToolExecutionResult> r = module.execute("recurrence_create", params, TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(recurrenceService).create(eq(TENANT), eq(caller), isNull(), any());
        }

        @Test
        @DisplayName("recurrence_list forwards scope")
        void list() {
            UUID caller = UUID.randomUUID();
            when(recurrenceService.list(TENANT, caller, "all_in_tenant")).thenReturn(List.of());

            module.execute("recurrence_list", Map.of("scope", "all_in_tenant"), TENANT, ctx(caller, "t1"));

            verify(recurrenceService).list(TENANT, caller, "all_in_tenant");
        }

        @Test
        @DisplayName("recurrence_update forwards enabled flag")
        void update() {
            UUID caller = UUID.randomUUID();
            UUID recId = UUID.randomUUID();
            when(recurrenceService.update(eq(TENANT), eq(recId), eq(caller), isNull(), any()))
                    .thenReturn(sampleRec(recId));

            Map<String, Object> params = new HashMap<>();
            params.put("recurrence_id", recId.toString());
            params.put("enabled", false);
            module.execute("recurrence_update", params, TENANT, ctx(caller, "t1"));

            verify(recurrenceService).update(eq(TENANT), eq(recId), eq(caller), isNull(), any());
        }

        @Test
        @DisplayName("recurrence_delete forwards id")
        void delete() {
            UUID caller = UUID.randomUUID();
            UUID recId = UUID.randomUUID();

            Optional<ToolExecutionResult> r = module.execute("recurrence_delete",
                    Map.of("recurrence_id", recId.toString()),
                    TENANT, ctx(caller, "t1"));

            assertThat(r.get().success()).isTrue();
            verify(recurrenceService).delete(TENANT, recId, caller, null);
        }

        @Test
        @DisplayName("recurrence_update without recurrence_id fails")
        void updateRequiresId() {
            Optional<ToolExecutionResult> r = module.execute("recurrence_update", Map.of(),
                    TENANT, ctx(UUID.randomUUID(), "t1"));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("recurrence_id");
        }
    }

    // ------------------------------------------------------------------
    // allowedAgentIds enforcement
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("allowedAgentIds enforcement")
    class AllowedAgentIds {

        private final UUID caller = UUID.randomUUID();
        private final UUID childAgent = UUID.randomUUID();
        private final UUID foreignAgent = UUID.randomUUID();

        private Map<String, Object> assignParams(UUID agentId) {
            Map<String, Object> p = new HashMap<>();
            if (agentId != null) p.put("agent_id", agentId.toString());
            p.put("title", "test");
            p.put("instructions", "do it");
            return p;
        }

        @Test
        @DisplayName("Agent with allowedAgentIds can assign to a listed child")
        void assignToAllowedChild() {
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), childAgent);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(childAgent),
                    TENANT, ctx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isTrue();
        }

        @Test
        @DisplayName("Agent with allowedAgentIds is blocked from assigning to unlisted agent")
        void assignToForeignAgentBlocked() {
            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(foreignAgent),
                    TENANT, ctx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("not in your allowed agents list");
        }

        @Test
        @DisplayName("null allowedAgentIds (god / primary chat) is unrestricted - assign succeeds")
        void nullAllowedAgentIdsIsUnrestricted() {
            // Pre-fix: this same call was rejected with "no agents configured in toolsConfig.agents".
            // Post-fix: convention align - null allowedAgentIds == god == no restriction. Symmetric
            // with TaskVisibilityResolver and SubAgentExecutionHandler.
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), foreignAgent);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(foreignAgent),
                    TENANT, ctx(caller, "t1"));
            assertThat(r.get().success())
                    .as("god caller (null allowedAgentIds) must be allowed to assign to any agent")
                    .isTrue();
        }

        @Test
        @DisplayName("Agent with empty allowedAgentIds list cannot assign to any agent")
        void assignWithEmptyListBlocked() {
            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(foreignAgent),
                    TENANT, ctx(caller, "t1", List.of()));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("not in your allowed agents list");
        }

        @Test
        @DisplayName("Backlog assign (null agent_id) succeeds even when caller has null allowedAgentIds")
        void backlogAllowedWithoutChildren() {
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), null);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);

            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(null),
                    TENANT, ctx(caller, "t1"));
            assertThat(r.get().success()).isTrue();
        }

        @Test
        @DisplayName("Human caller (no __agentId__) can assign to any agent without restriction")
        void humanCallerUnrestricted() {
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), foreignAgent);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Optional<ToolExecutionResult> r = module.execute("assign", assignParams(foreignAgent),
                    TENANT, ctx(null, "t1"));
            assertThat(r.get().success()).isTrue();
        }

        @Test
        @DisplayName("Reviewer set to non-allowed agent is blocked")
        void reviewerNonAllowedBlocked() {
            Map<String, Object> params = assignParams(childAgent);
            params.put("reviewer_agent_id", foreignAgent.toString());

            Optional<ToolExecutionResult> r = module.execute("assign", params,
                    TENANT, ctx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("reviewer_agent_id");
        }

        @Test
        @DisplayName("Reviewer set to caller itself is allowed (self-review)")
        void reviewerSelfAllowed() {
            AgentTaskEntity created = sampleTask(UUID.randomUUID(), childAgent);
            when(taskService.assignTask(anyString(), any(), any(), any(), anyBoolean())).thenReturn(created);
            when(taskService.executeTaskSync(any())).thenReturn(created);

            Map<String, Object> params = assignParams(childAgent);
            params.put("reviewer_agent_id", caller.toString());

            Optional<ToolExecutionResult> r = module.execute("assign", params,
                    TENANT, ctx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isTrue();
        }

        @Test
        @DisplayName("task_update reassign to non-allowed agent is blocked")
        void updateReassignBlocked() {
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            Map<String, Object> params = new HashMap<>();
            params.put("task_id", taskId.toString());
            params.put("agent_id", foreignAgent.toString());

            Optional<ToolExecutionResult> r = module.execute("task_update", params,
                    TENANT, orgCtx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isFalse();
            assertThat(r.get().error()).contains("not in your allowed agents list");
        }

        @Test
        @DisplayName("task_update reassign to allowed child succeeds")
        void updateReassignAllowed() {
            UUID taskId = UUID.randomUUID();
            taskVisible(taskId);
            AgentTaskEntity updated = sampleTask(taskId, childAgent);
            when(taskService.updateTask(anyString(), any(), any(), any(), any())).thenReturn(updated);

            Map<String, Object> params = new HashMap<>();
            params.put("task_id", taskId.toString());
            params.put("agent_id", childAgent.toString());

            Optional<ToolExecutionResult> r = module.execute("task_update", params,
                    TENANT, orgCtx(caller, "t1", List.of(childAgent.toString())));
            assertThat(r.get().success()).isTrue();
        }
    }
}
