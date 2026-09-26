// @vitest-environment jsdom
/**
 * The run-logs ID reads as execution coordinates ("20.0.2" = epoch 20, loop iteration 2), which a
 * user cannot guess. The "i" next to the ID header explains it. It must appear exactly where that
 * id is shown (the run-logs identity lane), stay off every other table, and never sort the column
 * when clicked (the header cell sorts on click).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { DataTableGrid } from '../DataTableGrid';
import { createViewConfig } from '../viewConfig';
import type { DataTableController } from '../useDataTableController';

const WORKFLOW_CONTEXT = { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'mcp:search' };

const col = (field: string) => ({ col_id: field, field, header_name: field, type: 'text' as const, sortable: true });

const stepRows = [
  {
    id: 555,
    data_source_id: 0,
    tenant_id: 't',
    data: { id: '7', status: 'completed', email: 'a@example.com' },
    priority: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    _isWorkflowStep: true,
  },
];

function controllerWith(
  viewConfig: ReturnType<typeof createViewConfig>,
  columns: ReturnType<typeof col>[],
  handleSort = vi.fn(),
): DataTableController {
  return {
    rows: stepRows,
    displayRows: stepRows,
    columns,
    getUniqueColumns: () => columns,
    getDynamicColumns: () => columns,
    getFieldPath: (field: string) => (field.startsWith('data.') ? field.slice(5) : field),
    makeCellKey: (rowId: unknown, field: string) => `${rowId}-${field}`,
    getRowUniqueKey: (row: { id: number }) => String(row.id),
    selectedColumns: new Set<string>(),
    selectedRows: new Set<string>(),
    progressTempValues: new Map(),
    viewConfig,
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
    readOnly: true,
    revealedColumnField: null,
    revealedRowIds: new Set<number>(),
    pagination: { currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false },
    handleSort,
    handleDragStart: vi.fn(),
    handleDragOver: vi.fn(),
    handleDragLeave: vi.fn(),
    handleDrop: vi.fn(),
    handleSaveEdit: vi.fn(),
    handleRowDataChange: vi.fn(),
    toggleRowSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    setSelectedRows: vi.fn(),
    setHoveredCell: vi.fn(),
    setEditingCellKey: vi.fn(),
    setProgressTempValues: vi.fn(),
    setShowAddColumnModal: vi.fn(),
    startAddingRowInline: vi.fn(),
    cancelAddingRowInline: vi.fn(),
    addNewRow: vi.fn(),
    setNewRowPriority: vi.fn(),
    openEditColumn: vi.fn(),
    loadMore: vi.fn(),
  } as unknown as DataTableController;
}

function renderGrid(
  workflowContext: typeof WORKFLOW_CONTEXT | undefined,
  showIdColumn: boolean,
  jsonPath: string,
  columns: ReturnType<typeof col>[],
  handleSort = vi.fn(),
) {
  const viewConfig = createViewConfig(workflowContext, showIdColumn, jsonPath);
  return render(
    <DataTableGrid
      controller={controllerWith(viewConfig, columns, handleSort)}
      workflowContext={workflowContext}
      jsonPath={jsonPath}
      dataSourceId={0}
      navigateTo={vi.fn()}
      dataSourceBasePath="/app/tables"
    />,
  );
}

const hint = () => screen.queryByTestId('workflow-row-id-hint');

beforeEach(() => {
  (window.HTMLElement.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = vi.fn();
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null,
  })) as unknown as typeof window.matchMedia;
});

afterEach(cleanup);

describe('DataTableGrid - the run-logs ID explains itself', () => {
  it('puts the "i" in the ID header of a step table, and only there', () => {
    renderGrid(WORKFLOW_CONTEXT, true, '', [col('id'), col('status')]);

    const button = hint();
    expect(button).not.toBeNull();
    expect(button).toHaveAttribute('aria-label', 'label');
    expect(button!.closest('th')!.textContent).toContain('id');
  });

  it('keeps it in the ID lane while drilled into an output (the lane shows `<parent id>:<n>` there)', () => {
    renderGrid(WORKFLOW_CONTEXT, true, 'output.items', [col('id'), col('email')]);

    expect(hint()).not.toBeNull();
  });

  it('leaves a data column that is merely named `id` alone (nested view with no ID lane)', () => {
    renderGrid(WORKFLOW_CONTEXT, false, 'output.items', [col('email'), col('id')]);

    expect(hint()).toBeNull();
  });

  it('never appears on an ordinary table, whose ids are not coordinates', () => {
    renderGrid(undefined, true, '', [col('id'), col('email')]);

    expect(hint()).toBeNull();
  });

  it('keeps the ID lane wide enough for its label AND the hint, even for a two-digit id', () => {
    renderGrid(WORKFLOW_CONTEXT, true, '', [col('id'), col('status')]);

    // Sized on the ids alone ("7"), the lane fell to 48px and squeezed "ID" out of its header.
    const header = hint()!.closest('th')!;
    expect(parseInt(header.style.width, 10)).toBeGreaterThanOrEqual(88);
  });

  it('opening it does not sort the column; the header itself still does', () => {
    const handleSort = vi.fn();
    renderGrid(WORKFLOW_CONTEXT, true, '', [col('id'), col('status')], handleSort);

    fireEvent.click(hint()!);
    expect(handleSort).not.toHaveBeenCalled();

    fireEvent.click(hint()!.closest('th')!);
    expect(handleSort).toHaveBeenCalledWith('id');
  });

  it('explains the format with the ids exactly as the table shows them', async () => {
    renderGrid(WORKFLOW_CONTEXT, true, '', [col('id'), col('status')]);

    // A CLICK, not a focus: this "i" goes through InfoPopover, the app's one info icon, which
    // opens on click or Enter/Space and never on hover or focus - a hover 'i' is unreachable on a
    // touch screen, which is why the repo has one component and a guard that enforces it.
    await act(async () => { fireEvent.click(hint()!); });

    // Scoped to the panel: none of these ids is on the grid, so they can only come from it.
    const tooltip = await screen.findByRole('dialog');
    for (const example of ['21', '20.1', '20.0.2', '20.0.0.3', '21:3']) {
      expect(tooltip.textContent).toContain(example);
    }
    for (const key of ['intro', 'exampleIteration', 'exampleNested', 'sameId']) {
      expect(tooltip.textContent).toContain(key);
    }
  });
});
