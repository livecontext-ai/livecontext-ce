import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import {
  sanitizeNodePolicy,
  gateNodePolicyForNode,
  nodeSupportsPolicy,
  isContinueOnFailureBlocked,
  isExecuteOnceBlocked,
  nodeCallsProvider,
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

describe('providerRetryMaxWaitSec', () => {
  // The one field of the block where 0 is a STATEMENT and not a default. Absent means "the
  // platform waits out a rate limit for me"; 0 means "do not, I pace my own calls". Every other
  // numeric field here resolves 0 to unset and drops it, so this field needed its own coercion,
  // and collapsing the two states would silently re-enable the retry it exists to switch off.
  it('keeps 0, which every other numeric field drops', () => {
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: 0 })).toEqual({
      providerRetryMaxWaitSec: 0,
    });
    expect(sanitizeNodePolicy({ retryCount: 0 })).toBeUndefined();
  });

  it('keeps a positive budget', () => {
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: 60 })).toEqual({
      providerRetryMaxWaitSec: 60,
    });
  });

  it('coerces a numeric string, the shape a number input produces', () => {
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: '30' })).toEqual({
      providerRetryMaxWaitSec: 30,
    });
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: '0' })).toEqual({
      providerRetryMaxWaitSec: 0,
    });
  });

  it('drops a value the backend would reject rather than sending it', () => {
    // -5 and 'soon' are what the backend refuses; sending them would turn a typo into a refused
    // save of the whole plan instead of a field the user can correct.
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: -5 })).toBeUndefined();
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: 'soon' })).toBeUndefined();
    expect(sanitizeNodePolicy({ providerRetryMaxWaitSec: null })).toBeUndefined();
  });

  it('survives the type gate on EVERY node type, including ones that call no provider', () => {
    // Deliberate: the gate runs on every save. Dropping an inert field here would mean that
    // opening an agent-built workflow and saving it silently deleted a setting nobody removed.
    const core = makeNode('flowNode', 'code', 'code-1');
    expect(gateNodePolicyForNode({ providerRetryMaxWaitSec: 0 }, core)).toEqual({
      providerRetryMaxWaitSec: 0,
    });
  });
});

describe('nodeCallsProvider', () => {
  // This decides whether the provider-retry field is OFFERED. The only defensible answer is the one
  // the plan emitter gives, because a node the emitter does not turn into a `mcps` entry has
  // nowhere for the setting to land: `attachNodePolicies` joins on emitted entries. A second,
  // similar-looking predicate disagreed with it in both directions, so these tests compare against
  // the emitter's own rule rather than restating a list.
  function withToolData(node: Node<BuilderNodeData>): Node<BuilderNodeData> {
    // The two fields toolData actually requires, so this is a shape the builder can really hold.
    return { ...node, data: { ...node.data, toolData: { apiName: 'Slack', method: 'POST' } } };
  }

  it('needs the tool data, not merely the absence of another family', () => {
    // The half a deny-list drops. A node with no toolData and no apiData is never emitted as a
    // `mcps` entry, so offering a provider-only control on it offers a setting that cannot land.
    expect(nodeCallsProvider(makeNode('flowNode', 'action'))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('flowNode', 'action', 'tool-2')))).toBe(true);
  });

  it('an apiData node counts too, like the emitter', () => {
    const node = makeNode('flowNode', 'action', 'api-1');
    const withApi = { ...node, data: { ...node.data, apiData: { apiName: 'Slack' } } };
    expect(nodeCallsProvider(withApi)).toBe(true);
  });

  it('every excluded family stays excluded even carrying tool data', () => {
    // One assertion per exclusion, so deleting any single clause of the shared predicate fails a
    // named test rather than passing silently.
    expect(nodeCallsProvider(withToolData(makeNode('triggerNode', 'entry', 'entry-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('noteNode', 'note', 'note-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('agentNode', 'reasoning', 'agent-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('interfaceNode', 'interface', 'interface-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('crudNode', 'crud', 'crud-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('decisionNode', 'decision', 'decision-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('mergeNode', 'merge', 'merge-1')))).toBe(false);
    expect(nodeCallsProvider(withToolData(makeNode('flowNode', 'code', 'code-1')))).toBe(false);
  });
});
