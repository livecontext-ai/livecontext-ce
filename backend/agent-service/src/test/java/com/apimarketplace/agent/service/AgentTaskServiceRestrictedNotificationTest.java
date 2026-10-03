package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review finding r5-1, other sinks): a notification row is untagged and is delivered as it
 * is, to the bell, by email and in the chat digest. A RESTRICTED task's title (which may quote an
 * email) is therefore never copied into one; the payload carries {@code restricted: true} instead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - notifications never carry a RESTRICTED task's title (LC-066)")
class AgentTaskServiceRestrictedNotificationTest {

    private static final String ORG = "org-1";
    private static final String TENANT = "tenant-1";
    private static final String MAIL_TITLE = "Answer Alice re: contract renewal at 41k EUR";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private NotificationClient notificationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        Field f = AgentTaskService.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(service, notificationClient);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> {
            AgentTaskEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(notificationClient.emit(any())).thenReturn(true);
    }

    @Test
    @DisplayName("AGENT_TASK_ASSIGNED for a task assigned from a restricted execution has no subjectName")
    void assignedNotificationOfRestrictedTaskHasNoTitle() {
        UUID assigneeId = UUID.randomUUID();
        AgentEntity assignee = new AgentEntity();
        assignee.setId(assigneeId);
        assignee.setTenantId(TENANT);
        assignee.setOrganizationId(ORG);
        assignee.setName("Worker");
        when(agentRepository.findByIdAndOrganizationIdStrict(assigneeId, ORG)).thenReturn(Optional.of(assignee));
        CreateTaskRequest req = new CreateTaskRequest(
                assigneeId, null, MAIL_TITLE, "Draft the answer", "normal", null, null, null, null);

        TenantResolver.runWithOrgScope(ORG, () -> service.assignTask(TENANT, UUID.randomUUID(), null, req,
                false, DataSensitivity.RESTRICTED));

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        Map<String, Object> payload = captor.getValue().getPayload();
        assertThat(payload).doesNotContainKey("subjectName").containsEntry("restricted", true);
        assertThat(payload.values()).noneMatch(v -> String.valueOf(v).contains("41k EUR"));
    }

    @Test
    @DisplayName("AGENT_TASK_AWAITING_REVIEW for a RESTRICTED task has no subjectName")
    void awaitingReviewNotificationOfRestrictedTaskHasNoTitle() throws Exception {
        AgentTaskEntity task = restrictedTask();
        task.setCreatedByUserId("user-7");

        invoke("emitTaskAwaitingReviewAfterCommit", new Class<?>[]{AgentTaskEntity.class, String.class},
                task, TENANT);

        assertNoTitle(captureSingle());
    }

    @Test
    @DisplayName("AGENT_TASK_MENTION on a note of a RESTRICTED task has no subjectName")
    void mentionNotificationOfRestrictedTaskHasNoTitle() throws Exception {
        AgentTaskEntity task = restrictedTask();
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setId(UUID.randomUUID());

        invoke("emitNoteMentionsAfterCommit",
                new Class<?>[]{AgentTaskEntity.class, AgentTaskNoteEntity.class, String.class, List.class},
                task, note, "author", List.of("teammate"));

        assertNoTitle(captureSingle());
    }

    @Test
    @DisplayName("AGENT_TASK_ASSIGNED to a human reviewer of a RESTRICTED task has no subjectName")
    void assignedToUserNotificationOfRestrictedTaskHasNoTitle() throws Exception {
        AgentTaskEntity task = restrictedTask();

        invoke("emitTaskAssignedToUserAfterCommit", new Class<?>[]{AgentTaskEntity.class, String.class, String.class},
                task, "user-9", "reviewer");

        assertNoTitle(captureSingle());
    }

    @Test
    @DisplayName("a NORMAL task keeps its title on the notification, as before")
    void normalTaskKeepsItsTitle() {
        AgentTaskEntity task = restrictedTask();
        task.setDataSensitivity(DataSensitivity.NORMAL.name());
        Map<String, Object> payload = new HashMap<>();

        AgentTaskService.putTaskSubjectName(payload, task);

        assertThat(payload).containsEntry("subjectName", MAIL_TITLE).doesNotContainKey("restricted");
    }

    private static AgentTaskEntity restrictedTask() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setTitle(MAIL_TITLE);
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        t.setDataSensitivity(DataSensitivity.RESTRICTED.name());
        return t;
    }

    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = AgentTaskService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(service, args);
    }

    private NotificationEmitRequest captureSingle() {
        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        return captor.getValue();
    }

    private static void assertNoTitle(NotificationEmitRequest emit) {
        assertThat(emit.getPayload()).doesNotContainKey("subjectName").containsEntry("restricted", true);
        assertThat(emit.getPayload().values()).noneMatch(v -> String.valueOf(v).contains("contract renewal"));
    }
}
