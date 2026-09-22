// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;

const { getRun, onBack, open, openTab, pathname, setActiveTab, sidePanel } = vi.hoisted(() => {
  const panel = {
    isOpen: true,
    activeTabId: 'workflow-run-wf-1-run-1' as string | null,
    tabs: [{ id: 'workflow-run-wf-1-run-1' }],
    open: vi.fn(),
    openTab: vi.fn(),
    setActiveTab: vi.fn(),
  };
  return {
    getRun: vi.fn(),
    onBack: vi.fn(),
    open: panel.open,
    openTab: panel.openTab,
    pathname: { current: '/app/workflow/wf-1' },
    setActiveTab: panel.setActiveTab,
    sidePanel: panel,
  };
});

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) =>
    params?.version ? `${key}:${params.version}` : key,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', () => ({ usePathname: () => pathname.current }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => sidePanel }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getRun } }));
vi.mock('@/components/workflow/WorkflowLogsExplorer', () => ({
  WorkflowLogsExplorer: ({
    initialStepAlias,
    view,
    onBreadcrumbChange,
  }: {
    initialStepAlias?: string;
    view?: string;
    onBreadcrumbChange?: (items: Array<{ label: string }>) => void;
  }) => {
    React.useEffect(() => {
      onBreadcrumbChange?.([{ label: 'run-1' }, { label: 'Payload' }]);
    }, [onBreadcrumbChange]);
    return <div data-testid="logs-content" data-view={view}>{initialStepAlias}</div>;
  },
}));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: () => <div data-testid="workflow-run-panel" />,
}));

import { WorkflowLogsPanelContent } from '../WorkflowLogsPanelContent';
import { useWorkflowLogsSidePanel } from '../useWorkflowLogsSidePanel';
import {
  clearPendingWorkflowPanelLogs,
  consumePendingWorkflowPanelLogs,
  requestWorkflowPanelLogs,
} from '@/lib/sidePanel/workflowLogsNavigation';
import { workflowPanelTabId } from '@/lib/sidePanel/tabResource';
import { WorkflowPanelHostProvider } from '@/contexts/WorkflowPanelHostContext';
import {
  BIND_RUN_EVENT,
  clearRunPanelCache,
  makeEmptyRunPanelData,
  publishRunPanelData,
} from '@/components/workflow/run-panel/runPanelBus';

describe('workflow logs run child view', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearRunPanelCache();
    sidePanel.isOpen = true;
    sidePanel.activeTabId = 'workflow-run-wf-1-run-1';
    sidePanel.tabs = [{ id: 'workflow-run-wf-1-run-1' }];
    pathname.current = '/app/workflow/wf-1';
    getRun.mockResolvedValue({
      id: 'internal-1',
      runId: 'run-1',
      workflowId: 'wf-1',
      status: 'COMPLETED',
      planVersion: 4,
      startedAt: '2026-09-14T08:00:00Z',
      lastFireAt: '2026-09-14T09:00:00Z',
    });
  });

  it('opens logs inside the existing run tab instead of creating a logs tab', () => {
    const { result } = renderHook(() => useWorkflowLogsSidePanel());

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      initialStepAlias: 'mcp:fetch',
    });

    expect(setActiveTab).toHaveBeenCalledWith('workflow-run-wf-1-run-1');
    expect(open).toHaveBeenCalled();
    expect(openTab).not.toHaveBeenCalled();
    expect(consumePendingWorkflowPanelLogs('workflow-run-wf-1-run-1')).toEqual(expect.objectContaining({
      workflowId: 'wf-1',
      runId: 'run-1',
      initialStepAlias: 'mcp:fetch',
    }));
  });

  it('creates a workflow run tab when the run is not already open', async () => {
    sidePanel.activeTabId = null;
    sidePanel.tabs = [];
    const { result } = renderHook(() => useWorkflowLogsSidePanel());

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      workflowName: 'Daily digest',
    });

    await waitFor(() => expect(openTab).toHaveBeenCalledTimes(1));
    const runTabId = workflowPanelTabId('wf-1', 'run-1');
    const tab = openTab.mock.calls[0][0];
    expect(tab).toEqual(expect.objectContaining({
      id: runTabId,
      label: 'Daily digest',
      keepMounted: true,
      preferredWidth: 0.55,
    }));
    expect(tab.content.props).toEqual(expect.objectContaining({
      workflowId: 'wf-1',
      runId: 'run-1',
      hostTabId: runTabId,
    }));
    consumePendingWorkflowPanelLogs(runTabId);
  });

  it('drops a pending logs handoff when the workspace changes', () => {
    requestWorkflowPanelLogs({
      targetTabId: 'workflow-run-wf-old-run-old',
      workflowId: 'wf-old',
      runId: 'run-old',
    });

    clearPendingWorkflowPanelLogs();

    expect(consumePendingWorkflowPanelLogs('workflow-run-wf-old-run-old')).toBeNull();
  });

  it('reuses the pinned workflow panel only when it belongs to the selected workflow', async () => {
    sidePanel.activeTabId = 'workflow-panel';
    sidePanel.tabs = [{ id: 'workflow-panel' }];
    const { result, rerender } = renderHook(() => useWorkflowLogsSidePanel());

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      reuseActiveWorkflowTab: true,
    });
    expect(setActiveTab).toHaveBeenCalledWith('workflow-panel');
    expect(openTab).not.toHaveBeenCalled();
    consumePendingWorkflowPanelLogs('workflow-panel');

    vi.clearAllMocks();
    pathname.current = '/app/workflow/wf-2';
    rerender();
    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      reuseActiveWorkflowTab: true,
    });

    await waitFor(() => expect(openTab).toHaveBeenCalledTimes(1));
    expect(setActiveTab).not.toHaveBeenCalled();
    consumePendingWorkflowPanelLogs(workflowPanelTabId('wf-1', 'run-1'));
  });

  it('rebinds a reused run tab so Logs and Back target the same run', () => {
    const runTabId = workflowPanelTabId('wf-1', 'run-1');
    sidePanel.activeTabId = 'files-panel';
    sidePanel.tabs = [{ id: 'files-panel' }, { id: runTabId }];
    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-1', runTabId),
      runId: 'run-2',
      runInfo: { runId: 'run-2', status: 'RUNNING' },
    });
    const bindRequests: unknown[] = [];
    const onBind = (event: Event) => bindRequests.push((event as CustomEvent).detail);
    window.addEventListener(BIND_RUN_EVENT, onBind);
    const { result } = renderHook(() => useWorkflowLogsSidePanel());

    try {
      result.current.openWorkflowLogs({ workflowId: 'wf-1', runId: 'run-1' });
    } finally {
      window.removeEventListener(BIND_RUN_EVENT, onBind);
    }

    expect(bindRequests).toEqual([{
      workflowId: 'wf-1',
      runId: 'run-1',
      surfaceId: runTabId,
    }]);
    expect(setActiveTab).toHaveBeenCalledWith(runTabId);
    consumePendingWorkflowPanelLogs(runTabId);
  });

  it('keeps application-hosted node logs inside the application run parent', () => {
    sidePanel.activeTabId = 'application-pub-1-run-1';
    sidePanel.tabs = [{ id: 'application-pub-1-run-1' }];
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <WorkflowPanelHostProvider workflowId="wf-1" hostTabId="application-pub-1-run-1">
        {children}
      </WorkflowPanelHostProvider>
    );
    const { result } = renderHook(() => useWorkflowLogsSidePanel(), { wrapper });

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      reuseActiveWorkflowTab: true,
    });

    expect(setActiveTab).toHaveBeenCalledWith('application-pub-1-run-1');
    expect(openTab).not.toHaveBeenCalled();
    consumePendingWorkflowPanelLogs('application-pub-1-run-1');
  });

  it('activates an explicit application host even when another panel tab is active', () => {
    sidePanel.activeTabId = 'files-panel';
    sidePanel.tabs = [{ id: 'files-panel' }, { id: 'application-panel' }];
    const { result } = renderHook(() => useWorkflowLogsSidePanel());

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      reuseActiveWorkflowTab: true,
      hostTabId: 'application-panel',
    });

    expect(setActiveTab).toHaveBeenCalledWith('application-panel');
    expect(openTab).not.toHaveBeenCalled();
    consumePendingWorkflowPanelLogs('application-panel');
  });

  it('activates the route workflow host even when another panel tab is active', () => {
    sidePanel.activeTabId = 'files-panel';
    sidePanel.tabs = [{ id: 'files-panel' }, { id: 'workflow-panel' }];
    const { result } = renderHook(() => useWorkflowLogsSidePanel());

    result.current.openWorkflowLogs({
      workflowId: 'wf-1',
      runId: 'run-1',
      reuseActiveWorkflowTab: true,
    });

    expect(setActiveTab).toHaveBeenCalledWith('workflow-panel');
    expect(openTab).not.toHaveBeenCalled();
    consumePendingWorkflowPanelLogs('workflow-panel');
  });

  it('returns from logs to the parent run view', async () => {
    render(
      <WorkflowLogsPanelContent
        workflowId="wf-1"
        runId="run-1"
        initialStepAlias="mcp:fetch"
        onBack={onBack}
      />,
    );

    await waitFor(() => expect(getRun).toHaveBeenCalledWith('run-1'));
    expect(screen.getByTestId('logs-content')).toHaveTextContent('mcp:fetch');

    fireEvent.click(screen.getByRole('button', { name: 'workflow.logs.backToRun' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('matches the compact run header without repeating the run id', async () => {
    render(
      <WorkflowLogsPanelContent
        workflowId="wf-1"
        runId="run-1"
        onBack={onBack}
      />,
    );

    const header = screen.getByTestId('workflow-logs-header');
    await screen.findByText('v4');
    expect(header.querySelector('[data-run-started-at]')).toHaveAttribute(
      'data-run-started-at',
      '2026-09-14T09:00:00Z',
    );
    expect(header).not.toHaveTextContent('run-1');

    const breadcrumb = screen.getByTestId('workflow-logs-breadcrumb');
    expect(header.children).toHaveLength(2);
    expect(breadcrumb).toHaveTextContent('workflow.logs.root');
    expect(breadcrumb).toHaveTextContent('Payload');
    expect(breadcrumb).not.toHaveTextContent('run-1');
    expect(screen.getAllByRole('radiogroup')).toHaveLength(1);
    const viewToggle = within(breadcrumb).getByRole('radiogroup', { name: 'workflow.logs.view' });
    expect(viewToggle).toHaveClass('h-9', 'p-0.5', 'rounded-[14px]');
    expect(viewToggle).not.toHaveClass('border');
    for (const label of ['workflow.logs.simple', 'workflow.logs.table']) {
      const option = within(viewToggle).getByRole('radio', { name: label });
      expect(option).toHaveClass('h-8', 'w-8', 'px-0');
      expect(within(option).getByText(label)).toHaveClass('sr-only');
      expect(within(option).getByTitle(label)).toBeInTheDocument();
    }
    expect(within(breadcrumb).getByRole('radio', { name: 'workflow.logs.simple' })).toBeChecked();
    expect(screen.getByTestId('logs-content')).toHaveAttribute('data-view', 'simple');
    fireEvent.click(within(breadcrumb).getByRole('radio', { name: 'workflow.logs.table' }));
    expect(screen.getByTestId('logs-content')).toHaveAttribute('data-view', 'table');
    fireEvent.click(within(breadcrumb).getByRole('radio', { name: 'workflow.logs.simple' }));
    expect(screen.getByTestId('logs-content')).toHaveAttribute('data-view', 'simple');
  });

  it('shows a load error instead of a permanent pending run status', async () => {
    getRun.mockRejectedValueOnce(new Error('network unavailable'));

    render(
      <WorkflowLogsPanelContent
        workflowId="wf-1"
        runId="run-1"
        onBack={onBack}
      />,
    );

    expect(await screen.findByTestId('workflow-logs-run-error'))
      .toHaveTextContent('workflow.logs.loadRunError');
    expect(screen.queryByTestId('run-status-badge')).not.toBeInTheDocument();
    expect(screen.getByTestId('logs-content')).toBeInTheDocument();
  });
});
