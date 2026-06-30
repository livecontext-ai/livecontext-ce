// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ColumnManagerDialog } from '../ColumnManagerDialog';
import type { TaskStatusConfig } from '@/lib/api/orchestrator/task.types';

const mocks = vi.hoisted(() => ({
  taskService: {
    listStatuses: vi.fn(),
    createStatus: vi.fn(),
    updateStatus: vi.fn(),
    deleteStatus: vi.fn(),
    reorderStatuses: vi.fn(),
  },
}));

vi.mock('@/lib/api/orchestrator/task.service', () => ({ taskService: mocks.taskService }));
// Identity translator: t('manageColumns.add') -> 'manageColumns.add'.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

const statuses: TaskStatusConfig[] = [
  { id: 'p', key: 'pending', label: 'Pending', category: 'pending', position: 0, color: null, wipLimit: null, isSystem: true, hidden: false },
  { id: 'q', key: 'qa', label: 'QA', category: 'in_review', position: 1, color: '#ef4444', wipLimit: 3, isSystem: false, hidden: false },
];

function renderDialog() {
  const onClose = vi.fn();
  const onChanged = vi.fn();
  render(<ColumnManagerDialog statuses={statuses} onClose={onClose} onChanged={onChanged} />);
  return { onClose, onChanged };
}

describe('ColumnManagerDialog', () => {
  beforeEach(() => {
    mocks.taskService.listStatuses.mockResolvedValue(statuses);
    mocks.taskService.createStatus.mockResolvedValue({ id: 'n', key: 'new', label: 'New', category: 'in_progress', position: 9, color: '#6366f1', wipLimit: null, isSystem: false, hidden: false });
    mocks.taskService.updateStatus.mockResolvedValue({});
    mocks.taskService.reorderStatuses.mockResolvedValue([]);
    mocks.taskService.deleteStatus.mockResolvedValue({ deleted: true, moved_tasks: 2, fallback_status: 'in_review' });
  });
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('creates a column with label + category + color + WIP and refreshes', async () => {
    const { onChanged } = renderDialog();
    // Category defaults to 'in_progress' (the Select is a Radix component, not a native <select>).
    fireEvent.change(screen.getByTestId('new-column-label'), { target: { value: 'Blocked' } });
    fireEvent.change(screen.getByTestId('new-column-wip'), { target: { value: '5' } });
    fireEvent.click(screen.getByTestId('add-column'));
    await waitFor(() => expect(mocks.taskService.createStatus).toHaveBeenCalledWith(
      expect.objectContaining({ label: 'Blocked', category: 'in_progress', wipLimit: 5 }),
    ));
    expect(onChanged).toHaveBeenCalled();
  });

  // Bug: opening "Manage columns" on a board whose status config had not loaded
  // (empty prop - e.g. its initial fetch raced/failed) showed an empty dialog with
  // no existing/default columns. The dialog now self-fetches on open (the GET seeds
  // the built-ins), so the existing columns are always listed.
  it('self-fetches and lists existing/default columns even when the prop is empty', async () => {
    mocks.taskService.listStatuses.mockResolvedValue(statuses);
    render(<ColumnManagerDialog statuses={[]} onClose={vi.fn()} onChanged={vi.fn()} />);
    await waitFor(() => expect(mocks.taskService.listStatuses).toHaveBeenCalled());
    expect(await screen.findByTestId('column-row-pending')).toBeInTheDocument();
    expect(screen.getByTestId('column-row-qa')).toBeInTheDocument();
  });

  it('re-fetches the column list after a mutation so it reflects server truth', async () => {
    renderDialog();
    await waitFor(() => expect(mocks.taskService.listStatuses).toHaveBeenCalledTimes(1)); // on open
    fireEvent.change(screen.getByTestId('new-column-label'), { target: { value: 'Blocked' } });
    fireEvent.click(screen.getByTestId('add-column'));
    await waitFor(() => expect(mocks.taskService.createStatus).toHaveBeenCalled());
    await waitFor(() => expect(mocks.taskService.listStatuses).toHaveBeenCalledTimes(2)); // + post-mutation reload
  });

  it('does not create when the label is blank (add button disabled)', () => {
    renderDialog();
    expect(screen.getByTestId('add-column')).toBeDisabled();
    fireEvent.click(screen.getByTestId('add-column'));
    expect(mocks.taskService.createStatus).not.toHaveBeenCalled();
  });

  it('renames a column on blur', async () => {
    renderDialog();
    const input = screen.getByTestId('column-label-pending') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'To Do' } });
    fireEvent.blur(input);
    await waitFor(() => expect(mocks.taskService.updateStatus).toHaveBeenCalledWith('p', { label: 'To Do' }));
  });

  it('sets a WIP limit on blur, and clears it when emptied', async () => {
    renderDialog();
    const wip = screen.getByTestId('column-wip-pending') as HTMLInputElement;
    fireEvent.change(wip, { target: { value: '4' } });
    fireEvent.blur(wip);
    await waitFor(() => expect(mocks.taskService.updateStatus).toHaveBeenCalledWith('p', { wipLimit: 4 }));

    const wipQa = screen.getByTestId('column-wip-qa') as HTMLInputElement;
    fireEvent.change(wipQa, { target: { value: '' } });
    fireEvent.blur(wipQa);
    await waitFor(() => expect(mocks.taskService.updateStatus).toHaveBeenCalledWith('q', { clearWipLimit: true }));
  });

  it('reorders by moving the first column down', async () => {
    renderDialog();
    const firstRow = screen.getByTestId('column-row-pending');
    fireEvent.click(within_aria(firstRow, 'manageColumns.moveDown'));
    await waitFor(() => expect(mocks.taskService.reorderStatuses).toHaveBeenCalledWith(['q', 'p']));
  });

  it('hides the delete control for built-in columns and deletes a custom one after confirm', async () => {
    const { onChanged } = renderDialog();
    // Built-in 'pending' is not deletable.
    expect(screen.queryByTestId('column-delete-pending')).toBeNull();
    // Custom 'qa' deletes via a two-step confirm.
    fireEvent.click(screen.getByTestId('column-delete-qa'));
    fireEvent.click(screen.getByTestId('column-delete-confirm-qa'));
    await waitFor(() => expect(mocks.taskService.deleteStatus).toHaveBeenCalledWith('q'));
    expect(onChanged).toHaveBeenCalled();
  });

  it('toggles hidden state', async () => {
    renderDialog();
    // 'pending' is visible -> clicking hides it.
    fireEvent.click(within_aria(screen.getByTestId('column-row-pending'), 'manageColumns.hide'));
    await waitFor(() => expect(mocks.taskService.updateStatus).toHaveBeenCalledWith('p', { hidden: true }));
  });

  // Chrome: the bottom "Close" button was removed in favour of a header X (icon, aria-label).
  it('closes via the header X and has no bottom text Close button', () => {
    const { onClose } = renderDialog();
    // No text-node "Close" button (the removed footer); only the icon X carries the aria-label.
    expect(screen.queryByText('manageColumns.close')).toBeNull();
    const x = screen.getByRole('button', { name: 'manageColumns.close' });
    fireEvent.click(x);
    expect(onClose).toHaveBeenCalled();
  });

  // Chrome: inputs/selects use rounded-md to match the task modal (regression guard).
  it('renders the row inputs with the rounded-md task-modal style', () => {
    renderDialog();
    expect(screen.getByTestId('column-label-pending')).toHaveClass('rounded-md');
    expect(screen.getByTestId('new-column-label')).toHaveClass('rounded-md');
  });

  // Bug B resilience: if the self-fetch on open fails, the dialog still shows the prop rows.
  it('keeps showing the prop rows when the self-fetch (listStatuses) rejects', async () => {
    mocks.taskService.listStatuses.mockRejectedValue(new Error('boom'));
    renderDialog();
    await waitFor(() => expect(mocks.taskService.listStatuses).toHaveBeenCalled());
    expect(screen.getByTestId('column-row-pending')).toBeInTheDocument();
    expect(screen.getByTestId('column-row-qa')).toBeInTheDocument();
  });
});

/** Find a button inside a row by its aria-label (the identity-translated key). */
function within_aria(row: HTMLElement, label: string): HTMLElement {
  const el = row.querySelector(`[aria-label="${label}"]`);
  if (!el) throw new Error(`no element with aria-label="${label}" in row`);
  return el as HTMLElement;
}
