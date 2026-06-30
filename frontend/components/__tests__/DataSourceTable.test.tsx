// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

// External deps stubbed so the component is exercised in isolation.
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { createDataSource: vi.fn(), deleteDataSource: vi.fn(), cloneDataSource: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/datasource.service', () => ({
  dataSourceService: { getDataSourcesPage: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getResourcePublicationStatus: vi.fn().mockResolvedValue({ published: false, exists: false }) },
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/components/marketplace/PublishResourceModal', () => ({ default: () => null }));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import DataSourceTable from '../DataSourceTable';
import { dataSourceService } from '@/lib/api/orchestrator/datasource.service';

const mockGetPage = vi.mocked(dataSourceService.getDataSourcesPage);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderTable() {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <DataSourceTable />
    </NextIntlClientProvider>,
  );
}

describe('DataSourceTable cards - column + row counts in the footer', () => {
  it('shows each table\'s column count and the server-batched row count', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '10', name: 'Alpha', description: 'first', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: { title: { type: 'text' }, score: { type: 'number' }, tags: { type: 'multi_select' } },
        },
        // Beta has no columns and is ABSENT from rowCounts → must default to 0.
        { id: '20', name: 'Beta', updated_at: '2026-06-01T00:00:00Z', mapping_spec: {} },
      ] as any,
      totalCount: 2,
      page: 0,
      size: 25, publicationStatuses: {},
      rowCounts: { '10': 5 },
      sampleRows: {},
    });

    renderTable();

    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();

    // Alpha: 3 user columns, 5 rows (from rowCounts['10']).
    expect(screen.getByText('3 columns')).toBeInTheDocument();
    expect(screen.getByText('5 rows')).toBeInTheDocument();
    // Column-type icon bubbles are no longer rendered anywhere on the cards.
    expect(screen.queryByTitle('text')).not.toBeInTheDocument();

    // Beta: no columns, and absent from rowCounts → "0 columns" / "0 rows".
    expect(screen.getByText('0 columns')).toBeInTheDocument();
    expect(screen.getByText('0 rows')).toBeInTheDocument();
  });

  it('renders a sober card backdrop - the blue spreadsheet-grid pattern is gone', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        { id: '10', name: 'Alpha', updated_at: '2026-06-01T00:00:00Z', mapping_spec: { title: { type: 'text' } } },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {}, rowCounts: { '10': 1 }, sampleRows: {},
    });

    const { container } = render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <DataSourceTable />
      </NextIntlClientProvider>,
    );
    await screen.findByText('Alpha');

    // The removed grid drew 1px linear-gradient lines via inline style; none should remain.
    const gridLayers = Array.from(container.querySelectorAll<HTMLElement>('[style*="linear-gradient"]'))
      .filter((el) => (el.getAttribute('style') ?? '').includes('1px'));
    expect(gridLayers).toHaveLength(0);
  });

  it('looks up the row count via String(id) so a numeric id still resolves the string-keyed map', async () => {
    // The backend serializes `id` as a JSON number (Long); the rowCounts map is
    // keyed by JSON string. The component must coerce with String(ds.id) - a
    // numeric-id lookup (rowCounts[ds.id]) would miss and wrongly show "0 rows".
    mockGetPage.mockResolvedValue({
      items: [
        { id: 30, name: 'Gamma', updated_at: '2026-06-01T00:00:00Z', mapping_spec: { a: { type: 'text' } } },
      ] as any,
      totalCount: 1,
      page: 0,
      size: 25, publicationStatuses: {},
      rowCounts: { '30': 7 },
      sampleRows: {},
    });

    renderTable();

    expect(await screen.findByText('Gamma')).toBeInTheDocument();
    expect(screen.getByText('7 rows')).toBeInTheDocument();
    expect(screen.queryByText('0 rows')).not.toBeInTheDocument();
  });

  it('renders the empty state when there are no tables', async () => {
    mockGetPage.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 25, publicationStatuses: {}, rowCounts: {}, sampleRows: {} });

    renderTable();

    expect(await screen.findByText('No tables found')).toBeInTheDocument();
  });
});

describe('DataSourceTable cards - mini-table preview (table-visualize-card style)', () => {
  it('paints headers + sample rows from the batched sampleRows payload', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '10', name: 'People', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: {
            full_name: { type: 'text', display: { label: 'Name' } },
            email: { type: 'text' },
          },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '10': 2 },
      sampleRows: {
        '10': [
          { full_name: 'Alice', email: 'alice@x.com' },
          { full_name: 'Bob', email: 'bob@x.com' },
        ],
      },
    });

    renderTable();

    // Header labels: display.label for full_name, bare-key fallback for email.
    expect(await screen.findByText('Name')).toBeInTheDocument();
    expect(screen.getByText('email')).toBeInTheDocument();
    // Sample cell values from the two preview rows.
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('alice@x.com')).toBeInTheDocument();
    expect(screen.getByText('Bob')).toBeInTheDocument();
    // Regression: the column-type icon strip that used to sit above the table is gone -
    // the card now shows the clean mini-table only, like the chat table-visualize card.
    expect(screen.queryByTitle('text')).not.toBeInTheDocument();
    // Both columns fit under the PREVIEW_COLS cap → no "+N" indicator.
    expect(screen.queryByText(/^\+\d+$/)).not.toBeInTheDocument();
  });

  it('derives preview headers from the row data when mapping_spec is empty', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        { id: '20', name: 'Cities', updated_at: '2026-06-01T00:00:00Z', mapping_spec: {} },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '20': 1 },
      sampleRows: { '20': [{ city: 'Paris', country: 'France' }] },
    });

    renderTable();

    // No mapping_spec → header keys come straight from the row payload.
    expect(await screen.findByText('city')).toBeInTheDocument();
    expect(screen.getByText('Paris')).toBeInTheDocument();
    expect(screen.getByText('France')).toBeInTheDocument();
  });

  it('formats boolean and empty cells compactly (Yes/No, em dash)', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '30', name: 'Flags', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: { active: { type: 'boolean' }, note: { type: 'text' } },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '30': 1 },
      sampleRows: { '30': [{ active: true, note: null }] },
    });

    renderTable();

    expect(await screen.findByText('Yes')).toBeInTheDocument(); // boolean → Yes
    expect(screen.getByText('-')).toBeInTheDocument();          // null → em dash
  });

  it('caps visible columns at PREVIEW_COLS behind a "+N" indicator (no horizontal scroll), clamps rows to 3, and formats numeric, object and empty cells', async () => {
    const longObject = { k: 'a'.repeat(50) }; // JSON longer than the 40-char preview cap
    const truncated = JSON.stringify(longObject).slice(0, 40);

    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '50', name: 'Wide', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: {
            c1: { type: 'number', display: { label: 'C1' } },
            c2: { type: 'text', display: { label: 'C2' } },
            c3: { type: 'text', display: { label: 'C3' } },
            c4: { type: 'text', display: { label: 'C4' } },
            c5: { type: 'text', display: { label: 'C5' } },
            c6: { type: 'text', display: { label: 'C6' } },
          },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '50': 4 },
      sampleRows: {
        '50': [
          { c1: 7, c2: longObject, c3: '', c4: 'r1c4', c5: 'r1c5', c6: 'r1c6' },
          { c1: 8, c2: 'b', c3: 'c', c4: 'x', c5: 'x', c6: 'x' },
          { c1: 9, c2: 'b', c3: 'c', c4: 'x', c5: 'x', c6: 'x' },
          { c1: 'row4-should-not-render', c2: 'x', c3: 'x', c4: 'x', c5: 'x', c6: 'x' },
        ],
      },
    });

    renderTable();

    // Only the first PREVIEW_COLS (4) columns render - the preview never scrolls
    // horizontally; the remaining columns collapse into a "+N" indicator instead.
    expect(await screen.findByText('C1')).toBeInTheDocument();
    expect(screen.getByText('C2')).toBeInTheDocument();
    expect(screen.getByText('C3')).toBeInTheDocument();
    expect(screen.getByText('C4')).toBeInTheDocument();
    // C5/C6 are beyond the cap → hidden behind the indicator, never rendered as columns.
    expect(screen.queryByText('C5')).not.toBeInTheDocument();
    expect(screen.queryByText('C6')).not.toBeInTheDocument();
    expect(screen.getByText('r1c4')).toBeInTheDocument();        // 4th column cell still shows
    expect(screen.queryByText('r1c5')).not.toBeInTheDocument();  // 5th column cell hidden
    // Two columns hidden (6 total − 4 cap) → "+2" indicator (the actual remaining count).
    expect(screen.getByText('+2')).toBeInTheDocument();

    // Cell formatting on row 1: numeric → '7', object → 40-char-truncated JSON, '' → em dash.
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText(truncated)).toBeInTheDocument();
    expect(screen.queryByText(JSON.stringify(longObject))).not.toBeInTheDocument(); // full object never shown
    expect(screen.getByText('-')).toBeInTheDocument();

    // Only the first PREVIEW_ROWS (3) rows render; the 4th row's marker never appears.
    expect(screen.queryByText('row4-should-not-render')).not.toBeInTheDocument();
  });

  it('shows every column with no "+N" indicator (nor ellipsis cell) when the count is exactly PREVIEW_COLS', async () => {
    // Boundary: derivedHeaders.length === PREVIEW_COLS (4) → extraCols === 0 → no badge.
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '60', name: 'Exactly4', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: {
            k1: { type: 'text', display: { label: 'K1' } },
            k2: { type: 'text', display: { label: 'K2' } },
            k3: { type: 'text', display: { label: 'K3' } },
            k4: { type: 'text', display: { label: 'K4' } },
          },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '60': 1 },
      sampleRows: { '60': [{ k1: 'v1', k2: 'v2', k3: 'v3', k4: 'v4' }] },
    });

    renderTable();

    // All 4 columns fit exactly under the cap → every header shows, no overflow indicator/cell.
    expect(await screen.findByText('K4')).toBeInTheDocument();
    expect(screen.queryByText(/^\+\d+$/)).not.toBeInTheDocument();
    expect(screen.queryByText('…')).not.toBeInTheDocument();
  });

  it('renders a "+1" indicator with one ellipsis overflow cell, in a fixed-layout non-scrolling table, when one column is past the cap', async () => {
    // Boundary: derivedHeaders.length === PREVIEW_COLS + 1 (5) → extraCols === 1 → "+1".
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '61', name: 'Five', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: {
            k1: { type: 'text', display: { label: 'K1' } },
            k2: { type: 'text', display: { label: 'K2' } },
            k3: { type: 'text', display: { label: 'K3' } },
            k4: { type: 'text', display: { label: 'K4' } },
            k5: { type: 'text', display: { label: 'K5' } },
          },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: { '61': 1 },
      sampleRows: { '61': [{ k1: 'v1', k2: 'v2', k3: 'v3', k4: 'v4', k5: 'v5' }] },
    });

    const { container } = render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <DataSourceTable />
      </NextIntlClientProvider>,
    );
    await screen.findByText('Five');

    // One column past the cap → 5th header hidden, exact "+1" count (not just a "+"),
    // and exactly one "…" overflow cell (one preview row → one ellipsis cell).
    expect(screen.queryByText('K5')).not.toBeInTheDocument();
    expect(screen.getByText('+1')).toBeInTheDocument();
    expect(screen.getAllByText('…')).toHaveLength(1);

    // Structural: the preview never scrolls horizontally - the table is fixed-layout + full
    // width, and there is no horizontal-scroll container (the pre-change code used
    // `overflow-x-auto` + `w-max`).
    const table = container.querySelector('table');
    expect(table?.className).toContain('table-fixed');
    expect(table?.className).toContain('w-full');
    expect(container.querySelector('.overflow-x-auto')).toBeNull();
  });

  it('shows the column header row + a "no rows yet" placeholder when the table has columns but no rows', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        {
          id: '40', name: 'Empty', updated_at: '2026-06-01T00:00:00Z',
          mapping_spec: { title: { type: 'text', display: { label: 'Title' } } },
        },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: {},      // zero rows
      sampleRows: {},     // …but the table HAS a column
    });

    const { container } = render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <DataSourceTable />
      </NextIntlClientProvider>,
    );
    await screen.findByText('Empty');

    // Columns exist → the mini-table renders its header row even with zero rows,
    // mirroring the chat table-visualize card (ChatTableView).
    expect(screen.getByText('Title')).toBeInTheDocument();
    expect(container.querySelector('table')).not.toBeNull();
    // The empty body shows the "no rows yet" placeholder, NOT the centered icon hero.
    expect(screen.getByText('No rows yet')).toBeInTheDocument();
    expect(container.querySelector('.lucide-table')).toBeNull();
  });

  it('falls back to a centered table icon (no mini-table) only when the table has no columns at all', async () => {
    mockGetPage.mockResolvedValue({
      items: [
        { id: '41', name: 'Schemaless', updated_at: '2026-06-01T00:00:00Z', mapping_spec: {} },
      ] as any,
      totalCount: 1, page: 0, size: 25, publicationStatuses: {},
      rowCounts: {},   // zero rows
      sampleRows: {},  // …and no rows to derive columns from → no columns at all
    });

    const { container } = render(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <DataSourceTable />
      </NextIntlClientProvider>,
    );
    await screen.findByText('Schemaless');

    // No columns AND no rows → no mini-table; the card shows the plain table icon hero,
    // and there is no "no rows yet" placeholder (there is no header row to sit under).
    expect(container.querySelector('table')).toBeNull();
    expect(screen.queryByText('No rows yet')).not.toBeInTheDocument();
    expect(screen.queryByTitle('text')).not.toBeInTheDocument();
    expect(container.querySelector('.lucide-table')).toBeInTheDocument();
  });
});
