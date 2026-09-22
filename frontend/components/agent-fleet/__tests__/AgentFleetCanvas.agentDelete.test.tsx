// @vitest-environment jsdom
/**
 * The wiring behind the agent node's Delete button: node click to API call.
 *
 * `fleetAgentDeletion.test.ts` proves the rule and `flowNode.fleetAgentDelete
 * .test.tsx` proves the button, and between them they still could not see the two
 * defects that shipped in the first draft of this feature: the canvas offered the
 * delete on sub-agent nodes (which render as `agent-<id>` too, so deleting one
 * destroyed a collaborator the user meant to detach), and it skipped the refetch
 * for every deletion on a single-agent canvas, leaving the deleted node on screen.
 * Both lived in the seam, so the seam is what this exercises: the real handler the
 * canvas injects into node data, called the way the node calls it.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const deleteAgent = vi.fn();
const refetch = vi.fn();
const disconnectFleetResource = vi.fn();

const SUBJECT = 'a0000000-0000-4000-8000-000000000001';
const SUB_AGENT = 'a0000000-0000-4000-8000-000000000002';

/** Nodes as the fleet hooks build them: an agent, a sub-agent, and a tool chip. */
const rawNodes = () => ([
  { id: `agent-${SUBJECT}`, type: 'flowNode', position: { x: 0, y: 0 }, data: { id: `agent-${SUBJECT}`, label: 'Nova' } },
  { id: `agent-${SUB_AGENT}`, type: 'flowNode', position: { x: 0, y: 0 }, data: { id: `agent-${SUB_AGENT}`, label: 'Helper' } },
  {
    id: `res-${SUBJECT}-tool-slack:post`,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id: `res-${SUBJECT}-tool-slack:post`, label: 'Slack', fleetResourceType: 'tool' },
  },
]);

/**
 * The tool chip needs its edge: the canvas drops any non-agent node that lost all
 * its edges to the category filter, so an edgeless fixture would leave nothing to
 * click and every assertion about it would pass for the wrong reason.
 */
const rawEdges = () => ([
  {
    id: `e-${SUBJECT}-slack`,
    source: `agent-${SUBJECT}`,
    target: `res-${SUBJECT}-tool-slack:post`,
    data: { category: 'tools' },
  },
]);

const agents = () => ([
  { id: SUBJECT, name: 'Nova' },
  { id: SUB_AGENT, name: 'Helper' },
]);

/**
 * The nodes the canvas last wrote back, which is where the injected callbacks and
 * `fleetCanDeleteAgent` live: the canvas computes them in an effect and applies
 * them through `setNodes`, so a stubbed setter would hide the very thing under test.
 */
let injectedNodes: any[] = [];
const captureNodes = (next: any) => {
  injectedNodes = typeof next === 'function' ? next(injectedNodes) : next;
};

const fleetHookState = () => ({
  nodes: rawNodes(), edges: rawEdges(), setNodes: captureNodes, setEdges: vi.fn(),
  onNodesChange: vi.fn(), onEdgesChange: vi.fn(), isLoading: false,
  allNodesRaw: rawNodes(), allEdgesRaw: rawEdges(), collapsibleGroupIds: [], refetch,
  agents: agents(), allAgents: agents(), visibleAgents: agents(), agent: agents()[0],
  skillsByAgent: {}, resourcesById: {}, onConnect: vi.fn(),
  workflowNames: {}, interfaceNames: {}, dataSourceNames: {}, skillFolders: [],
});

vi.mock('reactflow', () => ({
  __esModule: true,
  default: () => <div data-testid="rf" />,
  Background: () => null,
  BackgroundVariant: { Dots: 'dots' },
  ConnectionMode: { Loose: 'loose' },
  Panel: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  getBezierPath: () => ['', 0, 0],
  getSmoothStepPath: () => ['', 0, 0],
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useNodes: () => [],
  useEdges: () => [],
  useNodeId: () => null,
  useReactFlow: () => ({ getNodes: () => [], getEdges: () => [], fitView: vi.fn() }),
  useNodesState: () => [[], vi.fn(), vi.fn()],
  useEdgesState: () => [[], vi.fn(), vi.fn()],
  addEdge: (_c: unknown, e: unknown[]) => e,
}));

vi.mock('../useAgentFleetState', () => ({
  useAgentFleetState: () => fleetHookState(),
  applyFleetLayout: (nodes: unknown) => nodes,
  filterCollapsedNodes: (visibleNodes: unknown, visibleEdges: unknown) => ({ visibleNodes, visibleEdges }),
}));
vi.mock('../useSingleAgentFleet', () => ({ useSingleAgentFleet: () => fleetHookState() }));
vi.mock('../useSnapshotAgentFleet', () => ({ useSnapshotAgentFleet: () => fleetHookState() }));
// Returns the layout result shape the canvas destructures, not the bare array:
// `setNodes(cached.nodes)` on a bare array writes undefined, which is exactly the
// kind of fixture that certifies its author's assumption instead of the code.
vi.mock('../fleetLayout', () => ({
  applyFleetLayoutCached: (nodes: unknown) => ({ nodes, sig: 'sig', positions: {}, relaidOut: false }),
}));

// Announces after the call resolves, exactly as the real `deleteAgent` does. A
// bare spy here would be a fixture that removes the very thing under test: the
// canvas now heals from the broadcast, not from its own confirm handler, so a
// mock that never announces would prove the opposite of production.
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    deleteAgent: async (id: string) => {
      await deleteAgent(id);
      const { notifyResourceDeleted } = await import('@/lib/resources/resourceDeleted');
      notifyResourceDeleted('agent', id);
    },
  },
}));
vi.mock('@/lib/agents/agentResourceMutations', () => ({
  disconnectFleetResource: (...a: unknown[]) => disconnectFleetResource(...a),
  disconnectSubAgent: vi.fn(),
  isDisconnectableFleetResource: () => true,
  resolveFleetEdgeAction: () => null,
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars?.name ? `${key}:${vars.name}` : key) }));
// Spread the real module: the canvas tree also reads useCurrentOrg out of it, and
// a literal factory silently drops every other export.
vi.mock('@/lib/stores/current-org-store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/stores/current-org-store')>()),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/agent',
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useThemeSafely', () => ({ useThemeSafely: () => ({ theme: 'light' }) }));
vi.mock('@/hooks/useSvgSafeId', () => ({ useSvgSafeId: () => 'pattern-1' }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('./hooks/useAgentActivityStream', () => ({ useAgentActivitySubscriber: vi.fn() }));
vi.mock('../hooks/useAgentActivityStream', () => ({ useAgentActivitySubscriber: vi.fn() }));
vi.mock('@/app/workflows/builder/hooks/useInspectorDrag', () => ({
  useInspectorDrag: () => ({ position: { x: 0, y: 0 }, handleDragStart: vi.fn() }),
}));
vi.mock('@/app/workflows/builder/constants/graphTypes', () => ({ nodeTypes: {} }));
vi.mock('../FleetInspectorPanel', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../FleetInspectorPanel')>()),
  FleetInspectorPanel: () => null,
}));
// The canvas wraps itself in the workflow builder's providers, which reach for
// auth, feature flags and queries this test has no interest in. Pass-throughs.
// (Inline, not a shared const: vi.mock factories are hoisted above every binding.)
vi.mock('@/app/workflows/builder/contexts/ValidationContext', () => ({
  ValidationProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useValidation: () => ({ hasNodeErrors: () => false }),
}));
vi.mock('@/app/workflows/builder/contexts/StepByStepContext', () => ({
  StepByStepProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useNodeExecutionStatus: () => ({}),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: false }),
}));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({
  WorkflowLayoutDirectionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowLayoutDirection: () => ({ direction: 'TB', setDirection: vi.fn() }),
}));
vi.mock('@/app/workflows/builder/components/EdgeActionsContext', () => ({
  EdgeActionsProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useEdgeActions: () => ({ hoveredEdgeId: null, onDeleteEdge: vi.fn(), onUpdateEdgeData: vi.fn() }),
}));
vi.mock('../FleetPlanGenerator', () => ({ FleetPlanGenerator: () => null }));
vi.mock('../AgentPickerPanel', () => ({ AgentPickerPanel: () => null }));
vi.mock('../AgentIntegrationToolsModal', () => ({ AgentIntegrationToolsModal: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({ CreateAgentModal: () => null }));
vi.mock('../FleetEdge', () => ({ FleetEdge: () => null }));

/** Stands in for the confirmation modal so the test can read and drive it. */
let modal: Record<string, any> | null = null;
vi.mock('@/components/chat/ConfirmDeleteModal', () => ({
  ConfirmDeleteModal: (props: Record<string, any>) => { modal = props; return null; },
}));

import { AgentFleetCanvas } from '../AgentFleetCanvas';
import { notifyResourceDeleted } from '@/lib/resources/resourceDeleted';

const nodeData = (id: string) => injectedNodes.find(n => n.id === id)?.data ?? {};

/** The canvas pulls in the builder's validation context, which runs a query. */
const mount = (props: { singleAgentId?: string } = {}) => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <AgentFleetCanvas {...props} />
  </QueryClientProvider>,
);

/** Click the Delete button of a node, exactly as FlowNode does. */
const clickDelete = (id: string) => act(() => { nodeData(id).onFleetDelete?.(id); });

// jsdom has no ResizeObserver, and the canvas measures its container on mount.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as { ResizeObserver?: unknown }).ResizeObserver ??= NoopResizeObserver;

beforeEach(() => {
  injectedNodes = [];
  modal = null;
  deleteAgent.mockReset().mockResolvedValue(undefined);
  refetch.mockReset().mockResolvedValue(undefined);
  disconnectFleetResource.mockReset().mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('the fleet canvas (every node is one of the workspace agents)', () => {
  beforeEach(() => { mount(); });

  it('injects the delete handler onto every node the user can see', () => {
    // Guards every other assertion here: a node filtered off the canvas has no
    // handler, and `clickDelete` would then quietly do nothing.
    expect(injectedNodes.map(n => n.id).sort()).toEqual([
      `agent-${SUBJECT}`, `agent-${SUB_AGENT}`, `res-${SUBJECT}-tool-slack:post`,
    ].sort());
    injectedNodes.forEach(n => expect(typeof n.data.onFleetDelete).toBe('function'));
  });

  it('clears both agent nodes for deletion', () => {
    expect(nodeData(`agent-${SUBJECT}`).fleetCanDeleteAgent).toBe(true);
    expect(nodeData(`agent-${SUB_AGENT}`).fleetCanDeleteAgent).toBe(true);
  });

  it('names the agent in the confirmation and deletes exactly it', async () => {
    clickDelete(`agent-${SUB_AGENT}`);

    expect(modal!.isOpen).toBe(true);
    expect(modal!.message).toBe('deleteConfirmation:Helper');

    await act(async () => { await modal!.onConfirm(); });

    expect(deleteAgent).toHaveBeenCalledWith(SUB_AGENT);
    expect(deleteAgent).toHaveBeenCalledTimes(1);
  });

  it('refetches ONCE, so the deleted node leaves the canvas', async () => {
    // The refetch is owned by the deletion broadcast, not by the confirm handler,
    // so the canvas heals whether it or another surface performed the delete - and
    // a delete it performed itself does not read the list twice.
    clickDelete(`agent-${SUBJECT}`);
    await act(async () => { await modal!.onConfirm(); });

    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('heals when the delete happened somewhere ELSE entirely', async () => {
    // The gap unpinning the agents-list tab opened: that tab's menu can now delete
    // an agent while this canvas is on screen, and nothing else would tell it.
    await act(async () => { notifyResourceDeleted('agent', SUB_AGENT); });

    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('ignores a deletion of something that is not an agent', async () => {
    await act(async () => { notifyResourceDeleted('workflow', SUBJECT); });

    expect(refetch).not.toHaveBeenCalled();
  });

  it('says so when the delete is refused, instead of re-arming the same question', async () => {
    clickDelete(`agent-${SUBJECT}`);
    deleteAgent.mockRejectedValueOnce(new Error('HTTP 403'));

    await act(async () => { await modal!.onConfirm(); });

    expect(modal!.isOpen).toBe(true);
    // Names the agent that is still there, and nothing announced a deletion, so
    // no surface refetched on the strength of a refusal.
    expect(modal!.message).toBe('deleteFailed:Nova');
    expect(refetch).not.toHaveBeenCalled();
  });

  it('leaves a resource node meaning UNLINK, not delete', async () => {
    clickDelete(`res-${SUBJECT}-tool-slack:post`);
    await act(async () => { await modal!.onConfirm(); });

    expect(deleteAgent).not.toHaveBeenCalled();
    expect(disconnectFleetResource).toHaveBeenCalled();
  });
});

describe('the single-agent canvas (the side panel Configuration tab)', () => {
  beforeEach(() => { mount({ singleAgentId: SUBJECT }); });

  it('clears the subject and REFUSES the sub-agent', () => {
    expect(nodeData(`agent-${SUBJECT}`).fleetCanDeleteAgent).toBe(true);
    expect(nodeData(`agent-${SUB_AGENT}`).fleetCanDeleteAgent).toBe(false);
  });

  it('does nothing at all if a sub-agent delete is asked for anyway', () => {
    // The flag travels through node `data`, which a stale layout could carry, so
    // the handler reads the rule again rather than trusting what reached it.
    clickDelete(`agent-${SUB_AGENT}`);

    expect(modal).toBeNull();
    expect(deleteAgent).not.toHaveBeenCalled();
  });

  it('does NOT refetch when its own subject is deleted - there is nothing left to read', async () => {
    clickDelete(`agent-${SUBJECT}`);
    await act(async () => { await modal!.onConfirm(); });

    expect(deleteAgent).toHaveBeenCalledWith(SUBJECT);
    // The side panel carrying this canvas is closing on the same broadcast, so a
    // re-read would only buy a 404.
    expect(refetch).not.toHaveBeenCalled();
  });

  it('still heals when a DIFFERENT agent is deleted elsewhere', async () => {
    // A sub-agent it draws, deleted from the agents list in another tab: its node
    // and edges must go, even though this canvas is about someone else.
    await act(async () => { notifyResourceDeleted('agent', SUB_AGENT); });

    expect(refetch).toHaveBeenCalledTimes(1);
  });
});
