// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import messages from '@/messages/en.json';
import { getPickedEpoch, markEpochPickedByUser, resetEpochSelectionState } from '../run-panel/useDefaultEpochSelection';

const api = vi.hoisted(() => ({ getRun: vi.fn(), getRunState: vi.fn(), getEpochAggregatedSteps: vi.fn(), getRunStepsPaged: vi.fn(), execution: { getStepOutputObjectAtPath: vi.fn() } }));
const auth = vi.hoisted(() => ({ isReady: true, isAuthenticated: true }));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => auth }));
vi.mock('@/app/workflows/builder/services/canvasNodesStore', () => ({ getCanvasNodes: () => [], getCanvasEdges: () => [], subscribeCanvasNodes: () => () => {} }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({ getIconSlug: () => '', NodeIcon: () => null }));
vi.mock('@/app/workflows/builder/nodes/nodeClasses', () => ({ findNodeClassById: () => null }));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));

import { WorkflowLogsExplorer } from '../WorkflowLogsExplorer';
import { ToggleGroup } from '@/components/ui/toggle-group';
import { Breadcrumb, type BreadcrumbItem } from '@/components/ui/breadcrumb';

vi.mock('../WorkflowStepTable', () => ({
  WorkflowStepTable: ({ jsonPath, onNavigate, stepAlias, epoch }: { jsonPath: string; onNavigate: (path: string) => void; stepAlias: string; epoch: number | null }) => (
    <div data-testid="navigable-table" data-path={jsonPath} data-alias={stepAlias} data-epoch={String(epoch)}>
      <button onClick={() => onNavigate(jsonPath ? jsonPath + '.items' : 'output')}>Drill down</button>
    </div>
  ),
}));

class NoopResizeObserver { observe() {} unobserve() {} disconnect() {} }
globalThis.ResizeObserver = NoopResizeObserver;

function ExplorerWithBreadcrumb(props: Partial<React.ComponentProps<typeof WorkflowLogsExplorer>>) {
  const [view, setView] = React.useState<'simple' | 'table'>('simple');
  const [items, setItems] = React.useState<BreadcrumbItem[]>([]);
  const onBreadcrumbChange = React.useCallback((next: BreadcrumbItem[]) => {
    breadcrumb(next);
    setItems(next);
  }, []);
  return <><ToggleGroup ariaLabel="Log view" variant="pill" value={view} onValueChange={value => setView(value === 'table' ? 'table' : 'simple')} options={[{ value: 'simple', label: 'Simple' }, { value: 'table', label: 'Table' }]} />{items.length > 0 && <Breadcrumb items={items} />}<WorkflowLogsExplorer workflowId="wf" runId="run" onBreadcrumbChange={onBreadcrumbChange} view={view} {...props} /></>;
}

const epoch = (number: number) => ({ epoch: number, startedAt: '2026-09-14T08:00:00Z', endedAt: '2026-09-14T08:00:01Z' });
const step = (id: number, number = 2) => ({ id, stepAlias: 'fetch', epoch: number, outputStorageId: `storage-${id}`, status: 'COMPLETED', inputData: { query: `input-${id}` } });
const page = (content: unknown[], total = content.length, totalPages = 1) => ({ content, totalElements: total, totalPages, page: 0, size: 500 });
let client: QueryClient;
const breadcrumb = vi.fn();
function mount(props: Partial<React.ComponentProps<typeof WorkflowLogsExplorer>> = {}) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(<NextIntlClientProvider locale="en" messages={messages}><QueryClientProvider client={client}><ExplorerWithBreadcrumb {...props} /></QueryClientProvider></NextIntlClientProvider>);
}
beforeEach(() => {
  vi.clearAllMocks();
  resetEpochSelectionState();
  auth.isReady = true;
  api.getRun.mockResolvedValue({ id: 'uuid' });
  api.getRunState.mockResolvedValue({ epochTimestamps: [epoch(1), epoch(2)] });
  api.getEpochAggregatedSteps.mockResolvedValue([{ alias: 'fetch', status: 'COMPLETED' }, { alias: 'save', status: 'FAILED' }]);
  api.getRunStepsPaged.mockImplementation((_run, _alias, _page, _size, selectedEpoch) => Promise.resolve(page([step(12, selectedEpoch ?? 2), step(11, selectedEpoch ?? 1)])));
  api.execution.getStepOutputObjectAtPath.mockImplementation((_wf, _run, id) => Promise.resolve({ message: `output-${id}`, items: [{ title: 'First', count: 2 }, { title: 'Second', count: 3 }] }));
});
afterEach(() => { cleanup(); client?.clear(); });
/** Each view has its own epoch, so switching may load that epoch's node list first. */
async function switchView(name: 'Simple' | 'Table') {
  fireEvent.click(screen.getByRole('radio', { name }));
  await waitFor(() => expect(screen.queryByText(messages.workflow.logs.loading)).not.toBeInTheDocument());
}
const openTable = () => switchView('Table');
const openSimple = () => switchView('Simple');

describe('run logs shared presentation', () => {
  it('returns to Root from a direct node link and opens another node in the same view, on all epochs', async () => {
    mount({ initialStepAlias: 'mcp:save' });
    await screen.findByText('"output-12"');
    await openTable();
    fireEvent.click(screen.getByRole('button', { name: 'Root' }));
    const root = screen.getByTestId('workflow-logs-root');
    expect(breadcrumb).toHaveBeenLastCalledWith([{ label: 'Root' }]);
    expect(within(root).getAllByRole('button')).toHaveLength(2);
    expect(screen.queryByTestId('workflow-logs-table')).not.toBeInTheDocument();
    fireEvent.click(within(root).getByRole('button', { name: /fetch/ }));
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-alias', 'fetch');
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-epoch', 'null');
    expect(screen.getByLabelText('Epoch')).toHaveValue('all');
  });

  it('allows Root navigation even when the directly linked node did not execute', async () => {
    mount({ initialStepAlias: 'missing' });
    await screen.findByText(messages.workflow.logs.nodeUnavailable);
    fireEvent.click(screen.getByRole('button', { name: 'Root' }));
    expect(screen.getByTestId('workflow-logs-root')).toHaveTextContent('fetch');
    expect(api.getRunStepsPaged).not.toHaveBeenCalled();
  });

  it('hides and restores node navigation without losing payload selection', async () => {
    mount();
    await screen.findByText('"output-12"');
    fireEvent.change(screen.getByLabelText('Passage'), { target: { value: '11' } });
    await screen.findByText('"output-11"');
    fireEvent.click(within(screen.getByTestId('workflow-logs-nodes-header')).getByRole('button', { name: 'Hide nodes' }));
    expect(screen.getByRole('button', { name: 'Show nodes' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByLabelText('Node').parentElement).toHaveClass('hidden');
    expect(screen.getByRole('navigation', { name: 'Nodes' }).parentElement).not.toHaveClass('@min-[38rem]:flex');
    await openTable();
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-alias', 'fetch');
    await openSimple();
    fireEvent.click(screen.getByRole('button', { name: 'Show nodes' }));
    expect(screen.getByLabelText('Node').parentElement).not.toHaveClass('hidden');
    expect(screen.getByRole('navigation', { name: 'Nodes' }).parentElement).toHaveClass('@min-[38rem]:flex');
    expect(screen.getByLabelText('Passage')).toHaveValue('11');
  });

  it('returns to Root when the directly linked node steps fail to load', async () => {
    api.getRunStepsPaged.mockRejectedValueOnce(new Error('Node logs unavailable'));
    mount({ initialStepAlias: 'save' });
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Root' }));
    const root = screen.getByTestId('workflow-logs-root');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(within(root).getByRole('button', { name: /fetch/ }));
    expect(await screen.findByText('"output-12"')).toBeVisible();
  });

  it('keeps Show nodes available when a hidden selected node is absent from another epoch', async () => {
    mount();
    await screen.findByText('"output-12"');
    fireEvent.click(within(screen.getByTestId('workflow-logs-nodes-header')).getByRole('button', { name: 'Hide nodes' }));
    api.getEpochAggregatedSteps.mockResolvedValueOnce([{ alias: 'save', status: 'COMPLETED' }]);
    fireEvent.change(screen.getByLabelText('Epoch'), { target: { value: '1' } });
    await screen.findByText(messages.workflow.logs.nodeUnavailable);
    fireEvent.click(screen.getByRole('button', { name: 'Show nodes' }));
    expect(screen.getByLabelText('Node').parentElement).not.toHaveClass('hidden');
  });

  it('defaults to simple, pins the latest epoch and passes that filter to both endpoints', async () => {
    mount();
    expect(await screen.findByText('"output-12"')).toBeVisible();
    expect(screen.getByRole('radio', { name: 'Table' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByLabelText('Epoch')).toHaveValue('2');
    expect(api.getEpochAggregatedSteps).toHaveBeenCalledWith('run', 2);
    expect(api.getRunStepsPaged).toHaveBeenCalledWith('uuid', 'fetch', 0, 500, 2);
    expect(breadcrumb).toHaveBeenLastCalledWith([{ label: 'Root', onClick: expect.any(Function), alwaysClickable: true }, { label: 'fetch' }, { label: 'Output' }]);
  });

  it('restores the simple passage and direction after visiting the navigable table', async () => {
    mount();
    await screen.findByText('"output-12"');
    fireEvent.change(screen.getByLabelText('Passage'), { target: { value: '11' } });
    await screen.findByText('"output-11"');
    fireEvent.click(screen.getByRole('button', { name: 'Input' }));
    await screen.findByText('"input-11"');
    await openTable();
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', '');
    expect(screen.queryByLabelText('Passage')).not.toBeInTheDocument();
    await openSimple();
    expect(screen.getByTestId('workflow-logs-simple')).toHaveTextContent('input-11');
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
  });

  it('opens the table on all epochs every time while the simple view keeps its own epoch and passage', async () => {
    mount();
    await screen.findByText('"output-12"');
    fireEvent.change(screen.getByLabelText('Passage'), { target: { value: '11' } });
    await screen.findByText('"output-11"');
    await openTable();
    expect(screen.getByLabelText('Epoch')).toHaveValue('all');
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-epoch', 'null');
    expect(api.getEpochAggregatedSteps).toHaveBeenCalledWith('run', undefined);
    fireEvent.change(screen.getByLabelText('Epoch'), { target: { value: '1' } });
    await waitFor(() => expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-epoch', '1'));
    await openSimple();
    await screen.findByText('"output-11"');
    expect(screen.getByLabelText('Epoch')).toHaveValue('2');
    expect(screen.getByLabelText('Passage')).toHaveValue('11');
    await openTable();
    expect(screen.getByLabelText('Epoch')).toHaveValue('all');
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-epoch', 'null');
  });

  it('keeps the passage selector when more passages exist than are loaded', async () => {
    api.getRunStepsPaged.mockResolvedValue(page([step(12)], 2, 2));
    mount();
    await screen.findByText('"output-12"');
    expect(screen.getByLabelText('Passage')).toHaveValue('12');
  });

  it('shows the passage selector only when the node ran more than once', async () => {
    api.getRunStepsPaged.mockResolvedValue(page([step(12)]));
    mount();
    await screen.findByText('"output-12"');
    expect(screen.queryByLabelText('Passage')).not.toBeInTheDocument();
  });

  it('navigates nested table breadcrumbs and preserves the path across view toggles', async () => {
    mount();
    await screen.findByText('"output-12"');
    await openTable();
    fireEvent.click(screen.getByRole('button', { name: 'Drill down' }));
    fireEvent.click(screen.getByRole('button', { name: 'Drill down' }));
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', 'output.items');
    await openSimple();
    await openTable();
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', 'output.items');
    fireEvent.click(screen.getByRole('button', { name: 'output' }));
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', 'output');
    fireEvent.click(screen.getByRole('button', { name: 'fetch' }));
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', '');
  });

  it('resets the nested path when choosing another node or epoch', async () => {
    mount();
    await screen.findByText('"output-12"');
    await openTable();
    fireEvent.click(screen.getByRole('button', { name: 'Drill down' }));
    fireEvent.change(screen.getByLabelText('Node'), { target: { value: 'save' } });
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', '');
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-alias', 'save');
    fireEvent.click(screen.getByRole('button', { name: 'Drill down' }));
    fireEvent.change(screen.getByLabelText('Epoch'), { target: { value: '1' } });
    await waitFor(() => expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-epoch', '1'));
    expect(screen.getByTestId('navigable-table')).toHaveAttribute('data-path', '');
  });

  it('does not let a simple-payload failure block the independent navigable table', async () => {
    api.getRunStepsPaged.mockRejectedValue(new Error('Unavailable'));
    mount();
    await screen.findByRole('alert');
    await openTable();
    expect(screen.getByTestId('navigable-table')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each([1, null])('restores an explicit run epoch choice: %s', async selected => {
    markEpochPickedByUser('run', selected);
    mount();
    await screen.findByText('"output-12"');
    expect(screen.getByLabelText('Epoch')).toHaveValue(selected === null ? 'all' : '1');
    expect(api.getRunStepsPaged).toHaveBeenCalledWith('uuid', 'fetch', 0, 500, selected);
  });

  it('keeps log-only epoch browsing local to the Logs tab', async () => {
    markEpochPickedByUser('run', 2);
    mount();
    await screen.findByText('"output-12"');
    fireEvent.change(screen.getByLabelText('Epoch'), { target: { value: '1' } });
    await waitFor(() => expect(screen.getByLabelText('Epoch')).toHaveValue('1'));
    expect(getPickedEpoch('run')).toBe(2);
  });

  it('does not jump epoch or passage when fresh execution data arrives', async () => {
    mount();
    await screen.findByText('"output-12"');
    act(() => {
      client.setQueryData(['workflow-logs', 'wf', 'run', 'state'], { epochTimestamps: [epoch(3), epoch(2)] });
      client.setQueryData(['step-data', 'uuid', 'fetch', 2], { pages: [page([step(13), step(12)])], pageParams: [0] });
    });
    expect(screen.getByLabelText('Epoch')).toHaveValue('2');
    expect(screen.getByLabelText('Passage')).toHaveValue('12');
    expect(screen.getByText('"output-12"')).toBeVisible();
  });

  it('clears the old payload synchronously on epoch change, even with a delayed response', async () => {
    mount();
    await screen.findByText('"output-12"');
    let resolve!: (value: unknown) => void;
    api.getEpochAggregatedSteps.mockReturnValueOnce(new Promise(done => { resolve = done; }));
    fireEvent.change(screen.getByLabelText('Epoch'), { target: { value: '1' } });
    expect(screen.queryByText('"output-12"')).not.toBeInTheDocument();
    await act(async () => resolve([{ alias: 'fetch', status: 'COMPLETED' }]));
    await screen.findByText('"output-12"');
    expect(api.getRunStepsPaged).toHaveBeenLastCalledWith('uuid', 'fetch', 0, 500, 1);
  });

  it('resolves a node deep link without requiring an open canvas', async () => {
    mount({ initialStepAlias: 'mcp:save' });
    await screen.findByText('"output-12"');
    expect(api.getRunStepsPaged).toHaveBeenCalledWith('uuid', 'save', 0, 500, 2);
  });

  it('does not silently substitute another node when the requested node did not execute', async () => {
    mount({ initialStepAlias: 'missing' });
    expect(await screen.findByText(messages.workflow.logs.nodeUnavailable)).toBeVisible();
    expect(api.getRunStepsPaged).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Node'), { target: { value: 'fetch' } });
    await screen.findByText('"output-12"');
  });

  it('renders an empty epoch without trying to load a payload', async () => {
    api.getEpochAggregatedSteps.mockResolvedValue([]);
    mount();
    expect(await screen.findByText(messages.workflow.logs.emptyEpoch)).toBeVisible();
    expect(api.execution.getStepOutputObjectAtPath).not.toHaveBeenCalled();
  });

  it('loads passages beyond the first 500 without losing the current selection', async () => {
    api.getRunStepsPaged.mockImplementation((_run, _alias, pageIndex) => Promise.resolve(page(pageIndex === 0 ? Array.from({ length: 500 }, (_, index) => step(600 - index)) : [step(100)], 501, 2)));
    mount();
    await screen.findByText('"output-600"');
    fireEvent.click(screen.getByRole('button', { name: 'Load more passages' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Load more passages' })).not.toBeInTheDocument());
    expect(screen.getByLabelText('Passage')).toHaveValue('600');
    expect(api.getRunStepsPaged).toHaveBeenLastCalledWith('uuid', 'fetch', 1, 500, 2);
    fireEvent.change(screen.getByLabelText('Passage'), { target: { value: '100' } });
    await screen.findByText('"output-100"');
  });

  it('shows no read-only badge and no passage counter in the footer', async () => {
    // The whole Logs panel is read-only by nature and the passage selector already says how many
    // runs there are, so both footer labels were noise ("Read only", "1 / 1 passages loaded").
    api.getRunStepsPaged.mockResolvedValue(page([step(12)]));
    const { container } = mount();
    await screen.findByText('"output-12"');
    expect(screen.queryByText('Read only')).not.toBeInTheDocument();
    expect(screen.queryByText(/passages loaded/)).not.toBeInTheDocument();
    // With a single loaded page there is nothing left to put in the footer, so it is not rendered.
    expect(container.querySelector('footer')).toBeNull();
  });

  it('shows the recorded node failure even when it produced no output', async () => {
    api.getRunStepsPaged.mockResolvedValue(page([{ ...step(12), outputStorageId: null, status: 'FAILED', errorMessage: 'Provider rejected the request' }]));
    api.execution.getStepOutputObjectAtPath.mockRejectedValue(new Error('404: no stored output'));
    mount();
    expect(await screen.findByText('Provider rejected the request')).toBeVisible();
    expect(await screen.findByText(messages.workflow.logs.emptyData)).toBeVisible();
    expect(api.execution.getStepOutputObjectAtPath).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Input' }));
    expect(await screen.findByText('"input-12"')).toBeVisible();
  });

  it.each(['getRunState', 'getEpochAggregatedSteps', 'getRun', 'getRunStepsPaged'] as const)('offers a working retry when %s fails', async method => {
    api[method].mockRejectedValueOnce(new Error('Unavailable'));
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent(messages.workflow.logs.loadDataError);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('"output-12"')).toBeVisible();
  });

  it('distinguishes failed output retrieval from an empty output and retries it', async () => {
    api.execution.getStepOutputObjectAtPath.mockRejectedValueOnce(new Error('Unavailable'));
    mount();
    await screen.findByRole('alert');
    expect(screen.queryByText(messages.workflow.logs.emptyData)).not.toBeInTheDocument();
    expect(api.execution.getStepOutputObjectAtPath).toHaveBeenCalledWith('wf', 'run', 12, 'output', { throwOnError: true });
    fireEvent.click(screen.getByRole('button', { name: 'Input' }));
    expect(await screen.findByText('"input-12"')).toBeVisible();
    expect(screen.getByLabelText('Node')).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Output' }));
    // The cached failed request is retried on re-enabling Output.
    expect(await screen.findByText('"output-12"')).toBeVisible();
  });

  it('retries an output error without changing the selected passage', async () => {
    api.execution.getStepOutputObjectAtPath.mockRejectedValueOnce(new Error('Unavailable'));
    mount();
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByText('"output-12"');
    expect(screen.getByLabelText('Passage')).toHaveValue('12');
  });

  it.each([false, 0, '', [], {}])('renders valid falsy/empty JSON without changing hook order: %j', async value => {
    api.execution.getStepOutputObjectAtPath.mockResolvedValue(value);
    mount();
    await waitFor(() => expect(screen.getByTestId('workflow-logs-simple')).toHaveTextContent('Output data'));
    await openTable();
    expect(screen.getByTestId('workflow-logs-table')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('does not request protected resources before authentication is ready', async () => {
    auth.isReady = false;
    mount();
    expect(screen.getByRole('status')).toHaveTextContent('Loading logs');
    expect(api.getRunState).not.toHaveBeenCalled();
    expect(api.getRun).not.toHaveBeenCalled();
  });
});
