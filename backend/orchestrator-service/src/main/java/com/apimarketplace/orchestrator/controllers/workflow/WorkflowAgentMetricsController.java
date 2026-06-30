package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentMetricsSummaryDto;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for workflow-level agent metrics.
 * Delegates to agent-service via AgentClient.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowAgentMetricsController {

    private final AgentClient agentClient;
    private final TenantResolver tenantResolver;

    public WorkflowAgentMetricsController(
            AgentClient agentClient,
            TenantResolver tenantResolver) {
        this.agentClient = agentClient;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Get paginated agent executions for a workflow.
     */
    @GetMapping("/{id}/agent-metrics/executions")
    public ResponseEntity<AgentMetricsSummaryDto> getWorkflowAgentExecutions(
            HttpServletRequest httpRequest,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);

        AgentMetricsSummaryDto metrics = agentClient.getMetricsForWorkflow(
            id, tenantId, page, Math.min(size, 100));

        return ResponseEntity.ok(metrics);
    }
}
