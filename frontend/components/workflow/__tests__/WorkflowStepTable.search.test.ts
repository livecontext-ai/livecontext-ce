// @vitest-environment jsdom
/**
 * The run-logs search box matches what a row SHOWS. Step rows now carry their technical id as a
 * hidden `_rowId` and nested rows carry values the view injected for display (a synthetic `id`,
 * the `@epoch` context). Scanning every value of `data` matched rows by numbers nobody can see,
 * and a search for the id on screen ("21:3") found nothing, because it lives outside `data`.
 */
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/components/DataTable', () => ({ default: () => null }));
vi.mock('@/app/workflows/builder/hooks/useStepData', () => ({ useStepData: () => ({}) }));
vi.mock('@/app/workflows/builder/hooks/useStepCompletionInvalidation', () => ({ useStepCompletionInvalidation: () => undefined }));

import { rowMatchesSearch } from '../WorkflowStepTable';
import type { DataSourceItemRow } from '@/components/data-table/types';

const row = (data: Record<string, unknown>, extra: Partial<DataSourceItemRow> = {}): DataSourceItemRow => ({
  id: 987654,
  data_source_id: 0,
  tenant_id: 't',
  data,
  priority: 0,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  _isWorkflowStep: true,
  ...extra,
});

describe('run-logs search', () => {
  it('finds a step row by the coordinates it shows', () => {
    expect(rowMatchesSearch(row({ id: '20.0.2', _rowId: 987654, status: 'completed' }), '20.0.2')).toBe(true);
  });

  it('never matches the hidden technical row id', () => {
    expect(rowMatchesSearch(row({ id: '21', _rowId: 987654, status: 'completed' }), '9876')).toBe(false);
  });

  it('finds a nested item by its shown <parent id>:<index>, not by the injected synthetic id', () => {
    const nested = row(
      { id: 100001, title: 'hello', '@epoch': 21, array_index: 3 },
      { _injectedDataKeys: ['id', '@epoch', 'array_index'], _displayId: '21:3', _jsonPath: 'output.items' },
    );
    expect(rowMatchesSearch(nested, '21:3')).toBe(true);
    expect(rowMatchesSearch(nested, '100001')).toBe(false);
  });

  it('matches the visible Epoch / Spawn / Iteration context of a nested item', () => {
    const nested = row(
      { id: 4711, title: 'hello', '@epoch': 37, '@spawn': 0, '@iteration': 0 },
      { _injectedDataKeys: ['@epoch', '@spawn', '@iteration'], _jsonPath: 'output.items' },
    );
    expect(rowMatchesSearch(nested, '37')).toBe(true);
  });

  it('still matches visible values', () => {
    expect(rowMatchesSearch(row({ id: '21', _rowId: 1, email: 'ada@example.com' }), 'ADA@')).toBe(true);
  });
});
