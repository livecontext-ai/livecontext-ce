package com.apimarketplace.orchestrator.schedule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
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

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression: {@code ScheduleExecutorService.prepareScheduleExecution} re-WARNed
 * the NOT_PINNED outcome on every cron tick (~11/min × 6 unpinned schedules in
 * prod = 3982/day, 11% of orchestrator log volume - audit 2026-05-13). The
 * outcome is already surfaced via the dispatch verdict Prometheus counter
 * ({@code trigger_dispatch_total{verdict=REFUSE_NOT_PINNED}}) and via the
 * resolver's own log line (also demoted in the same patch). Post-fix: DEBUG.
 *
 * <p>Companion to {@link com.apimarketplace.orchestrator.trigger.ProductionRunResolverNotPinnedLogLevelTest}
 * - the resolver and the scheduler each log independently; both must be silenced.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleExecutorService - NOT_PINNED outcome logs at DEBUG, not WARN")
class ScheduleExecutorServiceNotPinnedLogLevelTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:cron";

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private com.apimarketplace.agent.client.AgentClient agentClient;
    @Mock private com.apimarketplace.conversation.client.ConversationClient conversationServiceClient;

    private ScheduleExecutorService service;
    private Logger scheduleLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        service = new ScheduleExecutorService(
            triggerClient, workflowRepository, runRepository, triggerService, productionRunResolver,
            agentClient, conversationServiceClient, null);
        service.setSpreadDispatcherForTesting((task, delayMs) -> task.run());
        // Disable heap-pressure backpressure for this test fixture.
        java.lang.reflect.Field thresholdField = ScheduleExecutorService.class
            .getDeclaredField("heapPressureThreshold");
        thresholdField.setAccessible(true);
        thresholdField.setDouble(service, 2.0);

        when(productionRunResolver.resolve(any(), any())).thenReturn(
            new ProductionRunResolver.Resolution(
                Optional.empty(),
                ProductionRunResolver.Outcome.NOT_PINNED,
                "daily-email-digest"));

        scheduleLogger = (Logger) LoggerFactory.getLogger(ScheduleExecutorService.class);
        previousLevel = scheduleLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        scheduleLogger.addAppender(appender);
        scheduleLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        scheduleLogger.detachAppender(appender);
        scheduleLogger.setLevel(previousLevel);
    }

    private ScheduledExecutionDto schedule() {
        ScheduledExecutionDto s = new ScheduledExecutionDto();
        s.setId(SCHEDULE_ID);
        s.setWorkflowId(WORKFLOW_ID);
        s.setTriggerId(TRIGGER_ID);
        s.setTenantId("tenant-1");
        s.setCronExpression("0 * * * *");
        s.setTimezone("UTC");
        s.setEnabled(true);
        return s;
    }

    private ScheduleExecutorService.ScheduleRunInfo invokePrepare(ScheduledExecutionDto s) throws Exception {
        Method m = ScheduleExecutorService.class.getDeclaredMethod(
            "prepareScheduleExecution", ScheduledExecutionDto.class);
        m.setAccessible(true);
        return (ScheduleExecutorService.ScheduleRunInfo) m.invoke(service, s);
    }

    @Test
    @DisplayName("Tick on an unpinned workflow logs DEBUG, NOT WARN - closes 11% log-noise bug")
    void unpinnedTickLogsDebugNotWarn() throws Exception {
        ScheduleExecutorService.ScheduleRunInfo info = invokePrepare(schedule());

        assertThat(info)
            .as("NOT_PINNED still skips the tick (functional behaviour unchanged)")
            .isNull();

        boolean warnFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("has no pinned version"));
        assertThat(warnFired)
            .as("Schedule must NOT WARN on every tick for unpinned workflows")
            .isFalse();

        boolean debugFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.DEBUG
            && e.getFormattedMessage().contains("has no pinned version"));
        assertThat(debugFired)
            .as("DEBUG kept so ad-hoc tracing of unpinned-schedule firings remains possible")
            .isTrue();
    }
}
