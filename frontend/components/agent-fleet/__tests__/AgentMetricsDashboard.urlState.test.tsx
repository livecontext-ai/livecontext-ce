// @vitest-environment jsdom
/**
 * The metrics dashboard keeps its selections in the address (chart period and agent, overview
 * agent, tool-stats agent, expanded row), so a reload reopens it as it was. Pinned both ways:
 * an address carrying them loads the matching data on the first pass, and a change writes it.
 * Plus the guard a restorable id needs: one that names an agent that no longer exists falls
 * back to "all" instead of leaving the dashboard on a selection it cannot draw.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const summary = vi.hoisted(() => ({
  totalExecutions: 3, successCount: 3, failureCount: 0, cancelledCount: 0, loopDetectedCount: 0,
  totalTokensUsed: 10, totalToolCalls: 1, totalDurationMs: 300, totalCreditsConsumed: 0,
  avgDurationMs: 100, successRate: 100, totalAgents: 1,
}));
const service = vi.hoisted(() => ({
  getAgents: vi.fn(),
  getFleetSummary: vi.fn(),
  getChatSummary: vi.fn(),
  getAgentTypeSummary: vi.fn(),
  getToolStats: vi.fn(),
  getDailyStats: vi.fn(),
  getChatDailyStats: vi.fn(),
  getChatToolStats: vi.fn(),
  getAgentToolStats: vi.fn(),
  getAgentExecutions: vi.fn(),
  getChatExecutions: vi.fn(),
  getAgentTypeExecutions: vi.fn(),
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('@/lib/api/orchestrator/agent.service', () => ({ agentService: service }));
vi.mock('next-intl', () => ({
  useTranslations: () => (k: string) => k,
  useLocale: () => 'en',
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/resources/resourceDeleted', () => ({ useResourceRowsDeleted: () => {} }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/contexts/UnifiedAppContext', () => ({ useUnifiedAppSafe: () => null }));
vi.mock('@/hooks/useModels', () => ({ useModels: () => ({ models: [] }), modelMatches: () => false }));
vi.mock('@/hooks/useThemeSafely', () => ({ useThemeValue: () => 'light' }));
vi.mock('../AgentExecutionDetail', () => ({ AgentExecutionConversation: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null, AGENT_CONFIGURATION_TAB: 'config' }));
vi.mock('@/components/agents/StopReasonBadge', () => ({ StopReasonBadge: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <div data-testid="select" data-value={value}>{children}</div>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: () => null,
  SelectItem: () => null,
}));
vi.mock('recharts', () => {
  const Stub = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return {
    BarChart: Stub, Bar: Stub, XAxis: Stub, YAxis: Stub, CartesianGrid: Stub, Tooltip: Stub,
    ResponsiveContainer: Stub, Legend: Stub,
  };
});

import { AgentMetricsDashboard } from '../AgentMetricsDashboard';

const AGENT = { id: 'agent-1', name: 'Nova', totalExecutions: 2, successCount: 2 };
const emptyPage = { content: [], totalPages: 0, number: 0, first: true, last: true };

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/agent');
  Object.values(service).forEach((fn) => fn.mockReset());
  service.getAgents.mockResolvedValue([AGENT]);
  service.getFleetSummary.mockResolvedValue(summary);
  service.getChatSummary.mockResolvedValue(summary);
  service.getAgentTypeSummary.mockResolvedValue(null);
  service.getToolStats.mockResolvedValue([]);
  service.getDailyStats.mockResolvedValue([]);
  service.getChatDailyStats.mockResolvedValue([]);
  service.getChatToolStats.mockResolvedValue([]);
  service.getAgentToolStats.mockResolvedValue([]);
  service.getAgentExecutions.mockResolvedValue(emptyPage);
  service.getChatExecutions.mockResolvedValue(emptyPage);
  service.getAgentTypeExecutions.mockResolvedValue(emptyPage);
});
afterEach(cleanup);

/** The dashboard is past its skeleton once the period buttons are drawn. */
const loaded = () => screen.findByText('period7d');

describe('AgentMetricsDashboard - the selections live in the address', () => {
  it('opens on the period and agent the address carries and loads that chart first', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=metrics&period=7&chart=agent-1');
    render(<AgentMetricsDashboard />);
    await loaded();

    expect(service.getDailyStats).toHaveBeenCalledWith(7, 'agent-1');
    expect(service.getDailyStats).not.toHaveBeenCalledWith(30, undefined);
  });

  it('loads the general chat chart when that is the restored selection', async () => {
    // The first load used to ask the agent endpoint whatever the selection was, which was
    // harmless while the selection always started empty.
    fakeFolderRouter.navigate('/en/app/agent?view=metrics&chart=__chat__&period=90');
    render(<AgentMetricsDashboard />);
    await loaded();

    expect(service.getChatDailyStats).toHaveBeenCalledWith(90);
    expect(service.getDailyStats).not.toHaveBeenCalled();
  });

  it('loads the tool stats and the expanded row the address names', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=metrics&tools=agent-1&expanded=agent-1');
    render(<AgentMetricsDashboard />);
    await loaded();

    expect(service.getAgentToolStats).toHaveBeenCalledWith('agent-1');
    expect(service.getAgentExecutions).toHaveBeenCalledWith('agent-1', 0, 10);
  });

  it('falls back to the default period on one the buttons do not offer', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=metrics&period=45');
    render(<AgentMetricsDashboard />);
    await loaded();

    expect(service.getDailyStats).toHaveBeenCalledWith(30, undefined);
  });

  it('drops a selection that names an agent that no longer exists', async () => {
    fakeFolderRouter.navigate(
      '/en/app/agent?view=metrics&overview=gone&chart=gone&tools=gone&expanded=gone',
    );
    render(<AgentMetricsDashboard />);
    await loaded();

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('view=metrics'));
    // The overview is drawn again, selector included: on the stale id it had nothing to show
    // and hid the very control that could have changed it.
    expect(screen.getByText('overview')).toBeInTheDocument();
    const selections = screen.getAllByTestId('select').map((el) => el.getAttribute('data-value'));
    expect(selections.length).toBeGreaterThanOrEqual(2);
    expect(selections.every((value) => value === '__all__')).toBe(true);
  });

  it('keeps the selections in the address when the load fails, so a reload comes back to them', async () => {
    // A dropped connection: the page shell loads, the metrics do not. An empty agent list from
    // a failure does not mean the selected agent is gone.
    fakeFolderRouter.navigate('/en/app/agent?view=metrics&chart=agent-1&tools=agent-1&expanded=agent-1');
    service.getAgents.mockRejectedValue(new Error('network down'));
    render(<AgentMetricsDashboard />);

    await waitFor(() => expect(service.getAgents).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText('period7d')).toBeInTheDocument());
    expect(fakeFolderRouter.search()).toBe('view=metrics&chart=agent-1&tools=agent-1&expanded=agent-1');
  });

  it('writes the period, keeping the tab', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=metrics');
    render(<AgentMetricsDashboard />);
    fireEvent.click(await loaded());

    expect(fakeFolderRouter.search()).toBe('view=metrics&period=7');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
    await waitFor(() => expect(service.getDailyStats).toHaveBeenCalledWith(7, undefined));
  });

  it('writes the expanded row and removes it when the row is collapsed', async () => {
    fakeFolderRouter.navigate('/en/app/agent?view=metrics');
    render(<AgentMetricsDashboard />);
    await loaded();

    fireEvent.click(screen.getByText('Nova'));
    expect(fakeFolderRouter.search()).toBe('view=metrics&expanded=agent-1');
    await waitFor(() => expect(service.getAgentExecutions).toHaveBeenCalledWith('agent-1', 0, 10));

    fireEvent.click(screen.getByText('Nova'));
    expect(fakeFolderRouter.search()).toBe('view=metrics');
  });
});
