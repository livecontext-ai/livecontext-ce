package com.apimarketplace.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskReviewSweeper")
class TaskReviewSweeperTest {

    @Mock private AgentTaskService taskService;

    private TaskReviewSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new TaskReviewSweeper(taskService);
        ReflectionTestUtils.setField(sweeper, "staleThresholdSeconds", 120L);
        ReflectionTestUtils.setField(sweeper, "batchSize", 50);
    }

    @Test
    @DisplayName("Delegates to taskService.sweepStuckReviewTasks with configured window/batch")
    void tickDelegatesToService() {
        when(taskService.sweepStuckReviewTasks(120L, 50)).thenReturn(0);

        sweeper.tick();

        verify(taskService).sweepStuckReviewTasks(120L, 50);
    }

    @Test
    @DisplayName("Swallows exceptions so the scheduler keeps ticking")
    void tickSwallowsExceptions() {
        when(taskService.sweepStuckReviewTasks(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("DB blip"));

        // Must not throw - next tick should keep firing.
        sweeper.tick();

        verify(taskService).sweepStuckReviewTasks(120L, 50);
    }

    @Test
    @DisplayName("Returns without calling service again when triggered=0 (just logs)")
    void tickReturnsQuietlyOnZero() {
        when(taskService.sweepStuckReviewTasks(120L, 50)).thenReturn(0);

        sweeper.tick();

        verify(taskService).sweepStuckReviewTasks(120L, 50);
        verify(taskService, never()).sweepStuckReviewTasks(0L, 0);
    }
}
