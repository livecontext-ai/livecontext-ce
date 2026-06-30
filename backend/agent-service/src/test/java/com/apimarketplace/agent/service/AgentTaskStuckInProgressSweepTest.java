package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the stuck-in_progress backstop reaper ({@link AgentTaskService#sweepStuckInProgressTasks}).
 * Verifies that each orphaned in_progress task is auto-failed (via the race-safe markExecutionFailed
 * CAS), that the stale window is applied, and that one task failing does not abort the rest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - stuck in_progress reaper")
class AgentTaskStuckInProgressSweepTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;

    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        // Test-only constructor: self-reference falls back to `this`, so self.markExecutionFailed
        // calls the real method (which short-circuits when the CAS reports 0 rows updated).
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository, agentRepository);
    }

    private static AgentTaskEntity stuckTask(String tenantId) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setUpdatedAt(Instant.now().minusSeconds(10_000)); // older than the default 9000s window
        return t;
    }

    @Test
    @DisplayName("auto-fails each orphaned in_progress task and returns the count")
    void reapsEachStuckTask() {
        AgentTaskEntity t1 = stuckTask("tenant-1");
        AgentTaskEntity t2 = stuckTask("tenant-2");
        when(taskRepository.findStuckInProgressTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t1, t2));
        // CAS reports 0 rows (treat as already-transitioned) so markExecutionFailed short-circuits
        // before any board publish - keeps this a pure unit test of the sweep loop.
        when(taskRepository.markExecutionFailed(any(UUID.class), anyString(), anyString())).thenReturn(0);

        int failed = service.sweepStuckInProgressTasks(9000, 50);

        assertThat(failed).isEqualTo(2);
        verify(taskRepository).markExecutionFailed(eq(t1.getId()), eq("tenant-1"), contains("stuck-task reaper"));
        verify(taskRepository).markExecutionFailed(eq(t2.getId()), eq("tenant-2"), anyString());
    }

    @Test
    @DisplayName("queries with a staleBefore cutoff in the past (the configured window ago)")
    void appliesStaleWindow() {
        when(taskRepository.findStuckInProgressTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.sweepStuckInProgressTasks(9000, 50);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).findStuckInProgressTasks(cutoff.capture(), any(Pageable.class));
        // staleBefore must be ~9000s in the past so only long-orphaned tasks match.
        assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(8000));
    }

    @Test
    @DisplayName("nothing stuck -> no markExecutionFailed calls, returns 0")
    void emptyResultIsNoOp() {
        when(taskRepository.findStuckInProgressTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.sweepStuckInProgressTasks(9000, 50)).isZero();
        verify(taskRepository, never()).markExecutionFailed(any(), any(), any());
    }

    @Test
    @DisplayName("one task failing does not abort the rest of the batch")
    void oneFailureDoesNotStopTheBatch() {
        AgentTaskEntity t1 = stuckTask("t1");
        AgentTaskEntity t2 = stuckTask("t2");
        when(taskRepository.findStuckInProgressTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t1, t2));
        when(taskRepository.markExecutionFailed(eq(t1.getId()), anyString(), anyString()))
                .thenThrow(new RuntimeException("db blip"));
        when(taskRepository.markExecutionFailed(eq(t2.getId()), anyString(), anyString())).thenReturn(0);

        int failed = service.sweepStuckInProgressTasks(9000, 50);

        assertThat(failed).isEqualTo(1); // t1 threw (caught + logged), t2 processed
        verify(taskRepository).markExecutionFailed(eq(t2.getId()), anyString(), anyString());
    }
}
