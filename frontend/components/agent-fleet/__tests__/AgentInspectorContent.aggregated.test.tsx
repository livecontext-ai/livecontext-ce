// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: (ns?: string) => Object.assign((k: string, _v?: any) => `${ns}.${k}`, { rich: (k: string) => k }) }));
vi.mock('next/image', () => ({ default: (props: any) => <img alt={props.alt} src={props.src} /> }));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({ NodeIcon: () => null, getIconSlug: () => undefined }));
vi.mock('@/app/workflows/builder/components/NodeStatusBadge', () => ({ NodeStatusBadge: () => null }));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({ useMcpToolDetails: () => ({ data: null }) }));
vi.mock('@/app/workflows/builder/components/inspector/OptionalSection', () => ({ OptionalSection: ({ children }: any) => <div>{children}</div> }));
vi.mock('@/app/workflows/builder/components/inspector/ExpressionField', () => ({ ExpressionField: () => null }));
vi.mock('@/app/workflows/builder/components/inspector/CredentialSection', () => ({ CredentialSection: () => null }));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({ agentService: { getAgentExecutions: vi.fn() } }));
vi.mock('./../AgentExecutionInspectorDetail', () => ({ AgentExecutionInspectorDetail: () => null }));
vi.mock('./../hooks/useAgentActivityStream', () => ({ useAgentActivity: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: vi.fn() }));
vi.mock('@/lib/format-cost', () => ({ formatCost: (v: number) => String(v), isCeMode: false }));

const { disconnectFleetResource, openFleetSidePanelTab, openTab } = vi.hoisted(() => ({
  disconnectFleetResource: vi.fn(),
  openFleetSidePanelTab: vi.fn(),
  openTab: vi.fn(),
}));
vi.mock('@/lib/agents/agentResourceMutations', () => ({ disconnectFleetResource }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ openTab }) }));
vi.mock('./../fleetSidePanelActions', () => ({
  openFleetSidePanelTab,
  FLEET_SIDE_PANEL_ICONS: { agent: () => null, workflow: () => null, table: () => null, interface: () => null },
}));
// The confirm modal renders a direct confirm button so the delete path is testable.
vi.mock('@/components/chat/ConfirmDeleteModal', () => ({
  ConfirmDeleteModal: ({ onConfirm }: any) => (
    <button data-testid="confirm-delete" onClick={onConfirm}>confirm</button>
  ),
}));

import { AgentInspectorContent, FleetInspectorPanel, parseNodeId } from '../FleetInspectorPanel';
import { consolidateFleetResources } from '../fleetLayout';

const AGENT_ID = '550e8400-e29b-41d4-a716-446655440000';
const agent = { id: AGENT_ID, name: 'Fleet Agent', toolsConfig: { mode: 'custom' } } as any;
const tStub = Object.assign((k: string) => k, { rich: (k: string) => k }) as any;

/**
 * Build the consolidated canvas state through the REAL producer, never by hand:
 * an agent with 7 resource chips run through consolidateFleetResources - the same
 * pure function the three fleet hooks call. Whatever node ids/shape it emits is
 * exactly what the inspector receives in production.
 */
function buildConsolidatedNodes() {
  const chips: Array<{ type: string; id: string; label: string }> = [
    { type: 'table', id: '123', label: 'Customers' },
    { type: 'workflow', id: 'wf-1', label: 'Daily Sync' },
    { type: 'interface', id: 'if-1', label: 'Dashboard' },
    { type: 'tool', id: 'gmail:send_email', label: 'send email' },
    { type: 'tool', id: 'all-tools', label: 'All tools' },
    { type: 'file', id: 'file-9', label: 'invoice.pdf' },
    { type: 'web_search', id: 'web-search', label: 'Web Search' },
  ];
  const agentNodeId = `agent-${AGENT_ID}`;
  const nodes: any[] = [{
    id: agentNodeId,
    position: { x: 0, y: 0 },
    data: {
      id: agentNodeId,
      label: 'Fleet Agent',
      fleetResourceCounts: { tools: 2, workflows: 1, interfaces: 1, tables: 1, files: 1, skills: 0, webSearch: true },
    },
  }];
  const edges: any[] = [];
  for (const chip of chips) {
    const resNodeId = `res-${AGENT_ID}-${chip.type}-${chip.id}`;
    nodes.push({
      id: resNodeId,
      position: { x: 0, y: 0 },
      data: { id: resNodeId, label: chip.label, fleetResourceType: chip.type },
    });
    edges.push({
      id: `res-edge-${AGENT_ID}-${chip.type}-${chip.id}`,
      source: agentNodeId,
      target: resNodeId,
      data: { category: chip.type === 'tool' ? 'tools' : 'resources' },
    });
  }
  const consolidated = consolidateFleetResources(nodes, edges);
  // Sanity: the producer really consolidated (otherwise these tests prove nothing).
  if (consolidated.nodes.some(n => n.id.startsWith(`res-${AGENT_ID}-`))) {
    throw new Error('fixture not consolidated - res-* nodes still present');
  }
  return consolidated;
}

describe('parseNodeId', () => {
  it('parses the REAL aggregator node id emitted by consolidateFleetResources back to the raw agent uuid', () => {
    const { nodes } = buildConsolidatedNodes();
    const aggNode = nodes.find(n => n.id.startsWith('agg-'))!;
    expect(aggNode).toBeDefined();
    expect(parseNodeId(aggNode.id)).toEqual({ category: 'aggregate', agentId: AGENT_ID });
  });

  it('parses a numeric-table resource node id', () => {
    expect(parseNodeId(`res-${AGENT_ID}-table-123`)).toEqual({
      category: 'resource', agentId: AGENT_ID, resourceType: 'table', resourceId: '123',
    });
  });

  it('parses a tool id containing colon and dashes', () => {
    expect(parseNodeId(`res-${AGENT_ID}-tool-slack:send-message`)).toEqual({
      category: 'resource', agentId: AGENT_ID, resourceType: 'tool', resourceId: 'slack:send-message',
    });
  });
});

describe('AgentInspectorContent - consolidated agent (6+ resources)', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  // Regression: past the aggregation threshold the res-* nodes are gone from the
  // canvas, and the inspector resource section rendered EMPTY (nothing to map,
  // edit, or delete) - exactly the broken state for agent-created agents.
  it('lists every aggregated resource even though no res-* node exists on the canvas', () => {
    const { nodes } = buildConsolidatedNodes();
    render(<AgentInspectorContent agent={agent} skills={[]} nodes={nodes as any} t={tStub} onRefresh={undefined} />);
    expect(screen.getByText('Customers')).toBeInTheDocument();
    expect(screen.getByText('Daily Sync')).toBeInTheDocument();
    expect(screen.getByText('Dashboard')).toBeInTheDocument();
    expect(screen.getByText('send email')).toBeInTheDocument();
    expect(screen.getByText('invoice.pdf')).toBeInTheDocument();
    expect(screen.getByText('Web Search')).toBeInTheDocument();
  });

  it('deletes an aggregated table through the shared disconnect path with the parsed id', async () => {
    const onRefresh = vi.fn();
    disconnectFleetResource.mockResolvedValue(undefined);
    const { nodes } = buildConsolidatedNodes();
    render(<AgentInspectorContent agent={agent} skills={[]} nodes={nodes as any} t={tStub} onRefresh={onRefresh} />);

    const row = screen.getByText('Customers').closest('div')!;
    // A table row carries [pencil (open), kebab (menu)] - the kebab has no title attr.
    const kebab = Array.from(row.querySelectorAll('button')).find(b => !b.hasAttribute('title'))!;
    fireEvent.click(kebab);
    fireEvent.click(screen.getByText('fleetInspector.removeAction'));
    fireEvent.click(screen.getByTestId('confirm-delete'));

    await waitFor(() => expect(disconnectFleetResource).toHaveBeenCalledWith(AGENT_ID, 'table', '123'));
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it('offers NO delete affordance on the synthetic "All tools" chip (mode change, not a tool)', () => {
    const { nodes } = buildConsolidatedNodes();
    render(<AgentInspectorContent agent={agent} skills={[]} nodes={nodes as any} t={tStub} onRefresh={undefined} />);
    const row = screen.getByText('All tools').closest('div')!;
    expect(row.querySelectorAll('button')).toHaveLength(0);
  });

  it('opens workflow/table/interface resources in the side panel from the inspector (edit affordance)', () => {
    const { nodes } = buildConsolidatedNodes();
    render(<AgentInspectorContent agent={agent} skills={[]} nodes={nodes as any} t={tStub} onRefresh={undefined} />);
    const row = screen.getByText('Daily Sync').closest('div')!;
    const pencil = row.querySelector('button[title="fleetInspector.edit"]')!;
    expect(pencil).not.toBeNull();
    fireEvent.click(pencil);
    expect(openFleetSidePanelTab).toHaveBeenCalledWith(
      expect.objectContaining({ openTab }),
      { type: 'workflow', resourceId: 'wf-1', label: 'Daily Sync' },
    );
  });

  it('still lists resources from live res-* nodes below the threshold (non-consolidated path unchanged)', () => {
    const nodes = [
      { id: `agent-${AGENT_ID}`, position: { x: 0, y: 0 }, data: { id: `agent-${AGENT_ID}`, label: 'Fleet Agent' } },
      { id: `res-${AGENT_ID}-table-42`, position: { x: 0, y: 0 }, data: { id: `res-${AGENT_ID}-table-42`, label: 'Orders', fleetResourceType: 'table' } },
    ] as any[];
    render(<AgentInspectorContent agent={agent} skills={[]} nodes={nodes} t={tStub} onRefresh={undefined} />);
    expect(screen.getByText('Orders')).toBeInTheDocument();
  });
});

describe('FleetInspectorPanel - aggregator node drill-down', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  function renderAggPanel(onRefresh?: () => void) {
    const { nodes } = buildConsolidatedNodes();
    const aggNode = nodes.find(n => n.id.startsWith('agg-'))!;
    render(
      <FleetInspectorPanel
        node={aggNode as any}
        allNodes={nodes as any}
        agents={[agent]}
        skillsByAgent={new Map()}
        resourcesById={new Map()}
        onClose={() => {}}
        onRefresh={onRefresh}
      />,
    );
    return aggNode;
  }

  // Regression: the drill-down derived the owning agent as `agent-{uuid}` (raw slice of
  // the `agg-agent-{uuid}` node id), so its delete called the agent API with a bogus id
  // → 404 → the remove button silently did nothing.
  it('deletes a drill-down item against the RAW agent uuid (not the agent-prefixed node id)', async () => {
    const onRefresh = vi.fn();
    disconnectFleetResource.mockResolvedValue(undefined);
    renderAggPanel(onRefresh);

    const row = screen.getByText('Customers').closest('div')!;
    const trash = row.querySelector('button[title="fleetInspector.removeAction"]')!;
    expect(trash).not.toBeNull();
    fireEvent.click(trash);
    fireEvent.click(screen.getByTestId('confirm-delete'));

    await waitFor(() => expect(disconnectFleetResource).toHaveBeenCalledWith(AGENT_ID, 'table', '123'));
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it('suppresses the trash on "All tools" but keeps it on real drill-down items', () => {
    renderAggPanel();
    const allToolsRow = screen.getByText('All tools').closest('div')!;
    expect(allToolsRow.querySelector('button[title="fleetInspector.removeAction"]')).toBeNull();
    const toolRow = screen.getByText('send email').closest('div')!;
    expect(toolRow.querySelector('button[title="fleetInspector.removeAction"]')).not.toBeNull();
  });

  it('opens a drill-down workflow in the side panel via the pencil', () => {
    renderAggPanel();
    const row = screen.getByText('Daily Sync').closest('div')!;
    const pencil = row.querySelector('button[title="fleetInspector.edit"]')!;
    expect(pencil).not.toBeNull();
    fireEvent.click(pencil);
    expect(openFleetSidePanelTab).toHaveBeenCalledWith(
      expect.objectContaining({ openTab }),
      { type: 'workflow', resourceId: 'wf-1', label: 'Daily Sync' },
    );
  });
});
