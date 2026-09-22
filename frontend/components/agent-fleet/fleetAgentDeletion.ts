/**
 * Which agent node on the fleet canvas may be DELETED, as opposed to unlinked.
 *
 * The canvas draws an agent as a node and a sub-agent RELATIONSHIP as an edge, so
 * the two verbs already have two homes: the edge's trash unlinks, the node's trash
 * deletes. That reads correctly on the fleet canvas, where every node is one of
 * the workspace's agents and deleting any of them is the point.
 *
 * It does NOT read correctly on the single-agent canvas in the side panel.
 * `useSingleAgentFleet` walks the sub-agent tree breadth-first and calls
 * `buildAgentGraph` for every agent it reaches, each of which pushes an
 * `agent-<id>` node - so a sub-agent there is indistinguishable from the subject
 * by node id alone. A trash on it would delete someone else's collaborator from
 * the account while the user believed they were detaching it from the agent whose
 * panel they had open. The panel is about ONE agent, so only that one is
 * deletable from it, and a sub-agent keeps the affordance it already had: the
 * edge.
 *
 * Extracted from the canvas so the rule is one testable function rather than a
 * condition spread across the node component and two callbacks.
 */

export interface FleetAgentDeletionContext {
  /** Set when the canvas is showing ONE agent (the side panel's Configuration tab). */
  singleAgentId?: string | null;
  /** A published snapshot is somebody else's agent, rendered read-only. */
  snapshotMode?: boolean;
  /**
   * Whether this member may mutate the current workspace. Edit mode is already
   * forced off for a VIEWER, so this is a second lock, and it is the one action on
   * this canvas that destroys an account-level resource rather than unhooking one.
   * Omitted means "not stated", which is refused: a caller that forgets it gets no
   * delete button rather than an unguarded one.
   */
  canMutate?: boolean;
}

/** The node-id prefix that marks an agent node. `agg-agent-<id>` is an aggregator, not an agent. */
const AGENT_NODE_PREFIX = 'agent-';

export function agentIdFromNodeId(nodeId: string): string | null {
  if (!nodeId.startsWith(AGENT_NODE_PREFIX)) return null;
  const id = nodeId.slice(AGENT_NODE_PREFIX.length);
  return id || null;
}

export function canDeleteAgentNode(nodeId: string, ctx: FleetAgentDeletionContext): boolean {
  const agentId = agentIdFromNodeId(nodeId);
  if (!agentId) return false;
  // Nothing on a snapshot is the reader's to delete. Edit mode is already off
  // there, so this is the second lock rather than the only one.
  if (ctx.snapshotMode) return false;
  if (!ctx.canMutate) return false;
  // Single-agent canvas: the subject only. Every other agent node it draws is a
  // sub-agent, reachable from here but not owned by this view.
  if (ctx.singleAgentId) return agentId === ctx.singleAgentId;
  // Fleet canvas: every node is one of the workspace's agents.
  return true;
}
