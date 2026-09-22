package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression cover for "started_at was persist time, so it landed after ended_at".
 *
 * <p>The row is written once, at the end of the execution. The writer stamped
 * {@code ended_at = now()} and left {@code started_at} to the entity default, which
 * stamped {@code now()} again microseconds later - so every row the table ever held had
 * {@code ended_at < started_at} (7,953 of 7,953 in prod on 2026-09-17) while
 * {@code duration_ms} carried the real, correct figure.
 *
 * <p>Each test below fails on the pre-fix code: {@code started_at} was simply never set
 * by the service, so the assertions on it are null, and the span assertions cannot hold.
 */
@DisplayName("AgentObservabilityService - execution start/end timestamps")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceExecutionTimestampsTest {

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private StorageService storageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentMetricsAggregationService aggregationService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private AgentRepository agentRepository;
    @Mock private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;
    @Mock private com.apimarketplace.agent.service.budget.BudgetReservationService budgetReservationService;

    @Captor private ArgumentCaptor<AgentExecutionEntity> execCaptor;

    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService
        );
    }

    // =========================================================================
    // The derivation itself
    // =========================================================================

    @Nested
    @DisplayName("startedAtFrom")
    class StartedAtFrom {

        private final Instant endedAt = Instant.parse("2026-09-17T20:59:57.615410Z");

        @Test
        @DisplayName("walks back exactly the measured duration")
        void walksBackExactlyTheMeasuredDuration() {
            Instant startedAt = AgentObservabilityService.startedAtFrom(endedAt, 215_416L);

            assertThat(startedAt).isEqualTo(Instant.parse("2026-09-17T20:56:22.199410Z"));
            assertThat(Duration.between(startedAt, endedAt).toMillis()).isEqualTo(215_416L);
        }

        @Test
        @DisplayName("keeps sub-millisecond precision so the span equals duration_ms exactly")
        void keepsSubMillisecondPrecision() {
            Instant startedAt = AgentObservabilityService.startedAtFrom(endedAt, 1L);

            assertThat(startedAt.getNano()).isEqualTo(endedAt.getNano() - 1_000_000);
            assertThat(Duration.between(startedAt, endedAt)).isEqualTo(Duration.ofMillis(1));
        }

        @Test
        @DisplayName("an unmeasured duration gives a zero-length window at the end, never a later stamp")
        void unmeasuredDurationCollapsesToEnd() {
            assertThat(AgentObservabilityService.startedAtFrom(endedAt, 0L)).isEqualTo(endedAt);
        }

        @Test
        @DisplayName("a negative duration (broken clock) collapses to the end rather than moving forward")
        void negativeDurationCollapsesToEnd() {
            assertThat(AgentObservabilityService.startedAtFrom(endedAt, -5_000L)).isEqualTo(endedAt);
        }

        @Test
        @DisplayName("a duration that would walk back past the epoch collapses to the end instead of dating the row to prehistory")
        void absurdDurationCollapsesToEndInsteadOfDatingToPrehistory() {
            // Deliberately NOT "instead of throwing": Instant.minusMillis(Long.MAX_VALUE)
            // returns a perfectly valid year -292,274,998. Nothing would have stopped it.
            assertThat(AgentObservabilityService.startedAtFrom(endedAt, Long.MAX_VALUE)).isEqualTo(endedAt);
            assertThat(AgentObservabilityService.startedAtFrom(endedAt, endedAt.toEpochMilli() + 1))
                .isEqualTo(endedAt);
        }

        @Test
        @DisplayName("a duration landing exactly on the epoch is still derived, not discarded")
        void durationLandingExactlyOnEpochIsDerived() {
            Instant startedAt = AgentObservabilityService.startedAtFrom(endedAt, endedAt.toEpochMilli());

            assertThat(startedAt).isEqualTo(Instant.EPOCH.plusNanos(endedAt.getNano() % 1_000_000));
            assertThat(startedAt).isAfterOrEqualTo(Instant.EPOCH);
        }
    }

    // =========================================================================
    // What actually reaches the row - the shape the prod bug had
    // =========================================================================

    @Nested
    @DisplayName("persisted row")
    class PersistedRow {

        @Test
        @DisplayName("workflow path: started_at precedes ended_at by exactly duration_ms")
        void workflowRowSpanEqualsDuration() {
            AgentObservabilityRequest req = workflowRequest(42_438L);

            service.recordFromRequest(req);

            AgentExecutionEntity exec = savedExecution();
            assertThat(exec.getStartedAt()).isNotNull();
            assertThat(exec.getEndedAt()).isNotNull();
            assertThat(exec.getStartedAt()).isBefore(exec.getEndedAt());
            assertThat(Duration.between(exec.getStartedAt(), exec.getEndedAt()).toMillis())
                .isEqualTo(exec.getDurationMs());
        }

        @Test
        @DisplayName("chat path: the 3.5-minute turn that exposed the bug no longer ends before it starts")
        void chatRowIsNotInverted() {
            // The real prod row: run c48ff8e5, 215_416 ms, recorded with ended_at BEFORE started_at.
            service.recordFromChat("tenant-1", "org-1", chatRequest(215_416L, false, "BUDGET_EXHAUSTED"));

            AgentExecutionEntity exec = savedExecution();
            assertThat(exec.getStartedAt()).isNotNull();
            assertThat(exec.getEndedAt()).isNotNull();
            assertThat(exec.getEndedAt()).isAfter(exec.getStartedAt());
            assertThat(Duration.between(exec.getStartedAt(), exec.getEndedAt()).toMillis())
                .isEqualTo(215_416L);
        }

        @Test
        @DisplayName("a row with no measured duration still has started_at at or before ended_at")
        void zeroDurationRowIsNotInverted() {
            service.recordFromChat("tenant-1", "org-1", chatRequest(0L));

            AgentExecutionEntity exec = savedExecution();
            assertThat(exec.getStartedAt()).isNotNull();
            assertThat(exec.getStartedAt()).isEqualTo(exec.getEndedAt());
        }

        @Test
        @DisplayName("ended_at stays the moment the row is recorded, not a value derived from the caller")
        void endedAtIsTheRecordingInstant() {
            Instant before = Instant.now();
            service.recordFromChat("tenant-1", "org-1", chatRequest(1_000L));
            Instant after = Instant.now();

            AgentExecutionEntity exec = savedExecution();
            assertThat(exec.getEndedAt()).isBetween(before, after);
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    /**
     * The single execution header the service built. {@code doRecordFromRequest} saves the
     * SAME instance twice (once to fix the row's identity, once after the child rows), so
     * every captured value is the same reference and taking the first is not a choice
     * between distinct entities.
     */
    private AgentExecutionEntity savedExecution() {
        org.mockito.Mockito.verify(executionRepository, org.mockito.Mockito.atLeastOnce())
            .save(execCaptor.capture());
        assertThat(execCaptor.getAllValues()).allSatisfy(
            saved -> assertThat(saved).isSameAs(execCaptor.getAllValues().get(0)));
        return execCaptor.getAllValues().get(0);
    }

    private AgentObservabilityRequest workflowRequest(long durationMs) {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setAgentEntityId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        req.setAgentType("agent");
        req.setNodeId("agent:my_agent");
        req.setRunId("run-123");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus("COMPLETED");
        req.setStopReason("end_turn");
        req.setDurationMs(durationMs);
        req.setPromptTokens(1000);
        req.setCompletionTokens(500);
        req.setTotalTokens(1500);
        req.setIterationCount(1);
        return req;
    }

    private ChatAgentObservabilityRequest chatRequest(long durationMs) {
        return chatRequest(durationMs, true, "end_turn");
    }

    /**
     * A chat turn as conversation-service posts it. The token counts are the real ones
     * from the production row that exposed the bug.
     *
     * <p>{@code success} and {@code stopReason} travel together because no producer sends
     * them apart: AgentObservabilityClient posts {@code success=false} with
     * {@code BUDGET_EXHAUSTED}, and a turn that simply finished posts {@code true} with
     * {@code end_turn}. Pinning one and hardcoding the other would build a request the
     * platform never sends, which is a fixture that cannot tell anyone anything.
     *
     * <p>A budget stop with tokens on the clock is still persisted: the early return in
     * {@code recordFromChat} is for a failure with ZERO tokens (the 402 at the gate),
     * where no LLM call happened at all.
     */
    private ChatAgentObservabilityRequest chatRequest(long durationMs, boolean success, String stopReason) {
        // 40 positional args - keep tightly aligned with ChatAgentObservabilityRequest.
        return new ChatAgentObservabilityRequest(
            "00000000-0000-0000-0000-000000000001", // agentEntityId
            "deepseek", "deepseek-flash",           // provider, model
            0.7, 4096, 40,                          // temperature, maxTokens, maxIterations
            success, stopReason, null, null,        // success, stopReason, budgetScope, errorMessage
            durationMs, 31, 47, 47, 0, 80,          // durationMs, iterations, 3x toolCall counts, messageCount
            1_563_983, 40_014, 1_603_997,           // prompt, completion, total tokens
            0, 0, 1_489_920, 0,                     // cache creation / read / cached / reasoning
            null, null, false, null, null,          // toolSequence, distinctTools, loopDetected, loopType, loopToolName
            "system prompt", "user prompt",
            "conv-abc", "CHAT",
            null, null,                             // taskId, executionId
            null, null, null, null, null, null      // toolResults, history, 4x per-iteration lists
        );
    }
}
