package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.TaskStatusCategory;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 step 2: {@link AgentTaskService#updateTask} classifies the target status by
 * its board {@link TaskStatusCategory}, so a board's CUSTOM columns (e.g. a "QA"
 * column in the in_review category, a "Shipped" column in the done category)
 * behave like their category. A mock {@link TaskStatusService} is injected so the
 * registry resolves custom keys; un-stubbed keys fall back to the historical
 * default-key classification (matching the wired-but-empty-board case).
 */
class AgentTaskServiceCustomStatusTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private TaskStatusService taskStatusService;
    private AgentTaskService self;
    private AgentTaskService service;

    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        AgentTaskNoteRepository noteRepository = mock(AgentTaskNoteRepository.class);
        AgentTaskEventRepository eventRepository = mock(AgentTaskEventRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentExecutionRepository executionRepository = mock(AgentExecutionRepository.class);
        TaskBoardPublisher taskBoardPublisher = mock(TaskBoardPublisher.class);
        ConversationClient conversationClient = mock(ConversationClient.class);
        self = mock(AgentTaskService.class);
        taskStatusService = mock(TaskStatusService.class);

        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        ReflectionTestUtils.setField(service, "taskStatusService", taskStatusService);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task(String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);          // organizationId left null (personal board)
        t.setStatus(status);
        t.setCreatedByUserId(TENANT);   // tenant owner may update
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
        return t;
    }

    private UpdateTaskRequest toStatus(String status) {
        return new UpdateTaskRequest(null, null, null, null, null, null, null, status);
    }

    @Test
    @DisplayName("moving to a custom in_review-category column requires an assignee")
    void customInReviewRequiresAssignee() {
        task(AgentTaskEntity.STATUS_PENDING); // no assignee
        when(taskStatusService.categoryOf(TENANT, null, "qa")).thenReturn(Optional.of(TaskStatusCategory.IN_REVIEW));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateTask(TENANT, taskId, null, TENANT, toStatus("qa")));
        assertTrue(ex.getMessage().contains("requires an assignee"));
    }

    @Test
    @DisplayName("a custom in_review column is accepted with a human assignee and clears completedAt")
    void customInReviewWithAssignee() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
        t.setAssignedToUserId("user-1");
        t.setCompletedAt(java.time.Instant.now());
        when(taskStatusService.categoryOf(TENANT, null, "qa")).thenReturn(Optional.of(TaskStatusCategory.IN_REVIEW));

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, toStatus("qa"));

        assertEquals("qa", result.getStatus());
        assertNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("a custom done-category column stamps completedAt")
    void customDoneStampsCompletedAt() {
        AgentTaskEntity t = task("qa");
        t.setAssignedToUserId("user-1");
        when(taskStatusService.categoryOf(TENANT, null, "qa")).thenReturn(Optional.of(TaskStatusCategory.IN_REVIEW));
        when(taskStatusService.categoryOf(TENANT, null, "shipped")).thenReturn(Optional.of(TaskStatusCategory.DONE));

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, toStatus("shipped"));

        assertEquals("shipped", result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("reopening from a custom done-category column (terminal) clears the result")
    void customDoneIsTerminalSoReopenClearsResult() {
        AgentTaskEntity t = task("shipped");
        t.setAssignedToUserId("user-1");
        t.setResult("the delivered output");
        when(taskStatusService.categoryOf(TENANT, null, "shipped")).thenReturn(Optional.of(TaskStatusCategory.DONE));
        when(taskStatusService.categoryOf(TENANT, null, "qa")).thenReturn(Optional.of(TaskStatusCategory.IN_REVIEW));

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, toStatus("qa"));

        assertEquals("qa", result.getStatus());
        assertNull(result.getResult(), "leaving a terminal category should clear the stored result");
    }

    @Test
    @DisplayName("an unknown status (not on the board, not a historical literal) is rejected")
    void unknownStatusRejected() {
        task(AgentTaskEntity.STATUS_PENDING);
        when(taskStatusService.categoryOf(TENANT, null, "mystery")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(TENANT, taskId, null, TENANT, toStatus("mystery")));
        assertTrue(ex.getMessage().contains("invalid status"));
    }

    @Test
    @DisplayName("a custom deleted-category column cannot be set directly (must use delete)")
    void deletedCategoryRejectedDirectly() {
        task(AgentTaskEntity.STATUS_PENDING);
        when(taskStatusService.categoryOf(TENANT, null, "trashbin")).thenReturn(Optional.of(TaskStatusCategory.DELETED));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(TENANT, taskId, null, TENANT, toStatus("trashbin")));
        assertTrue(ex.getMessage().toLowerCase().contains("delete"));
    }

    @Test
    @DisplayName("an AGENT-assigned task cannot be parked in a custom active column")
    void agentAssignedCannotUseCustomActiveColumn() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_PROGRESS);
        t.setAssignedToAgentId(UUID.randomUUID());
        when(taskStatusService.categoryOf(TENANT, null, "blocked")).thenReturn(Optional.of(TaskStatusCategory.IN_PROGRESS));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateTask(TENANT, taskId, null, TENANT, toStatus("blocked")));
        assertTrue(ex.getMessage().contains("built-in active columns"));
    }

    @Test
    @DisplayName("an AGENT-assigned task CAN move to a custom TERMINAL column (terminal is unguarded)")
    void agentAssignedCanUseCustomTerminalColumn() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_IN_PROGRESS);
        t.setAssignedToAgentId(UUID.randomUUID());
        when(taskStatusService.categoryOf(TENANT, null, "archived")).thenReturn(Optional.of(TaskStatusCategory.DONE));

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT, toStatus("archived"));
        assertEquals("archived", result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("an agent-assigned task moves freely between the built-in active columns")
    void agentAssignedCanUseCanonicalActiveColumn() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
        t.setAssignedToAgentId(UUID.randomUUID());
        when(taskStatusService.categoryOf(TENANT, null, AgentTaskEntity.STATUS_IN_PROGRESS))
                .thenReturn(Optional.of(TaskStatusCategory.IN_PROGRESS));

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT,
                toStatus(AgentTaskEntity.STATUS_IN_PROGRESS));
        assertEquals(AgentTaskEntity.STATUS_IN_PROGRESS, result.getStatus());
    }

    @Test
    @DisplayName("restoreTask returns a trashed task to a CUSTOM previous column when it still resolves")
    void restoreToCustomPreviousColumn() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
        t.setAssignedToUserId("user-1");
        t.setPreviousStatus("qa");
        t.setDeletedAt(java.time.Instant.now());
        when(taskStatusService.categoryOf(TENANT, null, "qa")).thenReturn(Optional.of(TaskStatusCategory.IN_REVIEW));

        AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);

        assertEquals("qa", result.getStatus());
        assertNull(result.getDeletedAt());
        assertNull(result.getPreviousStatus());
    }

    @Test
    @DisplayName("restoreTask falls back to pending when the custom previous column no longer exists")
    void restoreFallsBackWhenCustomColumnGone() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_DELETED);
        t.setAssignedToUserId("user-1");
        t.setPreviousStatus("removed_col");
        t.setDeletedAt(java.time.Instant.now());
        when(taskStatusService.categoryOf(TENANT, null, "removed_col")).thenReturn(Optional.empty());

        AgentTaskEntity result = service.restoreTask(TENANT, taskId, null, TENANT);
        assertEquals(AgentTaskEntity.STATUS_PENDING, result.getStatus());
    }

    @Test
    @DisplayName("hard-delete: a task in a custom TERMINAL column qualifies (by category, not the literal status)")
    void hardDeleteCustomTerminalColumnTask() {
        AgentTaskEntity t = task("shipped"); // custom done-category column
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, taskId))
                .thenReturn(java.util.List.of());
        when(taskStatusService.categoryOf(TENANT, null, "shipped")).thenReturn(Optional.of(TaskStatusCategory.DONE));

        // Pre-fix: 'shipped' is not in the literal {completed,failed,cancelled} set -> rejected.
        int deleted = service.hardDeleteTask(TENANT, taskId, TENANT);

        assertEquals(1, deleted);
        verify(taskRepository).delete(t);
    }

    @Test
    @DisplayName("hard-delete: a child in a custom TERMINAL column does not block (it is recursively deleted)")
    void hardDeleteRecursesIntoCustomTerminalChild() {
        UUID childId = UUID.randomUUID();
        AgentTaskEntity parent = new AgentTaskEntity();
        parent.setId(taskId); parent.setTenantId(TENANT); parent.setStatus(AgentTaskEntity.STATUS_COMPLETED);
        AgentTaskEntity child = new AgentTaskEntity();
        child.setId(childId); child.setTenantId(TENANT); child.setStatus("shipped");
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(parent));
        when(taskRepository.findByIdAndTenantId(childId, TENANT)).thenReturn(Optional.of(child));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, taskId))
                .thenReturn(java.util.List.of(child));
        when(taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(TENANT, childId))
                .thenReturn(java.util.List.of());
        when(taskStatusService.categoryOf(TENANT, null, "shipped")).thenReturn(Optional.of(TaskStatusCategory.DONE));
        // parent 'completed' is unstubbed -> categoryOf returns empty -> ofDefaultKey -> DONE (terminal).

        // Pre-fix: the 'shipped' child is not in the literal terminal set -> wrongly blocks the delete.
        int deleted = service.hardDeleteTask(TENANT, taskId, TENANT);

        assertEquals(2, deleted);
        verify(taskRepository).delete(parent);
        verify(taskRepository).delete(child);
    }

    @Test
    @DisplayName("a canonical status with no registry hit still works via the default-key fallback")
    void canonicalFallbackStillWorks() {
        AgentTaskEntity t = task(AgentTaskEntity.STATUS_PENDING);
        t.setAssignedToUserId("user-1");
        // categoryOf for canonical keys returns empty (un-materialised board) → ofDefaultKey fallback.
        when(taskStatusService.categoryOf(TENANT, null, AgentTaskEntity.STATUS_IN_PROGRESS)).thenReturn(Optional.empty());

        AgentTaskEntity result = service.updateTask(TENANT, taskId, null, TENANT,
                toStatus(AgentTaskEntity.STATUS_IN_PROGRESS));

        assertEquals(AgentTaskEntity.STATUS_IN_PROGRESS, result.getStatus());
        assertNotNull(result.getStartedAt());
    }
}
