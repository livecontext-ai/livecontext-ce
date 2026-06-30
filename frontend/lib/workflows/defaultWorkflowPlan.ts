export type EmptyWorkflowPlan = {
  id: string;
  name: string;
  description?: string;
  /**
   * Intentionally empty: the builder's empty-canvas experience (EmptyCanvasChat)
   * proposes the trigger types (Trigger dropdown + AI chat + suggestions), so
   * creation must NOT impose a trigger choice. Validation only requires a
   * trigger at run time (GraphStructureRule) and is skipped on a 0-node canvas.
   */
  triggers: [];
  // Both the backend parser (parseSteps reads "mcps") and the builder loader
  // (isValidPlan requires an "mcps" array) use "mcps" as the steps key - a
  // "steps" key is silently ignored and the plan fails builder validation.
  mcps: [];
  edges: [];
};

export function createEmptyWorkflowPlan(input: {
  id: string;
  name: string;
  description?: string;
}): EmptyWorkflowPlan {
  return {
    id: input.id,
    name: input.name,
    description: input.description || undefined,
    triggers: [],
    mcps: [],
    edges: [],
  };
}
