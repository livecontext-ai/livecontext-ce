package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F12: {@link AgentTaskService#setTaskEstimate} - set / clear / leave-unchanged / reject negative. */
class AgentTaskServiceEstimateTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        AgentTaskService self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, mock(AgentTaskNoteRepository.class),
                mock(AgentTaskEventRepository.class), mock(AgentRepository.class),
                mock(AgentExecutionRepository.class), mock(TaskBoardPublisher.class),
                mock(ConversationClient.class), self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("sets estimate and logged time")
    void setsBoth() {
        task();
        AgentTaskEntity r = service.setTaskEstimate(TENANT, taskId, null, TENANT, 120, false, 30, false);
        assertEquals(120, r.getEstimateMinutes());
        assertEquals(30, r.getTimeSpentMinutes());
    }

    @Test
    @DisplayName("null value without clear leaves the field unchanged")
    void leavesUnchanged() {
        AgentTaskEntity t = task();
        t.setEstimateMinutes(90);
        AgentTaskEntity r = service.setTaskEstimate(TENANT, taskId, null, TENANT, null, false, 45, false);
        assertEquals(90, r.getEstimateMinutes(), "estimate untouched when null + no clear");
        assertEquals(45, r.getTimeSpentMinutes());
    }

    @Test
    @DisplayName("clear flag resets the field to null")
    void clears() {
        AgentTaskEntity t = task();
        t.setEstimateMinutes(90);
        t.setTimeSpentMinutes(15);
        AgentTaskEntity r = service.setTaskEstimate(TENANT, taskId, null, TENANT, null, true, null, true);
        assertNull(r.getEstimateMinutes());
        assertNull(r.getTimeSpentMinutes());
    }

    @Test
    @DisplayName("rejects a negative estimate or logged time")
    void rejectsNegative() {
        task();
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskEstimate(TENANT, taskId, null, TENANT, -1, false, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskEstimate(TENANT, taskId, null, TENANT, null, false, -5, false));
    }

    @Test
    @DisplayName("accepts zero for both estimate and logged time (0 is valid, not negative)")
    void acceptsZero() {
        task();
        AgentTaskEntity r = service.setTaskEstimate(TENANT, taskId, null, TENANT, 0, false, 0, false);
        assertEquals(0, r.getEstimateMinutes());
        assertEquals(0, r.getTimeSpentMinutes());
    }

    @Test
    @DisplayName("clear flag wins even when a value is also supplied (clear beats value)")
    void clearWinsOverValue() {
        AgentTaskEntity t = task();
        t.setEstimateMinutes(90);
        t.setTimeSpentMinutes(20);
        // Both a non-null value AND clear=true - the clear must win (null), not the value.
        AgentTaskEntity r = service.setTaskEstimate(TENANT, taskId, null, TENANT, 120, true, 60, true);
        assertNull(r.getEstimateMinutes());
        assertNull(r.getTimeSpentMinutes());
    }

    @Test
    @DisplayName("rejects an unknown task id")
    void rejectsUnknownTask() {
        UUID ghost = UUID.randomUUID();
        when(taskRepository.findByIdAndTenantId(ghost, TENANT)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskEstimate(TENANT, ghost, null, TENANT, 60, false, null, false));
    }
}
