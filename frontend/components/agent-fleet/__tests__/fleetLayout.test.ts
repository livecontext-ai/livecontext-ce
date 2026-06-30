import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { applyFleetLayout, applyFleetLayoutCached, consolidateFleetResources, RESOURCE_AGGREGATION_THRESHOLD } from '../fleetLayout';
import { estimateNodeWidth } from '@/app/workflows/builder/services/LayoutService';

function makeNode(overrides: {
  id: string;
  label?: string;
  kind?: string;
  width?: number;
  height?: number;
  data?: Record<string, any>;
}): Node<BuilderNodeData> {
  return {
    id: overrides.id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    width: overrides.width,
    height: overrides.height,
    data: {
      id: overrides.id,
      label: overrides.label ?? '',
      kind: (overrides.kind ?? 'tool') as any,
      ...overrides.data,
    } as BuilderNodeData,
  };
}

/** Assert that, on every horizontal layer (same rounded y), node x-intervals are disjoint. */
function expectNoOverlapPerLayer(nodes: Node<BuilderNodeData>[]) {
  // Mirror the fleet layout's own (deterministic, measured-ignoring) width: the
  // label estimate capped at the 280px node cap.
  const widthOf = (n: Node<BuilderNodeData>) => {
    const d = n.data as any;
    const min = d?.fleetHandles?.length > 0 ? 280 : 200;
    return Math.min(280, estimateNodeWidth(d?.label, min));
  };
  const layers = new Map<number, Node<BuilderNodeData>[]>();
  for (const n of nodes) {
    const y = Math.round(n.position.y / 20) * 20;
    if (!layers.has(y)) layers.set(y, []);
    layers.get(y)!.push(n);
  }
  for (const layerNodes of layers.values()) {
    const sorted = [...layerNodes].sort((a, b) => a.position.x - b.position.x);
    for (let i = 1; i < sorted.length; i++) {
      const prev = sorted[i - 1];
      const curr = sorted[i];
      const prevRight = prev.position.x + widthOf(prev);
      expect(
        curr.position.x,
        `"${curr.data.label}" (x=${curr.position.x}) overlaps "${prev.data.label}" (right=${prevRight})`,
      ).toBeGreaterThanOrEqual(prevRight - 0.5);
    }
  }
}

const LONG_LABEL = 'List Channel Members (youtube.channel-memberships.creator)';

describe('applyFleetLayout', () => {
  it('returns the input unchanged for an empty graph', () => {
    expect(applyFleetLayout([], [])).toEqual([]);
  });

  it('does not overlap wide sibling resource chips under an agent', () => {
    // Agent with two tool chips, one carrying a long label (measured wide).
    // The flat-200 width assumption made the wide chip overlap its sibling.
    const agent = makeNode({
      id: 'agent-1', label: 'My Agent', kind: 'reasoning', width: 280, height: 90,
      data: { fleetHandles: ['tools'], fleetBottomHandles: true },
    });
    const wideChip = makeNode({
      id: 'res-1-tool-wide', label: LONG_LABEL, width: 480, height: 80,
      data: { fleetResourceType: 'tool', fleetTopHandle: true },
    });
    const smallChip = makeNode({
      id: 'res-1-tool-small', label: 'Get', width: 200, height: 80,
      data: { fleetResourceType: 'tool', fleetTopHandle: true },
    });

    const edges: Edge[] = [
      { id: 'e1', source: 'agent-1', target: 'res-1-tool-wide', sourceHandle: 'source-tools', targetHandle: 'target-top' },
      { id: 'e2', source: 'agent-1', target: 'res-1-tool-small', sourceHandle: 'source-tools', targetHandle: 'target-top' },
    ];

    const out = applyFleetLayout([agent, wideChip, smallChip], edges);

    // Chips sit below the agent and never overlap each other.
    const agentY = out.find(n => n.id === 'agent-1')!.position.y;
    const chipY = out.find(n => n.id === 'res-1-tool-wide')!.position.y;
    expect(chipY).toBeGreaterThan(agentY);
    expectNoOverlapPerLayer(out);
  });

  it('keeps many wide sibling chips fully separated (the YouTube-fleet case)', () => {
    const agent = makeNode({
      id: 'agent-1', label: 'YouTube Agent', kind: 'reasoning', width: 280, height: 90,
      data: { fleetHandles: ['tools'], fleetBottomHandles: true },
    });
    const labels = [
      'List Playlists (youtube.readonly)',
      'Upload Video (youtube.upload)',
      'Update Video (youtube write)',
      'Insert Comment (youtube.force-ssl)',
      LONG_LABEL,
    ];
    const chips = labels.map((label, i) =>
      makeNode({
        id: `res-1-tool-${i}`, label, width: 200 + i * 60, height: 80,
        data: { fleetResourceType: 'tool', fleetTopHandle: true },
      }),
    );
    const edges: Edge[] = chips.map((c, i) => ({
      id: `e${i}`, source: 'agent-1', target: c.id, sourceHandle: 'source-tools', targetHandle: 'target-top',
    }));

    const out = applyFleetLayout([agent, ...chips], edges);
    expectNoOverlapPerLayer(out);
  });

  it('produces an IDENTICAL layout with or without measured dims (initial paint == auto-layout button)', () => {
    // The bug: the fleet always auto-layouts, but first paint uses estimates (nodes
    // unmeasured) while the auto-layout button uses measured widths/heights → the
    // nodes jumped. The layout must be deterministic regardless of measured dims.
    const build = (measured: boolean) => {
      const dim = (w: number, h: number) => (measured ? { width: w, height: h } : {});
      const agent = makeNode({ id: 'agent-1', label: 'My Orchestrator Agent', kind: 'reasoning', ...dim(248, 150), data: { fleetHandles: ['tools'], fleetBottomHandles: true } });
      const c1 = makeNode({ id: 'res-1-tool-a', label: LONG_LABEL, ...dim(280, 96), data: { fleetResourceType: 'tool', fleetTopHandle: true } });
      const c2 = makeNode({ id: 'res-1-tool-b', label: 'Get Values', ...dim(214, 80), data: { fleetResourceType: 'tool', fleetTopHandle: true } });
      const edges: Edge[] = [
        { id: 'e1', source: 'agent-1', target: 'res-1-tool-a', sourceHandle: 'source-tools', targetHandle: 'target-top' },
        { id: 'e2', source: 'agent-1', target: 'res-1-tool-b', sourceHandle: 'source-tools', targetHandle: 'target-top' },
      ];
      return applyFleetLayout([agent, c1, c2], edges)
        .map(n => ({ id: n.id, x: Math.round(n.position.x), y: Math.round(n.position.y) }))
        .sort((a, b) => a.id.localeCompare(b.id));
    };
    // Same node set, one "unmeasured" (first paint), one "measured" (button) → identical positions.
    expect(build(true)).toEqual(build(false));
  });
});

// =============================================================================
// applyFleetLayout - grid of trees (fleet view, multiple root agents)
// =============================================================================
describe('applyFleetLayout - grid of trees', () => {
  /** Agent `a` with `chips` tool chips hanging off it. No sub-agent edges → each agent is a root. */
  function buildFleet(agentCount: number, chipsPerAgent: number) {
    const nodes: Node<BuilderNodeData>[] = [];
    const edges: Edge[] = [];
    for (let a = 0; a < agentCount; a++) {
      const aid = `agent-${a}`;
      nodes.push(makeNode({ id: aid, label: `Agent ${a}`, kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true } }));
      for (let c = 0; c < chipsPerAgent; c++) {
        const cid = `res-${a}-tool-${c}`;
        nodes.push(makeNode({ id: cid, label: `Tool ${a}.${c}`, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
        edges.push({ id: `e-${a}-${c}`, source: aid, target: cid, sourceHandle: 'source-tools', targetHandle: 'target-top' });
      }
    }
    return { nodes, edges };
  }

  it('packs many agents without ANY node hiding another (the fleet-overlap fix)', () => {
    const { nodes, edges } = buildFleet(4, 2);
    const out = applyFleetLayout(nodes, edges);
    expectNoOverlapPerLayer(out);
  });

  it('wraps agents into multiple rows instead of one over-wide row', () => {
    const { nodes, edges } = buildFleet(4, 2);
    const out = applyFleetLayout(nodes, edges);
    const agentRows = new Set(
      out.filter(n => /^agent-\d+$/.test(n.id)).map(n => Math.round(n.position.y / 20) * 20),
    );
    expect(agentRows.size).toBeGreaterThanOrEqual(2); // genuine grid, not a single column/row
  });

  it('leaves a single-root graph (single-agent panel) laid out verbatim - no grid offset', () => {
    const { nodes, edges } = buildFleet(1, 2);
    const out = applyFleetLayout(nodes, edges);
    const agent = out.find(n => n.id === 'agent-0')!;
    expect(Math.round(agent.position.x)).toBeLessThanOrEqual(280); // root packs from x≈0, not a grid cell
    expectNoOverlapPerLayer(out);
  });
});

// =============================================================================
// applyFleetLayout - nested sub-agents (sub-agent on a sub-agent)
//
// Regression: a sub-agent sits on the SAME rank as its parent's tool chips (both
// are direct children of the parent). Agent nodes render taller (90px) than chips
// (80px), so Dagre's rank-centering gives them slightly different TOP-LEFT y even
// though their CENTER y is identical. Bucketing layers by top-left y rounded to
// 20px split that 5px difference across a boundary (e.g. 388→380 vs 393→400),
// dropping the sub-agent and the chips into different "layers". Each layer then
// centered its members under the same parent → they stacked on top of each other
// (node-on-node), and the per-layer overlap pass never compared them. Fixed by
// bucketing on center y (height-invariant per rank).
// =============================================================================
describe('applyFleetLayout - nested sub-agents do not overlap parent chips', () => {
  // True 2D bounding-box overlap check (height-aware) - strictly stronger than the
  // per-layer check, because the bug placed nodes a few px apart in y yet fully
  // overlapping in x. Mirrors the layout's own deterministic width + height.
  const widthOf = (n: Node<BuilderNodeData>) => {
    const d = n.data as any;
    const min = d?.fleetHandles?.length > 0 ? 280 : 200;
    return Math.min(280, estimateNodeWidth(d?.label, min));
  };
  const heightOf = (n: Node<BuilderNodeData>) => ((n.data as any)?.fleetHandles?.length > 0 ? 90 : 80);

  function expectNoNodeOverlap(nodes: Node<BuilderNodeData>[]) {
    const TOL = 1;
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i], b = nodes[j];
        const ox = Math.min(a.position.x + widthOf(a), b.position.x + widthOf(b)) - Math.max(a.position.x, b.position.x);
        const oy = Math.min(a.position.y + heightOf(a), b.position.y + heightOf(b)) - Math.max(a.position.y, b.position.y);
        expect(
          ox <= TOL || oy <= TOL,
          `"${a.data.label}"(${a.id}) [${Math.round(a.position.x)},${Math.round(a.position.y)}] ` +
          `overlaps "${b.data.label}"(${b.id}) [${Math.round(b.position.x)},${Math.round(b.position.y)}] ` +
          `(ovx=${Math.round(ox)} ovy=${Math.round(oy)})`,
        ).toBe(true);
      }
    }
  }

  // Builders mirroring useAgentFleetState's node/edge shapes for the fleet canvas.
  function mkAgent(nodes: Node<BuilderNodeData>[], id: string, label: string) {
    nodes.push(makeNode({ id: `agent-${id}`, label, kind: 'reasoning',
      data: { fleetHandles: ['model', 'tools', 'resources'], fleetBottomHandles: true, fleetTopHandle: true } }));
  }
  function mkModel(nodes: Node<BuilderNodeData>[], edges: Edge[], aid: string, label: string) {
    const rid = `res-${aid}-model-model`;
    nodes.push(makeNode({ id: rid, label, data: { fleetResourceType: 'model', fleetTopHandle: true } }));
    edges.push({ id: `e-${rid}`, source: `agent-${aid}`, target: rid, sourceHandle: 'source-model', targetHandle: 'target-top', data: { category: 'model' } } as Edge);
  }
  function mkTool(nodes: Node<BuilderNodeData>[], edges: Edge[], aid: string, key: string, label: string) {
    const rid = `res-${aid}-tool-${key}`;
    nodes.push(makeNode({ id: rid, label, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
    edges.push({ id: `e-${rid}`, source: `agent-${aid}`, target: rid, sourceHandle: 'source-tools', targetHandle: 'target-top', data: { category: 'tools' } } as Edge);
  }
  function mkWeb(nodes: Node<BuilderNodeData>[], edges: Edge[], aid: string) {
    const rid = `res-${aid}-web_search-web-search`;
    nodes.push(makeNode({ id: rid, label: 'Web Search', kind: 'web_search', data: { fleetResourceType: 'web_search', fleetTopHandle: true } }));
    edges.push({ id: `e-${rid}`, source: `agent-${aid}`, target: rid, sourceHandle: 'source-resources', targetHandle: 'target-top', data: { category: 'resources' } } as Edge);
  }
  function mkSub(edges: Edge[], parent: string, child: string) {
    edges.push({ id: `edge-${parent}-${child}`, source: `agent-${parent}`, target: `agent-${child}`, sourceHandle: 'source-resources', targetHandle: 'target-top', data: { category: 'sub-agents' } } as Edge);
  }

  it('keeps a sub-agent off its parent\'s sibling tool chips (the screenshot case)', () => {
    const nodes: Node<BuilderNodeData>[] = [];
    const edges: Edge[] = [];
    // Root → sub-agent "Competitive" → sub-sub-agent "Smart" (sub-agent on a sub-agent).
    mkAgent(nodes, 'digest', 'Email Digest AI');
    mkModel(nodes, edges, 'digest', 'deepseek-chat');
    mkTool(nodes, edges, 'digest', 'gmail', 'gmail');
    mkSub(edges, 'digest', 'competitive');

    mkAgent(nodes, 'competitive', 'Competitive Intelligence Analyst');
    mkModel(nodes, edges, 'competitive', 'deepseek-chat');
    mkTool(nodes, edges, 'competitive', 'gp', 'gmail get profile');
    mkTool(nodes, edges, 'competitive', 'gi', 'gmail get information');
    mkTool(nodes, edges, 'competitive', 'gpm', 'gmail push message');
    mkTool(nodes, edges, 'competitive', 'gsm', 'gmail send message');
    mkWeb(nodes, edges, 'competitive');
    mkSub(edges, 'competitive', 'smart'); // the taller sub-agent on the chips' rank

    mkAgent(nodes, 'smart', 'Smart Assistant');
    mkModel(nodes, edges, 'smart', 'deepseek-chat');
    mkTool(nodes, edges, 'smart', 'all', 'All tools');

    const out = applyFleetLayout(nodes, edges);
    // The sub-agent and its parent's tool chips land on the same visual rank…
    const smart = out.find(n => n.id === 'agent-smart')!;
    const chip = out.find(n => n.id === 'res-competitive-tool-gi')!;
    expect(Math.abs((smart.position.y + 45) - (chip.position.y + 40))).toBeLessThan(20); // same center line
    // …and must NOT overlap it.
    expectNoNodeOverlap(out);
  });

  it('handles many sibling sub-agents + chips nested several levels deep (multi-root)', () => {
    const nodes: Node<BuilderNodeData>[] = [];
    const edges: Edge[] = [];
    const build = (id: string, depth: number) => {
      mkAgent(nodes, id, `Agent ${id} with a fairly long display name`);
      mkModel(nodes, edges, id, 'deepseek-chat');
      for (let t = 0; t < 5; t++) mkTool(nodes, edges, id, `t${t}`, `tool ${id} number ${t}`);
      mkWeb(nodes, edges, id);
      if (depth > 0) {
        for (let s = 0; s < 3; s++) {
          mkSub(edges, id, `${id}_s${s}`);
          build(`${id}_s${s}`, depth - 1);
        }
      }
    };
    build('root', 3);  // 3 levels of sub-agents
    build('root2', 2); // a second independent root tree → grid path
    const out = applyFleetLayout(nodes, edges);
    expectNoNodeOverlap(out);
  });

  it('keeps a provider group + an "agg-" aggregator (height-80 group nodes) off a sibling sub-agent', () => {
    // Locks in height-invariance for the NON-leaf fleet node types: a provider group
    // node and the "Resources (N)" aggregator (both type flowNode, height 80) must sit
    // on the same rank as a sibling sub-agent (height 90) without overlapping it. Runs
    // the FULL pipeline (consolidateFleetResources → applyFleetLayout) like the canvas.
    const nodes: Node<BuilderNodeData>[] = [];
    const edges: Edge[] = [];
    mkAgent(nodes, 'root', 'Root Orchestrator');
    mkModel(nodes, edges, 'root', 'deepseek-chat');
    // A provider group (2 tools under it) - direct tools child of the root.
    nodes.push(makeNode({ id: 'provider-root-gmail', label: 'Gmail', kind: 'tool', data: { fleetTopHandle: true, fleetBottomHandles: true } }));
    edges.push({ id: 'e-prov', source: 'agent-root', target: 'provider-root-gmail', sourceHandle: 'source-tools', targetHandle: 'target-top', data: { category: 'tools' } } as Edge);
    for (let i = 0; i < 2; i++) {
      nodes.push(makeNode({ id: `res-root-tool-p${i}`, label: `Gmail tool ${i}`, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
      edges.push({ id: `e-pt${i}`, source: 'provider-root-gmail', target: `res-root-tool-p${i}`, sourceHandle: 'source-bottom', targetHandle: 'target-top', data: { category: 'tools' } } as Edge);
    }
    // A sub-agent sibling of the provider group, which itself has 6+ resources → aggregates.
    mkSub(edges, 'root', 'classifier');
    mkAgent(nodes, 'classifier', 'Gmail Classifier');
    mkModel(nodes, edges, 'classifier', 'claude-sonnet-4-6');
    for (let i = 0; i < 6; i++) mkTool(nodes, edges, 'classifier', `t${i}`, `Sheets tool ${i}`);

    const c = consolidateFleetResources(nodes, edges);
    // Sanity: the classifier's 6 tools collapsed into one aggregator node.
    expect(c.nodes.some(n => n.id === 'agg-agent-classifier')).toBe(true);
    const out = applyFleetLayout(c.nodes, c.edges);
    expectNoNodeOverlap(out);
  });
});

// =============================================================================
// consolidateFleetResources - aggregate an agent's resources past the threshold
// =============================================================================
describe('consolidateFleetResources', () => {
  const resEdge = (agentId: string, target: string, category = 'tools'): Edge =>
    ({ id: `${agentId}->${target}`, source: agentId, target, sourceHandle: 'source-tools', targetHandle: 'target-top', data: { category } });

  /** Agent + `n` tool chips + a model chip + one sub-agent. */
  const buildAgent = (n: number, counts?: Record<string, unknown>) => {
    const agent = makeNode({ id: 'agent-1', label: 'Orchestrator', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: counts } });
    const model = makeNode({ id: 'res-agent-1-model-model', label: 'GPT-4o', data: { fleetResourceType: 'model', fleetTopHandle: true } });
    const sub = makeNode({ id: 'agent-2', label: 'Sub Agent', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true } });
    const chips = Array.from({ length: n }, (_, i) => makeNode({ id: `res-agent-1-tool-${i}`, label: `Tool ${i}`, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
    const nodes = [agent, model, sub, ...chips];
    const edges: Edge[] = [
      { id: 'e-model', source: 'agent-1', target: 'res-agent-1-model-model', data: { category: 'model' } } as Edge,
      { id: 'e-sub', source: 'agent-1', target: 'agent-2', data: { category: 'sub-agents' } } as Edge,
      ...chips.map((c) => resEdge('agent-1', c.id)),
    ];
    return { nodes, edges };
  };

  it('leaves an agent below the threshold untouched', () => {
    const { nodes, edges } = buildAgent(RESOURCE_AGGREGATION_THRESHOLD - 1); // 5
    const out = consolidateFleetResources(nodes, edges);
    expect(out.nodes).toBe(nodes); // same reference → no change
    expect(out.nodes.some((n) => n.id.startsWith('agg-'))).toBe(false);
  });

  it('aggregates at exactly the threshold (à partir de 6)', () => {
    const { nodes, edges } = buildAgent(RESOURCE_AGGREGATION_THRESHOLD, { tools: RESOURCE_AGGREGATION_THRESHOLD }); // 6
    const out = consolidateFleetResources(nodes, edges);
    const agg = out.nodes.filter((n) => (n.data as any).fleetAggregator);
    expect(agg.length).toBe(1);
    expect(agg[0].data.label).toBe('Resources (6)');
  });

  it('replaces > threshold resource children with ONE aggregator, keeping model + sub-agents', () => {
    const { nodes, edges } = buildAgent(7, { tools: 7 });
    const out = consolidateFleetResources(nodes, edges);

    // all 7 tool chips gone
    expect(out.nodes.filter((n) => n.id.startsWith('res-agent-1-tool-')).length).toBe(0);
    // exactly one aggregator
    const agg = out.nodes.filter((n) => (n.data as any).fleetAggregator);
    expect(agg.length).toBe(1);
    expect(agg[0].id).toBe('agg-agent-1');
    expect(agg[0].data.label).toBe('Resources (7)');
    // model chip + sub-agent still present
    expect(out.nodes.some((n) => n.id === 'res-agent-1-model-model')).toBe(true);
    expect(out.nodes.some((n) => n.id === 'agent-2')).toBe(true);
    // one edge agent-1 → aggregator; tool-chip edges gone
    expect(out.edges.some((e) => e.source === 'agent-1' && e.target === 'agg-agent-1')).toBe(true);
    expect(out.edges.some((e) => e.target.startsWith('res-agent-1-tool-'))).toBe(false);
    // model + sub-agent edges kept
    expect(out.edges.some((e) => (e.data as any)?.category === 'model')).toBe(true);
    expect(out.edges.some((e) => (e.data as any)?.category === 'sub-agents')).toBe(true);
  });

  it('removes nested subtrees (provider → its tool children)', () => {
    // agent with 7 providers, each owning 2 tool children → providers are the direct
    // resource children; their tool children must be removed too.
    const agent = makeNode({ id: 'agent-1', label: 'A', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: { tools: 14 } } });
    const nodes: Node<BuilderNodeData>[] = [agent];
    const edges: Edge[] = [];
    for (let p = 0; p < 7; p++) {
      const provId = `provider-agent-1-p${p}`;
      nodes.push(makeNode({ id: provId, label: `Provider ${p}`, kind: 'tool', data: { fleetBottomHandles: true, fleetTopHandle: true } }));
      edges.push(resEdge('agent-1', provId));
      for (let t = 0; t < 2; t++) {
        const toolId = `res-agent-1-tool-${p}-${t}`;
        nodes.push(makeNode({ id: toolId, label: `T${p}-${t}`, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
        edges.push({ id: `${provId}->${toolId}`, source: provId, target: toolId, data: { category: 'tools' } } as Edge);
      }
    }
    const out = consolidateFleetResources(nodes, edges);
    expect(out.nodes.filter((n) => n.id.startsWith('provider-agent-1-')).length).toBe(0);
    expect(out.nodes.filter((n) => n.id.startsWith('res-agent-1-tool-')).length).toBe(0);
    expect(out.nodes.filter((n) => (n.data as any).fleetAggregator).length).toBe(1);
    expect(out.nodes.find((n) => (n.data as any).fleetAggregator)!.data.label).toBe('Resources (14)'); // from counts
  });

  it('folds all resource categories (tools + resources + skills) into one aggregator', () => {
    // Mixed direct children across the three resource edge categories: a provider
    // (tools), two single chips (resources), and three skill folders (skills) = 6.
    const agent = makeNode({ id: 'agent-1', label: 'A', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: { tools: 3, workflows: 2, skills: 3 } } });
    const nodes: Node<BuilderNodeData>[] = [agent];
    const edges: Edge[] = [];
    // provider (category 'tools') owning 3 tools
    nodes.push(makeNode({ id: 'provider-agent-1-p0', label: 'Prov', kind: 'tool', data: { fleetBottomHandles: true, fleetTopHandle: true } }));
    edges.push({ id: 'e-prov', source: 'agent-1', target: 'provider-agent-1-p0', data: { category: 'tools' } } as Edge);
    for (let i = 0; i < 3; i++) {
      nodes.push(makeNode({ id: `res-agent-1-tool-${i}`, label: `T${i}`, data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
      edges.push({ id: `prov->t${i}`, source: 'provider-agent-1-p0', target: `res-agent-1-tool-${i}`, data: { category: 'tools' } } as Edge);
    }
    // two single workflow chips (category 'resources')
    for (let i = 0; i < 2; i++) {
      nodes.push(makeNode({ id: `res-agent-1-wf-${i}`, label: `WF${i}`, data: { fleetResourceType: 'workflow', fleetTopHandle: true } }));
      edges.push({ id: `e-wf${i}`, source: 'agent-1', target: `res-agent-1-wf-${i}`, data: { category: 'resources' } } as Edge);
    }
    // three skill folders (category 'skills')
    for (let i = 0; i < 3; i++) {
      nodes.push(makeNode({ id: `folder-agent-1-f${i}`, label: `F${i}`, data: { fleetResourceType: 'folder', fleetTopHandle: true } }));
      edges.push({ id: `e-f${i}`, source: 'agent-1', target: `folder-agent-1-f${i}`, data: { category: 'skills' } } as Edge);
    }
    const out = consolidateFleetResources(nodes, edges); // 6 direct children → aggregate
    const agg = out.nodes.filter((n) => (n.data as any).fleetAggregator);
    expect(agg.length).toBe(1); // ONE node for all three categories
    expect(agg[0].data.label).toBe('Resources (8)'); // 3 tools + 2 workflows + 3 skills
    // every original resource node removed across all categories
    expect(out.nodes.some((n) => n.id.startsWith('provider-agent-1-') || n.id.startsWith('res-agent-1-') || n.id.startsWith('folder-agent-1-'))).toBe(false);
    // single aggregator edge from the agent
    expect(out.edges.filter((e) => e.source === 'agent-1').length).toBe(1);
  });

  it('aggregates a many-resource agent whose resources are pre-grouped under ONE category node (direct edges < 6, total >= 6)', () => {
    // Real-world prod shape: an agent with 12 tables. Upstream grouping folds the
    // 12 tables into a single "Tables" category node, so the agent has only ONE
    // direct resource edge - but its total leaf count (fleetResourceCounts) is 12.
    // Counting direct edges would skip aggregation and "expand all" would show all
    // 12 leaves; counting totals correctly aggregates into "Resources (12)".
    const agent = makeNode({ id: 'agent-1', label: 'A', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: { tables: 12 } } });
    const catNode = makeNode({ id: 'category-agent-1-table', label: 'Tables', data: { fleetResourceType: 'table', fleetTopHandle: true } });
    const nodes: Node<BuilderNodeData>[] = [agent, catNode];
    const edges: Edge[] = [
      { id: 'e-cat', source: 'agent-1', target: 'category-agent-1-table', data: { category: 'resources' } } as Edge,
    ];
    for (let i = 0; i < 12; i++) {
      nodes.push(makeNode({ id: `res-agent-1-table-${i}`, label: `Tbl ${i}`, data: { fleetResourceType: 'table', fleetTopHandle: true } }));
      edges.push({ id: `cat->t${i}`, source: 'category-agent-1-table', target: `res-agent-1-table-${i}`, data: { category: 'resources' } } as Edge);
    }
    const out = consolidateFleetResources(nodes, edges);
    const agg = out.nodes.filter((n) => (n.data as any).fleetAggregator);
    expect(agg.length).toBe(1);
    expect(agg[0].data.label).toBe('Resources (12)');
    // the category group AND every table leaf are removed
    expect(out.nodes.some((n) => n.id === 'category-agent-1-table')).toBe(false);
    expect(out.nodes.filter((n) => n.id.startsWith('res-agent-1-table-')).length).toBe(0);
    // exactly one resource edge from the agent (to the aggregator)
    expect(out.edges.filter((e) => e.source === 'agent-1').length).toBe(1);
  });

  it('still aggregates an "all tools" agent at the direct-edge threshold (all-tools counts as 0 leaves)', () => {
    // Additivity guard: an "all tools" grant contributes 0 to fleetResourceCounts
    // (tools: -1, only counted when > 0) but IS a real direct resource edge. With 6
    // direct edges but total leaves = 5, the threshold must still fire via the
    // max(total, directEdges) floor, so an agent that aggregated before never stops.
    const agent = makeNode({ id: 'agent-1', label: 'A', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: { tools: -1, skills: 5 } } });
    const nodes: Node<BuilderNodeData>[] = [agent];
    const edges: Edge[] = [];
    // 1 "all tools" chip (category 'tools') - counts as 0 leaves
    nodes.push(makeNode({ id: 'res-agent-1-all-tools', label: 'All tools', data: { fleetResourceType: 'tool', fleetTopHandle: true } }));
    edges.push({ id: 'e-all', source: 'agent-1', target: 'res-agent-1-all-tools', data: { category: 'tools' } } as Edge);
    // 5 skill folders (category 'skills')
    for (let i = 0; i < 5; i++) {
      nodes.push(makeNode({ id: `folder-agent-1-f${i}`, label: `F${i}`, data: { fleetResourceType: 'folder', fleetTopHandle: true } }));
      edges.push({ id: `e-f${i}`, source: 'agent-1', target: `folder-agent-1-f${i}`, data: { category: 'skills' } } as Edge);
    }
    const out = consolidateFleetResources(nodes, edges); // 6 direct edges, total=5 → must still aggregate
    expect(out.nodes.filter((n) => (n.data as any).fleetAggregator).length).toBe(1);
    expect(out.nodes.some((n) => n.id === 'res-agent-1-all-tools')).toBe(false);
    expect(out.nodes.filter((n) => n.id.startsWith('folder-agent-1-')).length).toBe(0);
  });

  it('is idempotent (re-running does not re-aggregate)', () => {
    const { nodes, edges } = buildAgent(8, { tools: 8 });
    const once = consolidateFleetResources(nodes, edges);
    const twice = consolidateFleetResources(once.nodes, once.edges);
    expect(twice.nodes.length).toBe(once.nodes.length);
    expect(twice.nodes.filter((n) => (n.data as any).fleetAggregator).length).toBe(1);
  });

  it('carries cumulative status counts + a per-resource drill-down list on the aggregator', () => {
    // 6 tool chips with varying counts → aggregate. The aggregator must sum their
    // counts (so "Resources (N)" shows cumulative ✓/✗) and list each leaf for the
    // inspector drill-down.
    const agent = makeNode({ id: 'agent-1', label: 'Orchestrator', kind: 'reasoning', data: { fleetHandles: ['tools'], fleetBottomHandles: true, fleetResourceCounts: { tools: 6 } } });
    const chips = Array.from({ length: 6 }, (_, i) => makeNode({
      id: `res-agent-1-tool-${i}`, label: `Tool ${i}`,
      data: { fleetResourceType: 'tool', fleetTopHandle: true, toolData: { iconSlug: `icon${i}` }, statusCounts: { COMPLETED: i, FAILED: i % 2 } },
    }));
    const edges: Edge[] = chips.map((c) => resEdge('agent-1', c.id));
    const out = consolidateFleetResources([agent, ...chips], edges);

    const agg = out.nodes.find((n) => (n.data as any).fleetAggregator)!;
    // Σ COMPLETED = 0+1+2+3+4+5 = 15 ; Σ FAILED = 0+1+0+1+0+1 = 3
    expect((agg.data as any).statusCounts).toEqual({ COMPLETED: 15, FAILED: 3 });
    const items = (agg.data as any).fleetAggregatedItems;
    expect(items).toHaveLength(6);
    expect(items.every((it: any) => it.type === 'tool')).toBe(true);
    expect(items[0]).toMatchObject({ label: 'Tool 0', iconSlug: 'icon0' });
  });
});

describe('applyFleetLayoutCached - structural-signature skip (stats-only updates)', () => {
  const agentA = makeNode({ id: 'agent-a', label: 'A', data: { fleetHandles: ['model'] } });
  const chipB = makeNode({ id: 'res-a-tool-b', label: 'B' });
  const chipC = makeNode({ id: 'res-a-tool-c', label: 'C' });
  const edgeAB: Edge = { id: 'e-ab', source: 'agent-a', target: 'res-a-tool-b' };
  const edgeAC: Edge = { id: 'e-ac', source: 'agent-a', target: 'res-a-tool-c' };

  it('first call (empty cache) runs the layout and captures positions', () => {
    const r = applyFleetLayoutCached([agentA, chipB, chipC], [edgeAB, edgeAC], '', new Map());
    expect(r.relaidOut).toBe(true);
    expect(r.nodes.map(n => n.id).sort()).toEqual(['agent-a', 'res-a-tool-b', 'res-a-tool-c']);
    expect(r.positions.size).toBe(3);
  });

  it('SKIP returns ONLY the input node set - a data-only update with a filter active can never resurrect a filtered-out node', () => {
    // The displayed (filtered) graph is [agent-a, C] - B is collapsed away.
    const filtered = [agentA, chipC];
    const first = applyFleetLayoutCached(filtered, [edgeAC], '', new Map());
    expect(first.relaidOut).toBe(true);

    // Pollute the cache with a stale full-graph position for B (as if a prior FULL layout ran),
    // then re-run the SAME filtered input with a matching sig (a stats-only update).
    const polluted = new Map(first.positions);
    polluted.set('res-a-tool-b', { x: 999, y: 999 });

    const second = applyFleetLayoutCached(filtered, [edgeAC], first.sig, polluted);
    expect(second.relaidOut).toBe(false); // Dagre skipped → caller skips fitView too
    // B must NOT reappear, even though it sits in the (polluted) position cache.
    expect(second.nodes.map(n => n.id).sort()).toEqual(['agent-a', 'res-a-tool-c']);
    // Survivors keep their cached (filtered-layout) positions.
    const cPos = second.nodes.find(n => n.id === 'res-a-tool-c')!.position;
    expect(cPos).toEqual(first.positions.get('res-a-tool-c'));
  });

  it('a changed node/edge id-set re-runs the layout (structural change always re-fits)', () => {
    const base = applyFleetLayoutCached([agentA, chipC], [edgeAC], '', new Map());
    const grown = applyFleetLayoutCached([agentA, chipB, chipC], [edgeAB, edgeAC], base.sig, base.positions);
    expect(grown.relaidOut).toBe(true);
    expect(grown.positions.has('res-a-tool-b')).toBe(true);
  });

  it('SKIP refreshes node DATA while preserving positions (badges update without Dagre)', () => {
    const withCount = makeNode({ id: 'agent-a', label: 'A', data: { fleetHandles: ['model'], statusCounts: { COMPLETED: 1 } } });
    const first = applyFleetLayoutCached([withCount, chipC], [edgeAC], '', new Map());
    // Same ids, new statusCounts (a stats update) → skip, but return the FRESH data.
    const updated = makeNode({ id: 'agent-a', label: 'A', data: { fleetHandles: ['model'], statusCounts: { COMPLETED: 5 } } });
    const second = applyFleetLayoutCached([updated, chipC], [edgeAC], first.sig, first.positions);
    expect(second.relaidOut).toBe(false);
    const a = second.nodes.find(n => n.id === 'agent-a')!;
    expect((a.data as any).statusCounts.COMPLETED).toBe(5);          // fresh data on the skip path
    expect(a.position).toEqual(first.positions.get('agent-a'));      // same position, no re-layout
  });
});
