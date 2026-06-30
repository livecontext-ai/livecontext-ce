package com.apimarketplace.orchestrator.domain.workflow;

import java.util.HashMap;
import java.util.Map;

/**
 * Statistiques d'execution du workflow
 */
public record ExecutionStatistics(
    int totalSteps,
    int completedSteps,
    int failedSteps,
    int skippedSteps,
    int pendingSteps,
    long totalExecutionTime,
    RunStatus overallStatus,
    int currentLevel,
    int maxLevel,
    Map<String, Object> additionalMetrics
) {
    
    public static ExecutionStatistics empty() {
        return new ExecutionStatistics(0, 0, 0, 0, 0, 0L, RunStatus.PENDING, 0, 0, new HashMap<>());
    }
    
    public double getSuccessRate() {
        return totalSteps > 0 ? (double) completedSteps / totalSteps : 0.0;
    }
    
    public boolean isComplete() {
        return overallStatus == RunStatus.COMPLETED || overallStatus == RunStatus.FAILED;
    }
    
    public double progressPercentage() {
        return totalSteps > 0 ? (double) (completedSteps + failedSteps + skippedSteps) / totalSteps * 100.0 : 0.0;
    }
}
