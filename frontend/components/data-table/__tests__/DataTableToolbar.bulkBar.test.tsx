// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { DataTableToolbar } from '../DataTableToolbar';
import type { DataTableController } from '../useDataTableController';

/**
 * The table-detail (/app/tables/{id}) row/column selection actions float in the
 * bottom-center SelectionActionBar - but ONLY standalone. Embedded usages (side
 * panel / modal / builder inspector) keep the inline toolbar, because an
 * `absolute` bar anchored to <main> would float over the whole app, not the panel.
 */

const baseController = (over: Partial<DataTableController> = {}): DataTableController => ({
  searchQuery: '',
  setSearchQuery: vi.fn(),
  showColumnFilters: false,
  setShowColumnFilters: vi.fn(),
  selectedExportFormat: '',
  setSelectedExportFormat: vi.fn(),
  selectedRows: new Set<string>(['r1-0']),
  selectedColumns: new Set<string>(),
  handleExportCSV: vi.fn(),
  handleExportExcel: vi.fn(),
  handleExportFull: vi.fn(),
  handleExportJSON: vi.fn(),
  exportLoading: false,
  setShowAddColumnModal: vi.fn(),
  setShowCreateDataSourceModal: vi.fn(),
  deleteSelectedRows: vi.fn(),
  deleteSelectedColumns: vi.fn(),
  confirmDeleteColumns: vi.fn(),
  showDeleteColumnsModal: false,
  setShowDeleteColumnsModal: vi.fn(),
  columnsToDelete: [],
  clearSelection: vi.fn(),
  clearColumnSelection: vi.fn(),
  readOnly: false,
  ...over,
} as unknown as DataTableController);

function renderToolbar(controller: DataTableController, embedded?: boolean) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <DataTableToolbar controller={controller} embedded={embedded} />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('DataTableToolbar - selection actions placement', () => {
  it('standalone: floats the actions in the SelectionActionBar with a "{count} selected" label', () => {
    renderToolbar(baseController());
    const bar = screen.getByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete Rows' })).toBeInTheDocument();
  });

  it('standalone: the bar × clears BOTH row and column selection', () => {
    const clearSelection = vi.fn();
    const clearColumnSelection = vi.fn();
    renderToolbar(baseController({ clearSelection, clearColumnSelection }));
    fireEvent.click(within(screen.getByTestId('selection-action-bar')).getByTestId('selection-action-bar-clear'));
    expect(clearSelection).toHaveBeenCalledTimes(1);
    expect(clearColumnSelection).toHaveBeenCalledTimes(1);
  });

  it('embedded: keeps actions inline - no floating bar is rendered', () => {
    renderToolbar(baseController(), true);
    expect(screen.queryByTestId('selection-action-bar')).not.toBeInTheDocument();
    // The same actions still render inline (Create Table button is present).
    expect(screen.getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
    // …with an inline Cancel (clear) control instead of the bar's × icon.
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('hides the delete-rows action in read-only mode', () => {
    renderToolbar(baseController({ readOnly: true }));
    const bar = screen.getByTestId('selection-action-bar');
    expect(within(bar).queryByRole('button', { name: 'Delete Rows' })).not.toBeInTheDocument();
    // Create Table is not a mutation of the open table, so it stays available.
    expect(within(bar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
  });

  it('shows the delete-columns action for a column selection, and hides it in read-only mode', () => {
    // Editable: a column selection floats a "Delete Columns" action.
    const editable = renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set(['c1']) }));
    const editableBar = editable.getByTestId('selection-action-bar');
    expect(within(editableBar).getByRole('button', { name: 'Delete Columns' })).toBeInTheDocument();
    cleanup();

    // Read-only: same column selection, but the destructive action is gone.
    renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set(['c1']), readOnly: true }));
    const roBar = screen.getByTestId('selection-action-bar');
    expect(within(roBar).queryByRole('button', { name: 'Delete Columns' })).not.toBeInTheDocument();
    // Create Table is non-destructive, so it remains.
    expect(within(roBar).getByRole('button', { name: 'Create Table' })).toBeInTheDocument();
  });

  it('renders nothing selection-related when there is no selection', () => {
    renderToolbar(baseController({ selectedRows: new Set(), selectedColumns: new Set() }));
    expect(screen.queryByTestId('selection-action-bar')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create Table' })).not.toBeInTheDocument();
  });
});
