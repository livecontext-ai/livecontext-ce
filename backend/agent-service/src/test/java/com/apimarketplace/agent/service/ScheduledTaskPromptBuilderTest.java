package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V340 - the shared backlog is offered on a scheduled wake-up ONLY to agents that
 * opted in ({@code backlog_enabled = true}). A non-participating agent's wake-up
 * still drives its directly-assigned inbox + reviews; it is just never handed
 * unassigned backlog it may be ill-suited to claim.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduledTaskPromptBuilder - backlog opt-in gating (V340)")
class ScheduledTaskPromptBuilderTest {

    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-a";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentRepository agentRepository;

    private ScheduledTaskPromptBuilder builder;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        builder = new ScheduledTaskPromptBuilder(taskRepository, agentRepository);
        agentId = UUID.randomUUID();
    }

    private AgentTaskEntity task(String title) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setOrganizationId(ORG);
        t.setTitle(title);
        t.setStatus(AgentTaskEntity.STATUS_PENDING);
        t.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        return t;
    }

    @Test
    @DisplayName("backlog_enabled=true → the Backlog section + claim hint are served")
    void backlogShownWhenEnabled() {
        when(taskRepository.findActiveInboxByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(taskRepository.findPendingReviewsByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(true));
        when(taskRepository.findBacklogByOrganizationIdStrict(eq(ORG), any(Pageable.class)))
                .thenReturn(List.of(task("Unowned job")));

        String prompt = builder.build(TENANT, ORG, agentId, "static fallback");

        assertThat(prompt).contains("## Backlog");
        assertThat(prompt).contains("Unowned job");
        assertThat(prompt).contains("claim");
    }

    @Test
    @DisplayName("backlog_enabled=false → backlog is NEVER queried and never appears, inbox still served")
    void backlogHiddenWhenDisabled() {
        when(taskRepository.findActiveInboxByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of(task("Directly assigned to me")));
        when(taskRepository.findPendingReviewsByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(false));

        String prompt = builder.build(TENANT, ORG, agentId, "static fallback");

        // Assigned inbox is unaffected by the flag.
        assertThat(prompt).contains("Directly assigned to me");
        // The shared backlog is neither queried nor surfaced.
        assertThat(prompt).doesNotContain("## Backlog");
        verify(taskRepository, never()).findBacklogByOrganizationIdStrict(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("backlog_enabled=false + no assigned/review work → falls back to the static schedule prompt")
    void disabledWithNoOtherWorkReturnsFallback() {
        when(taskRepository.findActiveInboxByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(taskRepository.findPendingReviewsByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.of(false));

        String prompt = builder.build(TENANT, ORG, agentId, "static fallback");

        assertThat(prompt).isEqualTo("static fallback");
        verify(taskRepository, never()).findBacklogByOrganizationIdStrict(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("missing agent (no flag row) is treated as opted-out - backlog never queried")
    void missingAgentTreatedAsDisabled() {
        when(taskRepository.findActiveInboxByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of(task("Assigned")));
        when(taskRepository.findPendingReviewsByOrganizationIdStrict(eq(ORG), eq(agentId), any(Pageable.class)))
                .thenReturn(List.of());
        when(agentRepository.findBacklogEnabledById(agentId)).thenReturn(Optional.empty());

        String prompt = builder.build(TENANT, ORG, agentId, "static fallback");

        assertThat(prompt).doesNotContain("## Backlog");
        verify(taskRepository, never()).findBacklogByOrganizationIdStrict(any(), any(Pageable.class));
    }
}
