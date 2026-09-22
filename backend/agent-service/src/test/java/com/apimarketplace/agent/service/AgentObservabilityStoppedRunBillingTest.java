package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.metrics.AgentPrometheusMetrics;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.LlmCacheTokens;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stopped chat turn must still be billed for the tokens it spent, and a turn that
 * spent tokens nobody counted must not pass for a turn that never ran.
 *
 * <p>Both rules were broken by the same condition. The credit skip was keyed on
 * "not successful AND zero prompt tokens AND zero completion tokens", written for the
 * rows conversation-service records when NO model call happened (a 402 gate, a throttled
 * cron tick). A CLI killed mid-turn produces byte-identical input: it reports no usage at
 * all. So in prod every codex chat a user ever stopped billed zero credits, including one
 * that had made 21 tool calls, while the same chat allowed to finish billed its real
 * tokens (56,833 prompt tokens on the very next conversation, one minute later).
 *
 * <p>The row itself carries the evidence that separates the two: a TOOL CALL only exists
 * because a model asked for it. An iteration count deliberately does not count as that
 * evidence - the loop increments it before calling the provider - so a provider error is
 * never mistaken for work nobody billed.
 */
@DisplayName("AgentObservabilityService - a stopped run is billed for what it spent")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentObservabilityStoppedRunBillingTest {

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private StorageService storageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentMetricsAggregationService aggregationService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentPrometheusMetrics prometheusMetrics;
    @Mock private BudgetReservationService budgetReservationService;

    private AgentObservabilityService service;

    private static final String TENANT_ID = "1";
    private static final String CONV_ID = "a017fa4e-8781-40e0-9422-ef62ca617749";

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService
        );
        when(executionRepository.save(any(AgentExecutionEntity.class)))
            .thenAnswer(inv -> {
                AgentExecutionEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(UUID.randomUUID());
                return e;
            });
    }

    @Test
    @DisplayName("GUARD (green before and after the fix): a stop whose tokens ARE known bills them - widening the condition must not break this")
    void keepsBillingAStoppedTurnWhoseTokensAreKnown() {
        // This path already worked: the old condition only skipped at zero tokens. It is
        // pinned because the fix widens that condition, and widening it wrongly would
        // start dropping the very charges this change exists to protect.
        service.recordFromChat(TENANT_ID, null, request(false, "STOPPED_BY_USER", 15255, 40, 3, 0));

        verify(creditClient).consumeCredits(
            eq(TENANT_ID), eq("CHAT_CONVERSATION"), anyString(),
            eq("codex"), eq("gpt-5.6-sol"), eq(15255), eq(40),
            isNull(), any(LlmCacheTokens.class), isNull());
    }

    @Test
    @DisplayName("no model call at all (402 gate, throttled fire) -> still skipped, no ledger noise")
    void keepsSkippingRowsWhereNoModelCallHappened() {
        service.recordFromChat(TENANT_ID, null, request(false, "BUDGET_EXHAUSTED", 0, 0, 0, 0));

        verify(creditClient, never()).consumeCredits(
            anyString(), anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyInt(), any(), any(LlmCacheTokens.class), any());
        verify(prometheusMetrics, never()).recordUnreportedUsage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("did work but reported no usage -> counted as an unbilled run instead of passing for a no-op")
    void surfacesAStoppedRunWhoseUsageNobodyReported() {
        // The shape of the prod incident: killed while parked on a tool call, so the CLI
        // never emitted its usage. Three tool calls prove a model answered at least once.
        service.recordFromChat(TENANT_ID, null, request(false, "STOPPED_BY_USER", 0, 0, 3, 0));

        verify(prometheusMetrics).recordUnreportedUsage("codex", "gpt-5.6-sol", "STOPPED_BY_USER");
        // And it still goes down the ONE billing path rather than being short-circuited:
        // there must be no branch that decides on its own not to bill a run that worked.
        // At zero tokens the debit is a no-op server-side, so this costs nothing and keeps
        // the run billable the moment its usage becomes known.
        verify(creditClient).consumeCredits(
            eq(TENANT_ID), eq("CHAT_CONVERSATION"), anyString(),
            eq("codex"), eq("gpt-5.6-sol"), eq(0), eq(0),
            isNull(), any(LlmCacheTokens.class), isNull());
    }

    @Test
    @DisplayName("an iteration count is NOT evidence a model answered - a provider error must not be counted as unbilled work")
    void doesNotTreatAnIterationAsEvidenceOfAModelCall() {
        // The loop counts an iteration BEFORE calling the provider and returns that count
        // on failure, so a 5xx on the first iteration of an ordinary chat arrives here as
        // iterations=1 with nothing spent. Counting it would spike the metric during a
        // provider outage on runs that cost nothing - the opposite of its meaning.
        service.recordFromChat(TENANT_ID, null, request(false, "ERROR", 0, 0, 0, 2));

        verify(prometheusMetrics, never()).recordUnreportedUsage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a finished run that used tools and reported no usage is counted too, not excused for succeeding")
    void countsASuccessfulRunThatReportedNoUsage() {
        service.recordFromChat(TENANT_ID, null, request(true, "COMPLETED", 0, 0, 4, 1));

        verify(prometheusMetrics).recordUnreportedUsage("codex", "gpt-5.6-sol", "COMPLETED");
    }

    @Test
    @DisplayName("the counter name is the one the dashboards select")
    void pinsTheMetricName() {
        // Every assertion above runs against a mock, so a rename would leave them green
        // and the dashboard empty. The published name is part of the contract.
        assertThat(AgentPrometheusMetrics.UNREPORTED_USAGE_TOTAL).isEqualTo("agent_unreported_usage_total");
    }

    @Test
    @DisplayName("a normal successful turn is untouched by the guard")
    void leavesASuccessfulTurnAlone() {
        service.recordFromChat(TENANT_ID, null, request(true, "COMPLETED", 56833, 378, 3, 1));

        verify(creditClient).consumeCredits(
            eq(TENANT_ID), eq("CHAT_CONVERSATION"), anyString(),
            eq("codex"), eq("gpt-5.6-sol"), eq(56833), eq(378),
            isNull(), any(LlmCacheTokens.class), isNull());
        verify(prometheusMetrics, never()).recordUnreportedUsage(anyString(), anyString(), anyString());
    }

    /** 40 positional args - keep tightly aligned with ChatAgentObservabilityRequest. */
    private ChatAgentObservabilityRequest request(boolean success, String stopReason,
                                                  int promptTokens, int completionTokens,
                                                  int toolCalls, int iterations) {
        return new ChatAgentObservabilityRequest(
            null,                                 // agentEntityId - direct chat, no agent entity
            "codex", "gpt-5.6-sol",               // provider, model - the CLI that ran and lost its usage
            null, null, null,                     // temperature, maxTokens, maxIterations
            success, stopReason, null, null,      // success, stopReason, budgetScope, errorMessage
            86180L, iterations, toolCalls, toolCalls, 0, 1,
            promptTokens, completionTokens, promptTokens + completionTokens,
            null, null, null, null,               // cache + reasoning tokens
            null, null, false, null, null,        // tool patterns
            "system prompt", "demande moi de ask nimporte quoi",
            CONV_ID, "CHAT",
            null, null,                           // taskId, executionId
            null, null, null, null, null, null    // detailed lists
        );
    }
}
