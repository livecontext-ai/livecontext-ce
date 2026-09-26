/**
 * Which nodes an agent just added to the canvas, for the camera to frame while it builds.
 *
 * A node whose id survives the sync is the same node, whatever the agent did to its
 * label: a rename or a re-typing is an edit, not an addition. The rest are compared by
 * type + label, because a node the agent created has no stored `graphNodeId` until the
 * canvas saves, so the importer gives it a fresh random id on every plan sync and an id
 * diff alone would call every such node "new" on every action. A multiset, so a second
 * node with the same type and label still counts as added.
 */

export interface BuildFollowNode {
  id: string;
  type?: string | null;
  data?: { label?: unknown } | null;
}

function identity(node: BuildFollowNode): string {
  const label = typeof node.data?.label === 'string' ? node.data.label.trim().toLowerCase() : '';
  return `${node.type ?? ''}\u0000${label}`;
}

/** @returns the ids, in `next`, of the nodes `previous` did not have. */
export function findAddedNodeIds(previous: BuildFollowNode[], next: BuildFollowNode[]): string[] {
  const nextIds = new Set(next.map((node) => node.id));
  const previousIds = new Set(previous.map((node) => node.id));
  // Only the previous nodes whose id did NOT survive can be matched by identity: a
  // survivor is already accounted for, and letting it also absorb an identity would
  // hide a genuinely new node that happens to share its type and label.
  const remaining = new Map<string, number>();
  for (const node of previous) {
    if (nextIds.has(node.id)) continue;
    const key = identity(node);
    remaining.set(key, (remaining.get(key) ?? 0) + 1);
  }
  const added: string[] = [];
  for (const node of next) {
    if (previousIds.has(node.id)) continue;
    const key = identity(node);
    const left = remaining.get(key) ?? 0;
    if (left > 0) remaining.set(key, left - 1);
    else added.push(node.id);
  }
  return added;
}
