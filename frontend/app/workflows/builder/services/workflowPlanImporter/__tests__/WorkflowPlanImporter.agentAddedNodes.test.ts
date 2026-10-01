/**
 * A node an agent creates is saved with no position. When the plan already had positions,
 * the importer used to keep them all and set each new node down at Dagre's offset from ONE
 * placed neighbour, moving nothing else: a node the agent inserted between two others landed
 * on top of the downstream one, and the canvas looked as if no auto-layout had run (reported
 * 2026-09-30, on a workflow an agent edited and then pinned). The graph is now laid out again
 * whenever a node has no position, as the hover "+" already does after a user insertion.
 */
import { describe, expect, it } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getNodeDimensions } from '../../LayoutService';
import { WorkflowPlanImporter } from '../WorkflowPlanImporter';

type Position = { x: number; y: number };

/** Start -> Shape -> Finish, with the given node positions (undefined = agent-made, no position). */
function plan(positions: { start?: Position; shape?: Position; finish?: Position }) {
  const pos = (p?: Position) => (p ? { position: p } : {});
  return {
    name: 'Agent edit',
    layoutDirection: 'horizontal',
    triggers: [{ id: 'start', label: 'Start', type: 'manual', ...pos(positions.start) }],
    cores: [
      { id: 'core:shape', label: 'Shape', type: 'transform', ...pos(positions.shape), transform: { mappings: [] } },
      { id: 'core:finish', label: 'Finish', type: 'transform', ...pos(positions.finish), transform: { mappings: [] } },
    ],
    mcps: [],
    edges: [
      { from: 'trigger:start', to: 'core:shape' },
      { from: 'core:shape', to: 'core:finish' },
    ],
  };
}

async function importPlan(p: ReturnType<typeof plan>) {
  const result = await WorkflowPlanImporter.importPlan(JSON.stringify(p), [], { fallbackDirection: 'horizontal' });
  expect(result.success, result.error).toBe(true);
  return result;
}

const positionsByLabel = (nodes: Node<BuilderNodeData>[]) =>
  Object.fromEntries(nodes.map((n) => [n.data.label, n.position]));

function overlaps(a: Node<BuilderNodeData>, b: Node<BuilderNodeData>) {
  const da = getNodeDimensions(a, true, 'horizontal');
  const db = getNodeDimensions(b, true, 'horizontal');
  return a.position.x < b.position.x + db.width && b.position.x < a.position.x + da.width
    && a.position.y < b.position.y + db.height && b.position.y < a.position.y + da.height;
}

describe('WorkflowPlanImporter - nodes an agent added', () => {
  it('regression: a node the agent inserted between two placed nodes does not land on the downstream one', async () => {
    // The user laid out Start -> Finish; the agent then inserted Shape between them.
    const result = await importPlan(plan({ start: { x: 0, y: 0 }, finish: { x: 290, y: 0 } }));

    const shape = result.nodes.find((n) => n.data.label === 'Shape')!;
    const others = result.nodes.filter((n) => n !== shape);
    expect(others.some((n) => overlaps(shape, n)), 'inserted node overlaps a placed node').toBe(false);
    const at = positionsByLabel(result.nodes);
    expect(at.Start.x < at.Shape.x && at.Shape.x < at.Finish.x, 'flow order Start < Shape < Finish').toBe(true);
  });

  it('lays the whole graph out exactly as an agent build of the same plan', async () => {
    // Shape was saved off the automatic grid; the agent then appended Finish (no position).
    const withAddedNode = await importPlan(plan({ start: { x: 0, y: 0 }, shape: { x: 290, y: 400 } }));
    const fromScratch = await importPlan(plan({}));

    expect(withAddedNode.laidOutFromScratch).toBe(true);
    expect(positionsByLabel(withAddedNode.nodes)).toEqual(positionsByLabel(fromScratch.nodes));
  });

  it('keeps every stored position when no node was added', async () => {
    const result = await importPlan(plan({ start: { x: 0, y: 0 }, shape: { x: 290, y: 0 }, finish: { x: 580, y: 500 } }));

    expect(result.laidOutFromScratch).toBe(false);
    expect(positionsByLabel(result.nodes)).toEqual({
      Start: { x: 0, y: 0 },
      Shape: { x: 290, y: 0 },
      Finish: { x: 580, y: 500 },
    });
  });
});
