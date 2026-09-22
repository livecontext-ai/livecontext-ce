// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../../types';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  isFileRef: () => false,
  normalizeFileRef: (v: unknown) => v,
  getFilePath: () => '',
  fileRefToUrl: () => null,
  fileService: { downloadAndSave: vi.fn(), formatFileSize: () => '1 kB' },
}));
vi.mock('@/lib/utils/url-auth', () => ({ openAuthedFileInNewTab: vi.fn() }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: true }) }));

const runData = vi.hoisted(() => ({
  value: {
    totalItems: 1,
    isLoading: false,
    error: null as string | null,
    currentIndex: 0,
    currentItem: { id: 'row-1' },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(async (): Promise<unknown> => ({ items: [{ a: 1 }] })),
    availableStatuses: [],
  },
}));
vi.mock('../../../../hooks/useRunData', () => ({ useRunData: () => runData.value }));

const liveState = vi.hoisted(() => ({
  value: { liveState: null as null | 'running' | 'awaiting', pendingSignals: [] as unknown[] },
}));
vi.mock('../../../../hooks/useNodeLiveState', () => ({ useNodeLiveState: () => liveState.value }));

import { RunDataPreview } from '../RunDataPreview';

function node(): Node<BuilderNodeData> {
  return {
    id: 'step-1',
    type: 'toolNode',
    position: { x: 0, y: 0 },
    data: { id: 'step-1', label: 'Fetch', kind: 'mcp' } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

describe('RunDataPreview views', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    liveState.value = { liveState: null, pendingSignals: [] };
    runData.value = {
      ...runData.value,
      totalItems: 1,
      error: null,
      getObjectAtPath: vi.fn(async () => ({ items: [{ a: 1 }, { a: 2 }] })),
    };
  });

  it('offers the table view when the payload has a row-shaped field, and NAMES the field', async () => {
    render(<RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" />);
    await waitFor(() => expect(screen.getByRole('tab', { name: 'viewTable' })).toBeTruthy());

    fireEvent.click(screen.getByRole('tab', { name: 'viewTable' }));
    // The rows of the field, not the envelope around them.
    expect(screen.getByTestId('run-data-table-view').textContent).toContain('a');
    // ...and the table says WHICH field it is showing. This assertion is the one
    // that pins the call site: the preview must hand JsonTableView the whole
    // payload. Pre-unwrapping it here left the label and the selector unreachable
    // while every direct-component test still passed.
    expect(screen.getByTestId('run-data-table-field').textContent).toBe('items');
  });

  it('lets the reader choose which field to tabulate when several qualify', async () => {
    runData.value = {
      ...runData.value,
      getObjectAtPath: vi.fn(async () => ({ items: [{ a: 1 }], errors: [{ code: 'X' }] })),
    };
    render(<RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" />);
    await waitFor(() => expect(screen.getByRole('tab', { name: 'viewTable' })).toBeTruthy());

    fireEvent.click(screen.getByRole('tab', { name: 'viewTable' }));
    // Two row-shaped fields used to REMOVE the table entirely, with nothing on
    // screen explaining why.
    const select = screen.getByTestId('run-data-table-field') as HTMLSelectElement;
    expect([...select.options].map((o) => o.value)).toEqual(['items', 'errors']);

    fireEvent.change(select, { target: { value: 'errors' } });
    expect(screen.getByTestId('run-data-table-view').textContent).toContain('code');
  });

  it('hides the table view for a payload with no rows in it', async () => {
    runData.value = { ...runData.value, getObjectAtPath: vi.fn(async () => ({ status: 'ok' })) };
    render(<RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" />);
    await waitFor(() => expect(screen.getByRole('tab', { name: 'viewTree' })).toBeTruthy());
    expect(screen.queryByRole('tab', { name: 'viewTable' })).toBeNull();
  });

  it('falls back off the table view when the next payload is no longer tabular', async () => {
    const { rerender } = render(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" />,
    );
    await waitFor(() => expect(screen.getByRole('tab', { name: 'viewTable' })).toBeTruthy());
    fireEvent.click(screen.getByRole('tab', { name: 'viewTable' }));
    expect(screen.getByTestId('run-data-table-view')).toBeTruthy();

    runData.value = { ...runData.value, getObjectAtPath: vi.fn(async () => ({ status: 'ok' })) };
    rerender(<RunDataPreview workflowId="wf" runId="run" stepAlias="Other" dataType="output" />);

    await waitFor(() => expect(screen.queryByTestId('run-data-table-view')).toBeNull());
    expect(screen.getByRole('tab', { name: 'viewTree' }).getAttribute('aria-selected')).toBe('true');
  });

  it('goes back to the tree when the panel starts pointing at another node', async () => {
    const { rerender } = render(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" />,
    );
    await waitFor(() => expect(screen.getByRole('tab', { name: 'viewJson' })).toBeTruthy());
    fireEvent.click(screen.getByRole('tab', { name: 'viewJson' }));
    expect(screen.getByTestId('run-data-json-view')).toBeTruthy();

    rerender(<RunDataPreview workflowId="wf" runId="run" stepAlias="Another" dataType="output" />);
    await waitFor(() => expect(screen.queryByTestId('run-data-json-view')).toBeNull());
  });

  it('says the node is executing in the OUTPUT column, not just "no data"', async () => {
    runData.value = { ...runData.value, totalItems: 0 };
    liveState.value = { liveState: 'running', pendingSignals: [] };
    render(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" node={node()} />,
    );
    expect(screen.getByTestId('node-run-state-running')).toBeTruthy();
    expect(screen.getByText('stateRunningOutput')).toBeTruthy();
  });

  it('keeps the plain empty state when nothing says the node is live', () => {
    runData.value = { ...runData.value, totalItems: 0 };
    liveState.value = { liveState: null, pendingSignals: [] };
    render(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="Fetch" dataType="output" node={node()} />,
    );
    expect(screen.queryByTestId('node-run-state-running')).toBeNull();
    expect(screen.getByText('noData')).toBeTruthy();
  });
});
