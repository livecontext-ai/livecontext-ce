// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupProgress } from '@/lib/onboarding/setupChecklist';

vi.mock('next-intl', () => ({
  useLocale: () => 'fr',
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock('next/link', () => ({
  default: ({ href, children, onClick, title }: any) => <a href={href} onClick={onClick} title={title}>{children}</a>,
}));
let openChange: ((open: boolean) => void) | undefined;
let popoverOpen: boolean | undefined;
let popoverSide: string | undefined;
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children, onOpenChange, open }: any) => { openChange = onOpenChange; popoverOpen = open; return <div>{children}</div>; },
  PopoverTrigger: ({ children }: any) => <div onClick={() => openChange?.(true)}>{children}</div>,
  PopoverContent: ({ children, side }: any) => { popoverSide = side; return <div>{children}</div>; },
}));
const refresh = vi.fn();
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ id: 'client' }) }));

let state = {
  progress: setupProgress({ integration: true, chat: false, build: false, channel: true }),
  visible: true,
};
vi.mock('@/hooks/useSetupChecklist', () => ({
  useSetupChecklist: () => state,
  refreshSetupChecklist: (...a: unknown[]) => refresh(...a),
}));

const track = vi.fn();
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

import { SetupChecklist } from '../SetupChecklist';

beforeEach(() => {
  window.localStorage.clear();
  state = { ...state, visible: true };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SetupChecklist in the expanded sidebar (a card above the user)', () => {
  it('shows the title, the count and every task, open ones linking to where they are done', () => {
    render(<SetupChecklist variant="panel" />);

    expect(screen.getByTestId('setup-checklist-toggle').textContent).toContain('pill:{"done":2,"total":4}');
    expect(screen.getByTestId('setup-task-integration').getAttribute('data-done')).toBe('true');
    expect(screen.getByTestId('setup-task-chat').getAttribute('data-done')).toBe('false');
    // Locale-prefixed, and only for what is left to do.
    expect(screen.getByText('tasks.chat.title').closest('a')!.getAttribute('href')).toBe('/fr/app/chat');
    expect(screen.getByText('tasks.build.title').closest('a')!.getAttribute('href')).toBe('/fr/app/agent');
    expect(screen.getByText('tasks.integration.title').closest('a')).toBeNull();
    expect(screen.getByText('tasks.channel.title').closest('a')).toBeNull();
  });

  it('folds to one line, and the choice is remembered for the next page', () => {
    render(<SetupChecklist variant="panel" />);
    const toggle = screen.getByTestId('setup-checklist-toggle');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(toggle);

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    // Kept in the DOM so aria-controls always points at it, but hidden from sight and from AT.
    expect((document.getElementById('setup-checklist-body') as HTMLElement).hidden).toBe(true);
    // Still in sight while folded: the title and the count stay.
    expect(toggle.textContent).toContain('title');
    expect(toggle.textContent).toContain('pill:{"done":2,"total":4}');

    cleanup();
    render(<SetupChecklist variant="panel" />);
    expect(screen.getByTestId('setup-checklist-toggle').getAttribute('aria-expanded')).toBe('false');
  });

  it('unfolding re-reads every task, so what was just done elsewhere is ticked', () => {
    window.localStorage.setItem('lc.setupChecklist.collapsed', '1');
    render(<SetupChecklist variant="panel" />);

    fireEvent.click(screen.getByTestId('setup-checklist-toggle'));

    expect(refresh).toHaveBeenCalledWith({ id: 'client' });
    expect((document.getElementById('setup-checklist-body') as HTMLElement).hidden).toBe(false);
  });

  it('folding does not re-read anything', () => {
    render(<SetupChecklist variant="panel" />);

    fireEvent.click(screen.getByTestId('setup-checklist-toggle'));

    expect(refresh).not.toHaveBeenCalled();
  });

  it('works when storage is refused (private window): starts unfolded and still folds', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    try {
      render(<SetupChecklist variant="panel" />);
      const toggle = screen.getByTestId('setup-checklist-toggle');
      expect(toggle.getAttribute('aria-expanded')).toBe('true');

      fireEvent.click(toggle);

      expect(toggle.getAttribute('aria-expanded')).toBe('false');
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });

  it('a done task is announced as done to a screen reader, not only struck through', () => {
    render(<SetupChecklist variant="panel" />);

    expect(screen.getByTestId('setup-task-integration').textContent).toContain('statusDone');
    expect(screen.getByTestId('setup-task-chat').textContent).toContain('statusTodo');
  });

  it('following a task tells the host, so a mobile drawer closes over the page it opened', () => {
    const onNavigate = vi.fn();
    render(<SetupChecklist variant="panel" onNavigate={onNavigate} />);

    fireEvent.click(screen.getByText('tasks.chat.title'));

    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('the count is part of the toggle name, spelled out for a screen reader', () => {
    render(<SetupChecklist variant="panel" />);

    expect(screen.getByTestId('setup-checklist-toggle').textContent).toContain('pillAria:{"done":2,"total":4}');
  });

  it('offers no way to hide it: it goes away only once every task is done', () => {
    render(<SetupChecklist variant="panel" />);

    expect(screen.queryByText('hide')).toBeNull();
    expect(screen.getByTestId('setup-checklist-card').querySelectorAll('button')).toHaveLength(1);
  });
});

describe('SetupChecklist in the collapsed sidebar (the rail)', () => {
  it('is the count alone, opening the full list beside the rail and re-reading it', () => {
    render(<SetupChecklist variant="rail" />);

    const pill = screen.getByTestId('setup-checklist-pill');
    expect(pill.textContent).toContain('pill:{"done":2,"total":4}');
    expect(pill.getAttribute('aria-label')).toBe('pillAria:{"done":2,"total":4}');
    fireEvent.click(pill);

    expect(refresh).toHaveBeenCalledWith({ id: 'client' });
    expect(popoverSide).toBe('right');
    // The popover has room for why each open task matters.
    expect(screen.getByText('tasks.chat.why')).toBeTruthy();
    expect(screen.queryByText('tasks.integration.why')).toBeNull();
  });

  it('following a task from the rail tells the host too', () => {
    const onNavigate = vi.fn();
    render(<SetupChecklist variant="rail" onNavigate={onNavigate} />);

    fireEvent.click(screen.getByTestId('setup-checklist-pill'));
    expect(popoverOpen).toBe(true);
    fireEvent.click(screen.getByText('tasks.build.title'));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    // The popover closes too, not just the drawer behind it.
    expect(popoverOpen).toBe(false);
  });

  it('the rail popover offers no way to hide it either', () => {
    render(<SetupChecklist variant="rail" />);
    fireEvent.click(screen.getByTestId('setup-checklist-pill'));

    expect(screen.queryByText('hide')).toBeNull();
  });
});

it('renders nothing in either width when the hook says it is not visible', () => {
  state = { ...state, visible: false };

  expect(render(<SetupChecklist variant="panel" />).container.innerHTML).toBe('');
  expect(render(<SetupChecklist variant="rail" />).container.innerHTML).toBe('');
});

describe('SetupChecklist analytics', () => {
  it('reports which task was followed, with the progress at that moment', () => {
    render(<SetupChecklist variant="panel" />);

    fireEvent.click(screen.getByText('tasks.build.title'));

    expect(track).toHaveBeenCalledWith('setup_checklist_task_clicked', {
      task: 'build', task_done: false, done: 2, total: 4,
    });
  });

  it('reports the rail popover opening, and the task followed from it', () => {
    render(<SetupChecklist variant="rail" />);

    fireEvent.click(screen.getByTestId('setup-checklist-pill'));
    expect(track).toHaveBeenCalledWith('setup_checklist_opened', { done: 2, total: 4, variant: 'rail' });

    fireEvent.click(screen.getByText('tasks.chat.title'));
    expect(track).toHaveBeenCalledWith('setup_checklist_task_clicked', {
      task: 'chat', task_done: false, done: 2, total: 4,
    });
  });

  it('reports the card being unfolded, never being folded', () => {
    window.localStorage.setItem('lc.setupChecklist.collapsed', '1');
    render(<SetupChecklist variant="panel" />);

    fireEvent.click(screen.getByTestId('setup-checklist-toggle'));
    expect(track).toHaveBeenCalledWith('setup_checklist_opened', { done: 2, total: 4, variant: 'panel' });

    track.mockClear();
    fireEvent.click(screen.getByTestId('setup-checklist-toggle'));
    expect(track).not.toHaveBeenCalled();
  });
});
