package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskRecurrenceScheduler")
class AgentTaskRecurrenceSchedulerTest {

    @Mock private AgentTaskRecurrenceService recurrenceService;

    @Test
    @DisplayName("tick binds each recurrence organization and continues after a fire failure")
    void tickBindsEachRecurrenceOrganizationAndContinuesAfterFireFailure() {
        AgentTaskRecurrenceEntity first = recurrence("org-1");
        AgentTaskRecurrenceEntity second = recurrence("org-2");
        when(recurrenceService.findDue(any(Instant.class))).thenReturn(List.of(first, second));

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            AgentTaskRecurrenceEntity recurrence = invocation.getArgument(0);
            assertThat(TenantResolver.currentRequestOrganizationId())
                    .isEqualTo(recurrence.getOrganizationId());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return Optional.of(new AgentTaskEntity());
        }).when(recurrenceService).fireOnce(any(AgentTaskRecurrenceEntity.class));

        new AgentTaskRecurrenceScheduler(recurrenceService).tick();

        assertThat(calls).hasValue(2);
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
        verify(recurrenceService).fireOnce(first);
        verify(recurrenceService).fireOnce(second);
    }

    @Test
    @DisplayName("tick stops cleanly when due recurrence lookup fails")
    void tickStopsCleanlyWhenDueLookupFails() {
        when(recurrenceService.findDue(any(Instant.class))).thenThrow(new IllegalStateException("db down"));

        new AgentTaskRecurrenceScheduler(recurrenceService).tick();

        verify(recurrenceService).findDue(any(Instant.class));
        verify(recurrenceService, org.mockito.Mockito.never()).fireOnce(any());
    }

    private static AgentTaskRecurrenceEntity recurrence(String organizationId) {
        AgentTaskRecurrenceEntity recurrence = new AgentTaskRecurrenceEntity();
        recurrence.setId(UUID.randomUUID());
        recurrence.setTenantId("tenant-1");
        recurrence.setOrganizationId(organizationId);
        recurrence.setTitle("Scheduled task");
        recurrence.setInstructions("Run it");
        recurrence.setCronExpression("0 * * * * *");
        recurrence.setTimezone("UTC");
        recurrence.setEnabled(true);
        recurrence.setNextFireAt(Instant.now().minusSeconds(60));
        return recurrence;
    }
}
