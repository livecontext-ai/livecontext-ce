import type { PlanGeneratorContext } from './planGeneratorContext';
import { getNodePosition } from './planHelpers';
import { nodeRegistry } from '../registry/nodeRegistry';

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

    const notePosition = getNodePosition(noteNode);
    if (!notePosition) return;

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
      position: notePosition,
    };

    if (noteNode.data.label) {
      note.label = noteNode.data.label;
    }

    if (!ctx.plan.notes) {
      ctx.plan.notes = [];
    }
    ctx.plan.notes.push(note);
  });
}
