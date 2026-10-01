/**
 * @vitest-environment jsdom
 */
/**
 * The agent plan-sync, end to end through the REAL importer: when the agent added a node
 * (saved without a position) to a workflow whose other nodes have positions, the canvas
 * receives a fully laid-out graph and the measured re-layout is announced for THIS
 * workflow; when the agent added nothing, the stored positions reach the canvas unchanged
 * and nothing is announced (the announcement's replay would move every node).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { LAYOUT_APPLIED_EVENT } from '@/lib/workflow/layoutAppliedEvent';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';

const getWorkflow = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: (...args: unknown[]) => getWorkflow(...args),
    getAgents: vi.fn(async () => []),
  },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/contexts/WorkflowLayoutDirectionContext')>()),
  useWorkflowLayoutDirectionSafe: () => ({ direction: 'horizontal', setWorkflowDirection: vi.fn() }),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ setQueryData: vi.fn(), invalidateQueries: vi.fn() }),
}));

// Imported after the mocks it depends on.
import { useWorkflowEventListeners } from '../hooks/useWorkflowEventListeners';

const WF = 'wf-agent-edit';

type Position = { x: number; y: number };

/** Start -> Shape -> Finish, stamped horizontal; a missing position is an agent-made node. */
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

const positionsByLabel = (nodes: Node<BuilderNodeData>[]) =>
  Object.fromEntries(nodes.map((n) => [n.data.label, n.position]));

describe('agent plan-sync: layout of the nodes the agent added', () => {
  let announced: CustomEvent[];
  const record = (e: Event) => announced.push(e as CustomEvent);

  beforeEach(() => {
    vi.useFakeTimers();
    announced = [];
    getWorkflow.mockReset();
    window.addEventListener(LAYOUT_APPLIED_EVENT, record);
  });

  afterEach(() => {
    window.removeEventListener(LAYOUT_APPLIED_EVENT, record);
    vi.useRealTimers();
  });

  /** Mounts the listeners, lets the agent sync the given stored plan, returns what the canvas got. */
  async function agentSyncs(stored: ReturnType<typeof plan>) {
    getWorkflow.mockResolvedValue({ id: WF, plan: stored });
    const setNodes = vi.fn();
    renderHook(() =>
      useWorkflowEventListeners({
        workflowId: WF,
        isRunMode: false,
        setNodes,
        setEdges: vi.fn(),
        nodesRef: { current: [] as Node<BuilderNodeData>[] },
        edgesRef: { current: [] as Edge[] },
      }),
    );
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanModified'));
      await vi.runAllTimersAsync();
    });
    expect(setNodes).toHaveBeenCalledTimes(1);
    return setNodes.mock.calls[0][0] as Node<BuilderNodeData>[];
  }

  it('regression: a node the agent added lays the whole graph out and announces it for this workflow', async () => {
    const canvas = await agentSyncs(plan({ start: { x: 0, y: 0 }, shape: { x: 290, y: 400 } }));

    const scratch = await WorkflowPlanImporter.importPlan(JSON.stringify(plan({})), [], { fallbackDirection: 'horizontal' });
    expect(positionsByLabel(canvas)).toEqual(positionsByLabel(scratch.nodes));
    expect(announced.map((e) => e.detail)).toEqual([{ workflowId: WF }]);
  });

  it('keeps the stored positions and announces nothing when the agent added no node', async () => {
    const canvas = await agentSyncs(plan({ start: { x: 0, y: 0 }, shape: { x: 290, y: 400 }, finish: { x: 580, y: 0 } }));

    expect(positionsByLabel(canvas)).toEqual({
      Start: { x: 0, y: 0 },
      Shape: { x: 290, y: 400 },
      Finish: { x: 580, y: 0 },
    });
    expect(announced).toEqual([]);
  });
});
