/**
 * NoteNodeCreator - Handles creation of note nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { parsePosition, NODE_SPACING } from './nodeCreationHelpers';

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
  currentY: number
): { node: Node<BuilderNodeData>; incrementY: boolean } {
  const noteNodeId = note.id || `note-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

  // Parse position
  const { position: notePosition, useSavedPosition } = parsePosition(
    note.position,
    currentX,
    currentY,
    `note ${note.id || noteNodeId}`
  );

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
    },
  };

  return { node: noteNode, incrementY: !useSavedPosition };
}

/**
 * Create all note nodes from plan
 */
export function createNoteNodes(
  notes: NoteFromPlan[],
  startX: number,
  startY: number
): NoteCreationResult {
  const nodes: Node<BuilderNodeData>[] = [];
  let currentY = startY;

  for (const note of notes) {
    const result = createNoteNode(note, startX, currentY);

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
