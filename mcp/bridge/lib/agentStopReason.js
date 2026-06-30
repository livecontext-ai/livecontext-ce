// GENERATED FILE - do not edit by hand.
// Source of truth: shared/contracts/agent-stop-reason.json
// Re-run: node shared/contracts/scripts/generate-stop-reason.js

export const TerminalCategory = Object.freeze({
  SUCCESS: "SUCCESS",
  PARTIAL: "PARTIAL",
  FAILURE: "FAILURE",
});

export const AgentStopReason = Object.freeze({
  COMPLETED: "COMPLETED",
  MAX_ITERATIONS: "MAX_ITERATIONS",
  TIMEOUT: "TIMEOUT",
  BUDGET_EXHAUSTED: "BUDGET_EXHAUSTED",
  LOOP_DETECTED: "LOOP_DETECTED",
  STOPPED_BY_USER: "STOPPED_BY_USER",
  CANCELLED: "CANCELLED",
  NO_TOOLS: "NO_TOOLS",
  ERROR: "ERROR",
  INACTIVITY_TIMEOUT: "INACTIVITY_TIMEOUT",
});

export const STOP_REASON_META = Object.freeze({
  COMPLETED: Object.freeze({
    name: "COMPLETED",
    terminal: "SUCCESS",
    userVisible: "Completed",
    description: "LLM produced a final response without further tool calls.",
  }),
  MAX_ITERATIONS: Object.freeze({
    name: "MAX_ITERATIONS",
    terminal: "PARTIAL",
    userVisible: "Iteration limit reached",
    description: "Agent reached the configured iteration limit before completing the task.",
  }),
  TIMEOUT: Object.freeze({
    name: "TIMEOUT",
    terminal: "PARTIAL",
    userVisible: "Timed out",
    description: "Agent execution exceeded the configured wall-clock timeout.",
  }),
  BUDGET_EXHAUSTED: Object.freeze({
    name: "BUDGET_EXHAUSTED",
    terminal: "PARTIAL",
    userVisible: "Credit budget exhausted",
    description: "Credit budget was exhausted before the task completed. The metrics map carries `budgetScope` (\"tenant\", \"agent\", \"parent_reservation\", or \"browser\") to indicate which level was hit. `parent_reservation` means an ancestor agent in the caller chain refused the cascade budget reservation required to spawn this sub-agent. `browser` means the per-user browser-agent quota (concurrent sessions or steps/day) was exceeded.",
    scopes: ["tenant","agent","parent_reservation","browser"],
  }),
  LOOP_DETECTED: Object.freeze({
    name: "LOOP_DETECTED",
    terminal: "PARTIAL",
    userVisible: "Tool loop detected",
    description: "The loop detector found N identical or consecutive tool calls and stopped the agent before it could spiral.",
  }),
  STOPPED_BY_USER: Object.freeze({
    name: "STOPPED_BY_USER",
    terminal: "PARTIAL",
    userVisible: "Stopped by user",
    description: "A human explicitly cancelled the run (REST cancel endpoint, UI stop button).",
  }),
  CANCELLED: Object.freeze({
    name: "CANCELLED",
    terminal: "FAILURE",
    userVisible: "Cancelled by system",
    description: "The system cancelled the run (deploy, scale-down, internal supervisor). Distinct from STOPPED_BY_USER.",
  }),
  NO_TOOLS: Object.freeze({
    name: "NO_TOOLS",
    terminal: "FAILURE",
    userVisible: "No tools available",
    description: "Tool discovery returned an empty set; the agent cannot operate.",
  }),
  ERROR: Object.freeze({
    name: "ERROR",
    terminal: "FAILURE",
    userVisible: "Execution error",
    description: "Unrecoverable execution error (provider failure, invalid response, exception).",
  }),
  INACTIVITY_TIMEOUT: Object.freeze({
    name: "INACTIVITY_TIMEOUT",
    terminal: "FAILURE",
    userVisible: "Stopped (inactivity)",
    description: "Agent was force-stopped by the inactivity watchdog after producing no activity (no token, thinking, tool call, or tool result) for the configured inactivity window. Distinct from TIMEOUT, which means the agent was actively working but exceeded its total wall-clock budget: INACTIVITY_TIMEOUT means the agent went silent/stalled (e.g. a hung provider or downstream call) and had to be killed.",
  }),
});

/** Returns ERROR for any unknown input. */
export function parseStopReason(name) {
  if (name && Object.prototype.hasOwnProperty.call(AgentStopReason, name)) {
    return name;
  }
  return AgentStopReason.ERROR;
}

export function isSuccessLike(name) {
  const meta = STOP_REASON_META[name];
  return !!meta && meta.terminal === "SUCCESS";
}

export function isPartial(name) {
  const meta = STOP_REASON_META[name];
  return !!meta && meta.terminal === "PARTIAL";
}

export function isFailure(name) {
  const meta = STOP_REASON_META[name];
  return !!meta && meta.terminal === "FAILURE";
}
