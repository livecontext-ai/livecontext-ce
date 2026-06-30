package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for tenant statistics.
 * Provides counts of workflows, interfaces, tables (data sources), and agents.
 */
@RestController
@RequestMapping("/api/stats")
public class TenantStatsController {

    private static final Logger logger = LoggerFactory.getLogger(TenantStatsController.class);

    private final WorkflowRepository workflowRepository;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final AgentClient agentClient;

    public TenantStatsController(
            WorkflowRepository workflowRepository,
            InterfaceClient interfaceClient,
            DataSourceClient dataSourceClient,
            AgentClient agentClient) {
        this.workflowRepository = workflowRepository;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.agentClient = agentClient;
    }

    /**
     * Get statistics for the current tenant.
     * Returns counts of workflows, interfaces, tables, and agents.
     *
     * @param tenantId Tenant ID from gateway header
     * @return Tenant statistics
     */
    @GetMapping
    public ResponseEntity<TenantStatsDto> getStats(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        logger.debug("Getting stats for tenant: {} org: {}", tenantId, organizationId);

        // PR30b - scope-aware workflow count so the stats dashboard matches
        // enforcement. Pre-PR30b an org user saw their personal+org workflows
        // conflated in the dashboard count.
        // Post-V261 (2026-05-19): the gateway always injects X-Organization-ID
        // (personal workspaces resolve to the user's default personal org), so
        // the strict-org count covers every workspace; the legacy IS NULL
        // companion was removed.
        long workflowCount = (organizationId != null && !organizationId.isBlank())
                ? workflowRepository.countByOrganizationIdStrict(organizationId)
                : 0L;
        long interfaceCount = interfaceClient.countByTenant(tenantId);
        long tableCount = dataSourceClient.countByTenantId(tenantId);
        long agentCount = agentClient.countByTenantId(tenantId);

        TenantStatsDto stats = new TenantStatsDto(
            tenantId,
            workflowCount,
            interfaceCount,
            tableCount,
            agentCount
        );

        return ResponseEntity.ok(stats);
    }
}
