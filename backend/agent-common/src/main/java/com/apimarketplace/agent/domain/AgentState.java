package com.apimarketplace.agent.domain;

/**
 * Explicit state machine for agent execution lifecycle.
 * Provides clear visibility into the current execution phase.
 */
public enum AgentState {
    /**
     * Initial state - agent is being initialized
     */
    INITIALIZING("Agent is initializing"),

    /**
     * Agent is discovering relevant tools
     */
    DISCOVERING_TOOLS("Discovering relevant tools"),

    /**
     * Agent is waiting for LLM response
     */
    CALLING_LLM("Calling LLM provider"),

    /**
     * Agent is executing tool calls
     */
    EXECUTING_TOOLS("Executing tool calls"),

    /**
     * Agent is processing tool results
     */
    PROCESSING_RESULTS("Processing tool results"),

    /**
     * Agent has completed execution
     */
    COMPLETED("Execution completed"),

    /**
     * Agent execution failed
     */
    FAILED("Execution failed"),

    /**
     * Agent execution was cancelled
     */
    CANCELLED("Execution cancelled");

    private final String description;

    AgentState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if this is a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Check if this state allows tool execution
     */
    public boolean allowsToolExecution() {
        return this == EXECUTING_TOOLS;
    }

    /**
     * Check if this state allows LLM calls
     */
    public boolean allowsLLMCall() {
        return this == CALLING_LLM;
    }
}
