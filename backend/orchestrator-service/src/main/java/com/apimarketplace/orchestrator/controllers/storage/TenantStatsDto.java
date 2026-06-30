package com.apimarketplace.orchestrator.controllers.storage;

/**
 * DTO for tenant statistics (quick stats).
 */
public record TenantStatsDto(
    String tenantId,
    long workflowCount,
    long interfaceCount,
    long tableCount,
    long agentCount
) {}
