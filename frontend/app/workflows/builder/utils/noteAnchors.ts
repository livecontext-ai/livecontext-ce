/**
 * Note anchors: a sticky note may be attached to the node it explains.
 *
 * In the plan the anchor is that node's key (`attachedTo: "core:check_seen"`, the same
 * key edges use). On the canvas it is the anchor's ReactFlow id (`data.noteAttachedTo`),
 * so renaming the node in the editor never detaches its notes: the key is recomputed
 * from the node's current label on export.
 */

import type { Node, NodeChange } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';
import { computeNodeBackendKey } from '../services/edgeStatusService';
import { normalizeLabel } from './labelNormalizer';

type AnyNode = Node<BuilderNodeData>;

/** The plan key of a node (`core:check_seen`), or null for a note or an unlabelled node. */
export function nodeAnchorKey(node: AnyNode): string | null {
  if (nodeRegistry.isNoteNode(node) || !node.data) return null;
  return computeNodeBackendKey(node);
}

/**
 * The node a plan `attachedTo` names, or undefined when none does. Takes the key the canvas
 * saves (`core:check_seen`), and also a bare label (`Check Seen`), the form an agent reads
 * back from get_plan and may paste into the plan import.
 */
export function findNodeByAnchorKey(nodes: AnyNode[], key: string | null | undefined): AnyNode | undefined {
  if (!key || !key.trim()) return undefined;
  const wanted = key.trim().toLowerCase();
  const byKey = nodes.find((n) => nodeAnchorKey(n) === wanted);
  if (byKey) return byKey;
  // A label may itself hold a colon ("Step: Fetch"); a prefixed key that missed normalises to
  // "<prefix>_<label>", which no node label matches, so this never guesses across a prefix.
  const label = normalizeLabel(key);
  return nodes.find((n) => !nodeRegistry.isNoteNode(n) && normalizeLabel(n.data?.label) === label);
}

/** The node a note is attached to on the canvas, or undefined for a free (or orphaned) note. */
export function getNoteAnchor(note: AnyNode, nodes: AnyNode[]): AnyNode | undefined {
  const anchorId = note.data?.noteAttachedTo;
  if (!anchorId) return undefined;
  return nodes.find((n) => n.id === anchorId && !nodeRegistry.isNoteNode(n));
}

/** The notes attached to the node with this ReactFlow id. */
export function getAttachedNotes(nodes: AnyNode[], nodeId: string): AnyNode[] {
  return nodes.filter((n) => nodeRegistry.isNoteNode(n) && n.data?.noteAttachedTo === nodeId);
}

/**
 * The ids to delete when deleting `ids`: those, plus the notes attached to them. A note
 * explains its node, so it goes with it. Every delete path of the canvas goes through here.
 */
export function withAttachedNoteIds(nodes: AnyNode[], ids: Iterable<string>): string[] {
  const result = new Set(ids);
  for (const id of [...result]) {
    for (const note of getAttachedNotes(nodes, id)) result.add(note.id);
  }
  return [...result];
}

/**
 * Extend canvas position changes so a node's notes stay beside it: a node moved moves its
 * notes by the same amount, and the end of its drag ends theirs. A note that is itself part
 * of the changes (selected and dragged together, or placed by the layout) is left as the
 * change says.
 */
export function withAttachedNoteChanges(changes: NodeChange[], nodes: AnyNode[]): NodeChange[] {
  const touched = new Set(changes.map((c) => ('id' in c ? c.id : '')));
  const extra: NodeChange[] = [];
  for (const change of changes) {
    if (change.type !== 'position') continue;
    const moved = nodes.find((n) => n.id === change.id);
    if (!moved || nodeRegistry.isNoteNode(moved)) continue;
    const notes = getAttachedNotes(nodes, change.id).filter((note) => !touched.has(note.id));
    if (!change.position) {
      // The drag-end change carries no position, only `dragging: false`.
      if (change.dragging === false) {
        for (const note of notes) extra.push({ type: 'position', id: note.id, dragging: false });
      }
      continue;
    }
    const dx = change.position.x - moved.position.x;
    const dy = change.position.y - moved.position.y;
    if (dx === 0 && dy === 0) continue;
    for (const note of notes) {
      const position = { x: note.position.x + dx, y: note.position.y + dy };
      extra.push({ type: 'position', id: note.id, position, positionAbsolute: position, dragging: change.dragging });
    }
  }
  return extra.length > 0 ? [...changes, ...extra] : changes;
}
