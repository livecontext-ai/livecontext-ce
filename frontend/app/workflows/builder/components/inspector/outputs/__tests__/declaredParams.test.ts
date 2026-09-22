import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';
import { collectDeclaredParams, planEntryForNode } from '../declaredParams';
import { buildParamAlignment } from '../runParamAlignment';

// The palette id is what nodeRegistry keys node-type detection on, so a
// fixture that omits it is not the node the builder would produce.
function node(partial: Record<string, unknown>, id = 'filter-1', type = 'filterNode'): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, kind: 'core', ...partial } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

describe('planEntryForNode', () => {
  it('finds the entry the generator emits for a lone node', () => {
    const entry = planEntryForNode(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    expect(entry?.type).toBe('filter');
    expect(entry?.label).toBe('Filter Ops');
  });

  it('returns null rather than throwing for a node the generator emits nothing for', () => {
    expect(planEntryForNode(node({ label: 'A note' }, 'note-1', 'noteNode'))).toBeNull();
  });
});

describe('collectDeclaredParams', () => {
  it('flattens the typed block and the flat params into one key set', () => {
    const declared = collectDeclaredParams(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    // `conditions` and `mode` come from the typed `filter` block, `input` from params.
    expect(Object.keys(declared).sort()).toEqual(['conditions', 'input', 'mode']);
  });

  it('never reports the entry\'s own metadata as a configured parameter', () => {
    const declared = collectDeclaredParams(
      node({ label: 'Limit Top', limitCount: 2, limitFrom: 'first', limitOffset: 0 }, 'limit-1', 'limitNode'),
    );
    for (const metadataKey of ['id', 'graphNodeId', 'type', 'label', 'position']) {
      expect(declared).not.toHaveProperty(metadataKey);
    }
  });

  it('is empty for a null node instead of throwing', () => {
    expect(collectDeclaredParams(null)).toEqual({});
  });

  it('lines a real filter node up against what the backend reports for it', () => {
    // The keys FilterNode echoes under resolved_params (verified live).
    const reported = { input: '{{core:x.output.items}}', conditions: [], mode: 'and', input_count: 3 };
    const declared = collectDeclaredParams(
      node({
        label: 'Filter Ops',
        filterConditions: [{ field: 'category', operator: 'equals', value: 'ops' }],
        filterMode: 'and',
        filterInput: '{{core:x.output.items}}',
      }),
    );
    expect(buildParamAlignment(declared, reported).mismatches).toEqual([]);
  });
});

/**
 * The control cores are where the mismatch banner would cry wolf: their plan
 * entry names a structural block (`decisionConditions`, `switchCases`, …) while
 * the node reports the resolved values. These pin declared-vs-reported for each
 * of them against the keys the backend actually emits, so a rename on either
 * side surfaces here rather than as an amber warning on a healthy run.
 */
describe('control cores: declared vs reported', () => {
  function alignmentFor(
    nodeFixture: Node<BuilderNodeData>,
    reported: Record<string, unknown>,
    nodeType: string,
  ) {
    return buildParamAlignment(collectDeclaredParams(nodeFixture), reported, nodeType);
  }

  it('a decision reports its branches, not the structural condition list', () => {
    const decision = node(
      {
        label: 'Route',
        decisionConditions: [
          { id: 'if', label: 'if', expression: '{{trigger:start.output.type}} == "A"' },
          { id: 'else', label: 'else', expression: 'true' },
        ],
      },
      'decision-1',
      'decisionNode',
    );
    // DecisionNode reports one key per branch plus a count.
    const reported = { if: false, else: true, branches: 2 };
    expect(alignmentFor(decision, reported, 'decision').mismatches).toEqual([]);
  });

  it('a switch reports its expression and case count under the plan names', () => {
    const switchNode = node(
      {
        label: 'Pick',
        switchExpression: '{{trigger:start.output.type}}',
        switchCases: [{ id: 'case_1', label: 'A', value: 'A' }],
      },
      'switch-1',
      'switchNode',
    );
    const reported = { switchExpression: 'B', resolved_value: 'B', switchCases: 1 };
    expect(alignmentFor(switchNode, reported, 'switch').mismatches).toEqual([]);
  });

  it('declares a loop from the NODE, because registerControlNodes walks nodes before edges', () => {
    // This used to assert the opposite, under the name "a known blind spot, the plan
    // registers a loop from its EDGES". It is not: `processEdgesV2` calls
    // `registerControlNodes` BEFORE it touches an edge, and that function iterates
    // `ctx.nodes`. The old fixture returned null for an unrelated reason - it set
    // `loopCondition`, and the plan reads `node.data.whileCondition` - so the test
    // confirmed a blind spot that does not exist and hid the field-name trap that does.
    const loop = node(
      { label: 'Repeat', kind: 'while_group', whileCondition: '{{core:x.output.more}}', maxIterations: 3 },
      'while_group-1',
      'whileGroupNode',
    );
    expect(collectDeclaredParams(loop, 'while-group')).toEqual({
      loopCondition: '{{core:x.output.more}}',
      maxIterations: 3,
    });
  });

  it('a loop that keeps its condition under the WRONG field name declares nothing, and that is the real trap', () => {
    // `loopCondition` is what the plan entry is called; `whileCondition` is what the node
    // data must hold. A fixture that confuses them reports an empty Params column for a
    // configured loop, which is what the previous test mistook for an edge blind spot.
    const loop = node(
      { label: 'Repeat', kind: 'while_group', loopCondition: '{{core:x.output.more}}' },
      'while_group-2',
      'whileGroupNode',
    );
    expect(collectDeclaredParams(loop, 'while-group').loopCondition).toBe('');
  });

  it('a fork reports its branch count under the plan name', () => {
    const fork = node({ label: 'Split work', forkOutputs: ['a', 'b'] }, 'fork-1', 'forkNode');
    const reported = { forkOutputs: 2 };
    expect(alignmentFor(fork, reported, 'fork').mismatches).toEqual([]);
  });

  it('still flags a core that genuinely stops reporting a parameter', () => {
    const switchNode = node(
      {
        label: 'Pick',
        switchExpression: '{{trigger:start.output.type}}',
        switchCases: [{ id: 'case_1', label: 'A', value: 'A' }],
      },
      'switch-1',
      'switchNode',
    );
    const mismatches = alignmentFor(switchNode, { switchCases: 1 }, 'switch').mismatches;
    expect(mismatches.map((m) => m.key)).toEqual(['switchExpression']);
  });
});
