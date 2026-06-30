package com.apimarketplace.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTO for agent metrics summary data.
 * Used by workflow dashboard to display agent execution metrics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMetricsSummaryDto {

    private List<Map<String, Object>> executions;
    private long totalCount;
    private List<Map<String, Object>> toolStats;
    private List<Map<String, Object>> dailyStats;

    public AgentMetricsSummaryDto() {}

    public AgentMetricsSummaryDto(List<Map<String, Object>> executions, long totalCount) {
        this.executions = executions;
        this.totalCount = totalCount;
    }

    public List<Map<String, Object>> getExecutions() { return executions; }
    public void setExecutions(List<Map<String, Object>> executions) { this.executions = executions; }

    public long getTotalCount() { return totalCount; }
    public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

    public List<Map<String, Object>> getToolStats() { return toolStats; }
    public void setToolStats(List<Map<String, Object>> toolStats) { this.toolStats = toolStats; }

    public List<Map<String, Object>> getDailyStats() { return dailyStats; }
    public void setDailyStats(List<Map<String, Object>> dailyStats) { this.dailyStats = dailyStats; }
}
