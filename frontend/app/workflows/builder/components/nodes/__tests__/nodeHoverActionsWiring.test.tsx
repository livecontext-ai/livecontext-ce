// @vitest-environment jsdom
/**
 * Integration wiring of the bottom-bar hover delete/duplicate actions.
 *
 * FlowNode / WorkflowNode / BrowserAgentNode can show a persistent NodeBottomBar
 * in EDIT mode, so their hover delete/duplicate moved INTO the bar (hoverActions
 * prop) instead of the standalone NodeActionButtons. These specs pin the wiring:
 *   - edit mode passes hoverActions (and the bar renders even with no other content),
 *   - run mode / fleet mode pass none,
 *   - the delete/duplicate callbacks receive the right node id.
 * NodeBottomBar itself is covered by NodeHoverActions.test.tsx; here it is mocked
 * to capture the props each node hands it.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;

const execStatus = (over: Record<string, any> = {}) => ({
  isStepByStepMode: false,
  isReady: false,
  isReadyRaw: false,
  canExecute: false,
  isExecuting: false,
  isRerunning: false,
  isRunning: false,
  isFailed: false,
  isSkipped: false,
  isCompleted: false,
  canRerun: false,
  executeStep: vi.fn(),
  rerunStep: vi.fn(),
  fireFromAnyEpoch: vi.fn(),
  ...over,
});

// Captures every NodeBottomBar render's props, per test.
const bottomBarProps: any[] = [];
vi.mock('../NodeBottomBar', () => ({
  BTN_CLS: 'btn-cls-stub',
  ShimmerOverlay: () => null,
  NodeBottomBar: (props: any) => {
    bottomBarProps.push(props);
    return <div data-testid="bottom-bar" />;
  },
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../../contexts/StepByStepContext', () => ({
  useNodeExecutionStatus: () => mockExec,
}));
vi.mock('../../../contexts/ValidationContext', () => ({
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('../../../nodes/nodeClasses', () => ({
  findNodeClassById: () => undefined,
}));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../../NodePlayButton', () => ({
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => <div data-testid="standalone-action-buttons" /> };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
  useReactFlow: () => ({ getNodes: () => [], getEdges: () => [] }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => null,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// --- FlowNode-only heavy dependencies ---
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined, isLoading: false }),
  useInterfaceRender: () => ({ data: undefined, isLoading: false }),
}));
// Fully stubbed (importOriginal would drag the fleet canvas + reactflow edge
// helpers in). Flags mirror a plain non-trigger action node.
vi.mock('../../../hooks/useNodeContextualButtons', () => ({
  deriveNodeContextFlags: () => ({
    isSubWorkflowNode: false,
    isWorkflowsTriggerNode: false,
    referencedWorkflowId: undefined,
    isWebhookTrigger: false,
    isScheduleTrigger: false,
    isManualTrigger: false,
    isChatTrigger: false,
    isFormTrigger: false,
    isErrorTrigger: false,
    isTablesTrigger: false,
    isTriggerNode: false,
    isStaticFileProducingNode: false,
    isInterfaceNode: false,
    triggerVariant: 'play',
  }),
  useNodeContextualButtons: () => [],
}));
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({ totalItems: 0, currentIndex: 0, currentItem: undefined, goToIndex: vi.fn(), getObjectAtPath: vi.fn() }),
}));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({
  useAgentActivity: () => null,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [undefined],
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: () => '',
  normalizeFileRef: (x: any) => x,
  findFileRefs: () => [],
  isFileRef: () => false,
}));
vi.mock('../../interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('../FleetTriggerButtons', () => ({ FleetTriggerButtons: () => null }));
vi.mock('../TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));
vi.mock('../TriggerEditLaunchButton', () => ({ TriggerEditLaunchButton: () => null }));
vi.mock('../ResizableNodeWrapper', () => ({ ResizableNodeWrapper: () => null }));
// BrowserAgentNode side panel content (heavy, lazily opened)
vi.mock('../shared/BrowserLiveCdpPanel', () => ({ AgentBrowsePanelContent: () => null }));

import { FlowNode } from '../FlowNode';
import { WorkflowNode } from '../WorkflowNode';
import { BrowserAgentNode } from '../BrowserAgentNode';

beforeEach(() => {
  bottomBarProps.length = 0;
  mockMode = {
    isRunMode: false,
    isPreviewOnly: false,
    runId: undefined,
    viewingEpoch: null,
    setViewingEpoch: vi.fn(),
    workflowId: undefined,
  };
  mockExec = execStatus();
});

const baseData = (over: Record<string, any> = {}) => ({
  id: 'node-1',
  label: 'My Node',
  kind: 'action',
  onDeleteNode: vi.fn(),
  onDuplicateNode: vi.fn(),
  ...over,
});

const lastBar = () => bottomBarProps[bottomBarProps.length - 1];

describe('FlowNode hover-actions wiring', () => {
  it('passes hoverActions to the bottom bar in edit mode (bar renders even with no persistent buttons)', () => {
    const data = baseData();
    render(<FlowNode data={data as any} selected={false} id="rf-1" {...({} as any)} />);
    expect(bottomBarProps.length).toBeGreaterThan(0);
    const bar = lastBar();
    expect(bar.hoverActions).toBeTruthy();
    expect(typeof bar.hoverActions.onDelete).toBe('function');
    expect(typeof bar.hoverActions.onDuplicate).toBe('function');
    // The whole bar is hover-revealed: the node wires its hover state in.
    expect(typeof bar.hover?.isVisible).toBe('boolean');
    expect(typeof bar.hover?.onHover).toBe('function');
  });

  it('calls onDeleteNode / onDuplicateNode with the ReactFlow node id', () => {
    const data = baseData();
    render(<FlowNode data={data as any} selected={false} id="rf-1" {...({} as any)} />);
    const bar = lastBar();
    bar.hoverActions.onDelete();
    bar.hoverActions.onDuplicate();
    expect(data.onDeleteNode).toHaveBeenCalledWith('rf-1');
    expect(data.onDuplicateNode).toHaveBeenCalledWith('rf-1');
  });

  it('passes NO hoverActions in run mode', () => {
    mockMode.isRunMode = true;
    mockExec = execStatus({ isStepByStepMode: true });
    render(<FlowNode data={baseData() as any} selected={false} id="rf-1" {...({} as any)} />);
    expect(bottomBarProps.length).toBeGreaterThan(0);
    expect(lastBar().hoverActions).toBeUndefined();
  });

  it('passes NO hoverActions in fleet mode (the fleet top cluster owns edit/delete)', () => {
    const data = baseData({ fleetBottomHandles: true });
    render(<FlowNode data={data as any} selected={false} id="rf-1" {...({} as any)} />);
    for (const bar of bottomBarProps) {
      expect(bar.hoverActions).toBeUndefined();
    }
  });

  it('no longer renders the standalone NodeActionButtons', () => {
    const { queryByTestId } = render(<FlowNode data={baseData() as any} selected={false} id="rf-1" {...({} as any)} />);
    expect(queryByTestId('standalone-action-buttons')).toBeNull();
  });
});

describe('WorkflowNode hover-actions wiring', () => {
  it('passes hoverActions to the bottom bar in edit mode (bar renders without a sub-workflow button)', () => {
    const data = baseData();
    render(<WorkflowNode data={data as any} selected={false} id="rf-2" {...({} as any)} />);
    expect(bottomBarProps.length).toBeGreaterThan(0);
    const bar = lastBar();
    expect(bar.hoverActions).toBeTruthy();
  });

  it('calls onDeleteNode / onDuplicateNode with the logical node id (data.id)', () => {
    const data = baseData();
    render(<WorkflowNode data={data as any} selected={false} id="rf-2" {...({} as any)} />);
    const bar = lastBar();
    bar.hoverActions.onDelete();
    bar.hoverActions.onDuplicate();
    expect(data.onDeleteNode).toHaveBeenCalledWith('node-1');
    expect(data.onDuplicateNode).toHaveBeenCalledWith('node-1');
  });

  it('passes NO hoverActions in run mode and renders no empty bar when nothing else shows', () => {
    mockMode.isRunMode = true;
    render(<WorkflowNode data={baseData() as any} selected={false} id="rf-2" {...({} as any)} />);
    // No play (not step-by-step), no sub-workflow button, no hover actions → no bar at all.
    expect(bottomBarProps.length).toBe(0);
  });

  it('no longer renders the standalone NodeActionButtons', () => {
    const { queryByTestId } = render(<WorkflowNode data={baseData() as any} selected={false} id="rf-2" {...({} as any)} />);
    expect(queryByTestId('standalone-action-buttons')).toBeNull();
  });
});

describe('BrowserAgentNode hover-actions wiring', () => {
  it('passes hoverActions alongside the always-present live-view button', () => {
    const data = baseData({ kind: 'browser_agent' });
    render(<BrowserAgentNode data={data as any} selected={false} id="rf-3" {...({} as any)} />);
    expect(bottomBarProps.length).toBeGreaterThan(0);
    const bar = lastBar();
    expect(bar.hoverActions).toBeTruthy();
    expect(bar.buttons?.length).toBeGreaterThan(0);
    expect(typeof bar.hover?.isVisible).toBe('boolean');
    bar.hoverActions.onDelete();
    expect(data.onDeleteNode).toHaveBeenCalledWith('node-1');
  });

  it('no longer renders the standalone NodeActionButtons', () => {
    const data = baseData({ kind: 'browser_agent' });
    const { queryByTestId } = render(<BrowserAgentNode data={data as any} selected={false} id="rf-3" {...({} as any)} />);
    expect(queryByTestId('standalone-action-buttons')).toBeNull();
  });
});
