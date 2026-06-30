// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The workflow list is server-paged (mirrors the Agent / Interface / DataSource lists): the backend
 * applies the visibility filter + sort and inlines each row's publication status into the page
 * envelope, so there is NO client-side fetch-all loop. This pins:
 *  - the Globe (public) / Lock (private) marker comes straight from the page envelope (immediate);
 *    in-review / rejected keep their own chips (no Lock) and ACTIVE keeps the Globe;
 *  - the visibility filter + sort RE-QUERY the server (carry visibility / sort params), they no
 *    longer slice a fully-loaded client set;
 *  - a SINGLE server page is loaded (never a fetch-all loop), and changing the visibility filter
 *    resets back to page 0.
 *
 * next-intl is mocked to echo keys (titles/labels are the i18n keys). The ui/select is mocked to a
 * native <select> so the dropdowns can be driven deterministically (Radix needs real pointer events).
 */

const mocks = vi.hoisted(() => ({ getWorkflowsPage: vi.fn() }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: () => undefined }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: mocks }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
// PaginationBar stand-in exposing the page + a "next" control so paging can be exercised.
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
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));

// Native-<select> stand-in for the Radix select: collects the trigger's aria-label and the
// SelectItem options so each control becomes a labelled, fireEvent-driveable <select>.
vi.mock('@/components/ui/select', async () => {
  const ReactLib = await vi.importActual<typeof import('react')>('react');
  const collect = (children: any, acc: { ariaLabel?: string; options: Array<{ value: string; label: any }> }) => {
    ReactLib.Children.forEach(children, (child: any) => {
      if (!child || typeof child !== 'object') return;
      if (child.props?.['aria-label']) acc.ariaLabel = child.props['aria-label'];
      if (child.type?.__isSelectItem) acc.options.push({ value: child.props.value, label: child.props.children });
      if (child.props?.children) collect(child.props.children, acc);
    });
  };
  const Select = ({ value, onValueChange, children }: any) => {
    const acc: { ariaLabel?: string; options: Array<{ value: string; label: any }> } = { options: [] };
    collect(children, acc);
    return ReactLib.createElement(
      'select',
      { 'aria-label': acc.ariaLabel, value, onChange: (e: any) => onValueChange(e.target.value) },
      acc.options.map((o) => ReactLib.createElement('option', { key: o.value, value: o.value }, o.label)),
    );
  };
  const SelectTrigger = ({ children, 'aria-label': ariaLabel }: any) =>
    ReactLib.createElement('span', { 'aria-label': ariaLabel }, children);
  const SelectValue = () => null;
  const SelectContent = ({ children }: any) => children;
  const SelectItem: any = () => null;
  SelectItem.__isSelectItem = true;
  return { Select, SelectTrigger, SelectContent, SelectItem, SelectValue };
});

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import WorkflowTable from '../WorkflowTable';

const wf = (over: Record<string, unknown>) => ({
  id: 'id', name: 'WF', updatedAt: '2026-06-01T00:00:00Z', isPublished: false, ...over,
});
const page = (workflows: any[], totalCount = workflows.length) => ({
  workflows, count: workflows.length, totalCount, page: 0, size: 25,
});

/** Assert the named texts appear in the given DOM order. */
function expectOrder(...names: string[]) {
  const els = names.map((n) => screen.getByText(n));
  for (let i = 0; i < els.length - 1; i++) {
    expect(els[i].compareDocumentPosition(els[i + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WorkflowTable - Globe / Lock marker comes from the page envelope', () => {
  beforeEach(() => {
    mocks.getWorkflowsPage.mockResolvedValue(page([
      wf({ id: 'w1', name: 'Pending WF', publicationStatus: 'PENDING_REVIEW', isPublished: false }),
      wf({ id: 'w2', name: 'Active WF', publicationStatus: 'ACTIVE', isPublished: true }),
      wf({ id: 'w3', name: 'Rejected WF', publicationStatus: 'REJECTED', isPublished: false }),
      wf({ id: 'w4', name: 'Plain WF' }),
    ]));
  });

  it('shows a Lock for the never-shared workflow only (Globe for ACTIVE; chips, not Locks, for pending/rejected)', async () => {
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Plain WF')).toBeInTheDocument());
    expect(screen.getAllByTitle('workflow.shared')).toHaveLength(1);
    expect(screen.getAllByTitle('common.visibilityPrivate')).toHaveLength(1);
    expect(screen.getByTitle('workflow.sharedInReview')).toBeInTheDocument();
    expect(screen.getByTitle('workflow.sharedRejected')).toBeInTheDocument();
  });

  it('renders the visibility filter + sort controls', async () => {
    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Plain WF')).toBeInTheDocument());
    expect(screen.getByLabelText('common.filterByVisibility')).toBeInTheDocument();
    expect(screen.getByLabelText('common.sortBy')).toBeInTheDocument();
  });
});

describe('WorkflowTable - visibility filter + sort re-query the server', () => {
  it('changing the sort re-queries the server with the chosen sort key and reorders the cards', async () => {
    mocks.getWorkflowsPage.mockImplementation(async (opts: any = {}) =>
      opts.sort === 'name'
        ? page([wf({ id: 'w2', name: 'Alpha' }), wf({ id: 'w3', name: 'Mid' }), wf({ id: 'w1', name: 'Zoo' })])
        : page([
            wf({ id: 'w1', name: 'Zoo', updatedAt: '2026-06-18T00:00:00Z' }),
            wf({ id: 'w3', name: 'Mid', updatedAt: '2026-06-15T00:00:00Z' }),
            wf({ id: 'w2', name: 'Alpha', updatedAt: '2026-06-10T00:00:00Z' }),
          ]));

    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Zoo')).toBeInTheDocument());
    // Default lastModified order from the server.
    expectOrder('Zoo', 'Mid', 'Alpha');

    fireEvent.change(screen.getByLabelText('common.sortBy'), { target: { value: 'name' } });
    await waitFor(() => expectOrder('Alpha', 'Mid', 'Zoo'));
    expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'name' }));
  });

  it('the visibility filter narrows via a server query carrying visibility=public/private', async () => {
    mocks.getWorkflowsPage.mockImplementation(async (opts: any = {}) => {
      if (opts.visibility === 'public') {
        return page([wf({ id: 'w1', name: 'Zoo', isPublished: true, publicationStatus: 'ACTIVE' })]);
      }
      if (opts.visibility === 'private') {
        return page([wf({ id: 'w2', name: 'Alpha' }), wf({ id: 'w3', name: 'Mid' })]);
      }
      return page([
        wf({ id: 'w1', name: 'Zoo', isPublished: true, publicationStatus: 'ACTIVE' }),
        wf({ id: 'w2', name: 'Alpha' }),
        wf({ id: 'w3', name: 'Mid' }),
      ]);
    });

    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Zoo')).toBeInTheDocument());
    expect(screen.getByText('Alpha')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'public' } });
    await waitFor(() => expect(screen.queryByText('Alpha')).not.toBeInTheDocument());
    expect(screen.getByText('Zoo')).toBeInTheDocument();
    expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'public' }));

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'private' } });
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());
    expect(screen.getByText('Mid')).toBeInTheDocument();
    expect(screen.queryByText('Zoo')).not.toBeInTheDocument();
    expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'private' }));
  });
});

describe('WorkflowTable - loads a single server page (no fetch-all loop)', () => {
  it('requests page 0 ONCE and never pages through the rest, even when the total far exceeds the page', async () => {
    // totalCount (50) far exceeds the returned page: the old client looped page 0,1,2,... until it had
    // all 50; the server-paged version must request page 0 and never page through the rest.
    mocks.getWorkflowsPage.mockResolvedValue(page([wf({ id: 'w1', name: 'Only One' })], 50));

    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Only One')).toBeInTheDocument());

    expect(mocks.getWorkflowsPage).toHaveBeenCalledTimes(1);
    expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 25 }));
    expect(mocks.getWorkflowsPage).not.toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
  });

  it('resets back to page 0 when the visibility filter changes (the new query is for the first page)', async () => {
    // 60 rows over size 25 => 3 pages; jump to page 1, then flip the filter and assert the next
    // server query is page 0 (the filter reset), not the stale page 1.
    mocks.getWorkflowsPage.mockResolvedValue(page(
      Array.from({ length: 25 }, (_, i) => wf({ id: `w${i}`, name: `Item${String(i).padStart(2, '0')}` })),
      60,
    ));

    render(<WorkflowTable />);
    await waitFor(() => expect(screen.getByText('Item00')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('pg-next'));
    await waitFor(() => expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));

    mocks.getWorkflowsPage.mockClear();
    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'public' } });
    // Changing the filter snaps the active page back to 0: the settled query is page 0 + the new
    // filter. (A transient page-1 query may fire before the page-reset effect commits, but its
    // result is dropped by the request-id guard - we assert the page-0 query lands, not its absence.)
    await waitFor(() =>
      expect(mocks.getWorkflowsPage).toHaveBeenCalledWith(expect.objectContaining({ page: 0, visibility: 'public' })));
  });
});
