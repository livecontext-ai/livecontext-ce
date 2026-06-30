package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.ScheduledTaskPromptBuilder;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the TaskNode org-scope audit (2026-06-10):
 * {@code GET /api/internal/agents/tasks} (workflow {@code list_tasks} op) used the
 * tenant-wide {@code findAllFiltered} finder while the board endpoint and the
 * internal get/update/delete paths were org-strict (PR23/PR26). A workflow running
 * in workspace A could therefore list workspace B's tasks - tasks its own
 * {@code get_task} op then 404'd on.
 *
 * <p>These tests fail on the pre-fix controller: it never called
 * {@code findAllFilteredByOrganizationIdStrict} and returned 200 without any
 * workspace scope when the org header was absent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentTaskController - GET /tasks workspace scoping")
class InternalAgentTaskControllerListScopeTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-A";

    @Mock private ScheduledTaskPromptBuilder scheduledPromptBuilder;
    @Mock private AgentTaskService taskService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;

    private InternalAgentTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentTaskController(
                scheduledPromptBuilder, taskService, tenantResolver,
                agentRepository, taskRepository, noteRepository);
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    @Test
    @DisplayName("Lists through the org-strict finder with the caller's workspace - never the tenant-wide finder")
    void listsThroughOrgStrictFinder() {
        MockHttpServletRequest req = request();
        when(tenantResolver.resolve(req)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(req)).thenReturn(ORG);
        when(taskRepository.findAllFilteredByOrganizationIdStrict(
                eq(ORG), any(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.countAllFilteredByOrganizationIdStrict(
                eq(ORG), any(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        ResponseEntity<?> response = controller.listTasksInternal(
                null, null, null, null, 50, 0, req);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                eq(ORG), any(), anyBoolean(), any(), any(), any(), any(), any(), eq("updated_at"), eq(50), eq(0));
        verify(taskRepository, never()).findAllFiltered(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(taskRepository, never()).countAllFiltered(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Rejects the call when no workspace scope is bound (V261 guard) instead of listing tenant-wide")
    void rejectsWhenOrgScopeMissing() {
        MockHttpServletRequest req = request();
        when(tenantResolver.resolve(req)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);

        ResponseEntity<?> response = controller.listTasksInternal(
                null, null, null, null, 50, 0, req);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(String.valueOf(body.get("error"))).contains("organizationId");
        verify(taskRepository, never()).findAllFiltered(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(taskRepository, never()).findAllFilteredByOrganizationIdStrict(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Preserves filter semantics on the strict path: status/priority/search passthrough + assignedTo=unassigned flag")
    void preservesFilterSemantics() {
        MockHttpServletRequest req = request();
        when(tenantResolver.resolve(req)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(req)).thenReturn(ORG);
        when(taskRepository.findAllFilteredByOrganizationIdStrict(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.countAllFilteredByOrganizationIdStrict(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        controller.listTasksInternal(
                "pending", "unassigned", "high", "report", 50, 0, req);

        // unassigned keyword → backlog flag true, assignedTo UUID filter null
        verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                eq(ORG), eq("pending"), eq(true), eq(null), eq(null),
                eq("high"), eq("report"), eq(null), eq("updated_at"), eq(50), eq(0));
    }

    @Test
    @DisplayName("Preserves assignedTo UUID filter and pagination clamping on the strict path")
    void preservesAssignedToAndClamping() {
        MockHttpServletRequest req = request();
        String agentId = UUID.randomUUID().toString();
        when(tenantResolver.resolve(req)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(req)).thenReturn(ORG);
        when(taskRepository.findAllFilteredByOrganizationIdStrict(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.countAllFilteredByOrganizationIdStrict(
                any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(0L);

        // size above the 200 cap and negative page must clamp exactly like pre-fix
        controller.listTasksInternal(null, agentId, null, null, 500, -3, req);

        verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                eq(ORG), eq(null), eq(false), eq(agentId), eq(null),
                eq(null), eq(null), eq(null), eq("updated_at"), eq(200), eq(0));
    }
}
