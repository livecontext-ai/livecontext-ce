package com.apimarketplace.agent.domain;

/**
 * Reason why an agent execution terminated.
 *
 * <p><strong>GENERATED FILE - do not edit by hand.</strong>
 * Source of truth: {@code shared/contracts/agent-stop-reason.json}.
 * Re-run {@code node shared/contracts/scripts/generate-stop-reason.js} after editing the JSON.</p>
 *
 * <p>Each value carries a {@link TerminalCategory}:</p>
 * <ul>
 *   <li><b>success</b> - Agent finished as expected. Counts as a successful run.</li>
 *   <li><b>partial</b> - Agent terminated cleanly but did not complete the task as planned. Output is usable but truncated/early.</li>
 *   <li><b>failure</b> - Agent did not produce usable output. Counts as a failed run.</li>
 * </ul>
 */
public enum AgentStopReason {

    /** LLM produced a final response without further tool calls. */
    COMPLETED(TerminalCategory.SUCCESS, "Completed", "LLM produced a final response without further tool calls."),

    /** Agent reached the configured iteration limit before completing the task. */
    MAX_ITERATIONS(TerminalCategory.PARTIAL, "Iteration limit reached", "Agent reached the configured iteration limit before completing the task."),

    /** Agent execution exceeded the configured wall-clock timeout. */
    TIMEOUT(TerminalCategory.PARTIAL, "Timed out", "Agent execution exceeded the configured wall-clock timeout."),

    /** Credit budget was exhausted before the task completed. The metrics map carries `budgetScope` ("tenant", "agent", "parent_reservation", or "browser") to indicate which level was hit. `parent_reservation` means an ancestor agent in the caller chain refused the cascade budget reservation required to spawn this sub-agent. `browser` means the per-user browser-agent quota (concurrent sessions or steps/day) was exceeded. */
    BUDGET_EXHAUSTED(TerminalCategory.PARTIAL, "Credit budget exhausted", "Credit budget was exhausted before the task completed. The metrics map carries `budgetScope` (\"tenant\", \"agent\", \"parent_reservation\", or \"browser\") to indicate which level was hit. `parent_reservation` means an ancestor agent in the caller chain refused the cascade budget reservation required to spawn this sub-agent. `browser` means the per-user browser-agent quota (concurrent sessions or steps/day) was exceeded."),

    /** The loop detector found N identical or consecutive tool calls and stopped the agent before it could spiral. */
    LOOP_DETECTED(TerminalCategory.PARTIAL, "Tool loop detected", "The loop detector found N identical or consecutive tool calls and stopped the agent before it could spiral."),

    /** A human explicitly cancelled the run (REST cancel endpoint, UI stop button). */
    STOPPED_BY_USER(TerminalCategory.PARTIAL, "Stopped by user", "A human explicitly cancelled the run (REST cancel endpoint, UI stop button)."),

    /** The system cancelled the run (deploy, scale-down, internal supervisor). Distinct from STOPPED_BY_USER. */
    CANCELLED(TerminalCategory.FAILURE, "Cancelled by system", "The system cancelled the run (deploy, scale-down, internal supervisor). Distinct from STOPPED_BY_USER."),

    /** Tool discovery returned an empty set; the agent cannot operate. */
    NO_TOOLS(TerminalCategory.FAILURE, "No tools available", "Tool discovery returned an empty set; the agent cannot operate."),

    /** Unrecoverable execution error (provider failure, invalid response, exception). */
    ERROR(TerminalCategory.FAILURE, "Execution error", "Unrecoverable execution error (provider failure, invalid response, exception)."),

    /** Agent was force-stopped by the inactivity watchdog after producing no activity (no token, thinking, tool call, or tool result) for the configured inactivity window. Distinct from TIMEOUT, which means the agent was actively working but exceeded its total wall-clock budget: INACTIVITY_TIMEOUT means the agent went silent/stalled (e.g. a hung provider or downstream call) and had to be killed. */
    INACTIVITY_TIMEOUT(TerminalCategory.FAILURE, "Stopped (inactivity)", "Agent was force-stopped by the inactivity watchdog after producing no activity (no token, thinking, tool call, or tool result) for the configured inactivity window. Distinct from TIMEOUT, which means the agent was actively working but exceeded its total wall-clock budget: INACTIVITY_TIMEOUT means the agent went silent/stalled (e.g. a hung provider or downstream call) and had to be killed.");

    private final TerminalCategory terminal;
    private final String userVisible;
    private final String description;

    AgentStopReason(TerminalCategory terminal, String userVisible, String description) {
        this.terminal = terminal;
        this.userVisible = userVisible;
        this.description = description;
    }

    public TerminalCategory terminal() { return terminal; }
    public String userVisible() { return userVisible; }
    public String getDescription() { return description; }

    /** True for COMPLETED, MAX_ITERATIONS, BUDGET_EXHAUSTED, LOOP_DETECTED, etc. - runs that produced usable output. */
    public boolean isSuccessLike() { return terminal == TerminalCategory.SUCCESS; }

    /** True when output is potentially incomplete (max iter, timeout, budget, loop, user stop). */
    public boolean isPartial() { return terminal == TerminalCategory.PARTIAL; }

    public boolean isFailure() { return terminal == TerminalCategory.FAILURE; }

    /**
     * Lenient parser: returns ERROR if the input does not match any enum value
     * (instead of throwing). Use this when the value comes from an external source
     * such as the bridge HTTP response.
     */
    public static AgentStopReason valueOfOrError(String name) {
        if (name == null) return ERROR;
        try {
            return AgentStopReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ERROR;
        }
    }

    /** Three-bucket terminal classification driven by the contract. */
    public enum TerminalCategory { SUCCESS, PARTIAL, FAILURE }
}
