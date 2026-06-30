package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F11: @mention notifications on a note. Only workspace members are notified,
 * never the author, and a note without mentions emits nothing.
 */
class AgentTaskServiceMentionTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    private AgentTaskRepository taskRepository;
    private NotificationClient notificationClient;
    private AuthClient authClient;
    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        AgentTaskNoteRepository noteRepository = mock(AgentTaskNoteRepository.class);
        notificationClient = mock(NotificationClient.class);
        authClient = mock(AuthClient.class);
        AgentTaskService self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, noteRepository,
                mock(AgentTaskEventRepository.class), mock(AgentRepository.class),
                mock(AgentExecutionRepository.class), mock(TaskBoardPublisher.class),
                mock(ConversationClient.class), self);
        ReflectionTestUtils.setField(service, "notificationClient", notificationClient);
        ReflectionTestUtils.setField(service, "authClient", authClient);
        lenient().when(noteRepository.save(any(AgentTaskNoteEntity.class))).thenAnswer(inv -> {
            AgentTaskNoteEntity n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });
    }

    private void task() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setTitle("Ship it");
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("notifies only mentioned workspace members, excluding the author and non-members")
    void notifiesMembersOnly() {
        task();
        when(authClient.getOrganizationMemberIds(ORG)).thenReturn(Set.of("alice", "bob", "author"));

        // mention alice (member), outsider (non-member), author (self) -> only alice notified
        service.addNote(TENANT, taskId, null, "author", "ping @alice",
                List.of("alice", "outsider", "author"));

        ArgumentCaptor<NotificationEmitRequest> captor = ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient, times(1)).emit(captor.capture());
        NotificationEmitRequest req = captor.getValue();
        assertEquals("alice", req.getTenantId());
        assertEquals("AGENT_TASK_MENTION", req.getCategory());
        assertEquals(taskId, req.getSubjectId());
    }

    @Test
    @DisplayName("a note with no mentions emits nothing")
    void noMentionsNoEmit() {
        task();
        service.addNote(TENANT, taskId, null, "author", "just a note", null);
        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("mentioning only the author or non-members emits nothing")
    void selfOrNonMembersOnly() {
        task();
        when(authClient.getOrganizationMemberIds(ORG)).thenReturn(Set.of("author"));
        service.addNote(TENANT, taskId, null, "author", "note", List.of("author", "ghost"));
        verify(notificationClient, never()).emit(any());
    }
}
