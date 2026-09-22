// @vitest-environment jsdom
/**
 * The fleet edit cluster above a node, and the one button in it that is not what
 * its neighbours are.
 *
 * Every other Delete in this cluster UNLINKS a resource from the agent. On the
 * agent node itself, Delete deletes the agent. Same cluster, same glyph, opposite
 * blast radius, so the agent's button carries its own label and its own styling -
 * a neutral trash beside an identical neutral trash is how someone destroys an
 * agent meaning to unhook a tool.
 *
 * The node is deliberately i18n-agnostic: the labels arrive on `data
 * .fleetEditLabels`, injected by AgentFleetCanvas. That is what these assertions
 * read, because it is the contract between the two files.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

let mockExec: any;
let mockMode: any;

const execStatus = () => ({
  isStepByStepMode: false,
  isReady: false,
  canExecuteRaw: false,
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
});

vi.mock('../NodeBottomBar', () => ({
  ShimmerOverlay: () => null,
  NodeBottomBar: () => <div data-testid="bottom-bar" />,
}));
vi.mock('../FileNodePreview', () => ({ FileNodePreview: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => mockMode }));
vi.mock('../../../contexts/StepByStepContext', () => ({ useNodeExecutionStatus: () => mockExec }));
vi.mock('../../../contexts/ValidationContext', () => ({ useValidation: () => ({ hasNodeErrors: () => false }) }));
vi.mock('../../../nodes/nodeClasses', () => ({ findNodeClassById: () => undefined }));
vi.mock('../../NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('../../NodePlayButton', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../NodePlayButton')>()),
  NodePlayButton: () => null,
  deriveNodeStatus: () => undefined,
}));
vi.mock('../shared', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../shared')>();
  return { ...actual, NodeHeader: () => null, NodeActionButtons: () => null };
});
vi.mock('reactflow', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
  useNodeId: () => null,
  useReactFlow: () => ({ getNodes: () => [], getEdges: () => [] }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab: vi.fn(), updateTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), tabs: [] }),
}));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONVERSATION_TAB: 'conversation',
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/lib/sidePanel/openFilesPanel', () => ({ openFilesPanel: vi.fn() }));
vi.mock('../../../hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined, isLoading: false }),
  useInterfaceRender: () => ({ data: undefined, isLoading: false }),
}));
vi.mock('../../../hooks/useRunOutputData', () => ({
  useRunOutputData: () => ({ totalItems: 0, currentIndex: 0, currentItem: undefined, goToIndex: vi.fn(), getObjectAtPath: vi.fn() }),
}));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({ useAgentActivity: () => null }));
vi.mock('@/contexts/WorkflowRunContext', () => ({ useRun: () => [undefined] }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { getTokenProvider: () => null, getAuthToken: async () => null } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileRefToUrl: () => '',
  normalizeFileRef: (x: any) => x,
  findFileRefs: () => [],
  isFileRef: () => false,
  fileService: { formatFileSize: () => '' },
}));
vi.mock('../../interface/InterfaceThumbnail', () => ({ InterfaceThumbnail: () => null }));
vi.mock('../FleetTriggerButtons', () => ({ FleetTriggerButtons: () => null }));
vi.mock('../TriggerNodePinButton', () => ({ TriggerNodePinButton: () => null }));
vi.mock('../TriggerEditLaunchButton', () => ({ TriggerEditLaunchButton: () => null }));
vi.mock('../ResizableNodeWrapper', () => ({ ResizableNodeWrapper: () => null }));
vi.mock('../shared/BrowserLiveCdpPanel', () => ({ AgentBrowsePanelContent: () => null }));

import { FlowNode } from '../FlowNode';

const AGENT = 'a0000000-0000-4000-8000-000000000001';
const LABELS = { edit: 'Edit', remove: 'Remove', deleteAgent: 'Delete agent' };

let onFleetEdit: ReturnType<typeof vi.fn>;
let onFleetDelete: ReturnType<typeof vi.fn>;

beforeEach(() => {
  mockMode = {
    isRunMode: false,
    isPreviewOnly: false,
    runId: null,
    viewingEpoch: null,
    setViewingEpoch: vi.fn(),
    workflowId: 'wf-1',
  };
  mockExec = execStatus();
  onFleetEdit = vi.fn();
  onFleetDelete = vi.fn();
});

/** `fleetTopHandle` is what puts a node in fleet mode; `fleetEditMode` reveals the cluster. */
const fleetData = (over: Record<string, unknown> = {}) => ({
  label: 'Nova',
  kind: 'agent',
  fleetTopHandle: true,
  fleetEditMode: true,
  // The canvas's verdict on THIS node, computed by canDeleteAgentNode: true for any
  // agent on the fleet canvas, true only for the subject on the single-agent one.
  fleetCanDeleteAgent: true,
  onFleetEdit,
  onFleetDelete,
  fleetEditLabels: LABELS,
  ...over,
});

const renderNode = (id: string, data: Record<string, unknown>) =>
  render(<FlowNode data={{ id, ...data } as any} selected={false} id={id} {...({} as any)} />);

describe('FlowNode: the fleet cluster on an agent node', () => {
  it('offers Delete beside Edit - the button the agent node never had', () => {
    const c = renderNode(`agent-${AGENT}`, fleetData());

    expect(c.queryByTitle('Edit')).not.toBeNull();
    expect(c.queryByTitle('Delete agent')).not.toBeNull();
  });

  it('labels it "Delete agent", never the neighbours\' "Remove"', () => {
    // The whole point of the separate label: on this node the verb is delete, not
    // unlink, and the tooltip is the only thing that says which before the click.
    const c = renderNode(`agent-${AGENT}`, fleetData());

    expect(c.queryByTitle('Remove')).toBeNull();
    expect(c.getByTitle('Delete agent').getAttribute('aria-label')).toBe('Delete agent');
  });

  it('hands the node id to the canvas, which is what resolves it to the agent', () => {
    const c = renderNode(`agent-${AGENT}`, fleetData());

    c.getByTitle('Delete agent').click();

    expect(onFleetDelete).toHaveBeenCalledWith(`agent-${AGENT}`);
    expect(onFleetEdit).not.toHaveBeenCalled();
  });

  it('styles the destructive one apart, so it is not a neutral twin of Edit', () => {
    const c = renderNode(`agent-${AGENT}`, fleetData());

    expect(c.getByTitle('Delete agent').className).toContain('hover:bg-red-600');
    expect(c.getByTitle('Edit').className).not.toContain('hover:bg-red-600');
  });

  it('shows NO delete when the canvas withheld it - a sub-agent in the side panel', () => {
    // On the single-agent canvas a sub-agent is ALSO an `agent-<id>` node. Deleting
    // it there would destroy a collaborator the user meant to detach, so the canvas
    // clears only its subject and this node keeps Edit alone.
    const c = renderNode(`agent-${AGENT}`, fleetData({ fleetCanDeleteAgent: false }));

    expect(c.queryByTitle('Edit')).not.toBeNull();
    expect(c.queryByTitle('Delete agent')).toBeNull();
    expect(c.queryByTitle('Remove')).toBeNull();
  });

  it('falls back to the neighbours\' label rather than an untitled button', () => {
    // A canvas built before this label existed still injects {edit, remove}; an
    // unlabelled destructive button is worse than an imprecise one.
    const c = renderNode(`agent-${AGENT}`, fleetData({ fleetEditLabels: { edit: 'Edit', remove: 'Remove' } }));

    expect(c.queryByTitle('Remove')).not.toBeNull();
  });
});

describe('FlowNode: the fleet cluster on a resource node is unchanged', () => {
  it('keeps "Remove" and the neutral styling on a tool chip', () => {
    // A resource Delete still means "unlink from the agent". If this ever picked
    // up the agent label or the red, the cluster would be lying about its reach.
    const c = renderNode(`res-${AGENT}-tool-slack:post_message`, fleetData({
      label: 'Slack',
      fleetResourceType: 'tool',
    }));

    expect(c.queryByTitle('Remove')).not.toBeNull();
    expect(c.queryByTitle('Delete agent')).toBeNull();
    expect(c.getByTitle('Remove').className).not.toContain('hover:bg-red-600');
  });

  it('gives a grouping node no buttons at all', () => {
    const c = renderNode(`agg-agent-${AGENT}`, fleetData({ label: 'Resources (3)', fleetResourceType: 'tool' }));

    expect(c.queryByTitle('Remove')).toBeNull();
    expect(c.queryByTitle('Delete agent')).toBeNull();
    expect(c.queryByTitle('Edit')).toBeNull();
  });

  it('shows nothing outside edit mode, so a viewer cannot reach the delete', () => {
    const c = renderNode(`agent-${AGENT}`, fleetData({ fleetEditMode: false }));

    expect(c.queryByTitle('Delete agent')).toBeNull();
    expect(c.queryByTitle('Edit')).toBeNull();
  });
});
