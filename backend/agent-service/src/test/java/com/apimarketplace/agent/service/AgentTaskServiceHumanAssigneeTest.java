package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Jira-style HUMAN assignee / reviewer behavior on AgentTaskService:
 * a person assignee is set on {@code assigned_to_user_id} (agent id stays null),
 * never auto-executes, and the workspace-membership guard + exclusivity rules hold.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - human assignee / reviewer (Jira-style)")
class AgentTaskServiceHumanAssigneeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OWNER = "owner-1";
    private static final String ALICE = "42"; // a workspace teammate
    private static final String BOB = "7";    // another teammate
    private static final String OUTSIDER = "999";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private NotificationClient notificationClient;
    @Mock private AuthClient authClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        setField("notificationClient", notificationClient);
        setField("authClient", authClient);

        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> {
            AgentTaskEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(notificationClient.emit(any())).thenReturn(true);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AgentTaskService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void membersAre(String... ids) {
        when(authClient.getOrganizationMemberIds(ORG)).thenReturn(Set.of(ids));
    }

    private CreateTaskRequest createForUser(String assigneeUserId, String reviewerUserId) {
        return new CreateTaskRequest(null, null, "Design the landing page",
                "Mockups + copy", "normal", null, null, null, null,
                assigneeUserId, reviewerUserId);
    }

    // ─── assignTask ────────────────────────────────────────────────

    @Test
    @DisplayName("assign to a teammate → user assignee set, agent null, pending, no agent execution")
    void assignToHuman_doesNotAutoExecute() {
        membersAre(ALICE, BOB, OWNER);
        ArgumentCaptor<AgentTaskEntity> saved = ArgumentCaptor.forClass(AgentTaskEntity.class);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, createForUser(ALICE, null)));

        verify(taskRepository).save(saved.capture());
        AgentTaskEntity t = saved.getValue();
        assertThat(t.getAssignedToUserId()).isEqualTo(ALICE);
        assertThat(t.getAssignedToAgentId()).isNull();
        assertThat(t.getStatus()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
        // The defining Jira guarantee: a human assignee never dispatches an agent.
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("assigning a human emits the bell to THAT person (not the task owner)")
    void assignToHuman_notifiesThePerson() {
        membersAre(ALICE, OWNER);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, createForUser(ALICE, null)));

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        NotificationEmitRequest emit = captor.getValue();
        assertThat(emit.getTenantId()).isEqualTo(ALICE);            // recipient = the teammate
        assertThat(emit.getCategory()).isEqualTo("AGENT_TASK_ASSIGNED");
        assertThat(emit.getOrganizationId()).isEqualTo(ORG);
        assertThat(emit.getPayload()).containsEntry("role", "assignee");
        assertThat(emit.getSourceId()).isEqualTo(emit.getSubjectId() + ":assignee");
    }

    @Test
    @DisplayName("a human reviewer is set + notified, and is informational (agent reviewer stays null)")
    void assignWithHumanReviewer() {
        membersAre(ALICE, BOB, OWNER);
        ArgumentCaptor<AgentTaskEntity> saved = ArgumentCaptor.forClass(AgentTaskEntity.class);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, createForUser(ALICE, BOB)));

        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getReviewerUserId()).isEqualTo(BOB);
        assertThat(saved.getValue().getReviewerAgentId()).isNull();

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient, org.mockito.Mockito.times(2)).emit(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(e -> {
            assertThat(e.getTenantId()).isEqualTo(BOB);
            assertThat(e.getPayload()).containsEntry("role", "reviewer");
        });
    }

    @Test
    @DisplayName("assignee both agent AND human → rejected")
    void assignBothAgentAndHuman_rejected() {
        CreateTaskRequest req = new CreateTaskRequest(UUID.randomUUID(), null,
                "T", "i", "normal", null, null, null, null, ALICE, null);
        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, req)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent XOR a person");
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("assigning a non-member is rejected (workspace membership guard)")
    void assignNonMember_rejected() {
        membersAre(ALICE, OWNER); // OUTSIDER not in the set
        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, createForUser(OUTSIDER, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a member of this workspace");
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("reviewer and assignee cannot be the same person")
    void reviewerEqualsAssignee_rejected() {
        CreateTaskRequest req = createForUser(ALICE, ALICE);
        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.assignTask(OWNER, null, OWNER, req)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same person");
        verify(taskRepository, never()).save(any());
    }

    // ─── updateTask ────────────────────────────────────────────────

    private AgentTaskEntity existingTask() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(OWNER);
        t.setOrganizationId(ORG);
        t.setCreatedByUserId(OWNER);
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        return t;
    }

    private void stubFind(AgentTaskEntity t) {
        when(taskRepository.findByIdAndOrganizationIdStrict(t.getId(), ORG)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("reassign agent→human clears the agent and sets the user assignee")
    void reassignAgentToHuman() {
        AgentTaskEntity t = existingTask();
        t.setAssignedToAgentId(UUID.randomUUID());
        stubFind(t);
        membersAre(ALICE, OWNER);

        UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null,
                null, null, null, null, null, ALICE, null);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.updateTask(OWNER, t.getId(), null, OWNER, req));

        assertThat(t.getAssignedToUserId()).isEqualTo(ALICE);
        assertThat(t.getAssignedToAgentId()).isNull();
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("unassign clears a human assignee back to backlog")
    void unassignHuman() {
        AgentTaskEntity t = existingTask();
        t.setAssignedToUserId(ALICE);
        stubFind(t);

        UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null, Boolean.TRUE);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.updateTask(OWNER, t.getId(), null, OWNER, req));

        assertThat(t.getAssignedToUserId()).isNull();
        assertThat(t.getAssignedToAgentId()).isNull();
    }

    @Test
    @DisplayName("status→in_progress is allowed with a human assignee and does NOT dispatch an agent")
    void inProgressWithHumanAssignee_noKickoff() {
        AgentTaskEntity t = existingTask();
        t.setAssignedToUserId(ALICE);
        stubFind(t);

        UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null,
                null, null, null, AgentTaskEntity.STATUS_IN_PROGRESS);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.updateTask(OWNER, t.getId(), null, OWNER, req));

        assertThat(t.getStatus()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
        assertThat(t.getAssignedToAgentId()).isNull();
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("status→in_progress with no assignee at all is still rejected")
    void inProgressWithNoAssignee_rejected() {
        AgentTaskEntity t = existingTask(); // no assignee
        stubFind(t);

        UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null,
                null, null, null, AgentTaskEntity.STATUS_IN_PROGRESS);

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.updateTask(OWNER, t.getId(), null, OWNER, req)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an assignee");
    }

    @Test
    @DisplayName("set a human reviewer via update → reviewer_user_id set, agent reviewer null")
    void setHumanReviewerViaUpdate() {
        AgentTaskEntity t = existingTask();
        t.setAssignedToUserId(ALICE);
        stubFind(t);
        membersAre(ALICE, BOB, OWNER);

        UpdateTaskRequest req = new UpdateTaskRequest(null, null, null, null,
                null, null, null, null, null, null, BOB);

        TenantResolver.runWithOrgScope(ORG, () ->
                service.updateTask(OWNER, t.getId(), null, OWNER, req));

        assertThat(t.getReviewerUserId()).isEqualTo(BOB);
        assertThat(t.getReviewerAgentId()).isNull();
    }
}
