import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { buildNodeReportMessage, buildNodeReportHref, type NodeReportContext } from '../nodeReportLink';

// Mirrors the workflowBuilder.inspector.report en.json keys so assertions read
// against realistic output. Substitutes {placeholders} like next-intl does.
const messages: Record<string, string> = {
  none: '-',
  msgHeading: 'Workflow node issue report',
  msgWorkflowId: 'Workflow ID: {id}',
  msgRunId: 'Run ID: {id}',
  msgMode: 'Context: {mode}',
  modeEdit: 'Editing',
  modeRun: 'Run',
  msgNodeHeading: 'Reported node:',
  msgNodeLabel: '- Label: {label}',
  msgNodeType: '- Type: {type}',
  msgNodeId: '- Node ID: {id}',
  msgConfigHeading: 'Node configuration:',
  msgPlanHeading: 'Workflow plan ({nodes} nodes, {edges} edges):',
  msgEdgesHeading: 'Connections:',
  msgMore: '… and {count} more',
  msgReasonHeading: 'What went wrong?',
  msgReasonPlaceholder: '[Describe the problem.]',
};

const t = (key: string, values?: Record<string, string | number>): string =>
  (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, k: string) => String(values?.[k] ?? ''));

// `id` is the ReactFlow node id (node.id). BuilderNodeData.id is a *different*
// instance id (e.g. 'switch-1717…', 'agent-my_agent-123'), NOT a type - the
// semantic type is `data.kind`. `type` is the ReactFlow node type (e.g.
// 'noteNode'), which nodeRegistry uses to detect notes.
function node(id: string, data: Record<string, any>, type?: string): Node<BuilderNodeData> {
  return { id, type, position: { x: 0, y: 0 }, data: { label: id, kind: 'tool', ...data } as unknown as BuilderNodeData };
}

function ctx(overrides: Partial<NodeReportContext>): NodeReportContext {
  // Real nodes: node.id is the ReactFlow instance id; data.id is a *different*
  // instance id (e.g. 'agent-my_agent-123'); data.kind is the semantic type.
  const target = node('agent-1', { id: 'agent-my_agent-123', label: 'My Agent', kind: 'reasoning' });
  return {
    node: target,
    workflowId: 'wf-42',
    runId: null,
    isRunMode: false,
    allNodes: [target],
    edges: [],
    ...overrides,
  };
}

describe('buildNodeReportHref', () => {
  it('targets the bug contact category with a URL-encoded message', () => {
    const href = buildNodeReportHref(ctx({}), t);
    expect(href.startsWith('/contact?category=bug&message=')).toBe(true);
    // round-trips: the encoded payload decodes back to the raw message
    const encoded = href.replace('/contact?category=bug&message=', '');
    expect(decodeURIComponent(encoded)).toBe(buildNodeReportMessage(ctx({}), t));
  });
});

// In CE the support ticket must reach the cloud operator's contact form, not the
// local self-hosted install (localhost) whose form lands nowhere useful. IS_CE is
// frozen at module load, so flip the env then re-import a fresh module.
describe('buildNodeReportHref - CE points at the cloud origin', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('rewrites the bug contact link onto the cloud origin in CE', async () => {
    vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
    vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
    const { buildNodeReportHref: ceBuildHref } = await import('../nodeReportLink');
    const href = ceBuildHref(ctx({}), t);
    expect(href.startsWith('https://livecontext.ai/contact?category=bug&message=')).toBe(true);
    expect(href).not.toContain('localhost');
  });
});

describe('buildNodeReportMessage', () => {
  it('pre-fills node identity, workflow context, config and reason sections', () => {
    const msg = buildNodeReportMessage(ctx({ workflowId: 'wf-42', runId: 'run-9' }), t);
    expect(msg).toContain('Workflow node issue report');
    expect(msg).toContain('Workflow ID: wf-42');
    expect(msg).toContain('Run ID: run-9');
    expect(msg).toContain('- Label: My Agent');
    expect(msg).toContain('- Node ID: agent-1');
    expect(msg).toContain('Node configuration:');
    expect(msg).toContain('What went wrong?');
  });

  it('reports the stable kind as the node type, never the instance data.id', () => {
    const target = node('n1', { id: 'agent-my_agent-999', label: 'A', kind: 'reasoning' });
    const msg = buildNodeReportMessage(ctx({ node: target, allNodes: [target] }), t);
    expect(msg).toContain('- Type: reasoning');
    // the throwaway instance id must not surface as the type (or anywhere)
    expect(msg).not.toContain('agent-my_agent-999');
  });

  it('uses kind (not the timestamped data.id) for a core node, and never leaks that instance id', () => {
    // Real core nodes get data.id like 'switch-<Date.now()>' while kind stays coarse.
    const core = node('core-1', { id: 'switch-1717000000000', label: 'Route', kind: 'action' });
    const msg = buildNodeReportMessage(ctx({ node: core, allNodes: [core] }), t);
    expect(msg).toContain('- Type: action');
    expect(msg).toContain('- Node ID: core-1');
    // the timestamped instance id must never surface anywhere in the ticket
    expect(msg).not.toContain('switch-1717000000000');
  });

  it('reports the run context in run mode and the edit context otherwise', () => {
    expect(buildNodeReportMessage(ctx({ isRunMode: true }), t)).toContain('Context: Run');
    expect(buildNodeReportMessage(ctx({ isRunMode: false }), t)).toContain('Context: Editing');
  });

  it('falls back to the placeholder when workflow id / run id are absent', () => {
    const msg = buildNodeReportMessage(ctx({ workflowId: null, runId: null }), t);
    expect(msg).toContain('Workflow ID: -');
    expect(msg).toContain('Run ID: -');
  });

  it('includes a plan summary that counts nodes/edges and excludes notes', () => {
    const a = node('a', { id: 'agent-a-1', label: 'A', kind: 'reasoning' });
    const b = node('b', { label: 'B', kind: 'tool' });
    const note = node('n', { label: 'sticky' }, 'noteNode');
    const edges: Edge[] = [{ id: 'e1', source: 'a', target: 'b', sourceHandle: 'core:check:if' } as Edge];
    const msg = buildNodeReportMessage(ctx({ node: a, allNodes: [a, b, note], edges }), t);
    // 2 real nodes (note excluded), 1 edge
    expect(msg).toContain('Workflow plan (2 nodes, 1 edges):');
    expect(msg).toContain('- a "A" [reasoning]');
    expect(msg).toContain('Connections:');
    expect(msg).toContain('- a -> b (core:check:if)');
    expect(msg).not.toContain('sticky');
    expect(msg).not.toContain('agent-a-1');
  });

  it('curates configuration without dumping runtime noise', () => {
    const target = node('agent-1', {
      id: 'agent-my_agent-123',
      label: 'My Agent',
      kind: 'reasoning',
      prompt: 'Summarize',
      // runtime noise + secrets that must not leak into the ticket
      statusCounts: { COMPLETED: 1 },
      metrics: { tokens: 999 },
      authConfig: { bearerToken: `sk_${'live_FAKE_should_not_leak'}` },
    });
    const msg = buildNodeReportMessage(ctx({ node: target, allNodes: [target] }), t);
    expect(msg).toContain('"prompt": "Summarize"');
    expect(msg).not.toContain('statusCounts');
    expect(msg).not.toContain('metrics');
    // non-whitelisted fields (including credentials) are dropped entirely
    expect(msg).not.toContain('authConfig');
    expect(msg).not.toContain(`sk_${'live_FAKE_should_not_leak'}`);
  });

  it('stays under the contact-form cap for large workflows (truncates with an ellipsis)', () => {
    const many: Node<BuilderNodeData>[] = Array.from({ length: 300 }, (_, i) =>
      node(`node-${i}-with-a-fairly-long-identifier`, { label: `Node number ${i} with a long label`, kind: 'tool' }),
    );
    const edges: Edge[] = Array.from({ length: 300 }, (_, i) =>
      ({ id: `e${i}`, source: `node-${i}-with-a-fairly-long-identifier`, target: `node-${(i + 1) % 300}-with-a-fairly-long-identifier` } as Edge),
    );
    const msg = buildNodeReportMessage(ctx({ node: many[0], allNodes: many, edges }), t);
    expect(msg.length).toBeLessThanOrEqual(3000);
    expect(msg.endsWith('…')).toBe(true);
  });
});
