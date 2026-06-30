package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for retrieving workflow step information
 * Provides methods to get outputStorageId from step data
 */
@Service
@Transactional(readOnly = true)
public class WorkflowStepService {
    
    private static final Logger logger = LoggerFactory.getLogger(WorkflowStepService.class);
    
    private final WorkflowStepDataRepository stepDataRepository;
    
    public WorkflowStepService(WorkflowStepDataRepository stepDataRepository) {
        this.stepDataRepository = stepDataRepository;
    }
    
    /**
     * Get outputStorageId for a step
     * 
     * @param stepId ID of the step (WorkflowStepDataEntity.id)
     * @param runId Run ID (public runId string)
     * @param tenantId Tenant ID for isolation
     * @return Optional UUID of the output storage, empty if step not found or no output
     */
    public Optional<UUID> getOutputStorageId(Long stepId, String runId, String tenantId) {
        try {
            // Find the step by ID, runId, and tenantId
            Optional<WorkflowStepDataEntity> stepOpt = stepDataRepository.findById(stepId);
            
            if (stepOpt.isEmpty()) {
                logger.debug("Step not found: stepId={}", stepId);
                return Optional.empty();
            }
            
            WorkflowStepDataEntity step = stepOpt.get();
            
            // Verify runId and tenantId match
            if (!step.getRunId().equals(runId)) {
                logger.warn("Step runId mismatch: expected={}, actual={}", runId, step.getRunId());
                return Optional.empty();
            }
            
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, step.getTenantId(), step.getOrganizationId())) {
                logger.warn("Step tenantId mismatch: expected={}, actual={}", tenantId, step.getTenantId());
                return Optional.empty();
            }
            
            UUID outputStorageId = step.getOutputStorageId();
            if (outputStorageId == null) {
                logger.debug("Step has no outputStorageId: stepId={}", stepId);
                return Optional.empty();
            }
            
            return Optional.of(outputStorageId);
            
        } catch (Exception e) {
            logger.error("Error getting outputStorageId for step: stepId={}, runId={}, tenantId={}", 
                stepId, runId, tenantId, e);
            return Optional.empty();
        }
    }
}

