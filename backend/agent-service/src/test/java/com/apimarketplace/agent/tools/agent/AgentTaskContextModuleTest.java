package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver.ResolvedTask;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver.Role;
import com.apimarketplace.agent.tools.agent.permission.ToolCallRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskContextModule")
class AgentTaskContextModuleTest {

    @Mock private AgentService agentService;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private AgentTaskEventRepository taskEventRepository;
    @Mock private TaskVisibilityResolver visibilityResolver;

    private AgentTaskContextModule module;

    private static final String TENANT = "tenant-1";
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID ASSIGNEE = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real redactor - we want to verify content actually gets redacted end-to-end,
        // not just that the redactor is invoked. The redactor itself is unit-tested
        // separately; here we test the module wires it correctly.
        ToolCallRedactor redactor = new ToolCallRedactor(new ObjectMapper());
        module = new AgentTaskContextModule(agentService, executionRepository, messageRepository,
                toolCallRepository, taskEventRepository, visibilityResolver, redactor);
    }

    private ToolExecutionContext context(UUID callerAgentId) {
        Map<String, Object> creds = new HashMap<>();
        if (callerAgentId != null) creds.put("__agentId__", callerAgentId.toString());
        return new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, null, null);
    }

    private AgentTaskEntity buildTask() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(TASK_ID);
        task.setTenantId(TENANT);
        task.setAssignedToAgentId(ASSIGNEE);
        task.setReviewerAgentId(REVIEWER);
        task.setCreatedByAgentId(UUID.randomUUID());
        task.setTitle("Test task");
        task.setInstructions("Do thing");
        task.setStatus("in_review");
        return task;
    }

    private AgentExecutionEntity buildExecution(UUID id) {
        AgentExecutionEntity ex = new AgentExecutionEntity();
        ex.setId(id);
        ex.setTaskId(TASK_ID);
        ex.setTenantId(TENANT);
        ex.setStatus("COMPLETED");
        ex.setStartedAt(Instant.parse("2026-04-23T10:00:00Z"));
        ex.setEndedAt(Instant.parse("2026-04-23T10:01:00Z"));
        ex.setIterationCount(7);
        ex.setMessageCount(15);
        ex.setTotalToolCalls(12);
        ex.setSuccessfulToolCalls(11);
        ex.setFailedToolCalls(1);
        ex.setDistinctTools(List.of("web_search", "file_read"));
        ex.setModel("haiku-4-5");
        ex.setProvider("anthropic");
        return ex;
    }

    // ========================== task_get_context ==========================

    @Nested
    @DisplayName("task_get_context")
    class GetContext {

        @Test
        @DisplayName("REVIEWER is granted and gets task + events + executions metadata")
        void reviewerSeesContext() {
            UUID callerId = REVIEWER;
            AgentTaskEntity task = buildTask();
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);

            when(visibilityResolver.resolveRoleAndTask(eq(callerId), eq(TASK_ID), eq(TENANT), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, task));
            // Regression: events/executions are now fetched via paginated DESC repo methods.
            // Unbounded fetch was the OOM shape on tasks with hundreds of events/executions.
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            when(executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(eq(TASK_ID), eq(TENANT), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(exec)));

            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(callerId)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("viewer_role")).isEqualTo("REVIEWER");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> executions = (List<Map<String, Object>>) data.get("executions");
            assertThat(executions).hasSize(1);
            assertThat(executions.get(0).get("execution_id")).isEqualTo(EXECUTION_ID.toString());
            assertThat(executions.get(0).get("iteration_count")).isEqualTo(7);
            assertThat(executions.get(0).get("total_tool_calls")).isEqualTo(12);
            assertThat(executions.get(0).get("distinct_tools")).isEqualTo(List.of("web_search", "file_read"));
            assertThat(executions.get(0).get("order")).isEqualTo(0);

            // task block has snake_case keys promised by the help docs
            @SuppressWarnings("unchecked")
            Map<String, Object> taskBlock = (Map<String, Object>) data.get("task");
            assertThat(taskBlock).containsKeys("id", "title", "status",
                    "assignee_agent_id", "reviewer_agent_id", "creator_agent_id");
        }

        @Test
        @DisplayName("Caller without role gets failure with explicit auth error")
        void unauthorizedCallerRejected() {
            UUID callerId = UUID.randomUUID();
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.NONE, null));

            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(callerId)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Not authorized").contains(TASK_ID.toString());
            // No DB hits beyond the resolver
            verifyNoInteractions(taskEventRepository, executionRepository);
        }

        @Test
        @DisplayName("Missing task_id is rejected before any DB call")
        void missingTaskIdRejected() {
            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of(), TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("task_id").contains("required");
            verifyNoInteractions(visibilityResolver, taskEventRepository, executionRepository);
        }

        @Test
        @DisplayName("Task with no assignee returns empty executions list (no DB roundtrip for executions)")
        void noAssigneeReturnsEmptyExecutions() {
            AgentTaskEntity task = buildTask();
            task.setAssignedToAgentId(null);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.CREATOR, task));
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(UUID.randomUUID())).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((List<?>) data.get("executions")).isEmpty();
            verifyNoInteractions(executionRepository);
        }

        @Test
        @DisplayName("GOD agent path works the same as REVIEWER (resolver returns role)")
        void godAgentSeesContext() {
            AgentTaskEntity task = buildTask();
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.GOD, task));
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            when(executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(eq(TASK_ID), eq(TENANT), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(UUID.randomUUID())).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("viewer_role")).isEqualTo("GOD");
        }

        @Test
        @DisplayName("events block is built from a paginated DESC fetch and reversed to ASC chronological order")
        void eventsBlockReversedFromDescPage() {
            // Regression: previous impl pulled the un-paginated `findByTaskIdOrderByCreatedAtAsc(taskId)`
            // and sliced in Java. Long task histories OOM'd the JVM. The new path fetches a paginated
            // DESC page (newest first) and reverses to ASC for chronological reading.
            AgentTaskEntity task = buildTask();
            task.setAssignedToAgentId(null);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, task));
            com.apimarketplace.agent.domain.AgentTaskEventEntity newer = new com.apimarketplace.agent.domain.AgentTaskEventEntity();
            newer.setId(2L); newer.setEventType("status_changed");
            newer.setCreatedAt(Instant.parse("2026-04-23T12:00:00Z"));
            com.apimarketplace.agent.domain.AgentTaskEventEntity older = new com.apimarketplace.agent.domain.AgentTaskEventEntity();
            older.setId(1L); older.setEventType("created");
            older.setCreatedAt(Instant.parse("2026-04-23T10:00:00Z"));
            // Repo returns DESC: [newer, older]. Block must flip to ASC: [older, newer].
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 50), 2));

            ToolExecutionResult result = module.execute("task_get_context",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(REVIEWER)).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> eventsBlock = (Map<String, Object>) data.get("events");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) eventsBlock.get("items");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).get("event_type")).isEqualTo("created");
            assertThat(items.get(1).get("event_type")).isEqualTo("status_changed");
            assertThat(eventsBlock.get("total")).isEqualTo(2L);
            assertThat(eventsBlock.get("truncated")).isEqualTo(false);
        }
    }

    // ========================== task_get_execution ==========================

    @Nested
    @DisplayName("task_get_execution")
    class GetExecution {

        @Test
        @DisplayName("Execution belonging to a different task is rejected (404-style - no info leak)")
        void executionFromDifferentTaskRejected() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            exec.setTaskId(UUID.randomUUID()); // different task - should be rejected

            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString()),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not found");
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("Execution from a different tenant is rejected - even after task auth resolved")
        void executionFromDifferentTenantRejected() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            exec.setTenantId("other-tenant");

            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString()),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not found");
        }

        @Test
        @DisplayName("Pagination: returns has_more=true when offset+limit < total messages - fetched via DB-paginated repo")
        void paginationHasMore() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));

            // 10 messages total, ask for limit=3 starting at offset=0. Page 0 holds first 3 ASC.
            List<AgentExecutionMessageEntity> page0 = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                AgentExecutionMessageEntity m = new AgentExecutionMessageEntity();
                m.setSequenceNumber(i);
                m.setRole(i % 2 == 0 ? "user" : "assistant");
                m.setContent("msg-" + i);
                page0.add(m);
            }
            when(messageRepository.findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(page0, PageRequest.of(0, 3), 10));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString(),
                            "limit", 3, "offset", 0),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> mblock = (Map<String, Object>) data.get("messages");
            assertThat(mblock.get("total")).isEqualTo(10);
            assertThat(mblock.get("returned")).isEqualTo(3);
            assertThat(mblock.get("has_more")).isEqualTo(true);
            assertThat(mblock.get("offset")).isEqualTo(0);
            // Regression: the un-paginated repo method MUST NOT be touched here.
            verify(messageRepository, never()).findByExecutionIdOrderBySequenceNumber(any(UUID.class));
        }

        @Test
        @DisplayName("include_tool_calls=true loads tool_calls (paginated DESC, reversed to ASC) AND redacts credential-tool args")
        void includeToolCallsRedactsCredentialBody() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));
            when(messageRepository.findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // A credential-tool call carrying a token in its args - must be fully redacted
            AgentExecutionToolCallEntity tc = new AgentExecutionToolCallEntity();
            tc.setSequenceNumber(0);
            tc.setIterationNumber(1);
            tc.setToolName("credential");
            tc.setSuccess(true);
            tc.setArguments(new LinkedHashMap<>(Map.of(
                    "credential_name", "gmail-token",
                    "access_token", "ya29.a0Ad-secret-leak")));
            when(toolCallRepository.findByExecutionIdOrderBySequenceNumberDesc(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(tc), PageRequest.of(0, 100), 1));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString(),
                            "include_tool_calls", true),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> toolCallsBlock = (Map<String, Object>) data.get("tool_calls");
            assertThat(toolCallsBlock).isNotNull();
            assertThat(toolCallsBlock.get("total")).isEqualTo(1L);
            assertThat(toolCallsBlock.get("truncated")).isEqualTo(false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) toolCallsBlock.get("items");
            assertThat(items).hasSize(1);
            // Credential-tool body fully redacted - even the benign field is gone
            assertThat(items.get(0).get("arguments").toString())
                    .contains("[REDACTED:credential-tool]");
            assertThat(items.get(0).get("arguments").toString())
                    .doesNotContain("ya29.a0Ad-secret-leak");
            // Regression: the un-paginated tool-calls method MUST NOT be touched.
            verify(toolCallRepository, never()).findByExecutionIdOrderBySequenceNumber(any(UUID.class));
        }

        @Test
        @DisplayName("include_tool_calls=false (default) returns no tool_calls at all")
        void includeToolCallsFalseOmitsField() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));
            when(messageRepository.findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString()),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("tool_calls")).isNull();
            verifyNoInteractions(toolCallRepository);
        }

        @Test
        @DisplayName("Unauthorized caller is rejected without execution lookup")
        void unauthorizedShortCircuits() {
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.NONE, null));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString()),
                    TENANT, context(UUID.randomUUID())).orElseThrow();

            assertThat(result.success()).isFalse();
            verifyNoInteractions(executionRepository, messageRepository, toolCallRepository);
        }

        @Test
        @DisplayName("Missing execution_id is rejected upfront")
        void missingExecutionIdRejected() {
            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString()), TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("execution_id").contains("required");
        }

        @Test
        @DisplayName("Non-page-aligned offset returns only the page tail and advances `has_more` to the page boundary - no silent gap")
        void nonAlignedOffsetReturnsPageTailWithoutGap() {
            // Regression: previous slice math set `to = from + limit` even when only the page
            // TAIL was returned (because offset wasn't page-aligned), so the hint advised the
            // agent to resume from `to` and silently skipped the rows between the actual end
            // of the slice and the requested watermark.
            // Scenario: total=300, offset=75, limit=50 → page 1 holds rows [50,100). Slice is
            // [75,100) = 25 rows. The block's `to` must be 100 (not 125) so the next call
            // (offset=100) fetches [100,150) cleanly, capturing what would have been the lost
            // [100,125) range.
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));

            List<AgentExecutionMessageEntity> page1 = new ArrayList<>();
            for (int i = 50; i < 100; i++) {
                AgentExecutionMessageEntity m = new AgentExecutionMessageEntity();
                m.setSequenceNumber(i);
                m.setRole("user");
                m.setContent("msg-" + i);
                page1.add(m);
            }
            when(messageRepository.findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(page1, PageRequest.of(1, 50), 300));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString(),
                            "limit", 50, "offset", 75),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> mblock = (Map<String, Object>) data.get("messages");
            assertThat(mblock.get("total")).isEqualTo(300);
            assertThat(mblock.get("returned")).isEqualTo(25); // page tail only
            assertThat(mblock.get("offset")).isEqualTo(75);
            assertThat(mblock.get("has_more")).isEqualTo(true);
            // The hint must point the agent to offset=100 (page boundary), NOT offset=125,
            // otherwise rows [100,125) would be skipped on the next call.
            assertThat(data.get("hint").toString()).contains("offset=100").doesNotContain("offset=125");
        }

        @Test
        @DisplayName("Limit clamped to MAX_MESSAGES_PER_PAGE=100 when caller asks for too much - clamp applied to Pageable.pageSize")
        void limitClampedToMax() {
            AgentExecutionEntity exec = buildExecution(EXECUTION_ID);
            when(visibilityResolver.resolveRoleAndTask(any(), any(), any(), any()))
                    .thenReturn(new ResolvedTask(Role.REVIEWER, buildTask()));
            when(executionRepository.findById(EXECUTION_ID)).thenReturn(Optional.of(exec));

            // 150 messages exist; caller asks for limit=999. Internal clamp drops to 100,
            // so the repo is asked for page 0 size 100 and returns 100 of 150.
            List<AgentExecutionMessageEntity> page0 = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                AgentExecutionMessageEntity m = new AgentExecutionMessageEntity();
                m.setSequenceNumber(i);
                m.setRole("user");
                m.setContent("msg-" + i);
                page0.add(m);
            }
            org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
            when(messageRepository.findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(page0, PageRequest.of(0, 100), 150));

            ToolExecutionResult result = module.execute("task_get_execution",
                    Map.of("task_id", TASK_ID.toString(),
                            "execution_id", EXECUTION_ID.toString(),
                            "limit", 999),
                    TENANT, context(REVIEWER)).orElseThrow();

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> mblock = (Map<String, Object>) data.get("messages");
            assertThat(mblock.get("returned")).isEqualTo(100);
            assertThat(mblock.get("total")).isEqualTo(150);
            assertThat(mblock.get("has_more")).isEqualTo(true);
            verify(messageRepository).findByExecutionIdOrderBySequenceNumber(eq(EXECUTION_ID), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Action routing")
    class ActionRouting {

        @Test
        @DisplayName("canHandle returns true only for the 2 task-context actions")
        void canHandleScope() {
            assertThat(module.canHandle("task_get_context")).isTrue();
            assertThat(module.canHandle("task_get_execution")).isTrue();
            assertThat(module.canHandle("get_history")).isFalse();
            assertThat(module.canHandle("review_inbox")).isFalse();
            assertThat(module.canHandle("")).isFalse();
        }

        @Test
        @DisplayName("execute returns Optional.empty for unhandled actions")
        void unhandledActionReturnsEmpty() {
            assertThat(module.execute("get_history", Map.of(), TENANT, context(REVIEWER))).isEmpty();
        }
    }
}
