package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowManagementService.SaveResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for workflow CRUD operations.
 * Defines the contract for managing workflow entities.
 *
 * @see com.apimarketplace.orchestrator.services.WorkflowManagementService
 */
public interface WorkflowCrud {

    WorkflowPlan parsePlan(String planJson);

    WorkflowPlan parsePlanWithTenantId(String planJson, String userId);

    SaveResult saveWorkflow(WorkflowPlan plan, Map<String, Object> dataInputs, UUID workflowId);

    WorkflowEntity saveDraft(Map<String, Object> planMap, String tenantId, UUID workflowId);

    Map<String, Object> buildSaveResponse(SaveResult result);

    Optional<WorkflowEntity> getWorkflow(UUID workflowId);

    UUID resolveWorkflowId(String planId, UUID providedId);

    boolean deleteWorkflow(UUID workflowId, String tenantId);

    Optional<WorkflowRunEntity> getInstance(String runId);

    List<WorkflowRunEntity> getRecentInstances(UUID workflowId, int limit);
}
