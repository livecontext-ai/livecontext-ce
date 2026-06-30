// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * DataSourceTable (Tables list) is now server-paginated + server-enriched: one page request returns
 * only the visible slice, already filtered (visibility) and sorted, with each row's publication badge
 * inlined in `publicationStatuses`. This pins the new contract:
 *   - the Globe/Lock marker is painted straight from the page envelope (no per-row status sweep, no
 *     "suppress until resolved" gate);
 *   - changing the visibility filter or the sort re-queries the server with that param (the narrowing
 *     / ordering happens server-side, not client-side over a fully-loaded set).
 *
 * Real PublicationStatusIcon + DataSourceCard, next-intl echoed to keys, native-<select> stand-in.
 */

const mocks = vi.hoisted(() => ({
  getDataSourcesPage: vi.fn(),
  getResourcePublicationStatus: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { cloneDataSource: vi.fn(), deleteDataSource: vi.fn(), createDataSource: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/datasource.service', () => ({
  dataSourceService: { getDataSourcesPage: mocks.getDataSourcesPage },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getResourcePublicationStatus: mocks.getResourcePublicationStatus, unpublishResource: vi.fn() },
}));
vi.mock('@/components/chat/CreateDataSourceModal', () => ({ CreateDataSourceModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ children }: any) => children, DialogContent: ({ children }: any) => children,
  DialogHeader: ({ children }: any) => children, DialogTitle: ({ children }: any) => children,
}));
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
    return ReactLib.createElement('select',
      { 'aria-label': acc.ariaLabel, value, onChange: (e: any) => onValueChange(e.target.value) },
      acc.options.map((o) => ReactLib.createElement('option', { key: o.value, value: o.value }, o.label)));
  };
  const SelectTrigger = ({ children, 'aria-label': ariaLabel }: any) => ReactLib.createElement('span', { 'aria-label': ariaLabel }, children);
  const SelectValue = () => null;
  const SelectContent = ({ children }: any) => children;
  const SelectItem: any = () => null;
  SelectItem.__isSelectItem = true;
  return { Select, SelectTrigger, SelectContent, SelectItem, SelectValue };
});

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import DataSourceTable from '../DataSourceTable';

const ds = (id: string, name: string) => ({ id, name, updated_at: '2026-06-01T00:00:00Z' });
const page = (
  items: any[],
  publicationStatuses: Record<string, { status: string; rejectionReason?: string }> = {},
) => ({
  items, totalCount: items.length, page: 0, size: 25,
  rowCounts: Object.fromEntries(items.map((i) => [String(i.id), 0])), sampleRows: {},
  publicationStatuses,
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('DataSourceTable - Globe/Lock marker comes from the page envelope', () => {
  it('paints Globe (shared) / Lock (private) immediately, with no per-row status call', async () => {
    // Status arrives WITH the page (10 = ACTIVE/shared, 11 absent = private) - no async sweep.
    mocks.getDataSourcesPage.mockResolvedValue(
      page([ds('10', 'Shared Table'), ds('11', 'Private Table')], { '10': { status: 'ACTIVE' } }),
    );

    render(<DataSourceTable />);
    await waitFor(() => expect(screen.getByText('Shared Table')).toBeInTheDocument());

    // No gating: the markers are present as soon as the cards render.
    expect(screen.getByTitle('workflow.shared')).toBeInTheDocument();
    expect(screen.getByTitle('common.visibilityPrivate')).toBeInTheDocument();
    // The N+1 per-row status endpoint must never be called.
    expect(mocks.getResourcePublicationStatus).not.toHaveBeenCalled();
  });
});

describe('DataSourceTable - visibility filter + sort re-query the server', () => {
  it('the visibility filter narrows via a server query carrying visibility=public/private', async () => {
    mocks.getDataSourcesPage.mockImplementation((opts: any = {}) => {
      if (opts.visibility === 'public') {
        return Promise.resolve(page([ds('10', 'Shared Table')], { '10': { status: 'ACTIVE' } }));
      }
      if (opts.visibility === 'private') {
        return Promise.resolve(page([ds('11', 'Private Table')]));
      }
      return Promise.resolve(page([ds('10', 'Shared Table'), ds('11', 'Private Table')], { '10': { status: 'ACTIVE' } }));
    });

    render(<DataSourceTable />);
    await waitFor(() => expect(screen.getByText('Shared Table')).toBeInTheDocument());
    expect(screen.getByText('Private Table')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'public' } });
    await waitFor(() => expect(screen.queryByText('Private Table')).not.toBeInTheDocument());
    expect(screen.getByText('Shared Table')).toBeInTheDocument();
    expect(mocks.getDataSourcesPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'public' }));

    fireEvent.change(screen.getByLabelText('common.filterByVisibility'), { target: { value: 'private' } });
    await waitFor(() => expect(screen.getByText('Private Table')).toBeInTheDocument());
    expect(screen.queryByText('Shared Table')).not.toBeInTheDocument();
    expect(mocks.getDataSourcesPage).toHaveBeenCalledWith(expect.objectContaining({ visibility: 'private' }));
  });

  it('changing the sort re-queries the server with the chosen sort key', async () => {
    mocks.getDataSourcesPage.mockResolvedValue(page([ds('10', 'Alpha'), ds('11', 'Beta')]));

    render(<DataSourceTable />);
    await waitFor(() => expect(screen.getByText('Alpha')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('common.sortBy'), { target: { value: 'name' } });
    await waitFor(() =>
      expect(mocks.getDataSourcesPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'name' })));
    // Ordering is the server's job now - the client never re-sorts a fully-loaded set.
    expect(mocks.getResourcePublicationStatus).not.toHaveBeenCalled();
  });
});
