package com.apimarketplace.agent.service;

import com.apimarketplace.agent.dto.TaskSummaryResponse;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V340 - the system-prompt task summary advertises the shared backlog (count +
 * "use claim" hint) ONLY to agents that opted in. A non-participating agent gets
 * backlogCount=0 and the backlog is never even counted, so it is not nudged to
 * claim work it has no relationship to. Inbox/review counts are always reported.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.getTaskSummaryForPrompt - backlog opt-in gating (V340)")
class AgentTaskServiceBacklogSummaryTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    @Test
    @DisplayName("opted-in agent → backlog is counted and the claim hint is in the prompt section")
    void backlogCountedWhenEnabled() {
        UUID agentId = UUID.randomUUID();
        Instant since = Instant.now().minusSeconds(3600);
        when(taskRepository.countActiveInboxByOrganizationIdStrict(ORG, agentId)).thenReturn(0L);
        when(taskRepository.countCompletedOutboxSinceByOrganizationIdStrict(eq(ORG), eq(agentId), any())).thenReturn(0L);
        when(taskRepository.countPendingReviewsByOrganizationIdStrict(ORG, agentId)).thenReturn(0L);
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(true));
        when(taskRepository.countBacklogByOrganizationIdStrict(ORG)).thenReturn(3L);

        TaskSummaryResponse summary = service.getTaskSummaryForPrompt(TENANT, ORG, agentId, since);

        assertThat(summary.backlogCount()).isEqualTo(3L);
        assertThat(summary.hasTasks()).isTrue();
        assertThat(summary.toPromptSection()).contains("unassigned task(s) in the backlog");
    }

    @Test
    @DisplayName("opted-out agent → backlog is NEVER counted, backlogCount=0, no claim hint")
    void backlogNotCountedWhenDisabled() {
        UUID agentId = UUID.randomUUID();
        Instant since = Instant.now().minusSeconds(3600);
        when(taskRepository.countActiveInboxByOrganizationIdStrict(ORG, agentId)).thenReturn(0L);
        when(taskRepository.countCompletedOutboxSinceByOrganizationIdStrict(eq(ORG), eq(agentId), any())).thenReturn(0L);
        when(taskRepository.countPendingReviewsByOrganizationIdStrict(ORG, agentId)).thenReturn(0L);
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(false));

        TaskSummaryResponse summary = service.getTaskSummaryForPrompt(TENANT, ORG, agentId, since);

        assertThat(summary.backlogCount()).isZero();
        assertThat(summary.toPromptSection()).doesNotContain("in the backlog");
        verify(taskRepository, never()).countBacklogByOrganizationIdStrict(any());
    }

    @Test
    @DisplayName("opted-out agent still reports its directly-assigned inbox count (flag governs backlog only)")
    void inboxStillReportedWhenBacklogDisabled() {
        UUID agentId = UUID.randomUUID();
        Instant since = Instant.now().minusSeconds(3600);
        when(taskRepository.countActiveInboxByOrganizationIdStrict(ORG, agentId)).thenReturn(2L);
        when(taskRepository.countCompletedOutboxSinceByOrganizationIdStrict(eq(ORG), eq(agentId), any())).thenReturn(0L);
        when(taskRepository.countPendingReviewsByOrganizationIdStrict(ORG, agentId)).thenReturn(0L);
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(false));

        TaskSummaryResponse summary = service.getTaskSummaryForPrompt(TENANT, ORG, agentId, since);

        assertThat(summary.pendingCount()).isEqualTo(2L);
        assertThat(summary.backlogCount()).isZero();
        assertThat(summary.toPromptSection()).contains("in your inbox");
        verify(taskRepository, never()).countBacklogByOrganizationIdStrict(any());
    }
}
