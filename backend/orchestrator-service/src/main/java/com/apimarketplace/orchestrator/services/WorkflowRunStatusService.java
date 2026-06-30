package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkflowRunStatusService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunStatusService.class);

    private final WorkflowRunStatusRepository workflowRunStatusRepository;
    private final WorkflowRunRepository workflowRunRepository;

    public WorkflowRunStatusService(WorkflowRunStatusRepository workflowRunStatusRepository,
                                    WorkflowRunRepository workflowRunRepository) {
        this.workflowRunStatusRepository = workflowRunStatusRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowRunStatusEntity> findByRunId(UUID runId) {
        return workflowRunStatusRepository.findByRunId(runId);
    }

    @Transactional
    public WorkflowRunStatusEntity persistSnapshot(UUID runId,
                                                   RunStatus status,
                                                   Map<String, Object> payload) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required to persist workflow status snapshot");
        }
        WorkflowRunEntity workflowRun = workflowRunRepository.findById(runId)
            .orElseThrow(() -> new IllegalStateException("Unable to find workflow run " + runId));

        WorkflowRunStatusEntity entity = workflowRunStatusRepository.findByRunId(runId)
            .orElse(new WorkflowRunStatusEntity());

        entity.setRunId(runId);
        entity.setWorkflow(workflowRun.getWorkflow());
        entity.setTenantId(workflowRun.getTenantId());
        // Audit-D round-12 fix (2026-05-20): explicit org stamp on the new-entity
        // path so the @PrePersist fail-loud listener doesn't trip when this is
        // called from an async/queue-worker thread that has no request binding.
        // Parent workflowRun row already carries a non-null organization_id post-V263.
        entity.setOrganizationId(workflowRun.getOrganizationId());
        entity.setStatus(status);
        entity.setPayload(payload);

        WorkflowRunStatusEntity saved = workflowRunStatusRepository.save(entity);
        logger.info("Saved workflow_run_status snapshot for runId={} status={}", runId, status);
        return saved;
    }
}
