/**
 * Compute the set of ancestor agent IDs for a given agent.
 * An ancestor is any agent that directly or transitively calls the target agent.
 * Uses both static config (toolsConfig.agents) and runtime edges (sub-agent call stats).
 */
export function computeAncestorIds(
  targetAgentId: string,
  allAgents: Array<{ id: string; toolsConfig?: any }>,
  runtimeEdges: Array<{ callerId: string; calleeId: string }>,
): Set<string> {
  // Build caller→callees adjacency map from both sources
  const callerToCallees = new Map<string, Set<string>>();
  const addEdge = (callerId: string, calleeId: string) => {
    if (!callerToCallees.has(callerId)) callerToCallees.set(callerId, new Set());
    callerToCallees.get(callerId)!.add(calleeId);
  };

  // Static edges from toolsConfig.agents
  for (const a of allAgents) {
    const tc = a.toolsConfig;
    if (tc && 'agents' in tc && Array.isArray(tc.agents)) {
      for (const calleeId of tc.agents) {
        addEdge(a.id, calleeId);
      }
    }
  }

  // Runtime edges from backend
  for (const edge of runtimeEdges) {
    addEdge(edge.callerId, edge.calleeId);
  }

  // BFS upward: find all ancestors of targetAgentId
  const ancestors = new Set<string>();
  const queue: string[] = [targetAgentId];
  while (queue.length > 0) {
    const currentTarget = queue.shift()!;
    for (const [callerId, callees] of callerToCallees) {
      if (ancestors.has(callerId) || callerId === targetAgentId) continue;
      if (callees.has(currentTarget)) {
        ancestors.add(callerId);
        queue.push(callerId);
      }
    }
  }

  return ancestors;
}
