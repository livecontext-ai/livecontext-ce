/**
 * Sticky notes attached to the node they explain (`attachedTo` in the plan).
 *
 * An agent writes a note with no position and an anchor; the canvas must place it next to
 * that node WITHOUT moving the user's nodes, keep it beside the node through a layout, a
 * drag and a rename, remove it with the node, and frame it with the node on a focus.
 */
import { describe, expect, it } from 'vitest';
import type { Node, NodeChange } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';
import { applyDagreLayout, getNodeDimensions, layoutConfigForDirection, NOTE_GAP_PX, placeNotes, unplaceAttachedNotes } from '../services/LayoutService';
import { generateWorkflowPlan } from '../utils/workflowPlanGenerator';
import { findNodeByAnchorKey, withAttachedNoteChanges, withAttachedNoteIds } from '../utils/noteAnchors';
import { resolveFollowFrame } from '../services/runFollowBounds';

type Position = { x: number; y: number };
type AnyNode = Node<BuilderNodeData>;

const START: Position = { x: 0, y: 200 };
const SHAPE: Position = { x: 400, y: 200 };

function plan(notes: unknown[], direction: 'horizontal' | 'vertical' = 'horizontal') {
  return {
    name: 'Notes',
    layoutDirection: direction,
    triggers: [{ id: 'start', label: 'Start', type: 'manual', position: START }],
    cores: [{ id: 'core:shape', label: 'Shape', type: 'transform', position: SHAPE, transform: { mappings: [] } }],
    mcps: [],
    edges: [{ from: 'trigger:start', to: 'core:shape' }],
    notes,
  };
}

async function importNodes(p: unknown): Promise<AnyNode[]> {
  const result = await WorkflowPlanImporter.importPlan(JSON.stringify(p), [], { fallbackDirection: 'horizontal' });
  expect(result.success, result.error).toBe(true);
  return result.nodes;
}

const byLabel = (nodes: AnyNode[], label: string) => nodes.find((n) => n.data.label === label)!;

function overlaps(a: AnyNode, b: AnyNode): boolean {
  const da = getNodeDimensions(a), db = getNodeDimensions(b);
  return a.position.x < b.position.x + db.width && b.position.x < a.position.x + da.width
    && a.position.y < b.position.y + db.height && b.position.y < a.position.y + da.height;
}

describe('importing a note an agent attached to a node', () => {
  it('places it above its node and leaves every positioned node where the user put it', async () => {
    const nodes = await importNodes(plan([{ id: 'note:why', label: 'Why', text: 'Because.', attachedTo: 'core:shape' }]));

    const shape = byLabel(nodes, 'Shape');
    const note = byLabel(nodes, 'Why');
    expect(byLabel(nodes, 'Start').position).toEqual(START);
    expect(shape.position).toEqual(SHAPE);
    expect(note.data.noteAttachedTo).toBe(shape.id);
    expect(note.position.x).toBe(SHAPE.x);
    expect(note.position.y + getNodeDimensions(note).height + NOTE_GAP_PX).toBeLessThanOrEqual(SHAPE.y);
  });

  it('places it to the right of its node on a top-to-bottom canvas', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Why', text: 'x', attachedTo: 'core:shape' }], 'vertical'));

    const shape = byLabel(nodes, 'Shape');
    const note = byLabel(nodes, 'Why');
    expect(note.position.y).toBe(shape.position.y);
    expect(note.position.x).toBeGreaterThanOrEqual(shape.position.x + getNodeDimensions(shape, false, 'vertical').width);
  });

  it('puts a free note without a position above the whole graph', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Overview', text: 'What this does.' }]));

    const note = byLabel(nodes, 'Overview');
    expect(note.data.noteAttachedTo).toBeUndefined();
    expect(note.position.y + getNodeDimensions(note).height).toBeLessThan(Math.min(START.y, SHAPE.y));
  });

  it('treats an anchor naming no node as a free note instead of failing the import', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Lost', text: 'x', attachedTo: 'core:gone' }]));

    expect(byLabel(nodes, 'Lost').data.noteAttachedTo).toBeUndefined();
    expect(Number.isFinite(byLabel(nodes, 'Lost').position.y)).toBe(true);
  });

  it('slides a note further out when the spot above its node is taken', async () => {
    const p = plan([{ id: 'n', label: 'Why', text: 'x', attachedTo: 'core:shape' }]);
    // A second chain sits right above Shape, where the note would go.
    p.triggers.push({ id: 'other', label: 'Other', type: 'manual', position: { x: SHAPE.x, y: SHAPE.y - 120 } });
    const nodes = await importNodes(p);

    const note = byLabel(nodes, 'Why');
    expect(overlaps(note, byLabel(nodes, 'Other'))).toBe(false);
    expect(overlaps(note, byLabel(nodes, 'Shape'))).toBe(false);
    expect(note.position.y).toBeLessThan(SHAPE.y - 120);
  });
});

describe('an agent adding a note to a canvas the user arranged', () => {
  it('places the new note and leaves a note the user parked beside a node exactly where it was', async () => {
    const parked = { x: SHAPE.x + 210, y: SHAPE.y }; // 10px right of Shape, inside the slide margin
    const nodes = await importNodes(plan([
      { id: 'mine', label: 'Mine', text: 'x', attachedTo: 'core:shape', position: parked },
      { id: 'new', label: 'New', text: 'y', attachedTo: 'core:shape' },
    ]));

    expect(byLabel(nodes, 'Mine').position).toEqual(parked);
    expect(byLabel(nodes, 'Shape').position).toEqual(SHAPE);
    expect(overlaps(byLabel(nodes, 'New'), byLabel(nodes, 'Shape'))).toBe(false);
    expect(overlaps(byLabel(nodes, 'New'), byLabel(nodes, 'Mine'))).toBe(false);
  });

  it('keeps the new note off a user note listed AFTER it in the plan', async () => {
    // Right where the new note would go: above Shape.
    const parked = { x: SHAPE.x, y: SHAPE.y - 150 };
    const nodes = await importNodes(plan([
      { id: 'new', label: 'New', text: 'y', attachedTo: 'core:shape' },
      { id: 'mine', label: 'Mine', text: 'x', attachedTo: 'core:shape', position: parked },
    ]));

    expect(byLabel(nodes, 'Mine').position).toEqual(parked);
    expect(overlaps(byLabel(nodes, 'New'), byLabel(nodes, 'Mine'))).toBe(false);
    expect(overlaps(byLabel(nodes, 'New'), byLabel(nodes, 'Shape'))).toBe(false);
  });
});

describe('saving notes', () => {
  it('writes the anchor as the node CURRENT key, so a rename in the editor keeps it', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Why', text: 'x', attachedTo: 'core:shape' }]));
    const renamed = nodes.map((n) => (n.data.label === 'Shape' ? { ...n, data: { ...n.data, label: 'Reshape' } } : n));

    const saved = generateWorkflowPlan(renamed, []).notes!;

    expect(saved).toHaveLength(1);
    expect(saved[0].attachedTo).toBe('core:reshape');
  });

  it('keeps a note that has no position yet instead of dropping its text', () => {
    const note = {
      id: 'n', type: 'noteNode', position: { x: NaN, y: NaN },
      data: { id: 'n', label: 'Why', kind: 'action', noteText: 'Kept' },
    } as AnyNode;

    const saved = generateWorkflowPlan([note], []).notes!;

    expect(saved).toHaveLength(1);
    expect(saved[0].text).toBe('Kept');
    expect(saved[0].position).toBeUndefined();
  });

  it('drops the anchor of a note whose node was deleted', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Why', text: 'x', attachedTo: 'core:shape' }]));

    const saved = generateWorkflowPlan(nodes.filter((n) => n.data.label !== 'Shape'), []).notes!;

    expect(saved[0].attachedTo).toBeUndefined();
  });
});

describe('switching the reading direction', () => {
  /** Start -> Shape laid out left to right, with "Why" placed above Shape as an LR layout puts it. */
  const lrPlan = () => ({
    ...plan([
      { id: 'why', label: 'Why', text: 'x', attachedTo: 'core:shape', position: { x: SHAPE.x, y: SHAPE.y - 160 } },
      { id: 'free', label: 'Overview', text: 'y', position: { x: -400, y: -400 } },
    ]),
    layoutDirection: 'horizontal',
  });

  function expectRightOfItsNode(nodes: AnyNode[]) {
    const shape = byLabel(nodes, 'Shape');
    const note = byLabel(nodes, 'Why');
    expect(note.position.y).toBe(shape.position.y);
    expect(note.position.x).toBeGreaterThanOrEqual(shape.position.x + getNodeDimensions(shape, false, 'vertical').width);
    for (const n of nodes.filter((m) => m.type !== 'noteNode')) expect(overlaps(note, n)).toBe(false);
  }

  it('opening a left-to-right workflow top to bottom puts an attached note to the right of its node', async () => {
    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(lrPlan()), [], {
      fallbackDirection: 'vertical',
      forcedDirection: 'vertical',
    });

    expect(result.layoutDirection).toBe('vertical');
    expect(result.laidOutFromScratch).toBe(true);
    expectRightOfItsNode(result.nodes);
  });

  it('the canvas toggle to top to bottom puts an attached note to the right of its node, and keeps a free note', async () => {
    const nodes = await importNodes(lrPlan());
    const edges = [{ id: 'e', source: byLabel(nodes, 'Start').id, target: byLabel(nodes, 'Shape').id }];

    const turned = applyDagreLayout(unplaceAttachedNotes(nodes), edges, layoutConfigForDirection('vertical'));

    expectRightOfItsNode(turned);
    expect(byLabel(turned, 'Overview').position).toEqual({ x: -400, y: -400 });
  });

  it('a re-layout in the SAME direction keeps the offset the user gave an attached note', async () => {
    // One node without a position (an agent added it) re-lays the graph out, in the same direction.
    const p = lrPlan();
    p.cores.push({ id: 'core:extra', label: 'Extra', type: 'transform', transform: { mappings: [] } } as never);
    p.edges.push({ from: 'core:shape', to: 'core:extra' });
    const before = await importNodes({ ...lrPlan(), notes: lrPlan().notes });
    const offset = {
      x: byLabel(before, 'Why').position.x - byLabel(before, 'Shape').position.x,
      y: byLabel(before, 'Why').position.y - byLabel(before, 'Shape').position.y,
    };

    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(p), [], { fallbackDirection: 'horizontal' });

    expect(result.laidOutFromScratch).toBe(true);
    const shape = byLabel(result.nodes, 'Shape');
    const note = byLabel(result.nodes, 'Why');
    expect({ x: note.position.x - shape.position.x, y: note.position.y - shape.position.y }).toEqual(offset);
  });

  it('opening top to bottom keeps a free note where the user put it', async () => {
    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(lrPlan()), [], {
      fallbackDirection: 'vertical',
      forcedDirection: 'vertical',
    });

    expect(byLabel(result.nodes, 'Overview').position).toEqual({ x: -400, y: -400 });
  });

  it('turning back to left to right puts it above its node again', async () => {
    const nodes = await importNodes(lrPlan());
    const edges = [{ id: 'e', source: byLabel(nodes, 'Start').id, target: byLabel(nodes, 'Shape').id }];
    const vertical = applyDagreLayout(unplaceAttachedNotes(nodes), edges, layoutConfigForDirection('vertical'));

    const back = applyDagreLayout(unplaceAttachedNotes(vertical), edges, layoutConfigForDirection('horizontal'));

    const shape = byLabel(back, 'Shape');
    const note = byLabel(back, 'Why');
    expect(note.position.x).toBe(shape.position.x);
    expect(note.position.y + getNodeDimensions(note).height).toBeLessThan(shape.position.y);
  });
});

describe('layout with notes', () => {
  it('lays the flow out exactly as if the notes were not there', async () => {
    const nodes = await importNodes(plan([
      { id: 'a', label: 'Why', text: 'x', attachedTo: 'core:shape' },
      { id: 'b', label: 'Overview', text: 'y' },
    ]));
    const flowOnly = nodes.filter((n) => n.type !== 'noteNode');

    const withNotes = applyDagreLayout(nodes, []);
    const without = applyDagreLayout(flowOnly, []);

    for (const n of without) {
      expect(withNotes.find((m) => m.id === n.id)!.position).toEqual(n.position);
    }
  });

  it('moves a placed attached note by the amount its node moved', () => {
    const shape = { id: 's', type: 'flowNode', position: { x: 500, y: 300 }, data: { id: 's', label: 'Shape', kind: 'transform' } } as AnyNode;
    const note = {
      id: 'n', type: 'noteNode', position: { x: 410, y: 0 },
      data: { id: 'n', label: 'Why', kind: 'action', noteText: 'x', noteAttachedTo: 's' },
    } as AnyNode;

    const placed = placeNotes([shape, note], 'horizontal', new Map([['s', { x: 400, y: 200 }]]));

    expect(placed.find((n) => n.id === 'n')!.position).toEqual({ x: 510, y: 100 });
  });

  it('leaves a free note the user placed where it is', () => {
    const note = {
      id: 'n', type: 'noteNode', position: { x: -50, y: -60 },
      data: { id: 'n', label: 'Overview', kind: 'action', noteText: 'x' },
    } as AnyNode;

    expect(placeNotes([note], 'horizontal')[0].position).toEqual({ x: -50, y: -60 });
  });

  it('slides a placed free note off a node a re-layout put under it', () => {
    const shape = { id: 's', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 's', label: 'Shape', kind: 'transform' } } as AnyNode;
    const note = {
      id: 'n', type: 'noteNode', position: { x: 10, y: 10 },
      data: { id: 'n', label: 'Overview', kind: 'action', noteText: 'x' },
    } as AnyNode;

    const placed = placeNotes([shape, note], 'horizontal');

    expect(overlaps(placed[1], shape)).toBe(false);
    expect(placed[1].position.x).toBe(10);
  });

  it('keeps an attached note clear of the graph when the layout turns to top-to-bottom', async () => {
    const nodes = await importNodes(plan([{ id: 'n', label: 'Why', text: 'x', attachedTo: 'core:shape' }]));

    const turned = applyDagreLayout(nodes, [{ id: 'e', source: byLabel(nodes, 'Start').id, target: byLabel(nodes, 'Shape').id }], {
      rankdir: 'TB',
    });

    const note = byLabel(turned, 'Why');
    for (const n of turned.filter((m) => m.type !== 'noteNode')) expect(overlaps(note, n)).toBe(false);
  });

  it('reserves more height for a CJK note than for a latin one of the same length', () => {
    const note = (text: string) => ({
      id: 'n', type: 'noteNode', position: { x: 0, y: 0 },
      data: { id: 'n', label: 'N', kind: 'action', noteText: text },
    }) as AnyNode;

    expect(getNodeDimensions(note('便'.repeat(120))).height)
      .toBeGreaterThan(getNodeDimensions(note('x'.repeat(120))).height);
  });
});

describe('resolving a plan anchor', () => {
  const nodes = [
    { id: 's', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 's', label: 'Check Seen', kind: 'transform' } },
  ] as AnyNode[];

  it('takes the node label an agent reads back from get_plan, as well as the key', () => {
    expect(findNodeByAnchorKey(nodes, 'Check Seen')?.id).toBe('s');
  });

  it('resolves a label that itself holds a colon', () => {
    const withColon = [{ ...nodes[0], data: { ...nodes[0].data, label: 'Step: Fetch' } }] as AnyNode[];
    expect(findNodeByAnchorKey(withColon, 'Step: Fetch')?.id).toBe('s');
  });

  it('never guesses across a wrong prefix', () => {
    expect(findNodeByAnchorKey(nodes, 'trigger:check_seen')).toBeUndefined();
  });
});

describe('canvas changes carry attached notes along', () => {
  const nodes = [
    { id: 's', type: 'flowNode', position: { x: 100, y: 100 }, data: { id: 's', label: 'Shape' } },
    { id: 'n', type: 'noteNode', position: { x: 100, y: -40 }, data: { id: 'n', label: 'Why', noteAttachedTo: 's' } },
    { id: 'free', type: 'noteNode', position: { x: 0, y: 0 }, data: { id: 'free', label: 'Overview' } },
  ] as AnyNode[];

  it('lists the notes of a node with it for deletion, and only its own', () => {
    expect(withAttachedNoteIds(nodes, ['s'])).toEqual(['s', 'n']);
    expect(withAttachedNoteIds(nodes, ['free'])).toEqual(['free']);
  });

  it('ends the drag of the notes when the drag of their node ends', () => {
    const changes = withAttachedNoteChanges([{ type: 'position', id: 's', dragging: false }], nodes);

    expect(changes[1]).toEqual({ type: 'position', id: 'n', dragging: false });
  });

  it('moves the notes of a dragged node by the same amount', () => {
    const changes = withAttachedNoteChanges(
      [{ type: 'position', id: 's', position: { x: 130, y: 90 }, dragging: true }],
      nodes,
    );

    expect(changes[1]).toEqual({
      type: 'position', id: 'n', position: { x: 130, y: -50 }, positionAbsolute: { x: 130, y: -50 }, dragging: true,
    });
  });

  it('leaves a note that is itself part of the move alone', () => {
    const move: NodeChange[] = [
      { type: 'position', id: 's', position: { x: 130, y: 90 }, dragging: true },
      { type: 'position', id: 'n', position: { x: 999, y: 999 }, dragging: true },
    ];

    expect(withAttachedNoteChanges(move, nodes)).toBe(move);
  });

  it('does nothing for a move of a note or a change without a position', () => {
    const changes: NodeChange[] = [
      { type: 'position', id: 'n', position: { x: 5, y: 5 } },
      { type: 'position', id: 's', dragging: true },
    ];

    expect(withAttachedNoteChanges(changes, nodes)).toBe(changes);
  });
});

describe('focusing a node frames its notes', () => {
  it('includes the attached notes in the frame of a followed node', () => {
    const nodes = [
      { id: 's', position: { x: 100, y: 100 }, width: 200, height: 80 },
      { id: 'n', position: { x: 100, y: -200 }, width: 250, height: 120, data: { noteAttachedTo: 's' } },
      { id: 'other', position: { x: 2000, y: 2000 }, width: 200, height: 80 },
    ];

    const frame = resolveFollowFrame(nodes, { workflowId: 'w', nodeIds: ['s'] }, 'w', {
      pad: 0, fallbackWidth: 0, fallbackHeight: 0,
    });

    expect(frame).toEqual({ x: 100, y: -200, width: 250, height: 380 });
  });
});
