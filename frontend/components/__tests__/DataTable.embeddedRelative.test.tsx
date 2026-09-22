// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, fireEvent, screen } from '@testing-library/react';

const controllerOptions = vi.hoisted(() => vi.fn());
const state = vi.hoisted(() => ({ error: null as string | null, tableLoading: false, handlePageChange: vi.fn() }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

/**
 * The floating selection pill (SelectionActionBar) is `position: absolute` and
 * resolves to the nearest positioned ancestor. Standalone that ancestor is the
 * app's `<main>`; embedded (side panel / modal / builder inspector) the DataTable
 * root must itself be `relative` so the pill stays INSIDE the panel instead of
 * floating over the whole app. This pins that one load-bearing class toggle.
 */

// Heavy children are irrelevant here - stub them so we can render the root cheaply.
vi.mock('@/components/data-table/useDataTableController', () => ({
  useDataTableController: (options: unknown) => {
    controllerOptions(options);
    return { toasts: [], removeToast: vi.fn(), rows: [], displayRows: [], ...state };
  },
}));
vi.mock('@/components/data-table/DataTableToolbar', () => ({ DataTableToolbar: () => null }));
vi.mock('@/components/data-table/ColumnFiltersPanel', () => ({ ColumnFiltersPanel: () => null }));
vi.mock('@/components/data-table/DataTableGrid', () => ({ DataTableGrid: () => null }));
vi.mock('@/components/data-table/DataTablePagination', () => ({ DataTablePagination: () => null }));
vi.mock('@/components/data-table/DataTableModals', () => ({ DataTableModals: () => null }));
vi.mock('@/components/ui/breadcrumb', () => ({ Breadcrumb: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

import DataTable from '@/components/DataTable';

afterEach(() => { cleanup(); state.error = null; state.tableLoading = false; vi.clearAllMocks(); });

function rootClass(el: Element | null): string {
  return (el as HTMLElement | null)?.className ?? '';
}

describe('DataTable root positioning context', () => {
  it('shows failed workflow table loads and retries within the existing context instead of showing an empty grid', () => {
    state.error = 'Failed to load';
    const props = { workflowId: 'wf', runId: 'run', stepAlias: 'read' };
    const { rerender } = render(<DataTable workflowContext={props} jsonPath="output.items" />);
    expect(screen.getByRole('alert')).toHaveTextContent('loadDataError');
    fireEvent.click(screen.getByRole('button', { name: 'retry' }));
    expect(state.handlePageChange).toHaveBeenCalledWith(1);
    expect(controllerOptions).toHaveBeenLastCalledWith(expect.objectContaining({ jsonPath: 'output.items', workflowContext: props, readOnly: true }));
    state.error = null;
    rerender(<DataTable workflowContext={props} jsonPath="output.items" />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it.each(['', 'output.items.0'])('workflow logs are read-only at path %s even when a caller requests editing', jsonPath => {
    render(<DataTable dataSourceId={1} embedded readOnly={false} jsonPath={jsonPath} workflowContext={{ workflowId: 'wf', runId: 'run', stepId: 12 }} />);
    expect(controllerOptions).toHaveBeenLastCalledWith(expect.objectContaining({ readOnly: true, jsonPath }));
  });

  it('ordinary data source tables remain editable', () => {
    render(<DataTable dataSourceId={1} />);
    expect(controllerOptions).toHaveBeenLastCalledWith(expect.objectContaining({ readOnly: false }));
  });
  it('embedded: the root is `relative` so the floating pill anchors to the panel', () => {
    const { container } = render(<DataTable dataSourceId={1} embedded />);
    expect(rootClass(container.firstElementChild)).toContain('relative');
  });

  it('standalone: the root is NOT `relative` (pill anchors to <main>)', () => {
    const { container } = render(<DataTable dataSourceId={1} />);
    expect(rootClass(container.firstElementChild)).not.toContain('relative');
  });

  it('snapshot mode implies embedded, so the root is `relative`', () => {
    const snapshotData = { columns: [], rows: [], name: 'snap' } as never;
    const { container } = render(<DataTable snapshotData={snapshotData} />);
    expect(rootClass(container.firstElementChild)).toContain('relative');
  });
});
