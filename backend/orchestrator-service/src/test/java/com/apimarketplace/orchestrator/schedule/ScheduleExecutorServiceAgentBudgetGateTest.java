package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: an agent that has spent its own credit budget must not be fired by its
 * schedule.
 *
 * <p>Reported from production (2026-09-18): an agent capped at 1 credit kept running on its
 * cron, indefinitely, spending a fresh turn on every tick. The cap was real and it was
 * enforced - just not anywhere a schedule passes through. {@code AgentBudgetGuard} is a
 * {@code PreIterationGuard}: it runs BETWEEN two LLM iterations of a run that has already
 * started, which stops a runaway loop and can do nothing about a new fire. The dispatch path
 * checked that the agent existed, was active and was in the right workspace, and the word
 * "budget" appeared nowhere in it.
 *
 * <p>The assertion that matters in every case below is {@code sendChatSync} never being
 * called: that is the call that costs money, and on the pre-fix code it was reached every
 * time. Asserting only the returned message would pass against a version that refuses AFTER
 * paying for the turn.
 *
 * <p>Both manual and unattended paths are covered because they enter through different
 * public methods and only one of them reports anything back to a human.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExecutorService - an agent's own credit budget gates its schedule")
class ScheduleExecutorServiceAgentBudgetGateTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private AgentClient agentClient;
    @Mock private ConversationClient conversationServiceClient;

    private ScheduleExecutorService service;

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TENANT = "user-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new ScheduleExecutorService(triggerClient, workflowRepository, runRepository,
                triggerService, productionRunResolver, agentClient, conversationServiceClient, null);
        service.setSpreadDispatcherForTesting((task, delayMs) -> task.run());
    }

    private ScheduledExecutionDto agentSchedule() {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setAgentEntityId(AGENT_ID);
        dto.setTenantId(TENANT);
        dto.setOrganizationId(ORG);
        dto.setCronExpression("0 9 * * *");
        dto.setTimezone("UTC");
        dto.setEnabled(true);
        dto.setIsActive(true);
        dto.setSchedulePrompt("Do the thing");
        dto.setNextExecutionAt(Instant.now().plusSeconds(3600));
        return dto;
    }

    /**
     * The reported shape: cap 1 credit, 3 already spent, cumulative so it never rolls over.
     * The verdict is resolved by agent-service and arrives on the DTO; this test does not
     * re-derive it, for the same reason the production code does not.
     */
    private AgentDto cappedOutAgent(Instant blockedUntil) {
        AgentDto agent = runnableAgent();
        agent.setCreditBudget(new BigDecimal("1"));
        agent.setCreditsConsumed(new BigDecimal("3"));
        agent.setBudgetBlocked(true);
        agent.setBudgetBlockedUntil(blockedUntil);
        return agent;
    }

    private AgentDto runnableAgent() {
        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setName("Reporter");
        agent.setIsActive(true);
        agent.setOrganizationId(ORG);
        agent.setModelName("deepseek-chat");
        agent.setModelProvider("deepseek");
        return agent;
    }

    private void givenAgent(AgentDto agent) {
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(agent);
        when(triggerClient.recordScheduleExecution(any())).thenReturn(agentSchedule());
        when(conversationServiceClient.findOrCreateAgentConversation(anyString(), anyString(), anyString(), any()))
                .thenReturn("conv-1");
        when(conversationServiceClient.sendChatSync(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any()))
                .thenReturn(Map.of("success", true));
    }

    @Test
    @DisplayName("a manual run of a capped-out agent spends nothing and says why")
    void manualRunOfACappedOutAgentSpendsNothing() {
        givenAgent(cappedOutAgent(null));

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        // The whole point: no turn is bought.
        verify(conversationServiceClient, never()).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), any());
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("spent its own credit budget");
        // The figures are in the sentence, because "raise it to what" is the reader's next
        // question and the alternative is making them go and look.
        assertThat(result.message()).contains("3 of 1 credits");
    }

    @Test
    @DisplayName("a cron tick on a capped-out agent spends nothing either")
    void unattendedFireOfACappedOutAgentSpendsNothing() {
        // The path a human never sees, and the one that was burning credits every tick: it
        // enters through executeSchedule, reports to nobody, and used to run the agent.
        givenAgent(cappedOutAgent(null));

        service.executeSchedule(agentSchedule());

        verify(conversationServiceClient, never()).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the figure counts what a sub-agent holds, so it agrees with the other surfaces")
    void theRefusalPrintsTheCommittedFigure() {
        // 6 spent and 4 committed to an in-flight sub-agent, against a cap of 10. Printing the
        // spend alone reads "6 of 10 credits" beside a refusal, which looks like a bug to the
        // person who set the cap - and the webhook and the workflow node, which CAN see the
        // reserved counter, print 10 of 10 for the same agent. One condition, one figure.
        //
        // AgentDto deliberately carries no creditsReserved, so the committed total is resolved
        // by agent-service and read here.
        AgentDto committed = runnableAgent();
        committed.setCreditBudget(new BigDecimal("10"));
        committed.setCreditsConsumed(new BigDecimal("6"));
        committed.setBudgetCommitted(new BigDecimal("10"));
        committed.setBudgetBlocked(true);
        givenAgent(committed);

        assertThat(service.executeNow(agentSchedule()).message()).contains("10 of 10 credits");
    }

    @Test
    @DisplayName("falls back to the spend when the payload predates the committed figure")
    void theRefusalFallsBackToTheSpend() {
        // A rolling deploy puts a new orchestrator in front of an old agent-service. A missing
        // figure must not print an empty one.
        givenAgent(cappedOutAgent(null));

        assertThat(service.executeNow(agentSchedule()).message()).contains("3 of 1 credits");
    }

    @Test
    @DisplayName("an unattended refusal hands back the execution it did not perform")
    void anUnattendedRefusalDoesNotBurnTheExecutionCount() {
        // The defect this pins costs a schedule its life. The gate sits after the optimistic
        // advance, which has already incremented execution_count, and a cumulative cap never
        // lifts - so a schedule bounded at 10 executions was retired by ten refusals, having
        // run zero times, and the calendar greys it out for good.
        //
        // The fire time must NOT come back: re-arming it would have the daemon fire the same
        // schedule again within the minute, forever. So the restore is asked for the
        // ADVANCED fire time on both sides, which is what makes it counters-only.
        ScheduledExecutionDto advanced = agentSchedule();
        advanced.setNextExecutionAt(Instant.now().plusSeconds(7200));
        advanced.setExecutionCount(5);
        ScheduledExecutionDto before = agentSchedule();
        before.setExecutionCount(4);
        when(triggerClient.recordScheduleExecution(any())).thenReturn(advanced);
        when(agentClient.buildScheduledPrompt(any(), anyString(), any(), any())).thenReturn("Do the thing");
        when(agentClient.getAgent(any(), anyString(), any())).thenReturn(cappedOutAgent(null));

        service.executeSchedule(before);

        verify(triggerClient).restoreScheduleDispatch(
                eq(SCHEDULE_ID),
                eq(advanced.getNextExecutionAt()),   // previous fire time asked for = the advanced one
                any(), 
                eq(advanced.getNextExecutionAt()),   // ... so the CAS writes it back unchanged
                any(),
                eq(4),                               // the count the schedule had before the tick
                eq(5));
    }

    @Test
    @DisplayName("a periodic cap says when it lifts, a cumulative one says what to change")
    void theRefusalTellsTheReaderWhatHappensNext() {
        Instant lifts = Instant.now().plus(9, ChronoUnit.DAYS);
        givenAgent(cappedOutAgent(lifts));

        TriggerExecutionResult periodic = service.executeNow(agentSchedule());
        assertThat(periodic.message()).contains("runs again when the budget resets on");
        assertThat(periodic.message()).contains(
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                        .withZone(java.time.ZoneOffset.UTC).format(lifts));
        // A schedule that resumes on its own must NOT tell the reader to go and change
        // something: waiting is the answer, and they cannot discover that anywhere else.
        assertThat(periodic.message()).doesNotContain("Raise its credit budget");

        givenAgent(cappedOutAgent(null));
        TriggerExecutionResult forever = service.executeNow(agentSchedule());
        assertThat(forever.message()).contains("Raise its credit budget");
    }

    @Test
    @DisplayName("the failed manual run gives the schedule its slot back")
    void aRefusedManualRunDoesNotEatTheSlot() {
        // The gate sits after the optimistic advance, which is what lets an unattended fire
        // be refused once per cron tick instead of once per dispatcher tick. The price of
        // that placement is that a refused MANUAL run must be undone, or one click the user
        // never got anything for silently consumes their next occurrence.
        givenAgent(cappedOutAgent(null));

        service.executeNow(agentSchedule());

        verify(triggerClient, times(1)).restoreScheduleDispatch(
                any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an agent inside its budget still runs")
    void anAgentInsideItsBudgetStillRuns() {
        // The other half of the contract, and the one a gate gets wrong by being too eager:
        // a cap that is set and not reached must change nothing at all.
        AgentDto withRoom = runnableAgent();
        withRoom.setCreditBudget(new BigDecimal("100"));
        withRoom.setCreditsConsumed(new BigDecimal("3"));
        withRoom.setBudgetBlocked(false);
        givenAgent(withRoom);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isTrue();
        verify(conversationServiceClient, times(1)).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an agent with no verdict on its payload runs, rather than being stopped by silence")
    void anAbsentVerdictFailsOpen() {
        // budgetBlocked is null on a payload from a build that predates it, and during a
        // rolling deploy both builds are live at once. A gate that read null as "blocked"
        // would stop every scheduled agent in the fleet for the length of the rollout.
        AgentDto noVerdict = runnableAgent();
        noVerdict.setCreditBudget(new BigDecimal("1"));
        noVerdict.setCreditsConsumed(new BigDecimal("3"));
        noVerdict.setBudgetBlocked(null);
        givenAgent(noVerdict);

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("an uncapped agent is unaffected")
    void anUncappedAgentIsUnaffected() {
        givenAgent(runnableAgent());

        TriggerExecutionResult result = service.executeNow(agentSchedule());

        assertThat(result.success()).isTrue();
        verify(conversationServiceClient, times(1)).sendChatSync(anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), any());
    }
}
