// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Selection actions on the Workflows list moved from an inline top toolbar into
 * the floating bottom-center SelectionActionBar (mirrors the task board bulk bar).
 * This pins: the bar renders on selection, shows "{n} selected", carries the
 * clone + delete actions, and its × wires to clearSelection.
 */

const mocks = vi.hoisted(() => ({
  getWorkflowsPage: vi.fn(),
  cloneWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  clear: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflowsPage: mocks.getWorkflowsPage,
    cloneWorkflow: mocks.cloneWorkflow,
    deleteWorkflow: mocks.deleteWorkflow,
    saveWorkflowPlan: vi.fn(),
  },
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  // Consumed by the TemplateGallery banner, which scopes its collapsed pref per workspace.
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
// One workflow is selected - exercise the bar's populated state.
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: new Set<string>(['w1']),
    toggle: vi.fn(),
    clear: mocks.clear,
    selectAll: vi.fn(),
  }),
}));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import WorkflowTable from '../WorkflowTable';

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <WorkflowTable />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  mocks.getWorkflowsPage.mockResolvedValue({
    workflows: [{ id: 'w1', name: 'WF One', updatedAt: '2026-06-01T00:00:00Z', isPublished: false }],
    count: 1,
    totalCount: 1,
    page: 0,
    size: 25,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WorkflowTable - selection actions float in the bottom-center bar', () => {
  it('renders the SelectionActionBar with the "{count} selected" label when a workflow is selected', async () => {
    renderTable();
    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
  });

  it('carries the clone + delete actions inside the bar', async () => {
    renderTable();
    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByRole('button', { name: 'Clone (1)' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete (1)' })).toBeInTheDocument();
  });

  it('clicking the bar × triggers clearSelection', async () => {
    renderTable();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByTestId('selection-action-bar-clear'));
    expect(mocks.clear).toHaveBeenCalledTimes(1);
  });

  it('clicking Clone clones the selected workflow', async () => {
    mocks.cloneWorkflow.mockResolvedValue({});
    renderTable();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByRole('button', { name: 'Clone (1)' }));
    await waitFor(() => expect(mocks.cloneWorkflow).toHaveBeenCalledWith('w1'));
  });

  it('does not render the legacy inline selection toolbar (no plain-text "Clear selection" button outside the bar)', async () => {
    renderTable();
    await screen.findByTestId('selection-action-bar');
    // The only clear control is the bar's icon button (accessible via aria-label),
    // not a separate inline text button - assert there is exactly one such control.
    expect(screen.getAllByRole('button', { name: 'Clear selection' })).toHaveLength(1);
  });
});
