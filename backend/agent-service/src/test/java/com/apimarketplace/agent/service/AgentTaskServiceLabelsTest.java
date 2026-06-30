package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.TaskLabelEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F2: {@link AgentTaskService#setTaskLabels} validates via the catalog then stores the ids. */
class AgentTaskServiceLabelsTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private TaskLabelService taskLabelService;
    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        taskLabelService = mock(TaskLabelService.class);
        AgentTaskService self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, mock(AgentTaskNoteRepository.class),
                mock(AgentTaskEventRepository.class), mock(AgentRepository.class),
                mock(AgentExecutionRepository.class), mock(TaskBoardPublisher.class),
                mock(ConversationClient.class), self);
        ReflectionTestUtils.setField(service, "taskLabelService", taskLabelService);
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
    @DisplayName("stores the catalog-validated ids on the task")
    void storesValidatedIds() {
        task();
        String a = UUID.randomUUID().toString();
        when(taskLabelService.validateLabelIds(TENANT, null, List.of(a))).thenReturn(List.of(a));

        AgentTaskEntity result = service.setTaskLabels(TENANT, taskId, null, TENANT, List.of(a));
        assertEquals(List.of(a), result.getLabelIds());
    }

    @Test
    @DisplayName("an empty list clears the task's labels")
    void clearsLabels() {
        AgentTaskEntity t = task();
        t.setLabelIds(new java.util.ArrayList<>(List.of("old")));
        when(taskLabelService.validateLabelIds(TENANT, null, List.of())).thenReturn(List.of());

        AgentTaskEntity result = service.setTaskLabels(TENANT, taskId, null, TENANT, List.of());
        assertTrue(result.getLabelIds().isEmpty());
    }

    @Test
    @DisplayName("propagates the catalog validation error (unknown label id)")
    void propagatesValidationError() {
        task();
        when(taskLabelService.validateLabelIds(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("unknown label id(s) for this board: x"));
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskLabels(TENANT, taskId, null, TENANT, List.of("x")));
    }

    // ---- F20: label NAMES resolved via get-or-create ----

    private TaskLabelEntity labelNamed(String id, String name) {
        TaskLabelEntity e = new TaskLabelEntity();
        e.setId(UUID.fromString(id));
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("resolves label NAMES via get-or-create and stores their ids")
    void resolvesNamesViaGetOrCreate() {
        task();
        String qaId = UUID.randomUUID().toString();
        when(taskLabelService.getOrCreateByName(TENANT, null, "qa")).thenReturn(labelNamed(qaId, "qa"));
        when(taskLabelService.validateLabelIds(TENANT, null, List.of(qaId))).thenReturn(List.of(qaId));

        AgentTaskEntity result = service.setTaskLabels(TENANT, taskId, null, TENANT, null, List.of("qa"));

        assertEquals(List.of(qaId), result.getLabelIds());
    }

    @Test
    @DisplayName("unions resolved names (first) with explicit ids before validating")
    void unionsNamesAndIds() {
        task();
        String existingId = UUID.randomUUID().toString();
        String qaId = UUID.randomUUID().toString();
        when(taskLabelService.getOrCreateByName(TENANT, null, "qa")).thenReturn(labelNamed(qaId, "qa"));
        when(taskLabelService.validateLabelIds(TENANT, null, List.of(qaId, existingId)))
                .thenReturn(List.of(qaId, existingId));

        AgentTaskEntity result = service.setTaskLabels(
                TENANT, taskId, null, TENANT, List.of(existingId), List.of("qa"));

        assertEquals(List.of(qaId, existingId), result.getLabelIds());
    }

    @Test
    @DisplayName("blank names are skipped (never resolved or created)")
    void skipsBlankNames() {
        task();
        when(taskLabelService.validateLabelIds(TENANT, null, List.of())).thenReturn(List.of());

        AgentTaskEntity result = service.setTaskLabels(
                TENANT, taskId, null, TENANT, null, java.util.Arrays.asList("", "   "));

        assertTrue(result.getLabelIds().isEmpty());
        verify(taskLabelService, never()).getOrCreateByName(any(), any(), any());
    }
}
