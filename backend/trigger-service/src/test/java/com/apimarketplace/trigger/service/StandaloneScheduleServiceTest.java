package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StandaloneScheduleService.updateWorkflowReference (adopt-on-pin)")
class StandaloneScheduleServiceTest {

    @Mock
    private ScheduledExecutionRepository scheduleRepository;
    @Mock
    private ScheduleCronParser cronParser;
    @Mock
    private PlanLimitHelper planLimitHelper;

    private StandaloneScheduleService service;

    private static final String TENANT = "1";
    private static final String ORG = "d040d0cd-52b2-4bb0-9b57-25597e35050f";
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final UUID OTHER_WORKFLOW = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StandaloneScheduleService(scheduleRepository, cronParser, planLimitHelper);
    }

    private ScheduledExecutionEntity row(UUID currentWorkflowId) {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity();
        e.setTenantId(TENANT);
        e.setOrganizationId(ORG);
        e.setCronExpression("* * * * *");
        e.setWorkflowId(currentWorkflowId);
        return e;
    }

    @Test
    @DisplayName("adopts an UNOWNED schedule: sets BOTH workflow_id and trigger_id")
    void adoptsUnownedSchedule() {
        ScheduledExecutionEntity entity = row(null); // workflow_id NULL = standalone/orphan
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG)).thenReturn(Optional.of(entity));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledExecutionDto dto = service.updateWorkflowReference(
                TENANT, ORG, SCHEDULE_ID, WORKFLOW, "trigger:scheduler", "z");

        // Both must be set - a schedule fires only when workflow_id AND trigger_id are present.
        assertThat(entity.getWorkflowId()).isEqualTo(WORKFLOW);
        assertThat(entity.getTriggerId()).isEqualTo("trigger:scheduler");
        assertThat(dto.getWorkflowId()).isEqualTo(WORKFLOW);
        assertThat(dto.getTriggerId()).isEqualTo("trigger:scheduler");
    }

    @Test
    @DisplayName("is idempotent when the schedule is already owned by the SAME workflow (re-sync no-op)")
    void idempotentWhenAlreadyOwnedBySameWorkflow() {
        ScheduledExecutionEntity entity = row(WORKFLOW); // already adopted by this workflow
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG)).thenReturn(Optional.of(entity));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateWorkflowReference(TENANT, ORG, SCHEDULE_ID, WORKFLOW, "trigger:scheduler", "z");

        assertThat(entity.getWorkflowId()).isEqualTo(WORKFLOW);
    }

    @Test
    @DisplayName("REJECTS rebinding a schedule already owned by a DIFFERENT workflow (F4 PUB-HIJACK guard)")
    void rejectsRebindToDifferentWorkflow() {
        ScheduledExecutionEntity entity = row(OTHER_WORKFLOW); // owned by another workflow
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateWorkflowReference(
                TENANT, ORG, SCHEDULE_ID, WORKFLOW, "trigger:scheduler", "z"))
                .isInstanceOf(WorkflowReferenceImmutableException.class);

        // The hijack attempt must NOT persist a workflow_id change.
        assertThat(entity.getWorkflowId()).isEqualTo(OTHER_WORKFLOW);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws when the schedule does not exist in this org")
    void throwsWhenNotFound() {
        when(scheduleRepository.findByIdAndOrganizationIdStrict(SCHEDULE_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateWorkflowReference(
                TENANT, ORG, SCHEDULE_ID, WORKFLOW, "trigger:scheduler", "z"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
