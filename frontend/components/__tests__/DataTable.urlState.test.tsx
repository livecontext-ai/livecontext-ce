// @vitest-environment jsdom
/**
 * `urlState` lets a table keep its view (search, sort, filters, page) in the address. Only a
 * table on its OWN page may do that: an embedded one (side panel, modal, builder inspector,
 * marketplace snapshot) or one showing a workflow step sits on someone else's address, and
 * writing `?q=` or `?page=` there would change the page around it.
 *
 * DataTable is the one place that decides, and the controller only obeys what it is handed,
 * so the decision is read off the controller's argument.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

const controllerOptions = vi.hoisted(() => vi.fn());
const controller = vi.hoisted(() => ({
  toasts: [],
  removeToast: () => {},
  rows: [],
  displayRows: [],
  error: null,
  tableLoading: false,
  handlePageChange: () => {},
}));
const router = vi.hoisted(() => ({ push: () => {} }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// Heavy children are irrelevant here: stub them so the root renders cheaply.
vi.mock('@/components/data-table/useDataTableController', () => ({
  useDataTableController: (options: unknown) => {
    controllerOptions(options);
    return controller;
  },
}));
vi.mock('@/components/data-table/DataTableToolbar', () => ({ DataTableToolbar: () => null }));
vi.mock('@/components/data-table/ColumnFiltersPanel', () => ({ ColumnFiltersPanel: () => null }));
vi.mock('@/components/data-table/DataTableGrid', () => ({ DataTableGrid: () => null }));
vi.mock('@/components/data-table/DataTablePagination', () => ({ DataTablePagination: () => null }));
vi.mock('@/components/data-table/DataTableModals', () => ({ DataTableModals: () => null }));
vi.mock('@/components/ui/breadcrumb', () => ({ Breadcrumb: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => router }));

import DataTable from '@/components/DataTable';

afterEach(() => { cleanup(); vi.clearAllMocks(); });

/** What the controller was told about the address, on the last render. */
function urlStateGivenToController(): unknown {
  const options = controllerOptions.mock.calls.at(-1)?.[0] as { urlState?: unknown };
  return options.urlState;
}

const WORKFLOW_CONTEXT = { workflowId: 'wf', runId: 'run', stepAlias: 'read' };
const SNAPSHOT = { columns: [], rows: [] };

describe('DataTable - who may keep the view in the address', () => {
  it('a table on its own page that asks for it keeps its view in the address', () => {
    render(<DataTable dataSourceId={1} urlState />);
    expect(urlStateGivenToController()).toBe(true);
  });

  it('a table that does not ask for it leaves the address alone', () => {
    render(<DataTable dataSourceId={1} />);
    expect(urlStateGivenToController()).toBe(false);
  });

  it('an embedded table never writes to the address of the page it sits in', () => {
    render(<DataTable dataSourceId={1} urlState embedded />);
    expect(urlStateGivenToController()).toBe(false);
  });

  it('a snapshot table counts as embedded, so it leaves the address alone too', () => {
    render(<DataTable urlState snapshotData={SNAPSHOT} />);
    expect(urlStateGivenToController()).toBe(false);
  });

  it('a workflow step table leaves the address to the run page around it', () => {
    render(<DataTable urlState workflowContext={WORKFLOW_CONTEXT} jsonPath="output.items" />);
    expect(urlStateGivenToController()).toBe(false);
  });

  it('the decision follows the props: embedding a table that owned the address takes it away', () => {
    const { rerender } = render(<DataTable dataSourceId={1} urlState />);
    expect(urlStateGivenToController()).toBe(true);

    rerender(<DataTable dataSourceId={1} urlState embedded />);
    expect(urlStateGivenToController()).toBe(false);
  });
});
