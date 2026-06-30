package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Guard: the agent's schedule-trigger creation must reject a cron whose {@code *&#47;N} step
 * exceeds its field range (the "silent collapse" footgun), matching the canonical trigger-service
 * ScheduleCronParser. Regression for the published "Gmail Auto-Labeler" app whose schedule was
 * {@code *&#47;120 * * * *} ("every 120 minutes" in the minute field, max 59): the old basic 5-field
 * check accepted it, so the schedule silently never fired and the inspector flagged "Invalid cron".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Schedule cron step-range validation")
class TriggerCreatorScheduleCronTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private DataSourceClient dataSourceService;
    @Mock private SmartDefaultsEngine smartDefaultsEngine;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceService, smartDefaultsEngine, responseOptimizer, triggerClient);
    }

    private WorkflowBuilderSession session() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> scheduleParams(String cron) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Daily Sync");
        p.put("trigger_type", "schedule");
        p.put("cron", cron);
        return p;
    }

    /** The success path applies defaults + builds the response; the reject path returns before either. */
    private void stubDefaults() {
        when(smartDefaultsEngine.applyTriggerDefaults(any())).thenAnswer(inv -> inv.getArgument(0));
        when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
    }

    @Test
    @DisplayName("rejects */120 in the minute field and points at the hour-field fix")
    void rejectsMinuteStepBeyondRange() {
        ToolExecutionResult result = creator.executeAddTrigger(session(), scheduleParams("*/120 * * * *"), "tenant-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("minute");
        assertThat(result.error()).contains("0 */2 * * *"); // the every-2-hours guidance
    }

    @Test
    @DisplayName("rejects a zero step */0")
    void rejectsZeroStep() {
        ToolExecutionResult result = creator.executeAddTrigger(session(), scheduleParams("*/0 * * * *"), "tenant-1");
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("rejects an out-of-range hour step */30 (hour max 23)")
    void rejectsHourStepBeyondRange() {
        ToolExecutionResult result = creator.executeAddTrigger(session(), scheduleParams("0 */30 * * *"), "tenant-1");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("hour");
    }

    @Test
    @DisplayName("accepts the every-2-hours preset 0 */2 * * *")
    void acceptsEveryTwoHours() {
        stubDefaults();
        ToolExecutionResult result = creator.executeAddTrigger(session(), scheduleParams("0 */2 * * *"), "tenant-1");
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("accepts an in-range minute step */30")
    void acceptsInRangeMinuteStep() {
        stubDefaults();
        ToolExecutionResult result = creator.executeAddTrigger(session(), scheduleParams("*/30 * * * *"), "tenant-1");
        assertThat(result.success()).isTrue();
    }
}
