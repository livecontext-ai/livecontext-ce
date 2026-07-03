package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRecurrenceRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskRecurrenceService")
class AgentTaskRecurrenceServiceTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final UUID CALLING_AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_AGENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private AgentTaskRecurrenceRepository recurrenceRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;

    private AgentTaskRecurrenceService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskRecurrenceService(recurrenceRepository, taskRepository, agentRepository);
        service.setTaskBoardPublisher(taskBoardPublisher);
    }

    @Test
    @DisplayName("create stamps organization scope and computes the first fire time")
    void createStampsOrganizationScopeAndComputesFirstFireTime() {
        AgentEntity target = new AgentEntity();
        target.setId(TARGET_AGENT_ID);
        target.setTenantId(TENANT_ID);
        target.setOrganizationId(ORG_ID);
        when(agentRepository.findByIdAndOrganizationIdStrict(TARGET_AGENT_ID, ORG_ID))
                .thenReturn(Optional.of(target));
        when(recurrenceRepository.save(any(AgentTaskRecurrenceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateRecurrenceRequest request = new CreateRecurrenceRequest(
                "Nightly audit",
                "Review the active queue",
                "0 0 * * * *",
                "Europe/Paris",
                TARGET_AGENT_ID,
                "high",
                Map.of("scope", "daily"));

        AtomicReference<AgentTaskRecurrenceEntity> created = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG_ID, () ->
                created.set(service.create(TENANT_ID, CALLING_AGENT_ID, "user-1", request)));

        assertThat(created.get().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(created.get().getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(created.get().getCreatedByAgentId()).isEqualTo(CALLING_AGENT_ID);
        assertThat(created.get().getCreatedByUserId()).isEqualTo("user-1");
        assertThat(created.get().getTargetAgentId()).isEqualTo(TARGET_AGENT_ID);
        assertThat(created.get().getTitle()).isEqualTo("Nightly audit");
        assertThat(created.get().getInstructions()).isEqualTo("Review the active queue");
        assertThat(created.get().getTaskContext()).containsEntry("scope", "daily");
        assertThat(created.get().getPriority()).isEqualTo(AgentTaskEntity.PRIORITY_HIGH);
        assertThat(created.get().getCronExpression()).isEqualTo("0 0 * * * *");
        assertThat(created.get().getTimezone()).isEqualTo("Europe/Paris");
        assertThat(created.get().isEnabled()).isTrue();
        assertThat(created.get().getNextFireAt()).isNotNull();
    }

    @Test
    @DisplayName("create rejects target agents from another tenant before saving")
    void createRejectsTargetAgentFromAnotherTenantBeforeSaving() {
        AgentEntity target = new AgentEntity();
        target.setId(TARGET_AGENT_ID);
        target.setTenantId("other-tenant");
        when(agentRepository.findById(TARGET_AGENT_ID)).thenReturn(Optional.of(target));

        CreateRecurrenceRequest request = new CreateRecurrenceRequest(
                "Nightly audit",
                "Review the active queue",
                "0 0 * * * *",
                "UTC",
                TARGET_AGENT_ID,
                "normal",
                Map.of());

        assertThatThrownBy(() -> service.create(TENANT_ID, CALLING_AGENT_ID, "user-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different tenant");
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("create rejects target agents outside the active organization scope")
    void createRejectsTargetAgentOutsideActiveOrganizationScope() {
        when(agentRepository.findByIdAndOrganizationIdStrict(TARGET_AGENT_ID, ORG_ID))
                .thenReturn(Optional.empty());

        CreateRecurrenceRequest request = new CreateRecurrenceRequest(
                "Nightly audit",
                "Review the active queue",
                "0 0 * * * *",
                "UTC",
                TARGET_AGENT_ID,
                "normal",
                Map.of());

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG_ID,
                () -> service.create(TENANT_ID, CALLING_AGENT_ID, "user-1", request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target agent not found");
        verify(agentRepository, never()).findById(TARGET_AGENT_ID);
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("fireOnce claims the fire slot then copies the template into one task, skipping missed intervals")
    void fireOnceCopiesTemplateIntoOneTaskAndSkipsMissedIntervals() {
        AgentTaskRecurrenceEntity recurrence = recurrence();
        Instant staleNextFire = Instant.now().minusSeconds(3600);
        recurrence.setNextFireAt(staleNextFire);
        recurrence.setFireCount(7);
        Map<String, Object> context = new HashMap<>();
        context.put("workspace", "sales");
        recurrence.setTaskContext(context);

        when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(invocation -> {
            AgentTaskEntity task = invocation.getArgument(0);
            task.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
            return task;
        });
        when(recurrenceRepository.claimFireSlot(any(UUID.class), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(1);

        Optional<AgentTaskEntity> result = service.fireOnce(recurrence);

        assertThat(result).isPresent();
        AgentTaskEntity task = result.get();
        assertThat(task.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(task.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(task.getCreatedByAgentId()).isEqualTo(CALLING_AGENT_ID);
        assertThat(task.getCreatedByUserId()).isEqualTo("user-1");
        assertThat(task.getAssignedToAgentId()).isEqualTo(TARGET_AGENT_ID);
        assertThat(task.getRecurrenceId()).isEqualTo(recurrence.getId());
        assertThat(task.getTitle()).isEqualTo("Queued audit");
        assertThat(task.getInstructions()).isEqualTo("Do the recurring work");
        assertThat(task.getTaskContext()).containsEntry("workspace", "sales");
        assertThat(task.getTaskContext()).isNotSameAs(context);
        assertThat(task.getPriority()).isEqualTo(AgentTaskEntity.PRIORITY_URGENT);
        assertThat(task.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
        assertThat(task.getDepth()).isZero();

        // The tracker advances via the CAS claim, not an entity save: expected =
        // the stale next_fire_at read from findDue, next = strictly in the future
        // (single-step catch-up past all missed windows).
        ArgumentCaptor<Instant> expectedCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nextCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(recurrenceRepository).claimFireSlot(
                org.mockito.ArgumentMatchers.eq(recurrence.getId()),
                expectedCaptor.capture(), nextCaptor.capture(), any(Instant.class));
        assertThat(expectedCaptor.getValue()).isEqualTo(staleNextFire);
        assertThat(nextCaptor.getValue()).isAfter(Instant.now().minusSeconds(1));
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("fireOnce publishes task_created so open boards see the cron-spawned task live")
    void fireOncePublishesTaskCreated() {
        // Regression: fireOnce saved the task directly with no TaskBoardPublisher
        // wiring - every other creation path publishes via assignTask, so
        // cron-spawned tasks stayed invisible on an open board until the slow poll.
        AgentTaskRecurrenceEntity recurrence = recurrence();
        when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recurrenceRepository.claimFireSlot(any(UUID.class), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(1);

        Optional<AgentTaskEntity> result = service.fireOnce(recurrence);

        assertThat(result).isPresent();
        verify(taskBoardPublisher).publishTaskCreated(TENANT_ID, result.get());
    }

    @Test
    @DisplayName("fireOnce publishes nothing when the fire slot claim loses")
    void fireOncePublishesNothingWhenClaimLoses() {
        AgentTaskRecurrenceEntity recurrence = recurrence();
        when(recurrenceRepository.claimFireSlot(any(UUID.class), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(0);

        service.fireOnce(recurrence);

        org.mockito.Mockito.verifyNoInteractions(taskBoardPublisher);
    }

    @Test
    @DisplayName("fireOnce spawns nothing when the fire slot was already claimed by a concurrent tick")
    void fireOnceSpawnsNothingWhenFireSlotAlreadyClaimed() {
        // Regression: ShedLock lockAtMostFor expiry (or clock skew) lets a second
        // instance re-fetch the same due row. Pre-fix, fireOnce created the task
        // unconditionally and BOTH instances spawned one. Post-fix, the losing
        // CAS claim (0 rows updated) must spawn nothing.
        AgentTaskRecurrenceEntity recurrence = recurrence();
        when(recurrenceRepository.claimFireSlot(any(UUID.class), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(0);

        Optional<AgentTaskEntity> result = service.fireOnce(recurrence);

        assertThat(result).isEmpty();
        verify(taskRepository, never()).save(any());
        verify(recurrenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("fireOnce skips a row with no next_fire_at instead of firing unconditionally")
    void fireOnceSkipsRowWithoutNextFireAt() {
        AgentTaskRecurrenceEntity recurrence = recurrence();
        recurrence.setNextFireAt(null);

        Optional<AgentTaskEntity> result = service.fireOnce(recurrence);

        assertThat(result).isEmpty();
        verify(taskRepository, never()).save(any());
        verify(recurrenceRepository, never()).claimFireSlot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("fireOnce disables invalid recurrence definitions without creating a task")
    void fireOnceDisablesInvalidRecurrenceDefinitionWithoutCreatingTask() {
        AgentTaskRecurrenceEntity recurrence = recurrence();
        recurrence.setCronExpression("not a cron");

        Optional<AgentTaskEntity> result = service.fireOnce(recurrence);

        assertThat(result).isEmpty();
        assertThat(recurrence.isEnabled()).isFalse();
        verify(recurrenceRepository).save(recurrence);
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("list filters strict organization rows for supported scopes")
    void listFiltersStrictOrganizationRowsForSupportedScopes() {
        AgentTaskRecurrenceEntity createdByCaller = recurrence();
        createdByCaller.setId(UUID.randomUUID());
        createdByCaller.setCreatedByAgentId(CALLING_AGENT_ID);
        createdByCaller.setTargetAgentId(null);
        AgentTaskRecurrenceEntity targetingCaller = recurrence();
        targetingCaller.setId(UUID.randomUUID());
        targetingCaller.setCreatedByAgentId(UUID.randomUUID());
        targetingCaller.setTargetAgentId(CALLING_AGENT_ID);
        AgentTaskRecurrenceEntity unrelated = recurrence();
        unrelated.setId(UUID.randomUUID());
        unrelated.setCreatedByAgentId(UUID.randomUUID());
        unrelated.setTargetAgentId(UUID.randomUUID());
        when(recurrenceRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(List.of(createdByCaller, targetingCaller, unrelated));

        assertThat(service.list(TENANT_ID, ORG_ID, CALLING_AGENT_ID, "created_by_me"))
                .containsExactly(createdByCaller);
        assertThat(service.list(TENANT_ID, ORG_ID, CALLING_AGENT_ID, "targeting_me"))
                .containsExactly(targetingCaller);
        assertThat(service.list(TENANT_ID, ORG_ID, CALLING_AGENT_ID, "all_in_tenant"))
                .containsExactly(createdByCaller, targetingCaller, unrelated);
        assertThatThrownBy(() -> service.list(TENANT_ID, ORG_ID, CALLING_AGENT_ID, "bad_scope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid scope");
    }

    private AgentTaskRecurrenceEntity recurrence() {
        AgentTaskRecurrenceEntity recurrence = new AgentTaskRecurrenceEntity();
        recurrence.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        recurrence.setTenantId(TENANT_ID);
        recurrence.setOrganizationId(ORG_ID);
        recurrence.setCreatedByAgentId(CALLING_AGENT_ID);
        recurrence.setCreatedByUserId("user-1");
        recurrence.setTargetAgentId(TARGET_AGENT_ID);
        recurrence.setTitle("Queued audit");
        recurrence.setInstructions("Do the recurring work");
        recurrence.setPriority(AgentTaskEntity.PRIORITY_URGENT);
        recurrence.setCronExpression("0 * * * * *");
        recurrence.setTimezone("UTC");
        recurrence.setEnabled(true);
        recurrence.setNextFireAt(Instant.now().minusSeconds(60));
        return recurrence;
    }
}
