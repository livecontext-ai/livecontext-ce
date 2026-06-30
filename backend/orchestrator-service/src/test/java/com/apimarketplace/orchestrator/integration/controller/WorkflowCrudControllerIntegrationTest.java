package com.apimarketplace.orchestrator.integration.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WorkflowCrudController.
 * Tests workflow CRUD endpoints at /api/v2/workflows/dag.
 *
 * <p>Verifies HTTP contract: correct status codes, response structure,
 * tenant isolation, and input validation.</p>
 */
@DisplayName("WorkflowCrudController Integration Tests")
class WorkflowCrudControllerIntegrationTest extends BaseControllerIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @MockitoBean
    private TriggerClient triggerClient;

    // V261 / org-isolation rollout - AuthClient is bean-wired and tries to call
    // auth-service over HTTP on canAccess() / filterAccessible(). The integration
    // test profile doesn't start auth-service (Connection refused → 409
    // INVALID_STATE). Mock the guard to permit everything so the test exercises
    // the controller logic, not the cross-service auth probe.
    @MockitoBean
    private OrgAccessGuard orgAccessGuard;

    private WorkflowEntity testWorkflow;

    @BeforeEach
    void setUp() {
        when(triggerClient.getTokensForWorkflow(any(UUID.class)))
            .thenReturn(Map.of());

        // Default: allow all access. Returns the input list unchanged for list-filter
        // calls and true for point canAccess checks. lenient() because not every
        // test in the class hits every guard method.
        org.mockito.Mockito.lenient()
            .when(orgAccessGuard.canAccess(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(orgAccessGuard.canWrite(any(), any(), any(), any(), any()))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(orgAccessGuard.getRestrictedResourceIds(anyString(), anyString(), anyString(), any()))
            .thenReturn(Set.of());
        org.mockito.Mockito.lenient()
            .when(orgAccessGuard.filterAccessible(any(), anyString(), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                List<?> items = inv.getArgument(0);
                return items == null ? java.util.Collections.emptyList() : items;
            });

        testWorkflow = createAndSaveWorkflow(TENANT_ID, "Test Workflow", "A test workflow description");
    }

    // =========================================================================
    // GET /{workflowId} - Get Workflow
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v2/workflows/dag/{workflowId}")
    class GetWorkflow {

        @Test
        @DisplayName("Returns workflow when found and owned by tenant")
        void returnsWorkflowWhenOwnedByTenant() throws Exception {
            mockMvc.perform(get("/api/v2/workflows/dag/{workflowId}", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testWorkflow.getId().toString()))
                .andExpect(jsonPath("$.name").value("Test Workflow"))
                .andExpect(jsonPath("$.description").value("A test workflow description"));
        }

        @Test
        @DisplayName("Returns 404 when workflow not found")
        void returns404WhenNotFound() throws Exception {
            UUID nonExistentId = UUID.randomUUID();
            mockMvc.perform(get("/api/v2/workflows/dag/{workflowId}", nonExistentId)
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 404 when workflow owned by different tenant (tenant isolation)")
        void returns404WhenOwnedByDifferentTenant() throws Exception {
            mockMvc.perform(get("/api/v2/workflows/dag/{workflowId}", testWorkflow.getId())
                    .header(X_USER_ID, OTHER_TENANT_ID).header("X-Organization-ID", OTHER_TENANT_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 400 for invalid UUID format")
        void returns400ForInvalidUuid() throws Exception {
            mockMvc.perform(get("/api/v2/workflows/dag/{workflowId}", "not-a-uuid")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid workflow ID format"));
        }
    }

    // =========================================================================
    // PUT /{workflowId}/plan - Update Workflow Plan
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/v2/workflows/dag/{workflowId}/plan")
    class UpdateWorkflowPlan {

        @Test
        @DisplayName("Updates plan for owned workflow")
        void updatesPlanForOwnedWorkflow() throws Exception {
            Map<String, Object> plan = createMinimalPlan("Updated Workflow");
            Map<String, Object> requestBody = Map.of("plan", plan);

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.workflowId").value(testWorkflow.getId().toString()))
                .andExpect(jsonPath("$.message").value("Workflow plan updated successfully"));
        }

        @Test
        @DisplayName("Returns 404 when workflow not found")
        void returns404WhenNotFound() throws Exception {
            UUID nonExistentId = UUID.randomUUID();
            Map<String, Object> requestBody = Map.of("plan", createMinimalPlan("Test"));

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", nonExistentId)
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 404 when owned by different tenant (tenant isolation)")
        void returns404WhenDifferentTenant() throws Exception {
            Map<String, Object> requestBody = Map.of("plan", createMinimalPlan("Test"));

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, OTHER_TENANT_ID).header("X-Organization-ID", OTHER_TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Returns 400 when plan is missing")
        void returns400WhenPlanMissing() throws Exception {
            Map<String, Object> requestBody = Map.of("somethingElse", "value");

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Plan is required"));
        }

        @Test
        @DisplayName("Returns 400 for invalid UUID format")
        void returns400ForInvalidUuid() throws Exception {
            Map<String, Object> requestBody = Map.of("plan", createMinimalPlan("Test"));

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", "not-a-uuid")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid workflow ID format"));
        }

        @Test
        @DisplayName("Updates workflow name from plan")
        void updatesWorkflowNameFromPlan() throws Exception {
            Map<String, Object> plan = createMinimalPlan("New Workflow Name");
            Map<String, Object> requestBody = Map.of("plan", plan);

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

            // Verify name was updated in DB
            WorkflowEntity updated = workflowRepository.findById(testWorkflow.getId()).orElseThrow();
            assert updated.getName().equals("New Workflow Name");
        }

        @Test
        @DisplayName("PUT plan with schedule trigger on UNPINNED workflow → does NOT create schedule row (contract: row ACTIVE iff pinned)")
        void putPlanWithScheduleOnUnpinnedWorkflowDoesNotCreateRow() {
            // Contract regression guard (bug 2026-05-14): a draft save with a schedule
            // trigger in the plan must NOT call triggerClient.createOrUpdateSchedule
            // while the workflow has no pinned production version. Pre-fix, this
            // endpoint reached scheduleSyncService.syncFromPlan via PinAwareTriggerSyncService's
            // "backward compat" branch and CREATED an ACTIVE row that auto-fired
            // forever on workflows the user never made "live". Post-fix, schedules
            // follow pin state - toggling live in the UI (or pinning via API) is the
            // only path to an ACTIVE row.
            Map<String, Object> plan = createMinimalPlan("With Cron");
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("id", "cron");
            trigger.put("label", "Cron");
            trigger.put("type", "schedule");
            trigger.put("trigger_type", "schedule");
            trigger.put("params", Map.of("cron", "0 * * * *", "timezone", "UTC", "enabled", true));
            plan.put("triggers", java.util.List.of(trigger));
            Map<String, Object> requestBody = Map.of("plan", plan);

            // testWorkflow is created unpinned (pinnedVersion is null - see createAndSaveWorkflow).
            // Pin-aware disable path queries existing rows to suspend them; return empty.
            when(triggerClient.getSchedulesByWorkflow(testWorkflow.getId()))
                    .thenReturn(java.util.Collections.emptyList());

            try {
                mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                        .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                    .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Hard assertion: unpinned save must NEVER create or arm a schedule row,
            // regardless of what triggers the draft plan declares.
            verify(triggerClient, never()).createOrUpdateSchedule(
                    any(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("PUT plan with webhook config push 5xx → response carries triggerSyncWarning (SECURITY: user must see auth change didn't take effect)")
        void putPlanSurfacesWebhookSyncWarning() throws Exception {
            // SECURITY regression: a transient 5xx during webhook config push leaves the
            // public endpoint on stale auth_type. The user MUST be told via API response.
            UUID webhookId = UUID.randomUUID();
            Map<String, Object> plan = createMinimalPlan("With Webhook");
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("id", "hook");
            trigger.put("label", "Hook");
            trigger.put("type", "webhook");
            trigger.put("trigger_type", "webhook");
            Map<String, Object> params = new HashMap<>();
            params.put("webhookId", webhookId.toString());
            params.put("httpMethod", "POST");
            params.put("authType", "basic");
            params.put("basicUsername", "alice");
            params.put("basicPassword", "secret");
            trigger.put("params", params);
            plan.put("triggers", java.util.List.of(trigger));
            Map<String, Object> requestBody = Map.of("plan", plan);

            // Strict webhook update throws SERVER_ERROR
            org.mockito.Mockito.doThrow(new com.apimarketplace.trigger.client.TriggerClientException(
                    com.apimarketplace.trigger.client.TriggerClientException.Kind.SERVER_ERROR,
                    "503 Service Unavailable", null))
                    .when(triggerClient).updateStandaloneWebhookStrict(
                            eq(TENANT_ID), eq(webhookId), org.mockito.ArgumentMatchers.any());

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerSyncWarning").exists())
                .andExpect(jsonPath("$.triggerSyncWarning").value(
                        org.hamcrest.Matchers.containsString(webhookId.toString())));
        }

        @Test
        @DisplayName("PUT plan propagates trigger sync to trigger-service (regression: Gmail Auto-Labeler stuck cron)")
        void putPlanPropagatesTriggerSync() throws Exception {
            // Regression for the Gmail Auto-Labeler bug (2026-04-29):
            // PUT /plan must call PinAwareTriggerSyncService so that webhook/chat/form
            // cleanup AND schedule cron updates reach trigger-service. Pre-fix this
            // endpoint silently skipped the sync, leaving the schedule row stuck on its
            // creation-time cron forever.
            Map<String, Object> plan = createMinimalPlan("With Sync");
            Map<String, Object> requestBody = Map.of("plan", plan);

            mockMvc.perform(put("/api/v2/workflows/dag/{workflowId}/plan", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk());

            // Sync touches all 4 trigger-type lifecycle hooks: schedules (delete-by-workflow
            // when no schedule trigger remains), webhook tokens (orphan cleanup), chat & form
            // endpoint references (clear when no trigger of that type). Verifying any one
            // of these proves the controller wired into PinAwareTriggerSyncService.
            verify(triggerClient, atLeastOnce()).cleanupOrphanTokens(eq(testWorkflow.getId()), anyList());
            verify(triggerClient, atLeastOnce()).syncChatEndpointTriggerId(eq(testWorkflow.getId()), eq(null));
            verify(triggerClient, atLeastOnce()).syncFormEndpointTriggerId(eq(testWorkflow.getId()), eq(null));
        }
    }

    // =========================================================================
    // DELETE /{workflowId} - Delete Workflow
    // =========================================================================

    @Nested
    @DisplayName("DELETE /api/v2/workflows/dag/{workflowId}")
    class DeleteWorkflow {

        @Test
        @DisplayName("Returns 400 for invalid UUID format")
        void returns400ForInvalidUuid() throws Exception {
            mockMvc.perform(delete("/api/v2/workflows/dag/{workflowId}", "not-a-uuid")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid workflow ID format"));
        }
    }

    // =========================================================================
    // POST /{workflowId}/clone - Clone Workflow
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v2/workflows/dag/{workflowId}/clone")
    class CloneWorkflow {

        @Test
        @DisplayName("Returns 400 for invalid UUID format")
        void returns400ForInvalidUuid() throws Exception {
            mockMvc.perform(post("/api/v2/workflows/dag/{workflowId}/clone", "not-a-uuid")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        }
    }

    // =========================================================================
    // Workflow List Controller Tests - GET /api/workflows
    // =========================================================================

    @Nested
    @DisplayName("GET /api/workflows")
    class ListWorkflows {

        @Test
        @DisplayName("Returns workflows for tenant via X-User-ID header")
        void returnsWorkflowsForTenant() throws Exception {
            mockMvc.perform(get("/api/workflows")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows").isArray())
                .andExpect(jsonPath("$.count").isNumber());
        }

        @Test
        @DisplayName("Returns empty list for tenant with no workflows")
        void returnsEmptyListForTenantWithNoWorkflows() throws Exception {
            mockMvc.perform(get("/api/workflows")
                    .header(X_USER_ID, "00000000-0000-0000-0000-000000000000").header("X-Organization-ID", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows").isArray())
                .andExpect(jsonPath("$.workflows").isEmpty())
                .andExpect(jsonPath("$.count").value(0));
        }

        @Test
        @DisplayName("Respects limit parameter")
        void respectsLimitParameter() throws Exception {
            // Create additional workflows
            createAndSaveWorkflow(TENANT_ID, "Workflow 2", "Second workflow");
            createAndSaveWorkflow(TENANT_ID, "Workflow 3", "Third workflow");

            mockMvc.perform(get("/api/workflows")
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID)
                    .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows", hasSize(1)));
        }

        @Test
        @DisplayName("Returns tenant isolation - different tenant sees no workflows")
        void tenantIsolation() throws Exception {
            mockMvc.perform(get("/api/workflows")
                    .header(X_USER_ID, OTHER_TENANT_ID).header("X-Organization-ID", OTHER_TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows").isEmpty())
                .andExpect(jsonPath("$.count").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/workflows/{workflowId}")
    class GetWorkflowByList {

        @Test
        @DisplayName("Returns workflow by ID")
        void returnsWorkflowById() throws Exception {
            mockMvc.perform(get("/api/workflows/{workflowId}", testWorkflow.getId())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testWorkflow.getId().toString()))
                .andExpect(jsonPath("$.name").value("Test Workflow"));
        }

        @Test
        @DisplayName("Returns 404 for non-existent workflow")
        void returns404ForNonExistent() throws Exception {
            mockMvc.perform(get("/api/workflows/{workflowId}", UUID.randomUUID())
                    .header(X_USER_ID, TENANT_ID).header("X-Organization-ID", TENANT_ID))
                .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WorkflowEntity createAndSaveWorkflow(String tenantId, String name, String description) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(tenantId);
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setVersion("1.0.0");
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setCreatedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        workflow.setPlan(createMinimalPlan(name));
        // V263 OrgScopedEntity NOT NULL - stamp before save. @BeforeEach runs
        // outside any HTTP request, so no TenantResolver scope is bound. Reuse
        // tenantId as the org id for test isolation parity.
        workflow.setOrganizationId(tenantId);
        return workflowRepository.save(workflow);
    }

    private Map<String, Object> createMinimalPlan(String name) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("name", name);
        plan.put("description", "Test plan");
        plan.put("triggers", java.util.List.of());
        plan.put("mcps", java.util.List.of());
        plan.put("edges", java.util.List.of());
        return plan;
    }
}
