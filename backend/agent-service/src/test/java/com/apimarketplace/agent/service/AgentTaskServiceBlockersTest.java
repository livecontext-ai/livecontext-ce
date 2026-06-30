package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F9: {@link AgentTaskService#setTaskBlockers} - set / clear / self-block / unknown / reciprocal-cycle. */
class AgentTaskServiceBlockersTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private AgentTaskService self;
    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, mock(AgentTaskNoteRepository.class),
                mock(AgentTaskEventRepository.class), mock(AgentRepository.class),
                mock(AgentExecutionRepository.class), mock(TaskBoardPublisher.class),
                mock(ConversationClient.class), self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity registerTask(UUID id) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setTenantId(TENANT);
        when(taskRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("stores valid blocker ids, deduped")
    void storesBlockers() {
        registerTask(taskId);
        UUID b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
        registerTask(b1);
        registerTask(b2);

        AgentTaskEntity r = service.setTaskBlockers(TENANT, taskId, null, TENANT,
                new ArrayList<>(List.of(b1.toString(), b2.toString(), b1.toString())));
        assertEquals(List.of(b1.toString(), b2.toString()), r.getBlockedByIds());
    }

    @Test
    @DisplayName("rejects a task blocking itself")
    void rejectsSelfBlock() {
        registerTask(taskId);
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of(taskId.toString())));
    }

    @Test
    @DisplayName("rejects an unknown blocker task")
    void rejectsUnknownBlocker() {
        registerTask(taskId);
        UUID ghost = UUID.randomUUID();
        when(taskRepository.findByIdAndTenantId(ghost, TENANT)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of(ghost.toString())));
    }

    @Test
    @DisplayName("rejects a direct reciprocal cycle (blocker already blocked by this task)")
    void rejectsReciprocalCycle() {
        registerTask(taskId);
        UUID b = UUID.randomUUID();
        AgentTaskEntity blocker = registerTask(b);
        blocker.setBlockedByIds(new ArrayList<>(List.of(taskId.toString())));  // b already blocked by us

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of(b.toString())));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    @DisplayName("an empty list clears blockers")
    void clearsBlockers() {
        AgentTaskEntity t = registerTask(taskId);
        t.setBlockedByIds(new ArrayList<>(List.of(UUID.randomUUID().toString())));
        AgentTaskEntity r = service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of());
        assertTrue(r.getBlockedByIds().isEmpty());
    }

    // ------------------------------------------------ added coverage (gaps) ----

    @Test
    @DisplayName("rejects a malformed (non-UUID) blocker id with a readable message")
    void rejectsMalformedUuid() {
        registerTask(taskId);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of("not-a-uuid")));
        assertTrue(ex.getMessage().contains("invalid task id"));
    }

    @Test
    @DisplayName("silently skips a null entry in the blocker list")
    void skipsNullEntry() {
        registerTask(taskId);
        UUID b = UUID.randomUUID();
        registerTask(b);
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(b.toString());
        AgentTaskEntity r = service.setTaskBlockers(TENANT, taskId, null, TENANT, withNull);
        assertEquals(List.of(b.toString()), r.getBlockedByIds());
    }

    @Test
    @DisplayName("a null blocker list clears blockers (same as an empty list)")
    void nullListClears() {
        AgentTaskEntity t = registerTask(taskId);
        t.setBlockedByIds(new ArrayList<>(List.of(UUID.randomUUID().toString())));
        AgentTaskEntity r = service.setTaskBlockers(TENANT, taskId, null, TENANT, null);
        assertTrue(r.getBlockedByIds().isEmpty());
    }

    @Test
    @DisplayName("only direct reciprocal cycles are caught - an INDIRECT (transitive) cycle is silently permitted (documents the current limit)")
    void transitiveCycleNotDetected() {
        // taskId is already blocked by B (A <- B). We now add A as a blocker of C.
        // The only cycle check is the DIRECT reciprocal one (does A already list C?),
        // which is false here, so a transitive A->B and C->A chain is accepted.
        UUID c = UUID.randomUUID();
        AgentTaskEntity a = registerTask(taskId);
        a.setBlockedByIds(new ArrayList<>(List.of(UUID.randomUUID().toString()))); // A blocked by something (not C)
        registerTask(c);
        assertDoesNotThrow(() -> service.setTaskBlockers(TENANT, c, null, TENANT, List.of(taskId.toString())));
    }

    @Test
    @DisplayName("records an UPDATED audit event carrying the old + new blocked_by_ids")
    void recordsAuditEventWithBeforeAfter() {
        registerTask(taskId);
        UUID b = UUID.randomUUID();
        registerTask(b);
        service.setTaskBlockers(TENANT, taskId, null, TENANT, List.of(b.toString()));
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_UPDATED), isNull(), eq(TENANT),
                eq(java.util.Map.of("blocked_by_ids", List.of())),
                eq(java.util.Map.of("blocked_by_ids", List.of(b.toString()))));
    }
}
