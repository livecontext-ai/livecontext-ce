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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1: {@link AgentTaskService#reorderBoardTasks} stamps sequential within-column
 * board ranks in the provided order, validates board membership, and rejects
 * empty input.
 */
class AgentTaskServiceReorderTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        AgentTaskNoteRepository noteRepository = mock(AgentTaskNoteRepository.class);
        AgentTaskEventRepository eventRepository = mock(AgentTaskEventRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentExecutionRepository executionRepository = mock(AgentExecutionRepository.class);
        TaskBoardPublisher taskBoardPublisher = mock(TaskBoardPublisher.class);
        ConversationClient conversationClient = mock(ConversationClient.class);
        AgentTaskService self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task(UUID id) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId(TENANT);
        when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("assigns sequential ranks 0,1,2 in the requested order")
    void assignsSequentialRanks() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        AgentTaskEntity ta = task(a), tb = task(b), tc = task(c);

        service.reorderBoardTasks(TENANT, List.of(c, a, b)); // new order: c, a, b

        assertEquals(0.0, tc.getBoardRank());
        assertEquals(1.0, ta.getBoardRank());
        assertEquals(2.0, tb.getBoardRank());
    }

    @Test
    @DisplayName("rejects an empty or null id list")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> service.reorderBoardTasks(TENANT, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.reorderBoardTasks(TENANT, null));
    }

    @Test
    @DisplayName("rejects a task id that is not on the caller's board")
    void rejectsForeignTask() {
        UUID a = UUID.randomUUID(), ghost = UUID.randomUUID();
        task(a);
        when(taskRepository.findByIdAndTenantId(ghost, TENANT)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.reorderBoardTasks(TENANT, List.of(a, ghost)));
    }
}
