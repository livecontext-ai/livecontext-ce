import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import {
  sanitizeNodePolicy,
  gateNodePolicyForNode,
  nodeSupportsPolicy,
  isContinueOnFailureBlocked,
  isExecuteOnceBlocked,
} from '../nodePolicy';

function makeNode(type: string, kind: string, id = `${kind}-1`): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, label: id, kind } as BuilderNodeData,
  };
}

describe('sanitizeNodePolicy', () => {
  it('returns undefined for absent / non-object values', () => {
    expect(sanitizeNodePolicy(undefined)).toBeUndefined();
    expect(sanitizeNodePolicy(null)).toBeUndefined();
    expect(sanitizeNodePolicy('retryCount=2')).toBeUndefined();
    expect(sanitizeNodePolicy([1, 2])).toBeUndefined();
  });

  it('returns undefined for an all-default policy (plan stays clean)', () => {
    expect(sanitizeNodePolicy({})).toBeUndefined();
    expect(
      sanitizeNodePolicy({
        retryCount: 0,
        retryBackoffMs: 0,
        continueOnFailure: false,
        timeoutMs: 0,
        executeOnce: false,
      })
    ).toBeUndefined();
  });

  it('keeps only non-default fields', () => {
    expect(
      sanitizeNodePolicy({ retryCount: 2, retryBackoffMs: 0, continueOnFailure: false, timeoutMs: 30000 })
    ).toEqual({ retryCount: 2, timeoutMs: 30000 });
  });

  it('keeps boolean flags only when true', () => {
    expect(sanitizeNodePolicy({ continueOnFailure: true, executeOnce: true })).toEqual({
      continueOnFailure: true,
      executeOnce: true,
    });
  });

  it('coerces numeric strings and boolean strings (lenient like the backend parser)', () => {
    expect(sanitizeNodePolicy({ retryCount: '3', retryBackoffMs: '1500', continueOnFailure: 'true' })).toEqual({
      retryCount: 3,
      retryBackoffMs: 1500,
      continueOnFailure: true,
    });
  });

  it('drops negative and non-numeric values instead of emitting an invalid block', () => {
    expect(sanitizeNodePolicy({ retryCount: -1, timeoutMs: 'abc', retryBackoffMs: NaN })).toBeUndefined();
  });

  it('does not clamp large values - the backend is the validator', () => {
    expect(sanitizeNodePolicy({ retryCount: 50 })).toEqual({ retryCount: 50 });
  });

  it('ignores unknown fields (forward compatibility)', () => {
    expect(sanitizeNodePolicy({ retryCount: 1, fallbackValue: 'x' })).toEqual({ retryCount: 1 });
  });
});

describe('nodeSupportsPolicy', () => {
  it('rejects triggers and notes (parser ignores a policy there)', () => {
    expect(nodeSupportsPolicy(makeNode('triggerNode', 'entry'))).toBe(false);
    expect(nodeSupportsPolicy(makeNode('noteNode', 'note'))).toBe(false);
  });

  it('accepts every executable node type', () => {
    expect(nodeSupportsPolicy(makeNode('flowNode', 'action'))).toBe(true);
    expect(nodeSupportsPolicy(makeNode('decisionNode', 'decision'))).toBe(true);
    expect(nodeSupportsPolicy(makeNode('splitNode', 'split'))).toBe(true);
    expect(nodeSupportsPolicy(makeNode('interfaceNode', 'interface'))).toBe(true);
    expect(nodeSupportsPolicy(makeNode('agentNode', 'agent'))).toBe(true);
  });
});

describe('gating - mirrors WorkflowPlanParser rejections', () => {
  it('blocks continueOnFailure on decision / switch / option only', () => {
    expect(isContinueOnFailureBlocked(makeNode('decisionNode', 'decision'))).toBe(true);
    expect(isContinueOnFailureBlocked(makeNode('switchNode', 'switch', 'switch-1'))).toBe(true);
    expect(isContinueOnFailureBlocked(makeNode('optionNode', 'option', 'option-1'))).toBe(true);
    expect(isContinueOnFailureBlocked(makeNode('flowNode', 'action'))).toBe(false);
    expect(isContinueOnFailureBlocked(makeNode('forkNode', 'fork', 'fork-1'))).toBe(false);
    expect(isContinueOnFailureBlocked(makeNode('splitNode', 'split'))).toBe(false);
  });

  it('blocks executeOnce on split / aggregate / merge / loop only', () => {
    expect(isExecuteOnceBlocked(makeNode('splitNode', 'split'))).toBe(true);
    expect(isExecuteOnceBlocked(makeNode('aggregateNode', 'aggregate', 'aggregate-1'))).toBe(true);
    expect(isExecuteOnceBlocked(makeNode('mergeNode', 'merge', 'merge-1'))).toBe(true);
    expect(isExecuteOnceBlocked(makeNode('whileGroupNode', 'loop'))).toBe(true);
    expect(isExecuteOnceBlocked(makeNode('flowNode', 'action'))).toBe(false);
    expect(isExecuteOnceBlocked(makeNode('decisionNode', 'decision'))).toBe(false);
  });

  it('gateNodePolicyForNode strips only the blocked field and keeps the rest', () => {
    const decision = makeNode('decisionNode', 'decision');
    expect(
      gateNodePolicyForNode({ retryCount: 2, continueOnFailure: true }, decision)
    ).toEqual({ retryCount: 2 });

    const split = makeNode('splitNode', 'split');
    expect(
      gateNodePolicyForNode({ timeoutMs: 5000, executeOnce: true }, split)
    ).toEqual({ timeoutMs: 5000 });
  });

  it('gateNodePolicyForNode returns undefined when the only field is blocked', () => {
    const merge = makeNode('mergeNode', 'merge', 'merge-1');
    expect(gateNodePolicyForNode({ executeOnce: true }, merge)).toBeUndefined();
  });

  it('gateNodePolicyForNode is a no-op on unrestricted nodes', () => {
    const mcp = makeNode('flowNode', 'action');
    const policy = { retryCount: 1, continueOnFailure: true, executeOnce: true };
    expect(gateNodePolicyForNode(policy, mcp)).toEqual(policy);
  });
});
