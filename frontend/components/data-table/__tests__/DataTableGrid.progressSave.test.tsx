// @vitest-environment jsdom
/**
 * The progress slider THROUGH the grid: the save reaches the controller once, the drag value the
 * grid keeps for the cell is cleared on every path, and a read-only grid offers nothing to drag.
 *
 * The cell is covered on its own elsewhere. What only the grid can get wrong is the wiring: its
 * temp map, its duplicate guard and its read-only flag.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { DataTableGrid } from '../DataTableGrid';
import { normalizeRows } from '../utils/dataTableUtils';
import type { DataTableController } from '../useDataTableController';

const columns = [
  { col_id: 'data.Done', field: 'data.Done', header_name: 'Done', type: 'progress', displayConfig: { max: 10 } },
  { col_id: 'data.Score', field: 'data.Score', header_name: 'Score', type: 'rating' },
  { col_id: 'data.Ok', field: 'data.Ok', header_name: 'Ok', type: 'checkbox' },
];

const rows = normalizeRows(
  [{ id: 1, priority: 1, created_at: '2026-01-01T00:00:00Z', data: { Done: 2, Score: 3, Ok: false } }],
  { tenantId: 't', dataSourceId: 42 } as never,
);

function controllerWith(readOnly: boolean, progressTempValues = new Map<string, number>()) {
  const handleSaveEdit = vi.fn();
  const setProgressTempValues = vi.fn();
  const controller = {
    rows,
    displayRows: rows,
    columns,
    getUniqueColumns: () => columns,
    getDynamicColumns: () => columns,
    getFieldPath: (field: string) => (field.startsWith('data.') ? field.slice(5) : field),
    makeCellKey: (rowId: unknown, field: string) => `${rowId}-${field}`,
    getRowUniqueKey: (row: { id: number }) => String(row.id),
    selectedColumns: new Set<string>(),
    selectedRows: new Set<string>(),
    progressTempValues,
    viewConfig: { showCheckbox: false },
    sortConfig: null,
    editingCellKey: null,
    hoveredCell: null,
    draggedColumn: null,
    dragOverColumn: null,
    dragPosition: null,
    tableLoading: false,
    loadingColumns: false,
    isAddingRow: false,
    isAddingRowInline: false,
    newRowPriority: 1,
    newRowData: {},
    readOnly,
    revealedColumnField: null,
    revealedRowIds: new Set<number>(),
    pagination: { currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false },
    handleSort: vi.fn(),
    handleDragStart: vi.fn(),
    handleDragOver: vi.fn(),
    handleDragLeave: vi.fn(),
    handleDrop: vi.fn(),
    handleSaveEdit,
    handleRowDataChange: vi.fn(),
    toggleRowSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    setSelectedRows: vi.fn(),
    setHoveredCell: vi.fn(),
    setEditingCellKey: vi.fn(),
    setProgressTempValues,
    setShowAddColumnModal: vi.fn(),
    startAddingRowInline: vi.fn(),
    cancelAddingRowInline: vi.fn(),
    addNewRow: vi.fn(),
    setNewRowPriority: vi.fn(),
    openEditColumn: vi.fn(),
    loadMore: vi.fn(),
  } as unknown as DataTableController;
  return { controller, handleSaveEdit, setProgressTempValues };
}

function renderGrid(readOnly: boolean, temp?: Map<string, number>) {
  const made = controllerWith(readOnly, temp);
  const { container } = render(
    <DataTableGrid controller={made.controller} dataSourceId={42} navigateTo={vi.fn()} dataSourceBasePath="/app/tables" />,
  );
  return { container, ...made };
}

/** Applies every updater the grid handed to `setProgressTempValues`, in order, to a starting map. */
function replay(setter: ReturnType<typeof vi.fn>, start: Map<string, number>) {
  return setter.mock.calls.reduce<Map<string, number>>((map, [updater]) => updater(map), start);
}

const slider = (c: HTMLElement) => c.querySelector('tbody input[type="range"]') as HTMLInputElement;

beforeEach(() => {
  (window.HTMLElement.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = vi.fn();
  window.matchMedia = ((query: string) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null,
  })) as unknown as typeof window.matchMedia;
});

afterEach(() => cleanup());

describe('an editable grid', () => {
  it('a drag is saved once through the controller, as the text the save path expects', () => {
    const { container, handleSaveEdit } = renderGrid(false);
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.pointerUp(slider(container));
    expect(handleSaveEdit).toHaveBeenCalledTimes(1);
    expect(handleSaveEdit.mock.calls[0].slice(0, 3)).toEqual([1, 'data.Done', '7']);
  });

  it('the drag value is recorded for the cell, then cleared once it is saved', () => {
    const { container, setProgressTempValues } = renderGrid(false);
    fireEvent.change(slider(container), { target: { value: '7' } });
    expect([...replay(setProgressTempValues, new Map()).values()]).toEqual([7]);
    fireEvent.pointerUp(slider(container));
    expect(replay(setProgressTempValues, new Map()).size).toBe(0);
  });

  it('a move that ends where it started leaves no drag value behind in the grid', () => {
    const { container, setProgressTempValues } = renderGrid(false);
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.change(slider(container), { target: { value: '2' } });
    fireEvent.pointerUp(slider(container));
    expect(replay(setProgressTempValues, new Map()).size).toBe(0);
  });

  it('two different saves on the same cell both reach the controller, and nothing is left behind', () => {
    const { container, handleSaveEdit, setProgressTempValues } = renderGrid(false);
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    fireEvent.change(slider(container), { target: { value: '5' } });
    fireEvent.pointerUp(slider(container));
    expect(handleSaveEdit.mock.calls.map((c) => c[2])).toEqual(['7', '5']);
    expect(replay(setProgressTempValues, new Map()).size).toBe(0);
  });

  it('a grid that goes away with a move pending saves it to the right row and clears its drag value', () => {
    const made = controllerWith(false);
    const { container, unmount } = render(
      <DataTableGrid controller={made.controller} dataSourceId={42} navigateTo={vi.fn()} dataSourceBasePath="/app/tables" />,
    );
    fireEvent.change(slider(container), { target: { value: '8' } });
    expect(made.handleSaveEdit).not.toHaveBeenCalled();
    unmount();
    expect(made.handleSaveEdit).toHaveBeenCalledTimes(1);
    expect(made.handleSaveEdit.mock.calls[0].slice(0, 3)).toEqual([1, 'data.Done', '8']);
    expect(replay(made.setProgressTempValues, new Map()).size).toBe(0);
  });

  it('a slider save does not close the cell that is being edited elsewhere', () => {
    const made = controllerWith(false);
    const setEditingCellKey = (made.controller as unknown as { setEditingCellKey: ReturnType<typeof vi.fn> }).setEditingCellKey;
    const { container } = render(
      <DataTableGrid controller={made.controller} dataSourceId={42} navigateTo={vi.fn()} dataSourceBasePath="/app/tables" />,
    );
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(made.handleSaveEdit).toHaveBeenCalledTimes(1);
    expect(setEditingCellKey).not.toHaveBeenCalled();
  });

  it('a save the controller reports as refused puts the slider back on the stored value', async () => {
    const made = controllerWith(false);
    made.handleSaveEdit.mockResolvedValue(false);
    const { container } = render(
      <DataTableGrid controller={made.controller} dataSourceId={42} navigateTo={vi.fn()} dataSourceBasePath="/app/tables" />,
    );
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    expect(slider(container).value).toBe('7');
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('2');
  });

  it('a save the controller reports as stored keeps the value on screen', async () => {
    const made = controllerWith(false);
    made.handleSaveEdit.mockResolvedValue(true);
    const { container } = render(
      <DataTableGrid controller={made.controller} dataSourceId={42} navigateTo={vi.fn()} dataSourceBasePath="/app/tables" />,
    );
    fireEvent.change(slider(container), { target: { value: '7' } });
    fireEvent.pointerUp(slider(container));
    await act(async () => { await Promise.resolve(); });
    expect(slider(container).value).toBe('7');
  });

  it('draws a drag value the grid already holds for the cell', () => {
    // the key is the grid's own: read it from a first drag instead of guessing its spelling
    const probe = renderGrid(false);
    fireEvent.change(slider(probe.container), { target: { value: '7' } });
    const [cellKey] = [...replay(probe.setProgressTempValues, new Map()).keys()];
    cleanup();

    const { container } = renderGrid(false, new Map([[cellKey, 9]]));
    expect(slider(container).value).toBe('9');
    expect(container.textContent).toContain('9 / 10');
  });
});

describe('a read-only grid', () => {
  it('has no slider, and its stars and checkbox are disabled', () => {
    const { container } = renderGrid(true);
    expect(slider(container)).toBeNull();
    expect(container.textContent).toContain('2 / 10');
    const buttons = [...container.querySelectorAll('tbody button')] as HTMLButtonElement[];
    expect(buttons.length).toBeGreaterThan(0);
    buttons.forEach((b) => expect(b.disabled).toBe(true));
  });

  it('drops every slider move the grid was still holding', () => {
    // otherwise it is drawn over the stored value once the table is editable again
    const { setProgressTempValues } = renderGrid(true, new Map([['whatever', 6]]));
    expect(setProgressTempValues).toHaveBeenCalled();
    expect(replay(setProgressTempValues, new Map([['whatever', 6]])).size).toBe(0);
  });

  it('an editable grid leaves its slider moves alone on mount', () => {
    const { setProgressTempValues } = renderGrid(false, new Map([['whatever', 6]]));
    expect(setProgressTempValues).not.toHaveBeenCalled();
  });

  it('a click on a star or the checkbox reaches no save', () => {
    const { container, handleSaveEdit } = renderGrid(true);
    container.querySelectorAll('tbody button').forEach((b) => fireEvent.click(b));
    expect(handleSaveEdit).not.toHaveBeenCalled();
  });
});
