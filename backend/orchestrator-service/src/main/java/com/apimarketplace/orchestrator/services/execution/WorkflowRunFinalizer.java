package com.apimarketplace.orchestrator.services.execution;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.streaming.emitter.StreamingBatchScheduler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunFinalizer {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunFinalizer.class);

    private final StreamingBatchScheduler batchScheduler;
    private final WorkflowRunStatusService workflowRunStatusService;

    public WorkflowRunFinalizer(StreamingBatchScheduler batchScheduler,
                                WorkflowRunStatusService workflowRunStatusService) {
        this.batchScheduler = batchScheduler;
        this.workflowRunStatusService = workflowRunStatusService;
    }

    public Map<String, Object> flushAndPersist(WorkflowExecution execution) {
        if (execution == null) {
            return null;
        }
        Map<String, Object> payload = batchScheduler.snapshotForRun(execution.getRunId());
        if (payload != null && execution.getWorkflowRunId() != null) {
            workflowRunStatusService.persistSnapshot(
                execution.getWorkflowRunId(),
                execution.getStatus(),
                payload
            );
        } else {
            logger.debug("No snapshot persisted for run {} (payload={}, workflowRunId={})",
                execution.getRunId(), payload != null, execution.getWorkflowRunId());
        }
        return payload;
    }
}
