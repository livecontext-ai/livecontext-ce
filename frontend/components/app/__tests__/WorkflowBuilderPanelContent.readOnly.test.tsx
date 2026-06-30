/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';

const providerCalls = vi.hoisted(() => [] as Array<{
  workflowId?: string;
  initialRunId?: string;
  readOnly?: boolean;
}>);
const openTabMock = vi.hoisted(() => vi.fn());
const getPinnedWorkflowRunMock = vi.hoisted(() => vi.fn());
const sidePanelState = vi.hoisted(() => ({
  current: null as null | {
    openTab: typeof openTabMock;
    tabs: Array<{ id: string }>;
    removeTab: (id: string) => void;
  },
}));

type MockWorkflowModeProviderProps = {
  children: React.ReactNode;
  workflowId?: string;
  initialRunId?: string;
  readOnly?: boolean;
};

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children, workflowId, initialRunId, readOnly }: MockWorkflowModeProviderProps) => {
    providerCalls.push({ workflowId, initialRunId, readOnly });
    return <>{children}</>;
  },
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => sidePanelState.current,
}));

vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: () => undefined,
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getPinnedWorkflowRun: getPinnedWorkflowRunMock,
  },
}));

vi.mock('@/components/app/DataSourcePanelContent', () => ({
  DataSourcePanelContent: () => null,
}));

vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
}));

vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: ({ workflowCanvasSlot }: { workflowCanvasSlot?: React.ReactNode }) => (
    <div data-testid="workflow-panel">{workflowCanvasSlot}</div>
  ),
}));

vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: () => <div data-testid="workflow-canvas" />,
}));

import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';

describe('WorkflowBuilderPanelContent readOnly mode', () => {
  afterEach(() => {
    providerCalls.length = 0;
    openTabMock.mockClear();
    getPinnedWorkflowRunMock.mockReset();
    sidePanelState.current = null;
    cleanup();
  });

  it('opens workflow side-panel tabs editable by default', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-1" />);

    expect(providerCalls.map(call => call.readOnly)).toEqual([false, false]);
  });

  it('keeps preview workflows read-only when explicitly requested', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-1" readOnly />);

    expect(providerCalls.map(call => call.readOnly)).toEqual([true, true]);
  });

  it('opens run-mode workflow tabs without the preview read-only lock', () => {
    render(<WorkflowBuilderPanelContent workflowId="wf-1" runId="run-1" />);

    expect(providerCalls[0]).toMatchObject({
      workflowId: 'wf-1',
      initialRunId: 'run-1',
      readOnly: false,
    });
  });

  it('propagates explicit read-only mode to datasource tabs opened from the workflow panel', async () => {
    sidePanelState.current = {
      openTab: openTabMock,
      tabs: [],
      removeTab: vi.fn(),
    };
    render(<WorkflowBuilderPanelContent workflowId="wf-1" readOnly />);

    act(() => {
      window.dispatchEvent(new CustomEvent('workflowOpenDatasourceTab', {
        detail: { dataSourceId: '42', label: 'Customers' },
      }));
    });

    await waitFor(() => expect(openTabMock).toHaveBeenCalledTimes(1));
    const content = openTabMock.mock.calls[0][0].content as React.ReactElement<{ readOnly: boolean }>;
    expect(content.props.readOnly).toBe(true);
  });

  it('propagates explicit read-only mode to pinned sub-workflow run tabs', async () => {
    sidePanelState.current = {
      openTab: openTabMock,
      tabs: [],
      removeTab: vi.fn(),
    };
    getPinnedWorkflowRunMock.mockResolvedValue({ runId: 'run-sub-1' });
    render(<WorkflowBuilderPanelContent workflowId="wf-1" readOnly />);

    act(() => {
      window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
        detail: { workflowId: 'wf-sub', workflowName: 'Sub workflow', nodeId: 'node-1' },
      }));
    });

    await waitFor(() => expect(openTabMock).toHaveBeenCalledTimes(1));
    const content = openTabMock.mock.calls[0][0].content as React.ReactElement<{
      runId: string;
      readOnly: boolean;
    }>;
    expect(content.props).toMatchObject({
      runId: 'run-sub-1',
      readOnly: true,
    });
  });

  it('propagates explicit read-only mode to fallback sub-workflow builder tabs', async () => {
    sidePanelState.current = {
      openTab: openTabMock,
      tabs: [],
      removeTab: vi.fn(),
    };
    getPinnedWorkflowRunMock.mockRejectedValue(new Error('No pinned run'));
    render(<WorkflowBuilderPanelContent workflowId="wf-1" readOnly />);

    act(() => {
      window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
        detail: { workflowId: 'wf-sub', workflowName: 'Sub workflow', nodeId: 'node-1' },
      }));
    });

    await waitFor(() => expect(openTabMock).toHaveBeenCalledTimes(1));
    const content = openTabMock.mock.calls[0][0].content as React.ReactElement<{ readOnly: boolean }>;
    expect(content.props.readOnly).toBe(true);
  });
});
