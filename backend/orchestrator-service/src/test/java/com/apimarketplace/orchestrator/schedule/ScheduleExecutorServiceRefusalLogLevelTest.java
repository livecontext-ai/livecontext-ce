package com.apimarketplace.orchestrator.schedule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The louder half of the incident: a SCHEDULE keeps its cadence when a run is refused.
 *
 * <p>The webhook relay was fixed first because it is what showed up in the paste, but a schedule
 * fires on its own forever, so a workspace out of credits writes one line per fire until someone
 * tops it up or pauses the schedule. Same relayed message, one call frame up.
 */
@DisplayName("ScheduleExecutorService - a refused run is not an error")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleExecutorServiceRefusalLogLevelTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String RUN_ID = "run_<id>";

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;

    private ScheduleExecutorService service;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new ScheduleExecutorService(
            triggerClient, workflowRepository, runRepository, triggerService, productionRunResolver,
            mock(com.apimarketplace.agent.client.AgentClient.class),
            mock(com.apimarketplace.conversation.client.ConversationClient.class), null);

        serviceLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(ScheduleExecutorService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    private void logOutcome(String message) {
        TriggerExecutionResult failure = TriggerExecutionResult.failure(
            RUN_ID, "trigger:schedule", TriggerType.SCHEDULE, message);
        ReflectionTestUtils.invokeMethod(service, "logExecutionResult", WORKFLOW_ID, RUN_ID, failure);
    }

    @Test
    @DisplayName("out of credits is a refusal: WARN, and no ERROR line")
    void creditExhaustionIsWarn() {
        logOutcome(CreditExhaustion.MESSAGE);

        assertThat(appender.list)
            .as("a schedule fires forever, so this is one ERROR per fire until someone tops up")
            .noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused");
            assertThat(e.getFormattedMessage()).contains(CreditExhaustion.MESSAGE);
        });
    }

    @Test
    @DisplayName("a genuine failure keeps ERROR - the schedule log must not go uniformly quiet")
    void platformFailureStaysError() {
        logOutcome("NullPointerException in core:transform");

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("execution failed");
        });
    }

    @Test
    @DisplayName("an AGENT schedule out of credits is a refusal too - the branch 240 lines up")
    void agentScheduleCreditExhaustionIsWarn() {
        // Found by the third audit: the workflow branch was moved to WARN and the agent branch,
        // in this same file, was left at ERROR. An agent schedule fires on its own just the same.
        ReflectionTestUtils.invokeMethod(service, "logAgentOutcome",
            42L, 7L, ChatCreditRefusal.MESSAGE);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused");
        });
    }

    @Test
    @DisplayName("the WORKFLOW wording still reaches the agent branch - both vocabularies, not a swap")
    void agentScheduleAlsoAcceptsTheWorkflowWording() {
        // The previous version of this test fed ONLY CreditExhaustion.MESSAGE here, and that
        // is why the fix shipped dead: the agent branch cannot produce that sentence, so the
        // assertion certified the author's assumption instead of the behaviour. Keep it, but
        // as the second case rather than the only one.
        ReflectionTestUtils.invokeMethod(service, "logAgentOutcome",
            42L, 7L, CreditExhaustion.MESSAGE);

        // Asserting only "no ERROR" would pass on an EMPTY method body, which is the same
        // shape of hollow test this whole change exists to undo. Assert the line it must write.
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused");
        });
    }

    @Test
    @DisplayName("the string PRODUCTION actually relays is recognised, wrapper and all")
    void agentScheduleRecognisesTheRelayedTransportMessage() {
        // Verbatim from prod on 2026-09-17, orchestrator pod j7prq. Before the fix this exact
        // string reached logAgentOutcome, matched no vocabulary, and was logged at ERROR every
        // 30 minutes. ConversationClient now unwraps the body, so the plain sentence is what
        // arrives - but the wrapped form must stay recognised, because any caller may prefix it
        // and because an older orchestrator can be relayed to by a newer client during a rollout.
        String relayed = "402  on POST request for \"http://livecontext-livecontext-conversation:8087"
            + "/api/internal/chat/sync\": \"{\"conversationId\":\"3dddb2d1-5c16-4b57-b5b1-534c20d739d7\","
            + "\"error\":\"Insufficient credits\",\"success\":false}\"";

        ReflectionTestUtils.invokeMethod(service, "logAgentOutcome", 42L, 7L, relayed);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused");
        });
    }

    @Test
    @DisplayName("an AGENT schedule failing for a real fault keeps ERROR")
    void agentSchedulePlatformFailureStaysError() {
        ReflectionTestUtils.invokeMethod(service, "logAgentOutcome",
            42L, 7L, "NullPointerException in the agent loop");

        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("a successful run is still INFO, untouched")
    void successIsUntouched() {
        ReflectionTestUtils.invokeMethod(service, "logExecutionResult", WORKFLOW_ID, RUN_ID,
            TriggerExecutionResult.success(RUN_ID, "trigger:schedule", TriggerType.SCHEDULE,
                "ok", Set.of(), 1));

        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }
}
