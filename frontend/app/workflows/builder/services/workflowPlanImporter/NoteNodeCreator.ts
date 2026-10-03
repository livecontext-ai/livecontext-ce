/**
 * NoteNodeCreator - Handles creation of note nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { parsePosition, NODE_SPACING } from './nodeCreationHelpers';
import { findNodeByAnchorKey } from '../../utils/noteAnchors';

interface NoteFromPlan {
  id?: string;
  label?: string;
  text?: string;
  color?: string;
  borderColor?: string;
  textColor?: string;
  width?: number;
  height?: number;
  position?: { x?: number | string; y?: number | string };
  /** Plan key of the node the note explains, e.g. "core:check_seen". */
  attachedTo?: string;
}

interface NoteCreationResult {
  nodes: Node<BuilderNodeData>[];
  nextY: number;
}

/**
 * Create a single note node
 */
function createNoteNode(
  note: NoteFromPlan,
  currentX: number,
  currentY: number,
  existingNodes: Node<BuilderNodeData>[]
): { node: Node<BuilderNodeData>; incrementY: boolean } {
  const noteNodeId = note.id || `note-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

  // Parse position. A note without one (an agent wrote it) keeps a NaN position: the
  // layout places it next to its anchor node, or above the graph for a free note.
  const { position: notePosition, useSavedPosition } = parsePosition(
    note.position,
    currentX,
    currentY,
    `note ${note.id || noteNodeId}`
  );

  // The anchor is resolved to the ReactFlow node now, while every node exists. An anchor
  // that names no node leaves a free note rather than failing the import.
  const anchor = findNodeByAnchorKey(existingNodes, note.attachedTo);

  // Create note node
  const noteNode: Node<BuilderNodeData> = {
    id: noteNodeId,
    type: 'noteNode',
    position: notePosition,
    positionAbsolute: useSavedPosition ? notePosition : undefined,
    style: {
      width: note.width || 250,
      minHeight: note.height || 100,
    },
    data: {
      id: noteNodeId,
      label: note.label || 'Note',
      kind: 'action',
      noteText: note.text || '',
      noteColor: note.color,
      noteBorderColor: note.borderColor,
      noteTextColor: note.textColor,
      noteWidth: note.width,
      noteHeight: note.height,
      ...(anchor ? { noteAttachedTo: anchor.id } : {}),
    },
  };

  return { node: noteNode, incrementY: !useSavedPosition };
}

/**
 * Create all note nodes from plan. Runs after every other node is created, so
 * `existingNodes` holds every node a note can be attached to.
 */
export function createNoteNodes(
  notes: NoteFromPlan[],
  startX: number,
  startY: number,
  existingNodes: Node<BuilderNodeData>[] = []
): NoteCreationResult {
  const nodes: Node<BuilderNodeData>[] = [];
  let currentY = startY;

  for (const note of notes) {
    const result = createNoteNode(note, startX, currentY, existingNodes);

    nodes.push(result.node);

    if (result.incrementY) {
      currentY += NODE_SPACING.y;
    }
  }

  return {
    nodes,
    nextY: currentY,
  };
}
