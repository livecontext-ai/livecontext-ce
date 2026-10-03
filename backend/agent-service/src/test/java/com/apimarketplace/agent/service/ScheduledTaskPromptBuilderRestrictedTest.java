package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review finding r5-1): the scheduled wake-up prompt is an untagged turn of the agent's
 * own conversation, sent to whatever provider the agent runs (a CLI bridge included). A RESTRICTED
 * task's title may quote an email, so the prompt lists such a task the way the tool listings do:
 * id, priority, the restricted marker and the call that opens it, never the title.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskPromptBuilder - RESTRICTED tasks are listed without their title (LC-066)")
class ScheduledTaskPromptBuilderRestrictedTest {

    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-a";
    private static final String MAIL_TITLE = "Reply to Bob: invoice 4471 overdue, wire to IBAN FR76";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentRepository agentRepository;

    private ScheduledTaskPromptBuilder builder;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        builder = new ScheduledTaskPromptBuilder(taskRepository, agentRepository);
        agentId = UUID.randomUUID();
    }

    private static AgentTaskEntity task(String title, boolean restricted) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setTitle(title);
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        t.setPriority(AgentTaskEntity.PRIORITY_HIGH);
        if (restricted) {
            t.setDataSensitivity(DataSensitivity.RESTRICTED.name());
        }
        return t;
    }

    private void workload(List<AgentTaskEntity> inbox, List<AgentTaskEntity> reviews, List<AgentTaskEntity> backlog) {
        when(taskRepository.findActiveInboxByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(inbox);
        when(taskRepository.findPendingReviewsByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(reviews);
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(true));
        when(taskRepository.findBacklogByOrganizationIdStrict(eq(ORG), any(Pageable.class))).thenReturn(backlog);
    }

    @Test
    @DisplayName("an assigned RESTRICTED task shows id, priority, status, the marker and the inbox read, not its title")
    void restrictedInboxTaskTitleIsWithheld() {
        AgentTaskEntity restricted = task(MAIL_TITLE, true);
        workload(List.of(restricted), List.of(), List.of());

        String prompt = builder.build(TENANT, ORG, agentId, "fallback");

        assertThat(prompt).doesNotContain(MAIL_TITLE).doesNotContain("invoice 4471");
        assertThat(prompt).contains("id=" + restricted.getId())
                .contains("[high]")
                .contains("status=pending")
                .contains("restricted=true")
                .contains("agent(action='inbox', task_id='" + restricted.getId() + "')");
    }

    @Test
    @DisplayName("a RESTRICTED review task and a RESTRICTED shared-backlog task are withheld too, each with its own read")
    void restrictedReviewAndBacklogTitlesAreWithheld() {
        AgentTaskEntity review = task(MAIL_TITLE + " (review)", true);
        AgentTaskEntity backlog = task(MAIL_TITLE + " (backlog)", true);
        workload(List.of(), List.of(review), List.of(backlog));

        String prompt = builder.build(TENANT, ORG, agentId, "fallback");

        assertThat(prompt).doesNotContain("invoice 4471");
        assertThat(prompt).contains("agent(action='task_get_context', task_id='" + review.getId() + "')");
        assertThat(prompt).contains("agent(action='claim', task_id='" + backlog.getId() + "')");
    }

    @Test
    @DisplayName("a NORMAL task next to a restricted one is still listed with its title, as before")
    void normalTaskKeepsItsTitle() {
        AgentTaskEntity normal = task("Write the weekly report", false);
        AgentTaskEntity restricted = task(MAIL_TITLE, true);
        workload(List.of(normal, restricted), List.of(), List.of());

        String prompt = builder.build(TENANT, ORG, agentId, "fallback");

        assertThat(prompt).contains("- [high] Write the weekly report (id=" + normal.getId() + ", status=pending)");
        assertThat(prompt).doesNotContain(MAIL_TITLE);
    }
}
