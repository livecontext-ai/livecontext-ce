// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Wiring between the workflow list and its node-type filter.
 *
 * <p>The filter is SERVER-side (the backend narrows the whole tenant set before
 * paginating), so what has to hold here is the round trip: the picked tokens
 * reach the request, the facets the server answers with are handed back to the
 * picker, and picking one returns to page 0 - a selection that shrinks the set
 * while the page index stays at 3 shows an empty grid over a non-empty result.
 *
 * <p>The picker itself is stubbed to a button that reports a fixed selection.
 * Its own behaviour (search, grouping, toggling) is covered by
 * NodeTypeFilter.test.tsx; driving a Radix popover through this page as well
 * would test that library twice and this wiring not at all.
 */

const mocks = vi.hoisted(() => ({
  getWorkflowsPage: vi.fn(),
  getWorkflowRelationsBatch: vi.fn().mockResolvedValue({}),
  // Captures what the page hands the picker, so the facet pass-through is observable.
  lastFacets: { current: [] as Array<{ value: string; count: number }> },
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('next-intl', () => ({
  useTranslations: () => Object.assign((key: string) => key, { raw: (key: string) => key }),
}));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: () => undefined }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: mocks }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));

// Stand-in picker: records the facets it was given and offers two buttons that
// apply / clear a fixed selection.
vi.mock('@/components/NodeTypeFilter', () => ({
  NodeTypeFilter: ({ facets, value, onChange }: any) => {
    mocks.lastFacets.current = facets;
    return React.createElement('div', null,
      React.createElement('span', { 'data-testid': 'ntf-value' }, value.join(',')),
      React.createElement('span', { 'data-testid': 'ntf-facets' },
        facets.map((f: any) => `${f.value}:${f.count}`).join(',')),
      React.createElement('button', {
        'data-testid': 'ntf-pick',
        onClick: () => onChange(['mcp:gmail']),
      }, 'pick'),
      React.createElement('button', {
        'data-testid': 'ntf-clear',
        onClick: () => onChange([]),
      }, 'clear'),
    );
  },
}));

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({
  PaginationBar: ({ page, onPageChange }: any) =>
    React.createElement('div', null,
      React.createElement('span', { 'data-testid': 'pg-page' }, String(page)),
      React.createElement('button', { 'data-testid': 'pg-next', onClick: () => onPageChange(page + 1) }, 'next'),
    ),
}));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => React.createElement('div', null, children),
  SelectTrigger: ({ children }: any) => React.createElement('span', null, children),
  SelectContent: ({ children }: any) => children,
  SelectItem: () => null,
  SelectValue: () => null,
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import WorkflowTable from '../WorkflowTable';

const wf = (over: Record<string, unknown>) => ({
  id: 'id', name: 'WF', updatedAt: '2026-06-01T00:00:00Z', isPublished: false, ...over,
});

const page = (over: Record<string, unknown> = {}) => ({
  workflows: [wf({ id: 'w1', name: 'Gmail digest' })],
  count: 1,
  // Well past one page, so the pagination bar is actually rendered and the
  // "changing the filter goes back to page 0" rule can be exercised.
  totalCount: 100,
  page: 0,
  size: 25,
  nodeTypeFacets: [
    { value: 'mcp:gmail', count: 3 },
    { value: 'core:loop', count: 1 },
  ],
  ...over,
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WorkflowTable - node-type filter wiring', () => {
  beforeEach(() => {
    fakeFolderRouter.reset();
    mocks.getWorkflowsPage.mockResolvedValue(page());
  });

  it('sends no nodeTypes on the first load', async () => {
    render(<WorkflowTable />);
    await waitFor(() => expect(mocks.getWorkflowsPage).toHaveBeenCalled());

    expect(mocks.getWorkflowsPage.mock.calls[0][0]).toMatchObject({ nodeTypes: [] });
  });

  it('re-queries the server with the picked tokens', async () => {
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Gmail digest')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('ntf-pick'));

    await waitFor(() => {
      const last = mocks.getWorkflowsPage.mock.calls.at(-1)![0];
      expect(last.nodeTypes).toEqual(['mcp:gmail']);
    });
  });

  it('hands the server facets to the picker, so its options describe this workspace', async () => {
    render(<WorkflowTable />);

    await waitFor(() =>
      expect(screen.getByTestId('ntf-facets')).toHaveTextContent('mcp:gmail:3,core:loop:1'));
  });

  it('offers no options when the server reports no facets, instead of failing', async () => {
    mocks.getWorkflowsPage.mockResolvedValue(page({ nodeTypeFacets: undefined }));
    render(<WorkflowTable />);

    await waitFor(() => expect(mocks.getWorkflowsPage).toHaveBeenCalled());
    expect(screen.getByTestId('ntf-facets')).toHaveTextContent('');
  });

  it('returns to page 0 when the filter changes, so the grid cannot land past the end', async () => {
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Gmail digest')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('pg-next'));
    await waitFor(() => expect(screen.getByTestId('pg-page')).toHaveTextContent('1'));

    fireEvent.click(screen.getByTestId('ntf-pick'));

    await waitFor(() => expect(screen.getByTestId('pg-page')).toHaveTextContent('0'));
  });

  it('keeps the filter bar reachable when the filter matches nothing', async () => {
    // The bar is otherwise hidden on an empty list - which would strand the user
    // with an active filter and no control to undo it.
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Gmail digest')).toBeInTheDocument());

    mocks.getWorkflowsPage.mockResolvedValue(page({ workflows: [], count: 0, totalCount: 0 }));
    fireEvent.click(screen.getByTestId('ntf-pick'));

    await waitFor(() => expect(screen.getByTestId('ntf-value')).toHaveTextContent('mcp:gmail'));
    expect(screen.getByTestId('ntf-pick')).toBeInTheDocument();
  });
});
