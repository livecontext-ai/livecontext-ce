// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskExtrasEditor } from '../TaskExtrasEditor';
import type { Task } from '@/lib/api/orchestrator/task.types';

const mocks = vi.hoisted(() => ({
  taskService: {
    listLabels: vi.fn(),
    listTasks: vi.fn(),
    createLabel: vi.fn(),
    setTaskLabels: vi.fn(),
    setEstimate: vi.fn(),
    setBlockers: vi.fn(),
    setChecklist: vi.fn(),
  },
}));

vi.mock('@/lib/api/orchestrator/task.service', () => ({ taskService: mocks.taskService }));
// Identity translator: t('detail.save') -> 'detail.save'.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

const LABELS = [
  { id: 'l1', name: 'urgent', color: '#ef4444' },
  { id: 'l2', name: 'backend', color: '#3b82f6' },
];
const OTHER_TASKS = [
  { id: 'b1', title: 'Blocker One' },
  { id: 'b2', title: 'Blocker Two' },
];

function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 't1',
    title: 'Task one',
    labelIds: [],
    estimateMinutes: null,
    timeSpentMinutes: null,
    blockedByIds: [],
    checklist: [],
    attachments: [],
    boardRank: null,
    ...overrides,
  } as unknown as Task;
}

async function renderEditor(task: Task) {
  const onSaved = vi.fn();
  render(<TaskExtrasEditor task={task} onSaved={onSaved} />);
  // The editor loads the label catalog + task list on mount; wait for that to settle.
  await waitFor(() => expect(mocks.taskService.listLabels).toHaveBeenCalled());
  await waitFor(() => expect(mocks.taskService.listTasks).toHaveBeenCalled());
  return { onSaved };
}

describe('TaskExtrasEditor', () => {
  beforeEach(() => {
    mocks.taskService.listLabels.mockResolvedValue(LABELS);
    mocks.taskService.listTasks.mockResolvedValue({ tasks: OTHER_TASKS });
    mocks.taskService.createLabel.mockResolvedValue({ id: 'l9', name: 'frontend', color: '#6366f1' });
    mocks.taskService.setTaskLabels.mockResolvedValue({});
    mocks.taskService.setEstimate.mockResolvedValue({});
    mocks.taskService.setBlockers.mockResolvedValue({});
    mocks.taskService.setChecklist.mockResolvedValue({});
  });
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('pre-fills the estimate + time inputs from the task and saves parsed values, calling onSaved', async () => {
    const { onSaved } = await renderEditor(makeTask({ estimateMinutes: 120, timeSpentMinutes: 30 }));
    const est = screen.getByTestId('task-estimate-input') as HTMLInputElement;
    const spent = screen.getByTestId('task-timespent-input') as HTMLInputElement;
    expect(est.value).toBe('120');
    expect(spent.value).toBe('30');

    fireEvent.change(est, { target: { value: '90' } });
    fireEvent.change(spent, { target: { value: '45' } });
    fireEvent.blur(spent); // auto-save on blur (no explicit Save button)

    await waitFor(() => expect(mocks.taskService.setEstimate).toHaveBeenCalledWith('t1', {
      estimateMinutes: 90, clearEstimate: false, timeSpentMinutes: 45, clearTimeSpent: false,
    }));
    expect(onSaved).toHaveBeenCalled();
  });

  it('clears the estimate when the field is emptied (clearEstimate flag)', async () => {
    await renderEditor(makeTask({ estimateMinutes: 60, timeSpentMinutes: null }));
    const est = screen.getByTestId('task-estimate-input');
    fireEvent.change(est, { target: { value: '' } });
    fireEvent.blur(est);
    await waitFor(() => expect(mocks.taskService.setEstimate).toHaveBeenCalledWith('t1', expect.objectContaining({
      estimateMinutes: null, clearEstimate: true,
    })));
  });

  it('does not save a negative estimate (client guard)', async () => {
    await renderEditor(makeTask());
    const est = screen.getByTestId('task-estimate-input');
    fireEvent.change(est, { target: { value: '-5' } });
    fireEvent.blur(est);
    // The guard returns before calling the service.
    await new Promise((r) => setTimeout(r, 0));
    expect(mocks.taskService.setEstimate).not.toHaveBeenCalled();
  });

  it('does not re-save an unchanged estimate on blur (no redundant reload)', async () => {
    await renderEditor(makeTask({ estimateMinutes: 60, timeSpentMinutes: 15 }));
    fireEvent.blur(screen.getByTestId('task-estimate-input')); // focus-out without editing
    await new Promise((r) => setTimeout(r, 0));
    expect(mocks.taskService.setEstimate).not.toHaveBeenCalled();
  });

  it('does not save a negative time-spent value (client guard on the spent field)', async () => {
    await renderEditor(makeTask());
    const spent = screen.getByTestId('task-timespent-input');
    fireEvent.change(spent, { target: { value: '-3' } });
    fireEvent.blur(spent);
    await new Promise((r) => setTimeout(r, 0));
    expect(mocks.taskService.setEstimate).not.toHaveBeenCalled();
  });

  it('renders current labels as chips and removes one via the X (filtered set)', async () => {
    const { onSaved } = await renderEditor(makeTask({ labelIds: ['l1', 'l2'] }));
    expect(screen.getByText('urgent')).toBeInTheDocument();
    expect(screen.getByText('backend')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('remove urgent'));
    await waitFor(() => expect(mocks.taskService.setTaskLabels).toHaveBeenCalledWith('t1', ['l2']));
    expect(onSaved).toHaveBeenCalled();
  });

  // Bug: there was no way to ADD a label - the editor only attached pre-existing
  // catalog labels and hid the picker entirely when the catalog was empty, so the
  // first label could never be created. Typing a new name now creates + attaches it.
  it('creates a brand-new label by name and attaches it (no catalog match)', async () => {
    const { onSaved } = await renderEditor(makeTask({ labelIds: [] }));
    const input = screen.getByTestId('task-add-label-input');
    fireEvent.change(input, { target: { value: 'frontend' } });
    fireEvent.click(screen.getByTestId('task-add-label-create'));

    await waitFor(() => expect(mocks.taskService.createLabel).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'frontend' }),
    ));
    await waitFor(() => expect(mocks.taskService.setTaskLabels).toHaveBeenCalledWith('t1', ['l9']));
    expect(onSaved).toHaveBeenCalled();
  });

  it('attaches an existing label by name (case-insensitive) without creating a duplicate', async () => {
    await renderEditor(makeTask({ labelIds: [] }));
    const input = screen.getByTestId('task-add-label-input');
    fireEvent.change(input, { target: { value: 'Urgent' } }); // matches catalog 'urgent' (l1)
    fireEvent.click(screen.getByTestId('task-add-label-create'));

    await waitFor(() => expect(mocks.taskService.setTaskLabels).toHaveBeenCalledWith('t1', ['l1']));
    expect(mocks.taskService.createLabel).not.toHaveBeenCalled();
  });

  it('quick-picks an available catalog label by clicking its chip', async () => {
    await renderEditor(makeTask({ labelIds: [] }));
    fireEvent.click(screen.getByTestId('task-pick-label-l2'));
    await waitFor(() => expect(mocks.taskService.setTaskLabels).toHaveBeenCalledWith('t1', ['l2']));
    expect(mocks.taskService.createLabel).not.toHaveBeenCalled();
  });

  it('adds a label via the Enter key in the add-label input', async () => {
    await renderEditor(makeTask({ labelIds: [] }));
    const input = screen.getByTestId('task-add-label-input');
    fireEvent.change(input, { target: { value: 'frontend' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(mocks.taskService.createLabel).toHaveBeenCalledWith(expect.objectContaining({ name: 'frontend' })));
    await waitFor(() => expect(mocks.taskService.setTaskLabels).toHaveBeenCalledWith('t1', ['l9']));
  });

  it('renders current blockers (title resolved from the task list) and unblocks one via the X', async () => {
    await renderEditor(makeTask({ blockedByIds: ['b1', 'b2'] }));
    expect(screen.getByText('Blocker One')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('unblock Blocker One'));
    await waitFor(() => expect(mocks.taskService.setBlockers).toHaveBeenCalledWith('t1', ['b2']));
  });

  it('adds a checklist item on Enter (appended to the existing list)', async () => {
    await renderEditor(makeTask({ checklist: [{ id: 'c1', text: 'existing', done: false }] }));
    const input = screen.getByTestId('task-checklist-add');
    fireEvent.change(input, { target: { value: 'new step' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(mocks.taskService.setChecklist).toHaveBeenCalledWith('t1', [
      { id: 'c1', text: 'existing', done: false },
      { id: '', text: 'new step', done: false },
    ]));
  });

  it('toggles a checklist item done state', async () => {
    await renderEditor(makeTask({ checklist: [{ id: 'c1', text: 'do it', done: false }] }));
    const item = screen.getByTestId('task-checklist-item-c1');
    fireEvent.click(within(item).getByRole('button', { pressed: false }));
    await waitFor(() => expect(mocks.taskService.setChecklist).toHaveBeenCalledWith('t1', [
      { id: 'c1', text: 'do it', done: true },
    ]));
  });

  it('removes a checklist item', async () => {
    await renderEditor(makeTask({ checklist: [{ id: 'c1', text: 'keep', done: false }, { id: 'c2', text: 'drop', done: false }] }));
    const item = screen.getByTestId('task-checklist-item-c2');
    fireEvent.click(within(item).getByLabelText('remove item'));
    await waitFor(() => expect(mocks.taskService.setChecklist).toHaveBeenCalledWith('t1', [
      { id: 'c1', text: 'keep', done: false },
    ]));
  });

  it('shows a disabled blocker select with a no-options placeholder when there are no other tasks', async () => {
    mocks.taskService.listTasks.mockResolvedValue({ tasks: [] });
    await renderEditor(makeTask());
    const sel = screen.getByTestId('task-add-blocker');
    expect(sel).toBeDisabled();
    expect(sel).toHaveTextContent('detail.noBlockerOptions');
  });

  // Parity with the other rail selects: enabled when options exist + the min-h-0/h-7
  // sizing (the regression that made it taller than the Status/Priority selects).
  it('enables the blocker select with the add placeholder + rail select sizing when other tasks exist', async () => {
    await renderEditor(makeTask());
    const sel = screen.getByTestId('task-add-blocker');
    expect(sel).not.toBeDisabled();
    expect(sel).toHaveTextContent('detail.addBlocker');
    expect(sel).toHaveClass('min-h-0');
    expect(sel).toHaveClass('h-7');
  });

  // The rail splits this editor in two: only="labels" (core) and only="rest"
  // (advanced). Each must fetch only what it renders (no wasted/duplicate calls).
  it('with only="labels" fetches the label catalog only and renders just the labels block', async () => {
    render(<TaskExtrasEditor task={makeTask()} onSaved={vi.fn()} only="labels" />);
    await waitFor(() => expect(mocks.taskService.listLabels).toHaveBeenCalled());
    expect(mocks.taskService.listTasks).not.toHaveBeenCalled();
    expect(screen.getByTestId('task-labels-editor')).toBeInTheDocument();
    expect(screen.queryByTestId('task-estimate-input')).toBeNull();
  });

  it('with only="rest" fetches the task list only and renders estimate/blockers/checklist', async () => {
    render(<TaskExtrasEditor task={makeTask()} onSaved={vi.fn()} only="rest" />);
    await waitFor(() => expect(mocks.taskService.listTasks).toHaveBeenCalled());
    expect(mocks.taskService.listLabels).not.toHaveBeenCalled();
    expect(screen.getByTestId('task-estimate-input')).toBeInTheDocument();
    expect(screen.queryByTestId('task-add-label-input')).toBeNull();
  });

  it('survives a partial taskService mock without crashing (guarded list calls)', async () => {
    // The component guards listLabels?.()/listTasks?.() so a partial mock cannot crash the panel.
    mocks.taskService.listLabels.mockReturnValue(undefined as never);
    mocks.taskService.listTasks.mockReturnValue(undefined as never);
    const onSaved = vi.fn();
    expect(() => render(<TaskExtrasEditor task={makeTask()} onSaved={onSaved} />)).not.toThrow();
  });
});
