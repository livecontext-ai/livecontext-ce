// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';

/**
 * Regression test for the inline chat table card column source.
 *
 * Bug: the card derived its columns ONLY from the `/data-sources` list
 * (org-strict, WHERE organization_id = ?). A table the caller owns but that is
 * tagged to a different workspace - or one with an empty mapping_spec - was
 * absent / column-less in that list, so the card rendered its empty "No data
 * yet" state even though its rows loaded fine via the owner-permissive items
 * endpoint. The card only draws its body when columns.length > 0.
 *
 * The fix falls back to the per-id `/data-sources/{id}/columns` endpoint
 * (owner-permissive, same scope as the rows; infers from JSON keys when
 * mapping_spec is empty). These assertions FAIL on the pre-fix card, which had
 * no fallback (columns stayed [] → empty state, no headers, no rows).
 */
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
const pushMock = vi.fn();
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/hooks/useDeleteFlow', () => ({
  useDeleteFlow: () => ({
    isDeleted: false,
    showDeleteModal: false,
    isDeleting: false,
    toast: null,
    hideToast: vi.fn(),
    handleDeleteClick: vi.fn(),
    handleConfirmDelete: vi.fn(),
    handleCancelDelete: vi.fn(),
  }),
}));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));

const getDataSourcesMock = vi.fn();
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getDataSources: () => getDataSourcesMock(),
    deleteDataSource: vi.fn(),
  },
}));

const apiGetMock = vi.fn();
vi.mock('@/lib/api', () => ({ apiClient: { get: (url: string) => apiGetMock(url) } }));

import { ChatTableView } from '../ChatTableView';

afterEach(() => {
  cleanup();
});

beforeEach(() => {
  pushMock.mockReset();
  getDataSourcesMock.mockReset();
  apiGetMock.mockReset();
});

describe('ChatTableView - column source fallback', () => {
  it('renders columns + rows from the per-id endpoint when the table is absent from the org-strict list', async () => {
    // DS 42 owned but tagged to another workspace → not in the org-scoped list.
    getDataSourcesMock.mockResolvedValue([]);

    apiGetMock.mockImplementation((url: string) => {
      if (url === '/data-sources/42/columns') {
        return Promise.resolve([
          { col_id: 'id', field: 'id', header_name: 'ID', type: 'number' },
          { col_id: 'priority', field: 'priority', header_name: 'Priority', type: 'number' },
          { col_id: 'created_at', field: 'created_at', header_name: 'Created At', type: 'date' },
          { col_id: 'data.name', field: 'data.name', header_name: 'Name', type: 'text' },
          { col_id: 'data.email', field: 'data.email', header_name: 'Email', type: 'text' },
        ]);
      }
      if (url.startsWith('/data-sources/42/items')) {
        return Promise.resolve({ rowData: [{ id: 1, data: { name: 'Alice', email: 'a@example.com' } }], totalItems: 1 });
      }
      throw new Error(`unexpected url ${url}`);
    });

    render(<ChatTableView dataSourceId={42} />);

    // Column headers from the fallback endpoint (system columns dropped)
    await waitFor(() => expect(screen.getByText('Name')).toBeInTheDocument());
    expect(screen.getByText('Email')).toBeInTheDocument();
    // Row values rendered (proves rows + columns line up)
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('a@example.com')).toBeInTheDocument();
    // The fallback endpoint was actually consulted
    expect(apiGetMock).toHaveBeenCalledWith('/data-sources/42/columns');
  });

  it('keeps the list-derived columns when the datasource IS in the org-scoped list (no fallback call)', async () => {
    getDataSourcesMock.mockResolvedValue([
      {
        id: 42,
        name: 'My Table',
        mapping_spec: {
          title: { path: 'data.title', type: 'text', display: { label: 'Title' } },
        },
      },
    ]);

    apiGetMock.mockImplementation((url: string) => {
      if (url.startsWith('/data-sources/42/items')) {
        return Promise.resolve({ rowData: [{ id: 1, data: { title: 'Hello' } }], totalItems: 1 });
      }
      throw new Error(`unexpected url ${url}`);
    });

    render(<ChatTableView dataSourceId={42} />);

    await waitFor(() => expect(screen.getByText('Title')).toBeInTheDocument());
    expect(screen.getByText('Hello')).toBeInTheDocument();
    // No per-id columns call when the list already resolved the columns.
    expect(apiGetMock).not.toHaveBeenCalledWith('/data-sources/42/columns');
  });
});
