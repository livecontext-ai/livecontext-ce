// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * DataSourceTable (Tables list) selection actions float in the bottom-center
 * SelectionActionBar, with the same Share → Unshare → Pending-Review three-way.
 * Kept separate from DataSourceTable.test.tsx (which covers the card preview).
 */

const mocks = vi.hoisted(() => ({
  getDataSourcesPage: vi.fn(),
  getResourcePublicationStatus: vi.fn(),
  cloneDataSource: vi.fn(),
  deleteDataSource: vi.fn(),
  clear: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    cloneDataSource: mocks.cloneDataSource,
    deleteDataSource: mocks.deleteDataSource,
    createDataSource: vi.fn(),
  },
}));
vi.mock('@/lib/api/orchestrator/datasource.service', () => ({
  dataSourceService: { getDataSourcesPage: mocks.getDataSourcesPage },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getResourcePublicationStatus: mocks.getResourcePublicationStatus, unpublishResource: vi.fn() },
}));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
vi.mock('@/components/chat/CreateDataSourceModal', () => ({ CreateDataSourceModal: () => null }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: new Set<string>(['10']),
    toggle: vi.fn(),
    clear: mocks.clear,
    selectAll: vi.fn(),
  }),
}));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import DataSourceTable from '../DataSourceTable';

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <DataSourceTable />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

// The bar's Share/Unshare/Pending state now comes from the page envelope's `publicationStatuses`
// (batched server-side), not a per-row getResourcePublicationStatus call.
const oneTable = (publicationStatuses: Record<string, { status: string; rejectionReason?: string }> = {}) =>
  mocks.getDataSourcesPage.mockResolvedValue({
    items: [{ id: '10', name: 'Table One', updated_at: '2026-06-01T00:00:00Z', mapping_spec: { a: { type: 'text' } } }],
    totalCount: 1, page: 0, size: 25, rowCounts: { '10': 1 }, sampleRows: {}, publicationStatuses,
  });

describe('DataSourceTable - selection actions float + publish state machine in the bar', () => {
  it('an unpublished selection shows Clone + Share + Delete in the bar', async () => {
    oneTable();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Clone (1)' })).toBeInTheDocument();
    expect(await within(bar).findByRole('button', { name: 'Share' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete (1)' })).toBeInTheDocument();
    // The per-row status endpoint must NOT be called anymore (status ships with the page).
    expect(mocks.getResourcePublicationStatus).not.toHaveBeenCalled();
  });

  it('a published selection swaps Share → Unshare', async () => {
    oneTable({ '10': { status: 'ACTIVE' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Unshare' })).toBeInTheDocument();
    expect(within(bar).queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();
  });

  it('a pending-review selection shows a disabled "Pending Review" action', async () => {
    oneTable({ '10': { status: 'PENDING_REVIEW' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Pending Review' })).toBeDisabled();
  });

  it('the bar × clears the selection', async () => {
    oneTable();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByTestId('selection-action-bar-clear'));
    expect(mocks.clear).toHaveBeenCalledTimes(1);
  });
});
