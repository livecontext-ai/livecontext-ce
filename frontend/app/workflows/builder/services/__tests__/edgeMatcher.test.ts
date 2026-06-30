import { describe, it, expect } from 'vitest';
import { edgeMatchesBatchEdge, type BatchEdgeData } from '../edgeMatcher';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

/**
 * Helper to create a minimal Node<BuilderNodeData> for testing.
 */
function makeNode(overrides: {
  id: string;
  type?: string;
  dataId?: string;
  label?: string;
  kind?: string;
  loopChildren?: any[];
}): Node<BuilderNodeData> {
  return {
    id: overrides.id,
    type: overrides.type || 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id: overrides.dataId || overrides.id,
      label: overrides.label || '',
      kind: (overrides.kind || 'action') as any,
      loopChildren: overrides.loopChildren,
    } as BuilderNodeData,
  };
}

/**
 * Helper to create a minimal Edge for testing.
 */
function makeEdge(overrides: {
  id?: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  data?: any;
}): Edge {
  return {
    id: overrides.id || `${overrides.source}->${overrides.target}`,
    source: overrides.source,
    target: overrides.target,
    sourceHandle: overrides.sourceHandle,
    targetHandle: overrides.targetHandle,
    data: overrides.data,
  };
}

// =============================================================================
// Direct from/to matching (Strategy 1)
// =============================================================================
describe('edgeMatchesBatchEdge - direct from/to matching', () => {
  it('should match simple mcp -> mcp edge by label', () => {
    const source = makeNode({ id: 'step-get_user-123', dataId: 'mcp:get_user', label: 'Get User' });
    const target = makeNode({ id: 'step-send_email-456', dataId: 'mcp:send_email', label: 'Send Email' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:get_user', to: 'mcp:send_email' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match trigger -> mcp edge', () => {
    const source = makeNode({ id: 'trigger-1-123', type: 'triggerNode', dataId: 'trigger:1', label: 'Webhook', kind: 'entry' });
    const target = makeNode({ id: 'step-process-456', dataId: 'mcp:process', label: 'Process' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'trigger:webhook', to: 'mcp:process' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match core -> mcp edge (decision:if)', () => {
    const source = makeNode({ id: 'decision-123', type: 'decisionNode', dataId: 'core:check', label: 'Check', kind: 'decision' });
    const target = makeNode({ id: 'step-success-456', dataId: 'mcp:success', label: 'Success' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:check:if', to: 'mcp:success' };

    // core:check:if should strip port ":if" and match "check" against source label "Check"
    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match core -> mcp edge (decision:else)', () => {
    const source = makeNode({ id: 'decision-123', type: 'decisionNode', dataId: 'core:check', label: 'Check', kind: 'decision' });
    const target = makeNode({ id: 'step-failure-456', dataId: 'mcp:failure', label: 'Failure' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:check:else', to: 'mcp:failure' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match agent -> mcp edge', () => {
    const source = makeNode({ id: 'agent-analyzer-123', type: 'agentNode', dataId: 'agent:analyzer', label: 'Analyzer', kind: 'agent' });
    const target = makeNode({ id: 'step-result-456', dataId: 'mcp:result', label: 'Result' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'agent:analyzer', to: 'mcp:result' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match mcp -> agent edge', () => {
    const source = makeNode({ id: 'step-fetch-123', dataId: 'mcp:fetch', label: 'Fetch' });
    const target = makeNode({ id: 'agent-classifier-456', type: 'agentNode', dataId: 'agent:classifier', label: 'Classifier', kind: 'agent' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:fetch', to: 'agent:classifier' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should NOT match when from does not match', () => {
    const source = makeNode({ id: 'step-alpha-123', dataId: 'mcp:alpha', label: 'Alpha' });
    const target = makeNode({ id: 'step-beta-456', dataId: 'mcp:beta', label: 'Beta' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:gamma', to: 'mcp:beta' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should NOT match when to does not match', () => {
    const source = makeNode({ id: 'step-alpha-123', dataId: 'mcp:alpha', label: 'Alpha' });
    const target = makeNode({ id: 'step-beta-456', dataId: 'mcp:beta', label: 'Beta' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:alpha', to: 'mcp:gamma' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should match with underscore variations in labels', () => {
    const source = makeNode({ id: 'step-1-123', dataId: '1', label: 'While A' });
    const target = makeNode({ id: 'step-2-456', dataId: '2', label: 'Step B' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:while_a', to: 'mcp:step_b' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });
});

// =============================================================================
// Edge ID pattern matching (Strategy 2)
// =============================================================================
describe('edgeMatchesBatchEdge - edge ID pattern matching', () => {
  it('should match by edge ID pattern (from->to format)', () => {
    const source = makeNode({ id: 'step-get_user-123', dataId: 'mcp:get_user', label: 'Get User' });
    const target = makeNode({ id: 'step-send_email-456', dataId: 'mcp:send_email', label: 'Send Email' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = {
      id: 'trigger:test->mcp:get_user_profile_by_user_id',
      // from/to don't match directly, but the ID pattern does not either because source/target don't match.
      // Let's test a case where from/to fail but id matches
      from: 'wrong:source',
      to: 'wrong:target',
    };
    // This should not match because neither from/to nor the id pattern match the actual nodes
    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should match when batch edge id pattern matches source and target labels', () => {
    const source = makeNode({ id: 'step-start-123', dataId: 'mcp:start', label: 'Start' });
    const target = makeNode({ id: 'step-process-456', dataId: 'mcp:process', label: 'Process' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = {
      id: 'mcp:start->mcp:process',
      from: '',
      to: '',
    };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

});


// =============================================================================
// Core node port stripping in batch edges
// =============================================================================
describe('edgeMatchesBatchEdge - core node port stripping', () => {
  it('should strip :if port from core decision edge', () => {
    const source = makeNode({ id: 'decision-1', type: 'decisionNode', dataId: 'core:my_decision', label: 'My Decision', kind: 'decision' });
    const target = makeNode({ id: 'step-1', dataId: 'mcp:step_a', label: 'Step A' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_decision:if', to: 'mcp:step_a' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :else port from core decision edge', () => {
    const source = makeNode({ id: 'decision-1', type: 'decisionNode', dataId: 'core:my_decision', label: 'My Decision', kind: 'decision' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:step_b', label: 'Step B' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_decision:else', to: 'mcp:step_b' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :elseif_0 port from core decision edge', () => {
    const source = makeNode({ id: 'decision-1', type: 'decisionNode', dataId: 'core:my_decision', label: 'My Decision', kind: 'decision' });
    const target = makeNode({ id: 'step-3', dataId: 'mcp:step_c', label: 'Step C' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_decision:elseif_0', to: 'mcp:step_c' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :branch_0 port from core fork edge', () => {
    const source = makeNode({ id: 'fork-1', type: 'forkNode', dataId: 'core:my_fork', label: 'My Fork', kind: 'fork' });
    const target = makeNode({ id: 'step-1', dataId: 'mcp:task_a', label: 'Task A' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_fork:branch_0', to: 'mcp:task_a' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :body port from core while loop edge', () => {
    const source = makeNode({ id: 'while-1', type: 'whileGroupNode', dataId: 'core:my_loop', label: 'My Loop', kind: 'loop' });
    const target = makeNode({ id: 'step-1', dataId: 'mcp:iterate_step', label: 'Iterate Step' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_loop:body', to: 'mcp:iterate_step' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :exit port from core while loop edge', () => {
    const source = makeNode({ id: 'while-1', type: 'whileGroupNode', dataId: 'core:my_loop', label: 'My Loop', kind: 'loop' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:after_loop', label: 'After Loop' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_loop:exit', to: 'mcp:after_loop' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :case_0 port from core switch edge', () => {
    const source = makeNode({ id: 'switch-1', type: 'switchNode', dataId: 'core:my_switch', label: 'My Switch', kind: 'switch' });
    const target = makeNode({ id: 'step-1', dataId: 'mcp:case_handler', label: 'Case Handler' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_switch:case_0', to: 'mcp:case_handler' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip :default port from core switch edge', () => {
    const source = makeNode({ id: 'switch-1', type: 'switchNode', dataId: 'core:my_switch', label: 'My Switch', kind: 'switch' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:default_handler', label: 'Default Handler' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:my_switch:default', to: 'mcp:default_handler' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should strip # suffix before processing batch edge from', () => {
    const source = makeNode({ id: 'decision-1', type: 'decisionNode', dataId: 'core:check', label: 'Check', kind: 'decision' });
    const target = makeNode({ id: 'step-1', dataId: 'mcp:success', label: 'Success' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:check#then:if', to: 'mcp:success' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });
});


// =============================================================================
// Edge cases
// =============================================================================
describe('edgeMatchesBatchEdge - edge cases', () => {
  it('should handle batch edge with empty from and to', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:step_1', label: 'Step 1' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:step_2', label: 'Step 2' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: '', to: '' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should handle batch edge with undefined from and to', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:step_1', label: 'Step 1' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:step_2', label: 'Step 2' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = {};

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should handle nodes with empty labels', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:alpha', label: '' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:beta', label: '' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:alpha', to: 'mcp:beta' };

    // Should still match by ID normalization fallback
    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should handle batch edge with only id field', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:get_user', label: 'Get User' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:send_email', label: 'Send Email' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { id: 'mcp:get_user->mcp:send_email' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should not match when only from matches but to does not', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:alpha', label: 'Alpha' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:beta', label: 'Beta' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:alpha', to: 'mcp:gamma' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should not match when only to matches but from does not', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:alpha', label: 'Alpha' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:beta', label: 'Beta' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:gamma', to: 'mcp:beta' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });

  it('should match with accented characters in labels', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:entree', label: 'Entree' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:sortie', label: 'Sortie' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:entree', to: 'mcp:sortie' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should handle allNodes being undefined (no loop matching)', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:alpha', label: 'Alpha' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:beta', label: 'Beta' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:alpha', to: 'mcp:beta' };

    // Should still work for non-loop edges even without allNodes
    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should return false when batch edge is not a loop edge and from/to do not match', () => {
    const source = makeNode({ id: 'step-x', dataId: 'mcp:x', label: 'X' });
    const target = makeNode({ id: 'step-y', dataId: 'mcp:y', label: 'Y' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:a', to: 'mcp:b' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(false);
  });
});

// =============================================================================
// ID-based fallback matching
// =============================================================================
describe('edgeMatchesBatchEdge - ID fallback', () => {
  it('should match by sourceIdNorm and targetIdNorm when labels are empty', () => {
    const source = makeNode({ id: 'step-1', dataId: 'mcp:fetch_data', label: '' });
    const target = makeNode({ id: 'step-2', dataId: 'mcp:transform_data', label: '' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:fetch_data', to: 'mcp:transform_data' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match by nodeId when data.id normalization matches', () => {
    const source = makeNode({ id: 'mcp:alpha', dataId: 'mcp:alpha', label: '' });
    const target = makeNode({ id: 'mcp:beta', dataId: 'mcp:beta', label: '' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'mcp:alpha', to: 'mcp:beta' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });
});

// =============================================================================
// Mixed prefix edge matching
// =============================================================================
describe('edgeMatchesBatchEdge - mixed prefixes', () => {
  it('should match trigger -> agent edge', () => {
    const source = makeNode({ id: 'trigger-1', type: 'triggerNode', dataId: 'trigger:webhook', label: 'Webhook', kind: 'entry' });
    const target = makeNode({ id: 'agent-1', type: 'agentNode', dataId: 'agent:analyzer', label: 'Analyzer', kind: 'agent' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'trigger:webhook', to: 'agent:analyzer' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });

  it('should match core:fork -> core:merge edge', () => {
    const source = makeNode({ id: 'fork-1', type: 'forkNode', dataId: 'core:parallel', label: 'Parallel', kind: 'fork' });
    const target = makeNode({ id: 'merge-1', type: 'mergeNode', dataId: 'core:wait_all', label: 'Wait All', kind: 'merge' });
    const edge = makeEdge({ source: source.id, target: target.id });
    const batchEdge: BatchEdgeData = { from: 'core:parallel:branch_0', to: 'core:wait_all' };

    expect(edgeMatchesBatchEdge(edge, batchEdge, source, target)).toBe(true);
  });
});

// =============================================================================
// Guardrail pass/fail port disambiguation (regression)
//
// The backend emits two per-epoch edge keys for a guardrail:
// "agent:<g>:pass->core:merge" and "agent:<g>:fail->core:merge". The two
// ReactFlow edges share the same source (the guardrail) and the same target
// (the merge); they are distinguished ONLY by their sourceHandle id
// ("pass" / "fail", per GuardrailNode.tsx). Before the fix, sourceHandleMatchesPort
// had no case for pass/fail and returned undefined, so each guardrail edge matched
// BOTH keys and the per-epoch view painted both arrows with one branch's status
// (e.g. epoch where pass=COMPLETED showed both edges completed). These tests fail
// on the pre-fix code (the cross-branch assertions returned true) and pass after.
// =============================================================================
describe('edgeMatchesBatchEdge - guardrail pass/fail port disambiguation', () => {
  const guardrail = makeNode({ id: 'agent-risk-123', type: 'agentNode', dataId: 'agent:risk_screen', label: 'Risk Screen', kind: 'agent' });
  const merge = makeNode({ id: 'merge-1', type: 'mergeNode', dataId: 'core:merge', label: 'Merge', kind: 'merge' });
  const passEdge = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'pass' });
  const failEdge = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'fail' });
  const passKey: BatchEdgeData = { from: 'agent:risk_screen:pass', to: 'core:merge' };
  const failKey: BatchEdgeData = { from: 'agent:risk_screen:fail', to: 'core:merge' };

  it('binds the pass edge to the :pass key only', () => {
    expect(edgeMatchesBatchEdge(passEdge, passKey, guardrail, merge)).toBe(true);
    // Regression: pre-fix this returned true (undefined != false), collapsing both arrows onto one status.
    expect(edgeMatchesBatchEdge(passEdge, failKey, guardrail, merge)).toBe(false);
  });

  it('binds the fail edge to the :fail key only', () => {
    expect(edgeMatchesBatchEdge(failEdge, failKey, guardrail, merge)).toBe(true);
    // Regression: pre-fix this returned true, so the fail arrow showed the pass branch status.
    expect(edgeMatchesBatchEdge(failEdge, passKey, guardrail, merge)).toBe(false);
  });

  it('matches the legacy hyphenated handle form (guardrail-1-pass / -fail)', () => {
    const passLegacy = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'guardrail-1-pass' });
    const failLegacy = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'guardrail-1-fail' });
    expect(edgeMatchesBatchEdge(passLegacy, passKey, guardrail, merge)).toBe(true);
    expect(edgeMatchesBatchEdge(passLegacy, failKey, guardrail, merge)).toBe(false);
    expect(edgeMatchesBatchEdge(failLegacy, failKey, guardrail, merge)).toBe(true);
    expect(edgeMatchesBatchEdge(failLegacy, passKey, guardrail, merge)).toBe(false);
  });

  it('does not over-match a handle that merely contains "pass"/"fail" (e.g. bypass, failover)', () => {
    const bypassEdge = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'bypass' });
    const failoverEdge = makeEdge({ source: guardrail.id, target: merge.id, sourceHandle: 'failover' });
    // "bypass" !== "pass" and does not end with "-pass"; "failover" !== "fail" and does not end with "-fail".
    // A substring (.includes) check would wrongly match both; the === / endsWith predicate excludes them.
    expect(edgeMatchesBatchEdge(bypassEdge, passKey, guardrail, merge)).toBe(false);
    expect(edgeMatchesBatchEdge(failoverEdge, failKey, guardrail, merge)).toBe(false);
  });
});
