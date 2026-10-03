package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: AGENT_TASK notifications stored before task titles were withheld may carry a
 * RESTRICTED task's title (which may quote an email). The scrub rewrites those to the withheld
 * form, asking agent-service which tasks are RESTRICTED, never guessing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskNotificationNameScrubber (LC-066)")
class AgentTaskNotificationNameScrubberTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private AgentClient agentClient;

    private AgentTaskNotificationNameScrubber scrubber;

    private final UUID restrictedTask = UUID.randomUUID();
    private final UUID normalTask = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scrubber = new AgentTaskNotificationNameScrubber(notificationRepository, agentClient, true);
    }

    @Test
    @DisplayName("withholds the name on the RESTRICTED task's notifications only; NORMAL tasks keep theirs")
    void withholdsRestrictedTaskNamesOnly() {
        when(notificationRepository.findNamedAgentTaskNotifications(0L, AgentTaskNotificationNameScrubber.BATCH_SIZE))
                .thenReturn(List.of(new Object[] {10L, restrictedTask}, new Object[] {11L, normalTask},
                        new Object[] {12L, restrictedTask.toString()}));
        when(agentClient.findRestrictedTaskIds(Set.of(restrictedTask, normalTask))).thenReturn(Set.of(restrictedTask));
        when(notificationRepository.withholdAgentTaskNames(List.of(restrictedTask))).thenReturn(2);

        AgentTaskNotificationNameScrubber.Report report = scrubber.scrub();

        assertThat(report.notificationsChecked()).isEqualTo(3);
        assertThat(report.notificationsScrubbed()).isEqualTo(2);
        assertThat(report.batchesFailed()).isZero();
        verify(notificationRepository).withholdAgentTaskNames(List.of(restrictedTask));
    }

    @Test
    @DisplayName("agent-service cannot answer: nothing rewritten, nothing assumed clean, the batch is left for the next run")
    void lookupFailureWritesNothing() {
        when(notificationRepository.findNamedAgentTaskNotifications(0L, AgentTaskNotificationNameScrubber.BATCH_SIZE))
                .thenReturn(List.<Object[]>of(new Object[] {10L, restrictedTask}));
        when(agentClient.findRestrictedTaskIds(any())).thenThrow(new IllegalStateException("agent-service down"));

        AgentTaskNotificationNameScrubber.Report report = scrubber.scrub();

        assertThat(report.batchesFailed()).isEqualTo(1);
        assertThat(report.notificationsScrubbed()).isZero();
        verify(notificationRepository, never()).withholdAgentTaskNames(any());
    }

    @Test
    @DisplayName("pages by id: a full batch is followed by the next one, from the last id seen")
    @SuppressWarnings("unchecked")
    void pagesThroughBatches() {
        int size = AgentTaskNotificationNameScrubber.BATCH_SIZE;
        List<Object[]> full = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            full.add(new Object[] {(long) i, normalTask});
        }
        when(notificationRepository.findNamedAgentTaskNotifications(0L, size)).thenReturn(full);
        when(notificationRepository.findNamedAgentTaskNotifications((long) size, size))
                .thenReturn(List.<Object[]>of(new Object[] {(long) size + 5, restrictedTask}));
        when(agentClient.findRestrictedTaskIds(Set.of(normalTask))).thenReturn(Set.of());
        when(agentClient.findRestrictedTaskIds(Set.of(restrictedTask))).thenReturn(Set.of(restrictedTask));
        when(notificationRepository.withholdAgentTaskNames(List.of(restrictedTask))).thenReturn(1);

        AgentTaskNotificationNameScrubber.Report report = scrubber.scrub();

        assertThat(report.notificationsChecked()).isEqualTo(size + 1);
        assertThat(report.notificationsScrubbed()).isEqualTo(1);
        // One call per distinct task id set: the 200 rows of one task ask about it once.
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(agentClient, org.mockito.Mockito.times(2)).findRestrictedTaskIds(asked.capture());
        assertThat(asked.getAllValues().get(0)).containsExactly(normalTask);
    }

    @Test
    @DisplayName("disabled: touches nothing")
    void disabled() {
        new AgentTaskNotificationNameScrubber(notificationRepository, agentClient, false).scrub();
        verifyNoInteractions(notificationRepository, agentClient);
    }

    @Test
    @DisplayName("nothing named left (a re-run after a scrub): no call to agent-service")
    void idempotentReRun() {
        when(notificationRepository.findNamedAgentTaskNotifications(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(scrubber.scrub().notificationsChecked()).isZero();
        verifyNoInteractions(agentClient);
    }
}
