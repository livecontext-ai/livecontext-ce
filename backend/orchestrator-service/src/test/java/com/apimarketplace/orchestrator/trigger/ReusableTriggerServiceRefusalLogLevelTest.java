package com.apimarketplace.orchestrator.trigger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The site that produced the incident this change exists for.
 *
 * <p>On 2026-09-16 one workspace sat at 0.55 credits since the 13th. Its webhook caller kept
 * retrying, the credit gate kept refusing correctly, and this relay logged every refusal at
 * ERROR: eight lines in three minutes, for a product behaving exactly as designed. The run must
 * still fail and still report the same message; only the level moves.
 */
@DisplayName("ReusableTriggerService - a refused run is not an error")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReusableTriggerServiceRefusalLogLevelTest {

    private static final String RUN_ID = "run_<id>";
    private static final String TRIGGER_ID = "trigger:webhook";
    private static final int EPOCH = 7;

    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private WorkflowRunEntity run;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private ReusableTriggerService service;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new ReusableTriggerService(
                mock(WorkflowRunRepository.class),
                mock(WorkflowRepository.class),
                mock(WorkflowPlanVersionRepository.class),
                mock(TriggerEpochManager.class),
                mock(WorkflowStreamingService.class),
                mock(WorkflowExecutionService.class),
                mock(com.apimarketplace.orchestrator.services.TriggerResolverService.class),
                mock(StateSnapshotService.class),
                mock(EpochConcurrencyLimiter.class),
                mock(ExecutionQueue.class),
                mock(com.apimarketplace.orchestrator.services.credit.CreditBudgetService.class));
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);

        lenient().when(execution.getWebhookTriggerPayload(anyString())).thenReturn(null);

        serviceLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ReusableTriggerService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    /** Drive the relay with a V2 result that FAILED carrying {@code error}. */
    private TriggerExecutionResult relayFailureOf(String error) {
        StepByStepExecutionResult failed = new StepByStepExecutionResult(
                null,
                NodeExecutionResult.failure(TRIGGER_ID, error),
                java.util.Set.of(),
                false);
        when(v2StepByStepService.executeNode(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(failed);

        return (TriggerExecutionResult) ReflectionTestUtils.invokeMethod(
                service, "executeWithV2Service",
                run, execution, plan, TRIGGER_ID, TriggerType.WEBHOOK, RUN_ID, false, EPOCH, null);
    }

    @Test
    @DisplayName("out of credits is logged as a refusal, and no ERROR line is written")
    void creditExhaustionIsWarn() {
        TriggerExecutionResult result = relayFailureOf(CreditExhaustion.MESSAGE);

        // The refusal itself is unchanged: the caller still gets a failure carrying the message
        // the customer needs to read.
        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains(CreditExhaustion.MESSAGE);

        assertThat(appender.list)
                .as("a workspace out of credits must not fill the error dashboard")
                .noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("trigger refused");
            assertThat(e.getFormattedMessage()).contains(RUN_ID);
        });
    }

    @Test
    @DisplayName("a genuine failure keeps ERROR - the level must not become uniformly quiet")
    void platformFailureStaysError() {
        // The mirror image, asserted so the fix cannot be "log everything at WARN": a node that
        // blew up on a null pointer is still ours to investigate.
        relayFailureOf("NullPointerException in core:transform");

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("trigger execution failed");
        });
    }

    @Test
    @DisplayName("the message still reaches the caller unchanged, whichever level was used")
    void theMessageIsRelayedVerbatim() {
        assertThat(relayFailureOf("NullPointerException in core:transform").message())
                .isEqualTo("NullPointerException in core:transform");
        assertThat(relayFailureOf(CreditExhaustion.MESSAGE).message())
                .isEqualTo(CreditExhaustion.MESSAGE);
    }
}
