package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution context for workflow V2.
 * Manages execution state and data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowExecutionContext {
    
    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutionContext.class);
    
    private final String workflowId;
    private final String runId;
    private final String tenantId;
    private final Instant startTime;
    private final Map<String, Object> dataContext;
    private final Map<String, Object> stepOutputs;
    private final Map<String, NodeStatus> stepStatuses;
    private final Map<String, Object> globalVariables;
    private final Map<String, Object> loopStates;
    
    // Iteration management for while loops
    private int currentIteration = 0;
    private int currentItemIndex = 0;
    
    @JsonCreator
    public WorkflowExecutionContext(@JsonProperty("workflowId") String workflowId,
                                    @JsonProperty("runId") String runId,
                                    @JsonProperty("tenantId") String tenantId) {
        this.workflowId = workflowId;
        this.runId = runId;
        this.tenantId = tenantId;
        this.startTime = Instant.now();
        this.dataContext = new ConcurrentHashMap<>();
        this.stepOutputs = new ConcurrentHashMap<>();
        this.stepStatuses = new ConcurrentHashMap<>();
        this.globalVariables = new ConcurrentHashMap<>();
        this.loopStates = new ConcurrentHashMap<>();
        this.currentIteration = 0;
        this.currentItemIndex = 0;
    }
    
    // Default constructor for Jackson
    public WorkflowExecutionContext() {
        this.workflowId = null;
        this.runId = null;
        this.tenantId = null;
        this.startTime = Instant.now();
        this.dataContext = new ConcurrentHashMap<>();
        this.stepOutputs = new ConcurrentHashMap<>();
        this.stepStatuses = new ConcurrentHashMap<>();
        this.globalVariables = new ConcurrentHashMap<>();
        this.loopStates = new ConcurrentHashMap<>();
    }
    
    // Getters
    public String getWorkflowId() { return workflowId; }
    public String getRunId() { return runId; }
    public String getTenantId() { return tenantId; }
    public Instant getStartTime() { return startTime; }
    public Map<String, Object> getDataContext() { return dataContext; }
    public Map<String, Object> getStepOutputs() { return stepOutputs; }
    public Map<String, NodeStatus> getStepStatuses() { return stepStatuses; }
    public Map<String, Object> getGlobalVariables() { return globalVariables; }
    public Map<String, Object> getLoopStates() { return loopStates; }
    
    /**
     * Write a data item.
     *
     * <p><strong>Null semantics contract:</strong> writing {@code null} removes the key
     * rather than throwing. The backing map is a {@link ConcurrentHashMap} (rejects null
     * values by contract), but callers - notably
     * {@code V2TemplateAdapter.convertToV1Context} - iterate heterogeneous trigger / step
     * payloads where a field may legitimately be null (event-driven datasource triggers
     * emit nullable {@code offset}, {@code nextOffset}, {@code previous_row} on
     * {@code row_created}, etc.). Treating a null write as remove() keeps
     * {@code getDataItem(key)} returning {@code null} consistently whether the key was
     * never written or written with a null value.</p>
     *
     * <p>Do not "fix" this back to a direct put - it will crash the downstream set node
     * on any event-driven fire. See {@code WorkflowExecutionContextTest#nullWriteShouldBeTreatedAsRemove}.</p>
     */
    public void setDataItem(String dataId, Object data) {
        if (data == null) {
            dataContext.remove(dataId);
            return;
        }
        dataContext.put(dataId, data);
    }
    
    public Object getDataItem(String dataId) {
        return dataContext.get(dataId);
    }
    
    /**
     * Write a step output. Null writes are treated as remove() - see
     * {@link #setDataItem(String, Object)} for the full null-semantics contract.
     */
    public void setStepOutput(String stepAlias, Object output) {
        if (output == null) {
            stepOutputs.remove(stepAlias);
            return;
        }
        stepOutputs.put(stepAlias, output);
    }
    
    public Object getStepOutput(String stepAlias) {
        return stepOutputs.get(stepAlias);
    }
    
    public void setStepStatus(String stepAlias, NodeStatus status) {
        stepStatuses.put(stepAlias, status);
    }

    public NodeStatus getStepStatus(String stepAlias) {
        return stepStatuses.getOrDefault(stepAlias, NodeStatus.PENDING);
    }

    public boolean isStepCompleted(String stepAlias) {
        return NodeStatus.COMPLETED.equals(getStepStatus(stepAlias));
    }

    public boolean isStepFailed(String stepAlias) {
        return NodeStatus.FAILED.equals(getStepStatus(stepAlias));
    }
    
    // Persistence-related methods
    @JsonIgnore
    private final Map<String, String> stepToolIds = new ConcurrentHashMap<>();
    @JsonIgnore
    private final Map<String, Map<String, Object>> stepParams = new ConcurrentHashMap<>();
    @JsonIgnore
    private final Map<String, Instant> stepStartTimes = new ConcurrentHashMap<>();

    public void setStepToolId(String stepAlias, String toolId) {
        stepToolIds.put(stepAlias, toolId);
    }

    public String getStepToolId(String stepAlias) {
        return stepToolIds.get(stepAlias);
    }

    public void setStepParams(String stepAlias, Map<String, Object> params) {
        stepParams.put(stepAlias, params);
    }

    public Map<String, Object> getStepParams(String stepAlias) {
        return stepParams.get(stepAlias);
    }
    
    public void setStepStartTime(String stepAlias, Instant startTime) {
        stepStartTimes.put(stepAlias, startTime);
    }
    
    public Instant getStepStartTime(String stepAlias) {
        return stepStartTimes.get(stepAlias);
    }
    
    // Global variable methods
    public void setGlobalVariable(String name, Object value) {
        globalVariables.put(name, value);
    }
    
    public Object getGlobalVariable(String name) {
        return globalVariables.get(name);
    }
    
    public void incrementGlobalVariable(String name) {
        Object current = globalVariables.get(name);
        if (current instanceof Number) {
            globalVariables.put(name, ((Number) current).intValue() + 1);
        } else {
            globalVariables.put(name, 1);
        }
    }
    
    public void setGlobalVariable(String name, int value) {
        globalVariables.put(name, value);
    }
    
    public int getGlobalVariableAsInt(String name) {
        Object value = globalVariables.get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
    
    // Loop state methods
    public void setLoopState(String loopKey, Object state) {
        loopStates.put(loopKey, state);
    }
    
    public Object getLoopState(String loopKey) {
        return loopStates.get(loopKey);
    }
    
    public void incrementLoopIteration(String loopKey) {
        int current = getLoopIteration(loopKey);
        setLoopState(loopKey + "_iteration", current + 1);
    }
    
    public int getLoopIteration(String loopKey) {
        Object value = loopStates.get(loopKey + "_iteration");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
    
    public void setLoopHash(String loopKey, String hash) {
        setLoopState(loopKey + "_hash", hash);
    }
    
    public String getLoopHash(String loopKey) {
        Object value = loopStates.get(loopKey + "_hash");
        return value != null ? value.toString() : null;
    }
    
    public void addLoopHash(String loopKey, String hash) {
        @SuppressWarnings("unchecked")
        Set<String> hashes = (Set<String>) loopStates.get(loopKey + "_hashes");
        if (hashes == null) {
            hashes = new java.util.HashSet<>();
            loopStates.put(loopKey + "_hashes", hashes);
        }
        hashes.add(hash);
    }
    
    @SuppressWarnings("unchecked")
    public boolean hasSeenHash(String loopKey, String hash) {
        Set<String> hashes = (Set<String>) loopStates.get(loopKey + "_hashes");
        return hashes != null && hashes.contains(hash);
    }
    
    /**
     * Cleans all context data to free memory.
     */
    public void cleanup() {
        try {
            // Clear public maps
            dataContext.clear();
            stepOutputs.clear();
            stepStatuses.clear();
            globalVariables.clear();
            loopStates.clear();

            // Clear private maps
            stepToolIds.clear();
            stepParams.clear();
            stepStartTimes.clear();

            logger.debug("WorkflowExecutionContext cleaned for runId: {}", runId);
        } catch (Exception e) {
            logger.warn("Error cleaning execution context: {}", e.getMessage());
        }
    }
    
    /**
     * Checks if the context is empty (for debugging).
     */
    @JsonIgnore
    public boolean isEmpty() {
        return dataContext.isEmpty() && 
               stepOutputs.isEmpty() && 
               stepStatuses.isEmpty() &&
               stepToolIds.isEmpty() && 
               stepParams.isEmpty() && 
               stepStartTimes.isEmpty();
    }
    
    /**
     * Gets the total size of stored data.
     */
    @JsonIgnore
    public int getDataSize() {
        return dataContext.size() + 
               stepOutputs.size() + 
               stepStatuses.size() +
               stepToolIds.size() + 
               stepParams.size() + 
               stepStartTimes.size();
    }
    
    // Iteration management methods
    
    public int getCurrentIteration() {
        return currentIteration;
    }
    
    public void setCurrentIteration(int currentIteration) {
        this.currentIteration = currentIteration;
    }
    
    public void incrementIteration() {
        this.currentIteration++;
    }
    
    public void resetIteration() {
        this.currentIteration = 0;
    }
    
    public int getCurrentItemIndex() {
        return currentItemIndex;
    }
    
    public void setCurrentItemIndex(int currentItemIndex) {
        this.currentItemIndex = currentItemIndex;
    }
    
    public void incrementItemIndex() {
        this.currentItemIndex++;
    }
    
    public void resetItemIndex() {
        this.currentItemIndex = 0;
    }
    
    // ===== DAG ARCHITECTURE METHODS =====

    /**
     * Sets global data (for DAG architecture).
     */
    public void setGlobalData(Map<String, Object> globalData) {
        if (globalData != null) {
            globalVariables.putAll(globalData);
        }
    }

    /**
     * Returns a copy of global data (for DAG architecture).
     */
    public Map<String, Object> getGlobalData() {
        return new java.util.HashMap<>(globalVariables);
    }
}
