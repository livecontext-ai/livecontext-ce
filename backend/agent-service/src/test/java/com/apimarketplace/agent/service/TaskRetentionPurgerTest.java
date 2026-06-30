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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskRetentionPurger")
class TaskRetentionPurgerTest {

    @Mock private AgentTaskService taskService;

    private TaskRetentionPurger purger;

    @BeforeEach
    void setUp() {
        purger = new TaskRetentionPurger(taskService);
        ReflectionTestUtils.setField(purger, "retentionDays", 30L);
        ReflectionTestUtils.setField(purger, "batchSize", 200);
    }

    @Test
    @DisplayName("Delegates to purgeExpiredDeletedTasks with the configured window/batch")
    void tickDelegatesToService() {
        when(taskService.purgeExpiredDeletedTasks(30L, 200)).thenReturn(3);

        purger.tick();

        verify(taskService).purgeExpiredDeletedTasks(30L, 200);
    }

    @Test
    @DisplayName("Swallows exceptions so the daily scheduler keeps firing")
    void tickSwallowsExceptions() {
        when(taskService.purgeExpiredDeletedTasks(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("DB blip"));

        purger.tick(); // must not throw

        verify(taskService).purgeExpiredDeletedTasks(30L, 200);
    }
}
