package com.apimarketplace.orchestrator.services.streaming.state;

/**
 * Accessor interface for RunStateStore internal state.
 * Used by StatePrePopulator and EventApplier to access run states
 * without exposing the full RunStateStore implementation.
 */
public interface RunStateStoreAccessor {

    /**
     * Get or create a RunState for the given runId.
     *
     * @param runId The workflow run ID
     * @return The RunState for this run (created if not exists)
     */
    RunState getOrCreateRunState(String runId);
}
