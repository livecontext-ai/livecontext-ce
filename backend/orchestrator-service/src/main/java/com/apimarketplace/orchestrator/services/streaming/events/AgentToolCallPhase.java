package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Phases of agent tool call execution.
 */
public enum AgentToolCallPhase {
    /**
     * Tool call requested by the LLM (before execution).
     */
    CALLING,

    /**
     * Tool execution completed successfully.
     */
    COMPLETED,

    /**
     * Tool execution failed.
     */
    FAILED
}
