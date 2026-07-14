package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleController.toggleSchedule")
class ScheduleControllerToggleTest {

    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private TenantResolver tenantResolver;
    @Mock private com.apimarketplace.trigger.service.PlanLimitHelper planLimitHelper;
    @Mock private com.apimarketplace.trigger.service.TriggerLifecycleManager triggerLifecycleManager;

    private ScheduleController controller;

    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final String TRIGGER = "trigger:scheduler";

    @BeforeEach
    void setUp() {
        controller = new ScheduleController(scheduleRepository, cronParser, tenantResolver,
                planLimitHelper, triggerLifecycleManager);
    }

    private ScheduledExecutionEntity enabledSchedule() {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity();
        e.setWorkflowId(WORKFLOW);
        e.setTriggerId(TRIGGER);
        e.setCronExpression("0 9 * * *");
        e.setTimezone("UTC");
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("body {\"enabled\": null} does NOT NPE (500) - toggles the current state instead")
    void explicitNullEnabledTogglesInsteadOfNpe() {
        ScheduledExecutionEntity schedule = enabledSchedule(); // currently enabled
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(WORKFLOW, TRIGGER))
                .thenReturn(List.of(schedule));
        lenient().when(cronParser.getNextExecution(any(), any())).thenReturn(Instant.now());

        // Jackson stores a present-but-null value; getOrDefault returns null (default is for ABSENT keys),
        // and the pre-fix unboxing to primitive boolean threw NPE -> 500.
        Map<String, Boolean> body = new HashMap<>();
        body.put("enabled", null);

        // Must not throw (pre-fix: NullPointerException -> HTTP 500).
        ResponseEntity<Map<String, Object>> result = controller.toggleSchedule(WORKFLOW, TRIGGER, body);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // enabled was true, null -> toggle to false.
        assertThat(result.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("explicit enabled=false disables the schedule")
    void explicitFalseDisables() {
        ScheduledExecutionEntity schedule = enabledSchedule();
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(WORKFLOW, TRIGGER))
                .thenReturn(List.of(schedule));

        Map<String, Boolean> body = new HashMap<>();
        body.put("enabled", false);

        ResponseEntity<Map<String, Object>> result = controller.toggleSchedule(WORKFLOW, TRIGGER, body);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("enabled")).isEqualTo(false);
    }
}
