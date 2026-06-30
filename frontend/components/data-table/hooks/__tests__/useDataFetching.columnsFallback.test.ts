// @vitest-environment jsdom
/**
 * Regression tests for the root-view column source in {@link useDataFetching}.
 *
 * Bug: the data-table page (/app/tables/{id}) and side panel derived their
 * columns ONLY from the `/data-sources` list, which is org-strict
 * (WHERE organization_id = ?). A table the caller owns but that is tagged to a
 * different workspace - or one whose mapping_spec is empty - was therefore
 * absent / column-less in that list, so the grid showed only the system columns
 * (priority / created_at) even though its rows loaded fine via the
 * owner-permissive items endpoint.
 *
 * The fix falls back to the per-id `/data-sources/{id}/columns` endpoint, which
 * resolves columns with the SAME owner-permissive scope as the rows
 * (resolveAccessibleTenantId) and infers them from JSON data keys when
 * mapping_spec is empty. These tests pin:
 *   - the primary path is unchanged (list hit → no extra call);
 *   - DS absent from the org-strict list → fallback populates user columns;
 *   - DS present but empty mapping_spec → fallback populates user columns;
 *   - the always-prepended system columns (id/priority/created_at) are dropped;
 *   - the backend enum type (e.g. "NUMBER") is normalized to a canonical token.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
  setAuthTokenGetter: vi.fn(),
}));

import { useDataFetching } from '../useDataFetching';
import { authenticatedFetch } from '../../utils/authenticatedFetch';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const LIST_URL = '/api/proxy/data-sources';
const COLUMNS_URL = '/api/proxy/data-sources/42/columns';

const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const setup = () =>
  renderHook(() =>
    useDataFetching({
      dataSourceId: 42,
      jsonPath: undefined,
      workflowContext: null,
      showIdColumn: false,
      addToast: vi.fn(),
      setPagination: vi.fn(),
      snapshotData: undefined,
    })
  );

beforeEach(() => {
  mockFetch.mockReset();
});

describe('useDataFetching root column source', () => {
  it('uses the org-scoped list when it contains the datasource (no fallback call)', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === LIST_URL) {
        return Promise.resolve(
          okJson([
            {
              id: 42,
              mapping_spec: {
                name: { path: 'data.name', type: 'text', structure: 'scalar', display: { label: 'Name' } },
                email: { path: 'data.email', type: 'text', structure: 'scalar' },
              },
              column_order: [],
            },
          ])
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup();
    await act(async () => {
      await result.current.fetchColumns();
    });

    expect(result.current.columns.map((c) => c.field)).toEqual(['data.name', 'data.email']);
    // header label honored from mapping_spec display
    expect(result.current.columns.find((c) => c.field === 'data.name')?.header_name).toBe('Name');
    // The per-id columns endpoint must NOT be hit when the list already resolves columns.
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith(LIST_URL);
  });

  it('falls back to the per-id columns endpoint when the table is absent from the org-strict list', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === LIST_URL) return Promise.resolve(okJson([])); // DS 42 owned but tagged to another workspace
      if (url === COLUMNS_URL) {
        // Real wire shape: ColumnType / ColumnStructure serialize lowercase via @JsonValue.
        return Promise.resolve(
          okJson([
            { col_id: 'id', field: 'id', header_name: 'ID', type: 'number', structure: 'scalar' },
            { col_id: 'priority', field: 'priority', header_name: 'Priority', type: 'number', structure: 'scalar' },
            { col_id: 'created_at', field: 'created_at', header_name: 'Created At', type: 'date', structure: 'scalar' },
            { col_id: 'data.name', field: 'data.name', header_name: 'Name', type: 'text', structure: 'scalar' },
            { col_id: 'data.amount', field: 'data.amount', header_name: 'Amount', type: 'number', structure: 'scalar' },
            // Deprecated alias 'boolean' must resolve to the canonical 'checkbox'.
            { col_id: 'data.active', field: 'data.active', header_name: 'Active', type: 'boolean', structure: 'scalar' },
            // Non-scalar structure must mark the column navigable.
            { col_id: 'data.tags', field: 'data.tags', header_name: 'Tags', type: 'multi_select', structure: 'array' },
          ])
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup();
    await act(async () => {
      await result.current.fetchColumns();
    });

    const cols = result.current.columns;
    // system columns (id/priority/created_at) dropped; user columns kept in order
    expect(cols.map((c) => c.field)).toEqual(['data.name', 'data.amount', 'data.active', 'data.tags']);
    expect(cols.find((c) => c.field === 'data.amount')?.type).toBe('number');
    expect(cols.find((c) => c.field === 'data.name')?.header_name).toBe('Name');
    // deprecated alias normalized
    expect(cols.find((c) => c.field === 'data.active')?.type).toBe('checkbox');
    // non-scalar structure → navigable + structure preserved lowercase
    const tags = cols.find((c) => c.field === 'data.tags');
    expect(tags?.isNavigable).toBe(true);
    expect(tags?.structure).toBe('array');
    // scalar columns are not navigable
    expect(cols.find((c) => c.field === 'data.name')?.isNavigable).toBe(false);
    expect(mockFetch).toHaveBeenCalledWith(COLUMNS_URL);
  });

  it('clears stale columns when a forced refetch finds no user columns', async () => {
    // First load: the fallback populates a user column.
    mockFetch.mockImplementation((url: string) => {
      if (url === LIST_URL) return Promise.resolve(okJson([]));
      if (url === COLUMNS_URL) {
        return Promise.resolve(
          okJson([{ col_id: 'data.x', field: 'data.x', header_name: 'X', type: 'text', structure: 'scalar' }])
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup();
    await act(async () => {
      await result.current.fetchColumns();
    });
    expect(result.current.columns.map((c) => c.field)).toEqual(['data.x']);

    // The table now has no user columns left; a forced refetch must clear the grid
    // instead of leaving the previously-rendered columns stale.
    mockFetch.mockImplementation((url: string) => {
      if (url === LIST_URL) return Promise.resolve(okJson([{ id: 42, mapping_spec: {} }]));
      if (url === COLUMNS_URL) {
        return Promise.resolve(
          okJson([{ col_id: 'id', field: 'id', header_name: 'ID', type: 'number', structure: 'scalar' }])
        );
      }
      throw new Error(`unexpected url ${url}`);
    });
    await act(async () => {
      await result.current.fetchColumns(true);
    });
    expect(result.current.columns).toEqual([]);
  });

  it('falls back to the per-id columns endpoint when the listed datasource has an empty mapping_spec', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === LIST_URL) return Promise.resolve(okJson([{ id: 42, mapping_spec: {} }]));
      if (url === COLUMNS_URL) {
        return Promise.resolve(
          okJson([
            { col_id: 'data.title', field: 'data.title', header_name: 'Title', type: 'TEXT', structure: 'SCALAR' },
          ])
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup();
    await act(async () => {
      await result.current.fetchColumns();
    });

    expect(result.current.columns.map((c) => c.field)).toEqual(['data.title']);
    expect(mockFetch).toHaveBeenCalledWith(COLUMNS_URL);
  });
});
