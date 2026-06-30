package com.apimarketplace.orchestrator.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration centralisee pour l'execution des workflows
 * Remplace les valeurs hardcodees par des proprietes configurables
 */
@Configuration
@ConfigurationProperties(prefix = "workflow.execution")
public class WorkflowExecutionConfig {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutionConfig.class);

    @PostConstruct
    public void logMockConfiguration() {
        logger.info("🔧 [CONFIG] orchestrator.mock.enabled = {} (isEnableMockData() = {})",
                    orchestratorMockEnabled, isEnableMockData());
    }
    
    // Pool de threads
    private int threadPoolSize = 100;
    private int maxConcurrentSteps = 5;
    private int maxConcurrentLevels = 3;
    
    // Timeouts
    private long stepTimeoutMs = 60000;
    private long workflowTimeoutMs = 3600000;
    private long maxExecutionMinutes = 60;
    
    // Retry configuration
    private int maxRetryAttempts = 3;
    private long retryDelayMs = 1000; // 1 seconde
    private double retryBackoffMultiplier = 2.0;
    
    // Loop configuration
    private int defaultMaxIterations = 10;
    private int maxAllowedIterations = 100;
    private String defaultLoopStrategy = "continue-anyway";
    
    // Validation
    private int maxComplexityScore = 1000;
    private int maxStepsCount = 100;
    private int maxEdgesCount = 200;
    
    // Streaming
    @Value("${workflow.streaming.batch-interval-ms:1000}")
    private long streamingBatchIntervalMs = 1000;
    
    // Mock configuration (only for MCP tools via MockToolsGateway)
    @Value("${orchestrator.mock.enabled:true}")
    private boolean orchestratorMockEnabled = true;
    private Boolean enableMockData;

    // Datasource configuration
    private int triggerBatchSize = 50;
    private int maxDatasourceItems = 5000;

    // Itemized execution
    private int maxItemQueueSize = 1000;
    private int itemWorkerPoolSize = 8;
    
    // Getters and setters
    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
    
    public int getMaxConcurrentSteps() { return maxConcurrentSteps; }
    public void setMaxConcurrentSteps(int maxConcurrentSteps) { this.maxConcurrentSteps = maxConcurrentSteps; }
    
    public int getMaxConcurrentLevels() { return maxConcurrentLevels; }
    public void setMaxConcurrentLevels(int maxConcurrentLevels) { this.maxConcurrentLevels = maxConcurrentLevels; }
    
    public long getStepTimeoutMs() { return stepTimeoutMs; }
    public void setStepTimeoutMs(long stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; }
    
    public long getWorkflowTimeoutMs() { return workflowTimeoutMs; }
    public void setWorkflowTimeoutMs(long workflowTimeoutMs) { this.workflowTimeoutMs = workflowTimeoutMs; }
    
    public long getMaxExecutionMinutes() { return maxExecutionMinutes; }
    public void setMaxExecutionMinutes(long maxExecutionMinutes) { this.maxExecutionMinutes = maxExecutionMinutes; }
    
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
    
    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
    
    public double getRetryBackoffMultiplier() { return retryBackoffMultiplier; }
    public void setRetryBackoffMultiplier(double retryBackoffMultiplier) { this.retryBackoffMultiplier = retryBackoffMultiplier; }
    
    public int getDefaultMaxIterations() { return defaultMaxIterations; }
    public void setDefaultMaxIterations(int defaultMaxIterations) { this.defaultMaxIterations = defaultMaxIterations; }
    
    public int getMaxAllowedIterations() { return maxAllowedIterations; }
    public void setMaxAllowedIterations(int maxAllowedIterations) { this.maxAllowedIterations = maxAllowedIterations; }
    
    public String getDefaultLoopStrategy() { return defaultLoopStrategy; }
    public void setDefaultLoopStrategy(String defaultLoopStrategy) { this.defaultLoopStrategy = defaultLoopStrategy; }
    
    public int getMaxComplexityScore() { return maxComplexityScore; }
    public void setMaxComplexityScore(int maxComplexityScore) { this.maxComplexityScore = maxComplexityScore; }
    
    public int getMaxStepsCount() { return maxStepsCount; }
    public void setMaxStepsCount(int maxStepsCount) { this.maxStepsCount = maxStepsCount; }
    
    public int getMaxEdgesCount() { return maxEdgesCount; }
    public void setMaxEdgesCount(int maxEdgesCount) { this.maxEdgesCount = maxEdgesCount; }
    
    public long getStreamingBatchIntervalMs() { return streamingBatchIntervalMs; }
    public void setStreamingBatchIntervalMs(long streamingBatchIntervalMs) { this.streamingBatchIntervalMs = streamingBatchIntervalMs; }
    
    public boolean isEnableMockData() {
        return enableMockData != null ? enableMockData : orchestratorMockEnabled;
    }
    public Boolean getEnableMockData() { return enableMockData; }
    public void setEnableMockData(Boolean enableMockData) { this.enableMockData = enableMockData; }

    public int getTriggerBatchSize() { return triggerBatchSize; }
    public void setTriggerBatchSize(int triggerBatchSize) { this.triggerBatchSize = triggerBatchSize; }

    public int getMaxDatasourceItems() { return maxDatasourceItems; }
    public void setMaxDatasourceItems(int maxDatasourceItems) { this.maxDatasourceItems = maxDatasourceItems; }

    public int getMaxItemQueueSize() { return maxItemQueueSize; }
    public void setMaxItemQueueSize(int maxItemQueueSize) { this.maxItemQueueSize = maxItemQueueSize; }

    public int getItemWorkerPoolSize() { return itemWorkerPoolSize; }
    public void setItemWorkerPoolSize(int itemWorkerPoolSize) { this.itemWorkerPoolSize = itemWorkerPoolSize; }

    /**
     * Retourne la duree maximale d'execution autorisee en millisecondes.
     * Si une valeur en minutes est configuree, elle est prioritaire.
     */
    public long resolveWorkflowTimeoutMs() {
        if (maxExecutionMinutes > 0) {
            return TimeUnit.MINUTES.toMillis(maxExecutionMinutes);
        }
        return workflowTimeoutMs;
    }
}
