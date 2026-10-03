import type { PlanGeneratorContext } from './planGeneratorContext';
import { getNodePosition } from './planHelpers';
import { nodeRegistry } from '../registry/nodeRegistry';
import { getNoteAnchor, nodeAnchorKey } from './noteAnchors';

/**
 * Collects all note nodes and adds them to the plan.
 */
export function collectNotes(ctx: PlanGeneratorContext): void {
  const noteNodes = ctx.nodes.filter((node) => nodeRegistry.isNoteNode(node));

  noteNodes.forEach((noteNode) => {
    // Skip notes without text
    if (!noteNode.data.noteText || !noteNode.data.noteText.trim()) {
      return;
    }

    // A note not placed yet is still saved, without a position: the canvas places it
    // next to its anchor (or above the graph) on the next load. Dropping it lost the text.
    const notePosition = getNodePosition(noteNode);

    // Default styling values
    const defaultColor = '#fef3c7';
    const defaultBorder = '#fbbf24';
    const defaultText = '#92400e';
    const defaultWidth = 250;
    const defaultHeight = 100;

    const note: any = {
      id: noteNode.id,
      text: noteNode.data.noteText.trim(),
      color: noteNode.data.noteColor || defaultColor,
      borderColor: noteNode.data.noteBorderColor || defaultBorder,
      textColor: noteNode.data.noteTextColor || defaultText,
      width: noteNode.data.noteWidth || defaultWidth,
      height: noteNode.data.noteHeight || defaultHeight,
    };
    if (notePosition) {
      note.position = notePosition;
    }

    if (noteNode.data.label) {
      note.label = noteNode.data.label;
    }

    // The anchor is written as the node's CURRENT key, so a node renamed in the editor
    // keeps its notes. A deleted anchor leaves a free note.
    const anchor = getNoteAnchor(noteNode, ctx.nodes);
    const anchorKey = anchor ? nodeAnchorKey(anchor) : null;
    if (anchorKey) {
      note.attachedTo = anchorKey;
    }

    if (!ctx.plan.notes) {
      ctx.plan.notes = [];
    }
    ctx.plan.notes.push(note);
  });
}
