package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.webhook.WebhookIndexService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the just-shipped WorkflowManagementService.saveWorkflow
 * org-stamp fix.
 *
 * <p>Bug shape: prior to the fix, the non-draft save path (full validation, used
 * by POST /api/v2/workflows/dag and the agent finish/save path) created a new
 * WorkflowEntity row without {@code organization_id}. Even when the calling
 * controller had resolved the active org from the gateway header, the value
 * was dropped between controller and persistence - leaving the workflow
 * functional but invisible from the org-scoped Triggers tab.
 *
 * <p>The fix adds a 4-arg overload {@code saveWorkflow(plan, dataInputs,
 * workflowId, organizationId)} that stamps the new entity when the caller
 * provides a non-blank org id. A blank string is rejected by the same guard
 * pattern used in {@code saveDraft}, so a malformed caller cannot land
 * {@code organization_id = ""} (which would be invisible to BOTH personal
 * AND org list endpoints).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowManagementService.saveWorkflow - organization_id stamping")
class WorkflowManagementServiceSaveWorkflowOrgScopeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private StorageRepository storageRepository;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private TriggerClient triggerClient;
    @Mock private PublicationClient publicationClient;
    @Mock private WebhookIndexService webhookIndexService;
    @Mock private PinAwareTriggerSyncService pinAwareTriggerSyncService;

    private WorkflowManagementService service;

    private static final String TENANT_ID = "tenant-alice";

    @BeforeEach
    void setUp() {
        service = new WorkflowManagementService();
        setField(service, "workflowRepository", workflowRepository);
        setField(service, "orgAccessService", orgAccessService);
        setField(service, "breakdownService", breakdownService);
        setField(service, "storageRepository", storageRepository);
        setField(service, "entitlementGuard", entitlementGuard);
        setField(service, "triggerClient", triggerClient);
        setField(service, "publicationClient", publicationClient);
        setField(service, "webhookIndexService", webhookIndexService);
        setField(service, "pinAwareTriggerSyncService", pinAwareTriggerSyncService);
        setField(service, "objectMapper", new ObjectMapper());

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // EntitlementGuard.check is void - allow the quota check to pass silently so
        // the org-stamp branch (further down in saveWorkflow) is what we exercise.
        doNothing().when(entitlementGuard).check(anyString(), any(ResourceType.class), any(LongSupplier.class));
    }

    /** Build a minimal but parseable plan map (no triggers/steps so syncWebhookToken is a no-op). */
    private WorkflowPlan buildEmptyPlan(UUID workflowId) {
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("name", "Test Workflow");
        planMap.put("triggers", new ArrayList<>());
        planMap.put("mcps", new ArrayList<>());
        planMap.put("cores", new ArrayList<>());
        planMap.put("edges", new ArrayList<>());
        return WorkflowPlan.fromMap(planMap, workflowId.toString(), TENANT_ID);
    }

    @Test
    @DisplayName("saveWorkflow_withOrgId_stampsOnNewWorkflow - full-save path threads the active org onto the new entity (bell Triggers tab visibility)")
    void saveWorkflow_withOrgId_stampsOnNewWorkflow() {
        // Arrange - new workflow (findById empty triggers the createNewWorkflow branch).
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());
        WorkflowPlan plan = buildEmptyPlan(workflowId);

        // Act
        service.saveWorkflow(plan, null, workflowId, "ORG-1");

        // Assert - captured entity carries organization_id = ORG-1. Pre-fix the field
        // was simply not set, persisting as NULL even when the caller knew the org.
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("new workflow in org workspace must carry organization_id so org-scoped Triggers tab surfaces it")
                .isEqualTo("ORG-1");
    }

    @Test
    @DisplayName("saveWorkflow_withBlankOrgId_doesNotStamp - blank-string guard prevents organization_id='' (invisible to BOTH org and personal scopes)")
    void saveWorkflow_withBlankOrgId_doesNotStamp() {
        // Arrange - new workflow + blank org id (malformed caller forwarding an empty header).
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());
        WorkflowPlan plan = buildEmptyPlan(workflowId);

        // Act
        service.saveWorkflow(plan, null, workflowId, "");

        // Assert - the blank-guard branch (`!isBlank()`) prevents setOrganizationId from
        // being called, so the entity stays NULL-org (personal scope). Without this
        // guard the row would land organization_id="" and disappear from BOTH personal
        // list endpoints (filter: organization_id IS NULL) and org list endpoints
        // (filter: organization_id = :orgId).
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("blank organization id must NOT be stamped - would orphan the row across both scopes")
                .isNull();
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
