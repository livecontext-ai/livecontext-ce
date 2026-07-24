/**
 * @vitest-environment jsdom
 *
 * Run-context semantics in the application toolbar:
 *   - When pinned to ONE epoch with several rendered pages, the bare
 *     "{page+1} / {totalPages}" counter becomes "Item X/Y" derived from the
 *     CURRENT page's item triple (itemIndex is 0-based in the API → 1-based
 *     for humans).
 *   - A "Re-execution N" badge is appended ONLY when the displayed item is a
 *     re-run (spawn > 0) - first executions (spawn 0) stay badge-free.
 *   - In "All epochs" mode pages span epochs, so the bare counter is kept.
 *   - The Continue button's tooltip carries "Epoch X · Item Y" so the user
 *     knows exactly what will be continued.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import * as React from 'react';

async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

// Mutable render-API response - the item triple under test is changed per test.
const renderDataRef = vi.hoisted(() => ({
  current: {
    htmlTemplate: '<div>app</div>',
    items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
    pagination: { totalPages: 3 },
  } as Record<string, any>,
}));

const runStateRef = vi.hoisted(() => ({
  current: { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] } as Record<string, unknown>,
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: renderDataRef.current,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

// The Continue button only renders for a BLOCKING interface awaiting its
// signal with the displayed item still pending - force both helpers true.
vi.mock('../interfaceAwaitingSignal', () => ({
  computeIsAwaitingSignal: () => true,
  isCurrentInterfaceItemPending: () => true,
}));

// Surface the new pageLabel/pageBadge props (and the extraControls hosting the
// Continue button) instead of re-testing InterfaceToolbar internals here.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: any) => (
    <div data-testid="toolbar-stub">
      <span data-testid="page-label">
        {props.pageLabel ?? `${props.currentPage + 1} / ${props.totalPages}`}
      </span>
      {props.pageBadge && <span data-testid="page-badge">{props.pageBadge}</span>}
      {props.extraControls}
    </div>
  ),
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div data-testid="trigger-panel-stub" />,
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '', centeringCssFor: () => '' }));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      itemOfTotal: 'Item {number}/{total}',
      reExecutionBadge: 'Re-execution {number}',
      epochBadge: 'Epoch {number}',
      itemBadge: 'Item {number}',
      continueWorkflow: 'Continue',
      continueItemRemaining: 'Continue this item ({count} pending)',
      itemAlreadyResolved: 'itemAlreadyResolved',
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  actionMapping: { submit: '__continue' },
  nodeId: 'interface:app',
};

function renderApp(viewingEpoch: number | null) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      viewingEpoch={viewingEpoch}
      onViewingEpochChange={() => undefined}
      toolbarOpen
      onToolbarOpenChange={() => undefined}
    />,
  );
}

describe('ApplicationTabContent - run-context pagination semantics', () => {
  beforeEach(() => {
    runStateRef.current = { runStatus: 'awaiting_signal', executionTotal: 0, pendingSignals: [] };
    renderDataRef.current = {
      htmlTemplate: '<div>app</div>',
      items: [{ epoch: 4, spawn: 0, itemIndex: 1, data: { foo: 'bar' } }],
      pagination: { totalPages: 3 },
    };
  });
  afterEach(cleanup);

  it('shows "Item X/Y" (1-based itemIndex) instead of the bare page counter when pinned to an epoch', async () => {
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-label').textContent).toBe('Item 2/3');
    await flushEffects();
  });

  it('keeps the bare page counter in "All epochs" mode (pages span epochs there)', async () => {
    const { getByTestId } = renderApp(null);
    expect(getByTestId('page-label').textContent).toBe('1 / 3');
    await flushEffects();
  });

  it('appends a 1-based "Re-execution N" badge when the displayed item is a re-run (spawn > 0)', async () => {
    renderDataRef.current = {
      ...renderDataRef.current,
      items: [{ epoch: 4, spawn: 1, itemIndex: 1, data: { foo: 'bar' } }],
    };
    const { getByTestId } = renderApp(4);
    expect(getByTestId('page-badge').textContent).toBe('Re-execution 2');
    await flushEffects();
  });

  it('hides the re-execution badge for a first execution (spawn = 0)', async () => {
    const { queryByTestId } = renderApp(4);
    expect(queryByTestId('page-badge')).toBeNull();
    await flushEffects();
  });

  it('Continue button tooltip carries the "Epoch X · Item Y" context of what will be continued', async () => {
    const { getByTitle } = renderApp(4);
    // Raw epoch (matches the epoch selector numbers) + 1-based item.
    expect(getByTitle('Continue - Epoch 4 · Item 2')).toBeTruthy();
    await flushEffects();
  });
});
