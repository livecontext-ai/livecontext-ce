// @vitest-environment jsdom
/**
 * `handleSaveEdit` says whether the edit landed, and its toasts go through the translations.
 *
 * It used to resolve `undefined` on every path and swallow a refusal into a toast, so no caller
 * could tell a stored value from a refused one: the progress slider kept showing a value the
 * server had just rejected. The toasts themselves were English literals.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  // key plus its values, so a test sees both which message and what it was given
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useRowOperations } from '../useRowOperations';
import { authenticatedFetch } from '../../utils/authenticatedFetch';
import { createViewConfig, getRowLevelExportFields } from '../../viewConfig';
import type { DataSourceItemRow, PaginationState } from '../../types';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const pagination: PaginationState = {
  currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false,
};

const rows = [{
  id: 1, data_source_id: 42, tenant_id: 't', data: { score: 2 }, priority: 0,
  created_at: '2026-01-01T00:00:00Z', updated_at: null,
} as DataSourceItemRow];

function setup(jsonPath?: string, extra: Record<string, unknown> = {}) {
  const addToast = vi.fn();
  const setRows = vi.fn();
  const hook = renderHook(() =>
    useRowOperations({
      dataSourceId: 42,
      jsonPath,
      workflowContext: null,
      rows,
      setRows,
      pagination,
      rowLevelFields: getRowLevelExportFields(createViewConfig(undefined, false, jsonPath)),
      fetchData: vi.fn(),
      addToast,
      ...extra,
    } as never),
  );
  return { hook, addToast, setRows };
}

async function save(hook: ReturnType<typeof setup>['hook'], column = 'data.score', value = '7') {
  let outcome: unknown;
  await act(async () => {
    outcome = await hook.result.current.handleSaveEdit(1, column, value);
  });
  return outcome;
}

beforeEach(() => {
  mockFetch.mockReset();
});

describe('handleSaveEdit reports whether the edit was stored', () => {
  it('resolves true once the write is accepted, and updates the row', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    const { hook, addToast, setRows } = setup();
    expect(await save(hook)).toBe(true);
    expect(setRows).toHaveBeenCalledTimes(1);
    expect(addToast).not.toHaveBeenCalled();
  });

  // Real `Response` objects: the error parser reads headers, clones and parses, and a hand-built
  // stub that lacks one of those makes the test pass through the wrong branch.
  const refusal = (body: unknown, init: ResponseInit = { status: 400, statusText: 'Bad Request' }) =>
    new Response(typeof body === 'string' ? body : JSON.stringify(body), init);

  it('resolves false when the server refuses, leaves the row alone, and gives the server reason', async () => {
    mockFetch.mockResolvedValue(refusal({ message: 'value out of range' }));
    const { hook, addToast, setRows } = setup();
    expect(await save(hook)).toBe(false);
    expect(setRows).not.toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledTimes(1);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'value out of range' });
  });

  it('a refusal whose body gives no reason falls back to the translated message', async () => {
    mockFetch.mockResolvedValue(refusal({}));
    const { hook, addToast } = setup();
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('a refusal that names its reason in the error header uses it', async () => {
    mockFetch.mockResolvedValue(refusal({}, { status: 409, headers: { 'X-Error-Message': 'row is locked' } }));
    const { hook, addToast } = setup();
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'row is locked' });
  });

  it('resolves false when the request itself fails, with the reason in the toast', async () => {
    mockFetch.mockRejectedValue(new Error('network down'));
    const { hook, addToast } = setup();
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'network down' });
  });

  it('a failure that carries no reason falls back to the translated message, not an English literal', async () => {
    mockFetch.mockRejectedValue('boom');
    const { hook, addToast } = setup();
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('resolves false for the injected index of a nested view, which is never written', async () => {
    const { hook, addToast } = setup('payload.items');
    expect(await save(hook, 'array_index', '9')).toBe(false);
    expect(mockFetch).not.toHaveBeenCalled();
    expect(addToast).not.toHaveBeenCalled();
  });
});

describe('a workflow table edit fails with the translated message, never an English literal', () => {
  const workflowContext = { workflowId: 'w', runId: 'r', stepId: 's' };
  const workflowRows = [{ ...rows[0], _outputStorageId: 'st-1' }];

  it('a row with no storage behind it', async () => {
    const { hook, addToast } = setup(undefined, { workflowContext, rows });
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('a storage read that fails', async () => {
    mockFetch.mockResolvedValue({ ok: false, status: 500, json: async () => ({}) });
    const { hook, addToast } = setup(undefined, { workflowContext, rows: workflowRows });
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('an item index outside the stored array', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ data: [] }) });
    const { hook, addToast } = setup(undefined, { workflowContext, rows: workflowRows });
    expect(await save(hook)).toBe(false);
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('a storage write the server refuses with no reason', async () => {
    mockFetch
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ data: [{ score: 2 }] }) })
      .mockResolvedValueOnce(new Response('{}', { status: 500 }));
    const { hook, addToast, setRows } = setup(undefined, { workflowContext, rows: workflowRows });
    expect(await save(hook)).toBe(false);
    expect(setRows).not.toHaveBeenCalled();
    expect(addToast.mock.calls[0][0]).toEqual({ type: 'error', title: 'saveErrorTitle', message: 'saveErrorMessage' });
  });

  it('a workflow edit that goes through resolves true', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ data: [{ score: 2 }] }) });
    const { hook, addToast } = setup(undefined, { workflowContext, rows: workflowRows });
    expect(await save(hook)).toBe(true);
    expect(addToast).not.toHaveBeenCalled();
    const put = mockFetch.mock.calls.find(([, init]) => (init as { method?: string } | undefined)?.method === 'PUT');
    expect(JSON.parse((put![1] as { body: string }).body).data).toEqual([{ score: 7 }]);
  });
});

describe('adding and deleting rows speak through the translations', () => {
  const selected = new Set(['1']);
  const keyOf = (row: DataSourceItemRow) => String(row.id);

  it('a row added says so, with its priority', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    const { hook, addToast } = setup();
    act(() => { hook.result.current.setNewRowPriority(3); });
    await act(async () => { await hook.result.current.addNewRow(); });
    expect(addToast).toHaveBeenCalledWith({
      type: 'success', title: 'rowAddedTitle', message: 'rowAddedMessage:{"priority":3}',
    });
  });

  it('a refused add says so', async () => {
    mockFetch.mockResolvedValue({ ok: false, status: 400, json: async () => ({}) });
    const { hook, addToast } = setup();
    await act(async () => { await hook.result.current.addNewRow(); });
    expect(addToast).toHaveBeenCalledWith({
      type: 'error', title: 'addRowErrorTitle', message: 'addRowErrorMessage',
    });
  });

  it('rows deleted says how many', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
    const { hook, addToast } = setup();
    await act(async () => { await hook.result.current.deleteSelectedRows(selected, keyOf); });
    expect(addToast).toHaveBeenCalledWith({
      type: 'success', title: 'rowsDeletedTitle', message: 'rowsDeletedMessage:{"count":1}',
    });
  });

  it('a refused delete says so', async () => {
    mockFetch.mockResolvedValue({ ok: false, status: 400, json: async () => ({}), text: async () => '' });
    const { hook, addToast } = setup();
    await act(async () => { await hook.result.current.deleteSelectedRows(selected, keyOf); });
    expect(addToast).toHaveBeenCalledWith({
      type: 'error', title: 'deleteRowsErrorTitle', message: 'deleteRowsErrorMessage',
    });
  });
});
