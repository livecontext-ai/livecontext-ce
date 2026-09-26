/**
 * The importer decides which way a plan reads and places the nodes accordingly, through the
 * real node creation, edge creation and Dagre. See planLayoutDirection.test for the rule;
 * this suite proves the positions that come out match the direction that is returned,
 * which is the invariant the canvas relies on (the handles follow the returned direction).
 */
import { describe, expect, it } from 'vitest';
import { WorkflowPlanImporter } from '../WorkflowPlanImporter';

type Plan = Record<string, unknown>;

function chain(positions: Array<{ x: number; y: number } | undefined>, layoutDirection?: string): Plan {
  const pos = (i: number) => (positions[i] ? { position: positions[i] } : {});
  return {
    name: 'Direction test',
    ...(layoutDirection ? { layoutDirection } : {}),
    triggers: [{ id: 'start', label: 'Start', type: 'manual', ...pos(0) }],
    cores: [
      { id: 'core:shape', label: 'Shape', type: 'transform', ...pos(1), transform: { mappings: [] } },
      { id: 'core:finish', label: 'Finish', type: 'transform', ...pos(2), transform: { mappings: [] } },
    ],
    mcps: [],
    edges: [
      { from: 'trigger:start', to: 'core:shape' },
      { from: 'core:shape', to: 'core:finish' },
    ],
  };
}

const HORIZONTAL_POSITIONS = [{ x: 0, y: 0 }, { x: 400, y: 0 }, { x: 800, y: 0 }];
const VERTICAL_POSITIONS = [{ x: 0, y: 0 }, { x: 0, y: 300 }, { x: 0, y: 600 }];

async function importPlan(plan: Plan, layout: Parameters<typeof WorkflowPlanImporter.importPlan>[2]) {
  const result = await WorkflowPlanImporter.importPlan(JSON.stringify(plan), [], layout);
  expect(result.success, result.error).toBe(true);
  return result;
}

function positionOf(result: Awaited<ReturnType<typeof importPlan>>, label: string) {
  return result.nodes.find((n) => n.data.label === label)!.position;
}

/** Each node sits further along the flow axis than the one before it. */
function flowsAlong(result: Awaited<ReturnType<typeof importPlan>>, axis: 'x' | 'y') {
  const [a, b, c] = ['Start', 'Shape', 'Finish'].map((label) => positionOf(result, label)[axis]);
  return a < b && b < c;
}

describe('WorkflowPlanImporter - reading direction', () => {
  it('regression: keeps a legacy plan horizontal, positions untouched, for a vertical-default viewer', async () => {
    const result = await importPlan(chain(HORIZONTAL_POSITIONS), { fallbackDirection: 'vertical' });

    expect(result.layoutDirection).toBe('horizontal');
    expect(result.laidOutFromScratch).toBe(false);
    expect(positionOf(result, 'Finish')).toEqual({ x: 800, y: 0 });
  });

  it('reads a stamped vertical plan vertically for a horizontal-default viewer, positions untouched', async () => {
    const result = await importPlan(chain(VERTICAL_POSITIONS, 'vertical'), { fallbackDirection: 'horizontal' });

    expect(result.layoutDirection).toBe('vertical');
    expect(positionOf(result, 'Finish')).toEqual({ x: 0, y: 600 });
  });

  it('lays a plan without positions out in the viewer default', async () => {
    const result = await importPlan(chain([undefined, undefined, undefined]), { fallbackDirection: 'vertical' });

    expect(result.layoutDirection).toBe('vertical');
    expect(result.laidOutFromScratch).toBe(true);
    expect(flowsAlong(result, 'y')).toBe(true);
  });

  it('re-lays the whole graph when a pinned surface reads it the other way', async () => {
    const result = await importPlan(
      chain(HORIZONTAL_POSITIONS, 'horizontal'),
      { fallbackDirection: 'vertical', forcedDirection: 'vertical' },
    );

    expect(result.layoutDirection).toBe('vertical');
    expect(result.laidOutFromScratch).toBe(true);
    expect(flowsAlong(result, 'y'), 'horizontal positions kept under vertical handles').toBe(true);
  });

  it('keeps the positions a pin agrees with', async () => {
    const result = await importPlan(
      chain(VERTICAL_POSITIONS, 'vertical'),
      { fallbackDirection: 'horizontal', forcedDirection: 'vertical' },
    );

    expect(result.laidOutFromScratch).toBe(false);
    expect(positionOf(result, 'Shape')).toEqual({ x: 0, y: 300 });
  });

  it('places only the new node of a vertical plan, and places it below its neighbour', async () => {
    const result = await importPlan(
      chain([VERTICAL_POSITIONS[0], VERTICAL_POSITIONS[1], undefined], 'vertical'),
      { fallbackDirection: 'horizontal' },
    );

    expect(result.layoutDirection).toBe('vertical');
    expect(result.laidOutFromScratch).toBe(false);
    expect(positionOf(result, 'Shape')).toEqual({ x: 0, y: 300 });
    expect(positionOf(result, 'Finish').y).toBeGreaterThan(300);
  });

  it('reports the fallback direction when the plan cannot be parsed', async () => {
    const result = await WorkflowPlanImporter.importPlan('{not json', [], { fallbackDirection: 'vertical' });

    expect(result.success).toBe(false);
    expect(result.layoutDirection).toBe('vertical');
  });
});
