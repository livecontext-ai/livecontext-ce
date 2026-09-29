// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BuilderNodeData } from '@/app/workflows/builder/types';

/**
 * A lazy panel must still render. The side-panel contents opened from a canvas
 * node became `next/dynamic` imports; the other tests read props off the tab
 * object and would stay green if the lazy wrapper rendered nothing. These mount
 * the tab content and wait for the real (mocked) panel to appear inside it.
 */
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: ({ entryId }: { entryId?: string }) => <div data-testid="file-detail-view">{entryId}</div>,
}));
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({
  StorageExplorerTab: () => <div data-testid="storage-explorer" />,
}));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: ({ agentId, initialTab }: { agentId: string; initialTab: string }) => (
    <div data-testid="agent-panel" data-tab={initialTab}>{agentId}</div>
  ),
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({
  DataSourcePanelContent: ({ dataSourceId }: { dataSourceId: string }) => <div data-testid="datasource-panel">{dataSourceId}</div>,
}));
vi.mock('@/lib/sidePanel/preloadNodePanels', () => ({ preloadNodePanels: vi.fn() }));
const sidePanel = { openTab: vi.fn(), updateTab: vi.fn(), setActiveTab: vi.fn(), open: vi.fn(), tabs: [] as { id: string }[] };
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => sidePanel }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ workflowId: 'wf-1' }) }));

import { openFilesPanel } from '../openFilesPanel';
import { useNodeContextualButtons, deriveNodeContextFlags } from '@/app/workflows/builder/hooks/useNodeContextualButtons';

afterEach(() => {
  cleanup();
  sidePanel.openTab.mockReset();
});

function hook(fields: Record<string, unknown>) {
  const data = fields as unknown as BuilderNodeData;
  const flags = deriveNodeContextFlags(data, data.id);
  return renderHook(() => useNodeContextualButtons({ data, nodeUiId: data.id || 'node', isRunMode: false, flags }));
}

const lastTabContent = () => sidePanel.openTab.mock.calls.at(-1)![0].content as React.ReactElement;

describe('lazy side-panel contents still render', () => {
  it('the files detail view appears inside the opened tab', async () => {
    openFilesPanel(sidePanel, { id: 'row-1', name: 'a.png' });
    render(lastTabContent());
    expect(await screen.findByTestId('file-detail-view')).toHaveTextContent('row-1');
  });

  it('the files list appears inside the opened tab', async () => {
    openFilesPanel(sidePanel);
    render(lastTabContent());
    expect(await screen.findByTestId('storage-explorer')).toBeInTheDocument();
  });

  it('the agent panel opened from an agent node appears, on the tab that was asked for', async () => {
    const { result } = hook({ id: 'ai-agent', label: 'Helper', agentConfigId: 'cfg-1', agentConfigName: 'Helper' });
    result.current.find((button) => button.key === 'agent-conv')!.onClick({ stopPropagation() {} } as never);

    render(lastTabContent());
    const panel = await screen.findByTestId('agent-panel');
    expect(panel).toHaveTextContent('cfg-1');
    expect(panel).toHaveAttribute('data-tab', '__conversation__');
  });

  it('the data table opened from a table node appears', async () => {
    const { result } = hook({ id: 'read-rows', kind: 'read', label: 'Leads', dataSourceData: { dataSourceId: 'ds-9', dataSourceName: 'Leads' } });
    result.current.find((button) => button.key === 'table-data')!.onClick({ stopPropagation() {} } as never);

    render(lastTabContent());
    expect(await screen.findByTestId('datasource-panel')).toHaveTextContent('ds-9');
  });
});
