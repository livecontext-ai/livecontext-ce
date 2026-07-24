import { describe, it, expect, beforeEach } from 'vitest';
import { MockConfigurationRule } from '../rules-v2/MockConfigurationRule';
import { buildContext, resetNodeCounter, resetEdgeCounter } from './test-helpers';

function node(type: string, data: Record<string, unknown>, id = 'n-1') {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, label: data.label ?? 'Node', ...data },
  } as any;
}

function mcpToolNode(mock: unknown, id = 'n-mcp') {
  return node(
    'flowNode',
    {
      id,
      label: 'Send Email',
      kind: 'action',
      toolData: { toolId: 'gmail/send_email', toolSlug: 'send_email' },
      mock,
    },
    id
  );
}

function issuesOf(rule: MockConfigurationRule, nodes: any[], ruleTag?: string) {
  const result = rule.validate(buildContext(nodes, []));
  return ruleTag ? result.issues.filter((i) => i.context?.rule === ruleTag) : result.issues;
}

describe('MockConfigurationRule', () => {
  let rule: MockConfigurationRule;

  beforeEach(() => {
    rule = new MockConfigurationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata (warnings only)', () => {
    expect(rule.ruleName).toBe('MockConfiguration');
    expect(rule.isCritical).toBe(false);
  });

  it('passes clean on nodes without any mock', () => {
    const result = rule.validate(
      buildContext([mcpToolNode(undefined), node('decisionNode', { kind: 'decision' })], [])
    );
    expect(result.issues).toHaveLength(0);
    expect(result.hasWarnings).toBe(false);
  });

  it('passes clean on valid mocks (static output / catalog example / error / bare port)', () => {
    const nodes = [
      mcpToolNode({ output: { ok: true } }, 'n-mcp-1'),
      mcpToolNode({ source: 'catalog_example' }, 'n-mcp-2'),
      mcpToolNode({ error: { message: 'boom' } }, 'n-mcp-3'),
      node(
        'decisionNode',
        {
          kind: 'decision',
          label: 'Check',
          decisionConditions: [
            { id: 'c-if', type: 'if', label: 'If' },
            { id: 'c-else', type: 'else', label: 'Else' },
          ],
          mock: { port: 'else' },
        },
        'n-dec'
      ),
    ];
    const result = rule.validate(buildContext(nodes, []));
    expect(result.issues).toHaveLength(0);
  });

  it('(a) warns when no source is defined (empty-ish mock, no bare port)', () => {
    expect(issuesOf(rule, [mcpToolNode({ enabled: false })], 'mock_source_count')).toHaveLength(1);
    expect(issuesOf(rule, [mcpToolNode({})], 'mock_source_count')).toHaveLength(1);
    // Bare port on a NON-port-selecting node does not count as a source
    expect(issuesOf(rule, [mcpToolNode({ port: 'if' })], 'mock_source_count')).toHaveLength(1);
  });

  it('(a) warns when more than one source is defined', () => {
    const issues = issuesOf(
      rule,
      [mcpToolNode({ output: { a: 1 }, source: 'catalog_example', error: { message: 'x' } })],
      'mock_source_count'
    );
    expect(issues).toHaveLength(1);
    expect(issues[0].severity).toBe('warning');
  });

  it('(g) accepts valid durations: number, numeric string, at the 10-minute cap', () => {
    const nodes = [
      mcpToolNode({ output: { ok: true }, durationMs: 90000 }, 'n-d-1'),
      mcpToolNode({ output: { ok: true }, durationMs: '90000' }, 'n-d-2'),
      mcpToolNode({ output: { ok: true }, durationMs: 600000 }, 'n-d-3'),
      mcpToolNode({ output: { ok: true }, duration_ms: 5000 }, 'n-d-4'),
    ];
    expect(issuesOf(rule, nodes, 'mock_duration_invalid')).toHaveLength(0);
  });

  it('(g) warns for invalid durations: non-numeric, negative, beyond the cap, NaN', () => {
    expect(
      issuesOf(rule, [mcpToolNode({ output: { ok: true }, durationMs: 'fast' })], 'mock_duration_invalid')
    ).toHaveLength(1);
    expect(
      issuesOf(rule, [mcpToolNode({ output: { ok: true }, durationMs: -1 })], 'mock_duration_invalid')
    ).toHaveLength(1);
    expect(
      issuesOf(rule, [mcpToolNode({ output: { ok: true }, durationMs: 600001 })], 'mock_duration_invalid')
    ).toHaveLength(1);
    expect(
      issuesOf(rule, [mcpToolNode({ output: { ok: true }, durationMs: Number.NaN })], 'mock_duration_invalid')
    ).toHaveLength(1);
  });

  it('(b) warns for catalog_example on a non-mcp node', () => {
    const transform = node('flowNode', { kind: 'transform', mock: { source: 'catalog_example' } });
    expect(issuesOf(rule, [transform], 'mock_catalog_example_non_mcp')).toHaveLength(1);
    // ...and not on an mcp catalog tool node
    expect(
      issuesOf(rule, [mcpToolNode({ source: 'catalog_example' })], 'mock_catalog_example_non_mcp')
    ).toHaveLength(0);
  });

  it('(c) warns for a port on a non-port-selecting node', () => {
    expect(
      issuesOf(rule, [mcpToolNode({ output: { a: 1 }, port: 'if' })], 'mock_port_unsupported_node')
    ).toHaveLength(1);
  });

  it('(c) warns for a port that does not exist on the node', () => {
    const decision = node('decisionNode', {
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
      mock: { port: 'elseif_5' },
    });
    const issues = issuesOf(rule, [decision], 'mock_port_unknown');
    expect(issues).toHaveLength(1);
    expect(issues[0].message).toContain('elseif_5');
    // A valid port stays clean
    const validDecision = node('decisionNode', {
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
      mock: { port: 'else' },
    });
    expect(issuesOf(rule, [validDecision])).toHaveLength(0);
  });

  it('(f) warns for a static mock without a port on a branching node (backend parse error)', () => {
    // WorkflowPlanParser rejects this at parse time, so EVERY editor run of the
    // workflow would fail until the mock selects a branch.
    const decision = node('decisionNode', {
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
      mock: { output: { verdict: true } },
    });
    const issues = issuesOf(rule, [decision], 'mock_port_required');
    expect(issues).toHaveLength(1);
    expect(issues[0].severity).toBe('warning');
  });

  it('(f) stays clean when the static mock on a branching node selects a port', () => {
    const decision = node('decisionNode', {
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
      mock: { output: { verdict: true }, port: 'if' },
    });
    expect(issuesOf(rule, [decision], 'mock_port_required')).toHaveLength(0);
  });

  it('(f) does not fire for static mocks on non-branching nodes, nor for catalog/error mocks', () => {
    const approval = node('userApprovalNode', {
      kind: 'approval',
      mock: { error: { message: 'boom' } },
    });
    expect(issuesOf(rule, [mcpToolNode({ output: { a: 1 } })], 'mock_port_required')).toHaveLength(0);
    expect(issuesOf(rule, [approval], 'mock_port_required')).toHaveLength(0);
  });

  it('(d) warns when output is present but not a plain object', () => {
    expect(issuesOf(rule, [mcpToolNode({ output: [1, 2] })], 'mock_output_not_object')).toHaveLength(1);
    expect(issuesOf(rule, [mcpToolNode({ output: 'text' })], 'mock_output_not_object')).toHaveLength(1);
    expect(issuesOf(rule, [mcpToolNode({ output: { a: 1 } })], 'mock_output_not_object')).toHaveLength(0);
  });

  it('(e) warns for a mock on unsupported nodes (trigger / note / split / merge / aggregate / loop / fork)', () => {
    const unsupported = [
      node('triggerNode', { kind: 'entry', mock: { output: { a: 1 } } }, 'n-trig'),
      node('noteNode', { kind: 'note', mock: { output: { a: 1 } } }, 'n-note'),
      node('splitNode', { kind: 'split', mock: { output: { a: 1 } } }, 'n-split'),
      node('mergeNode', { kind: 'merge', mock: { output: { a: 1 } } }, 'n-merge'),
      node('aggregateNode', { kind: 'aggregate', mock: { output: { a: 1 } } }, 'n-agg'),
      node('whileGroupNode', { kind: 'loop', mock: { output: { a: 1 } } }, 'n-loop'),
      node('forkNode', { kind: 'fork', mock: { output: { a: 1 } } }, 'n-fork'),
    ];
    const issues = issuesOf(rule, unsupported, 'mock_unsupported_node');
    expect(issues).toHaveLength(7);
    expect(issues.every((i) => i.severity === 'warning')).toBe(true);
  });

  it('warns when a simulated error also selects a branch', () => {
    const decision = node('decisionNode', {
      kind: 'decision',
      decisionConditions: [
        { id: 'c-if', type: 'if', label: 'If' },
        { id: 'c-else', type: 'else', label: 'Else' },
      ],
      mock: { error: { message: 'boom' }, port: 'if' },
    });
    expect(issuesOf(rule, [decision], 'mock_error_with_port')).toHaveLength(1);
  });

  it('warns when the mock block itself is not an object', () => {
    expect(issuesOf(rule, [mcpToolNode('not-an-object')], 'mock_invalid_shape')).toHaveLength(1);
  });

  it('never emits errors - warnings only', () => {
    const result = rule.validate(
      buildContext(
        [
          mcpToolNode({ output: 'bad', port: 'if', source: 'catalog_example' }),
          node('triggerNode', { kind: 'entry', mock: { output: {} } }, 'n-trig'),
        ],
        []
      )
    );
    expect(result.issues.length).toBeGreaterThan(0);
    expect(result.hasErrors).toBe(false);
    expect(result.issues.every((i) => i.severity === 'warning')).toBe(true);
  });
});
