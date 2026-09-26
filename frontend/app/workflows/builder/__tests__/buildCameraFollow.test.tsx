/**
 * @vitest-environment jsdom
 */
/**
 * Camera follow while an AGENT builds (edit mode).
 *
 * What is worth pinning: an agent's plan sync moves the camera to the nodes it ADDED
 * (through the same scoped event a run uses, never one that selects a node), an edit
 * that adds nothing leaves the camera alone, and switching following off restores the
 * old whole-graph fit. "Added" is judged by type + label because an agent-created node
 * gets a fresh random id on every import until the canvas saves it.
 */
import * as React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { findAddedNodeIds } from '../services/buildFollow';
import { WORKFLOW_FOLLOW_NODES_EVENT } from '../services/runFollowEvent';
import {
  setRunCameraFollowEnabled,
  __resetRunCameraFollowForTests,
} from '../services/runCameraFollowStore';

const getWorkflow = vi.fn();
const importPlan = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: (...args: unknown[]) => getWorkflow(...args),
    getAgents: vi.fn(async () => []),
  },
}));
vi.mock('../services/workflowPlanImporter/WorkflowPlanImporter', () => ({
  WorkflowPlanImporter: { importPlan: (...args: unknown[]) => importPlan(...args) },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
const canvasDirection = { value: 'horizontal' };
const setWorkflowDirection = vi.fn();
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  useWorkflowLayoutDirectionSafe: () => ({ direction: canvasDirection.value, setWorkflowDirection }),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ setQueryData: vi.fn(), invalidateQueries: vi.fn() }),
}));

// Imported after the mocks it depends on.
import { useWorkflowEventListeners } from '../hooks/useWorkflowEventListeners';

const WF = 'wf-build';

function n(id: string, label: string, type = 'mcpNode'): Node<BuilderNodeData> {
  return { id, type, position: { x: 0, y: 0 }, data: { label } as BuilderNodeData } as Node<BuilderNodeData>;
}

describe('findAddedNodeIds', () => {
  it('reports the nodes the previous canvas did not have', () => {
    expect(findAddedNodeIds([n('a', 'Fetch')], [n('a', 'Fetch'), n('b', 'Send')])).toEqual(['b']);
  });

  it('ignores an id that changed on re-import, which is every agent node not yet saved', () => {
    expect(findAddedNodeIds([n('agent-1-x', 'Fetch')], [n('agent-1-y', 'Fetch')])).toEqual([]);
  });

  it('counts a second node with the same type and label as added', () => {
    expect(findAddedNodeIds([n('a', 'Fetch')], [n('a', 'Fetch'), n('b', 'Fetch')])).toEqual(['b']);
  });

  it('tells two node types with the same label apart', () => {
    expect(findAddedNodeIds([n('a', 'Check', 'decisionNode')], [n('a', 'Check', 'decisionNode'), n('b', 'Check', 'mcpNode')]))
      .toEqual(['b']);
  });

  it('treats a node whose id survived as the same node, even renamed', () => {
    expect(findAddedNodeIds([n('a', 'Fetch')], [n('a', 'Fetch orders')])).toEqual([]);
  });

  it('still reports a new node that shares its type and label with a surviving one', () => {
    expect(findAddedNodeIds([n('a', 'Fetch')], [n('a', 'Fetch'), n('x', 'Fetch')])).toEqual(['x']);
  });

  it('calls every node added when the canvas was empty', () => {
    expect(findAddedNodeIds([], [n('a', 'Fetch'), n('b', 'Send')])).toEqual(['a', 'b']);
  });
});

describe('useWorkflowEventListeners: agent plan sync', () => {
  let events: CustomEvent[];
  const record = (e: Event) => events.push(e as CustomEvent);

  beforeEach(() => {
    vi.useFakeTimers();
    events = [];
    __resetRunCameraFollowForTests();
    window.localStorage.clear();
    getWorkflow.mockReset();
    importPlan.mockReset();
    canvasDirection.value = 'horizontal';
    setWorkflowDirection.mockReset();
    getWorkflow.mockResolvedValue({ id: WF, plan: { steps: [] } });
    window.addEventListener(WORKFLOW_FOLLOW_NODES_EVENT, record);
    window.addEventListener('workflowViewFitView', record);
    window.addEventListener('workflowFocusNode', record);
  });

  afterEach(() => {
    window.removeEventListener(WORKFLOW_FOLLOW_NODES_EVENT, record);
    window.removeEventListener('workflowViewFitView', record);
    window.removeEventListener('workflowFocusNode', record);
    vi.useRealTimers();
  });

  function mount(initial: Node<BuilderNodeData>[], isRunMode = false) {
    const nodesRef = { current: initial };
    const edgesRef = { current: [] as Edge[] };
    renderHook(() =>
      useWorkflowEventListeners({
        workflowId: WF,
        isRunMode,
        setNodes: vi.fn(),
        setEdges: vi.fn(),
        nodesRef,
        edgesRef,
      }),
    );
    return nodesRef;
  }

  async function agentSyncs(nodes: Node<BuilderNodeData>[]) {
    importPlan.mockResolvedValue({ success: true, nodes, edges: [], laidOutFromScratch: false });
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanModified'));
      await vi.runAllTimersAsync();
    });
  }

  it('regression: re-reads the plan as THIS canvas, taking unstamped positions as its own', async () => {
    // The agent session used to drop the plan's direction on save. Read as legacy
    // horizontal, a vertical workflow was re-laid out after every agent action.
    canvasDirection.value = 'vertical';
    mount([n('a', 'Fetch')]);
    await agentSyncs([n('a', 'Fetch')]);

    expect(importPlan.mock.calls.at(-1)?.[2]).toEqual({
      fallbackDirection: 'vertical', unstampedPositionsDirection: 'vertical',
    });
  });

  it('puts the canvas in the direction the synced nodes were placed in', async () => {
    // The agent stated horizontal with set_plan: the importer kept its positions in that
    // direction, so the handles must follow, or they would contradict the positions.
    canvasDirection.value = 'vertical';
    mount([n('a', 'Fetch')]);
    importPlan.mockResolvedValue({
      success: true, nodes: [n('a', 'Fetch')], edges: [], laidOutFromScratch: false, layoutDirection: 'horizontal',
    });
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanModified'));
      await vi.runAllTimersAsync();
    });

    expect(setWorkflowDirection).toHaveBeenCalledWith('horizontal');
  });

  it('frames the node the agent just added, on this workflow, without selecting it', async () => {
    mount([n('a', 'Fetch')]);
    await agentSyncs([n('a2', 'Fetch'), n('b', 'Send')]);
    expect(events.map((e) => e.type)).toEqual([WORKFLOW_FOLLOW_NODES_EVENT]);
    expect(events[0].detail).toEqual({ workflowId: WF, nodeIds: ['b'] });
  });

  it('leaves the camera alone when the agent only edited existing nodes', async () => {
    mount([n('a', 'Fetch')]);
    await agentSyncs([n('a', 'Fetch')]);
    expect(events).toHaveLength(0);
  });

  it('keeps the old whole-graph fit when following is switched off', async () => {
    setRunCameraFollowEnabled(false);
    mount([n('a', 'Fetch')]);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    expect(events.map((e) => e.type)).toEqual(['workflowViewFitView']);
  });

  it('diffs against the canvas the agent built on, and the next sync against the result', async () => {
    const nodesRef = mount([n('a', 'Fetch')]);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    expect(nodesRef.current.map((x) => x.id)).toEqual(['a', 'b']);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send'), n('c', 'Log')]);
    expect(events.map((e) => e.detail?.nodeIds)).toEqual([['b'], ['c']]);
  });

  function streaming(isStreaming: boolean, workflowId = WF) {
    act(() => {
      window.dispatchEvent(new CustomEvent('workflowStreamingStateChange', { detail: { isStreaming, workflowId } }));
    });
  }

  it('frames the whole finished graph once the agent stream ends, after following it', async () => {
    mount([]);
    streaming(true);
    await agentSyncs([n('a', 'Fetch')]);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    streaming(false);
    expect(events.map((e) => e.type)).toEqual([
      WORKFLOW_FOLLOW_NODES_EVENT,
      WORKFLOW_FOLLOW_NODES_EVENT,
      'workflowViewFitView',
    ]);
  });

  it('frames the whole graph for a sync that lands after the stream ended', async () => {
    // The plan sync is debounced and fetched, so the last one routinely arrives after
    // the stream closed; framing only its new node would end the build on one node.
    mount([n('a', 'Fetch')]);
    streaming(true);
    streaming(false);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    expect(events.map((e) => e.type)).toEqual(['workflowViewFitView']);
  });

  it('ignores the stream of another workflow', async () => {
    mount([n('a', 'Fetch')]);
    streaming(true, 'another-wf');
    streaming(false, 'another-wf');
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    expect(events.map((e) => e.type)).toEqual([WORKFLOW_FOLLOW_NODES_EVENT]);
  });

  it('honours the toggle flipped between the import and the move', async () => {
    mount([n('a', 'Fetch')]);
    importPlan.mockResolvedValue({ success: true, nodes: [n('a', 'Fetch'), n('b', 'Send')], edges: [], laidOutFromScratch: false });
    await act(async () => {
      window.dispatchEvent(new CustomEvent('workflowPlanModified'));
      await vi.advanceTimersByTimeAsync(0);
    });
    setRunCameraFollowEnabled(false);
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(events.map((e) => e.type)).toEqual(['workflowViewFitView']);
  });

  it('does nothing in run mode, where the plan is fixed', async () => {
    mount([n('a', 'Fetch')], true);
    await agentSyncs([n('a', 'Fetch'), n('b', 'Send')]);
    expect(events).toHaveLength(0);
    expect(getWorkflow).not.toHaveBeenCalled();
  });
});
