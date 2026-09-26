// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import messages from '@/messages/en.json';
import type { DataTableProps } from '@/components/data-table/types';
import { createViewConfig, getFixedColumns, getRowLevelExportFields } from '@/components/data-table/viewConfig';

const { table, stepData, load } = vi.hoisted(() => ({ table: vi.fn(), stepData: vi.fn(), load: vi.fn() }));
vi.mock('@/app/workflows/builder/hooks/useStepData', () => ({ useStepData: stepData }));
vi.mock('@/components/DataTable', () => ({ default: (props: DataTableProps) => {
  table(props);
  React.useEffect(() => { load(props); }, []);
  return <button onClick={() => props.onNavigate?.('output.items')}>Open items</button>;
} }));
import { WorkflowStepTable } from '../WorkflowStepTable';

const props = { workflowId: 'wf', runId: 'run', stepAlias: 'read' };
function mount(extra: Partial<React.ComponentProps<typeof WorkflowStepTable>> = {}) {
  return render(<NextIntlClientProvider locale="en" messages={messages}><WorkflowStepTable {...props} {...extra} /></NextIntlClientProvider>);
}
const latest = () => table.mock.lastCall![0] as DataTableProps;
beforeEach(() => { vi.clearAllMocks(); stepData.mockReturnValue({ stepData: [{ epoch: 1 }, { epoch: 2 }] }); });
afterEach(cleanup);

describe('workflow table navigation and identity', () => {
  it('reloads the navigable table on real step completion without losing path, epoch, status or search', async () => {
    mount({ epoch: 2, jsonPath: 'output.items' });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '4711' } });
    Element.prototype.scrollIntoView = vi.fn();
    fireEvent.keyDown(screen.getByLabelText('Status'), { key: 'ArrowDown' });
    fireEvent.click(await screen.findByRole('option', { name: 'Failed' }));
    expect(load).toHaveBeenCalledTimes(1);
    act(() => window.dispatchEvent(new CustomEvent('stepExecutionCompleted', { detail: { runId: 'other', steps: [{ stepAlias: 'read' }] } })));
    act(() => window.dispatchEvent(new CustomEvent('stepExecutionCompleted', { detail: { runId: 'run', steps: [{ stepAlias: 'other' }] } })));
    expect(load).toHaveBeenCalledTimes(1);
    act(() => window.dispatchEvent(new CustomEvent('stepExecutionCompleted', { detail: { runId: 'run', steps: [{ stepAlias: 'read' }] } })));
    expect(load).toHaveBeenCalledTimes(2);
    expect(load.mock.lastCall![0]).toEqual(expect.objectContaining({ jsonPath: 'output.items', serverFilters: { status: 'failed', epoch: 2 }, readOnly: true }));
    expect(screen.getByRole('textbox')).toHaveValue('4711');
    expect(latest().rowFilter).not.toBeNull();
  });
  it('keeps the #ID lane at the root AND inside input/output', () => {
    // Regression: nested paths turned the lane off, so the id surfaced as a plain text column
    // (or not at all when the item had no id). The lane shows the item's own id, else 1..N.
    mount();
    expect(latest().showIdColumn).toBe(true);
    cleanup();
    mount({ jsonPath: 'output.items' });
    expect(latest().showIdColumn).toBe(true);
    expect(latest().jsonPath).toBe('output.items');
    expect(latest().workflowContext).toEqual(props);
    // What the grid builds from those props: the pinned #ID lane, not a plain `id` data column,
    // and the export writes the same id the lane shows.
    const view = createViewConfig(latest().workflowContext, latest().showIdColumn, latest().jsonPath);
    expect(view.idIsRowLevel).toBe(true);
    expect(getFixedColumns(view)).toContain('id');
    expect(getRowLevelExportFields(view)).toContain('id');
  });

  it.each([2, 0, null])('forwards the enclosing epoch %s without another epoch dropdown or all-epoch request', epoch => {
    mount({ epoch });
    expect(latest().serverFilters).toEqual({ status: null, epoch });
    expect(screen.queryByLabelText('Epoch')).not.toBeInTheDocument();
    expect(stepData).toHaveBeenCalledWith('run', 'read', { enabled: false });
  });

  it('retains the independent epoch selector for existing standalone callers', () => {
    mount();
    expect(screen.getByLabelText('Epoch')).toBeEnabled();
    expect(stepData).toHaveBeenCalledWith('run', 'read', { enabled: true });
    expect(latest().serverFilters).toEqual({ status: null, epoch: null });
  });

  it('delegates nested navigation to the enclosing breadcrumb and keeps every depth read-only', () => {
    const onNavigate = vi.fn();
    mount({ jsonPath: 'output', onNavigate, epoch: 2 });
    fireEvent.click(screen.getByRole('button', { name: 'Open items' }));
    expect(onNavigate).toHaveBeenCalledWith('output.items');
    expect(latest().readOnly).toBe(true);
    expect(latest().infiniteScroll).toBe(true);
  });

  it('preserves explicit ID lane overrides for other callers', () => {
    mount({ jsonPath: 'output.items', showIdColumn: false });
    expect(latest().showIdColumn).toBe(false);
  });

  it('retains search over nested fields without dropping the selected epoch', () => {
    mount({ epoch: 2 });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '4711' } });
    const filter = latest().rowFilter!;
    expect(filter({ data: { id: 'CUST-4711-LONG' } } as unknown as Parameters<typeof filter>[0])).toBe(true);
    expect(filter({ data: { id: 'CUST-4712-LONG' } } as unknown as Parameters<typeof filter>[0])).toBe(false);
    expect(latest().serverFilters?.epoch).toBe(2);
  });
});
