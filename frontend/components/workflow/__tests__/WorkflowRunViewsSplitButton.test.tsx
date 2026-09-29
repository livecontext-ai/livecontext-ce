/**
 * @vitest-environment jsdom
 *
 * The page header's run-mode button (where "Logs" used to be alone): a split button whose primary
 * part opens a view of the bound run and whose chevron lists Analysis and Logs (the run itself is
 * on the canvas pill). Pinned: the primary keeps opening Logs until the user picks Analysis, each
 * pick reaches the side panel through the right entry point, and nothing in it looks like a
 * "launch" play button.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const openRunPanel = vi.hoisted(() => vi.fn());
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/workflow/run-panel/runPanelBus', () => ({ openRunPanel }));

import { HEADER_RUN_VIEW_STORAGE_KEY, WorkflowRunViewsSplitButton, resetHeaderRunViewForTests } from '../WorkflowRunViewsSplitButton';

const primary = () => document.querySelector('[data-run-views-primary]') as HTMLButtonElement;
const chevron = () => document.querySelector('[data-run-views-menu-button]') as HTMLButtonElement;
const option = (view: string) => document.querySelector(`[data-run-view-option="${view}"]`) as HTMLElement;

beforeEach(() => {
  window.localStorage.clear();
  resetHeaderRunViewForTests();
});
afterEach(() => {
  cleanup();
  openRunPanel.mockReset();
});

function renderButton(props: Partial<React.ComponentProps<typeof WorkflowRunViewsSplitButton>> = {}) {
  const onOpenLogs = vi.fn();
  const utils = render(<WorkflowRunViewsSplitButton workflowId="wf-1" desktop onOpenLogs={onOpenLogs} {...props} />);
  return { onOpenLogs, ...utils };
}

describe('WorkflowRunViewsSplitButton', () => {
  it('opens Logs in one click by default, as the header button always did', () => {
    const { onOpenLogs } = renderButton();

    expect(primary()).toHaveAttribute('data-run-views-primary', 'logs');
    expect(primary()).toHaveAttribute('title', 'actions.logs');
    fireEvent.click(primary());
    expect(onOpenLogs).toHaveBeenCalledTimes(1);
    expect(openRunPanel).not.toHaveBeenCalled();
  });

  it('lists Analysis and Logs only, with what each is for, the primary one checked', () => {
    renderButton();
    fireEvent.click(chevron());

    const items = [...document.querySelectorAll('[role="menuitemradio"]')];
    expect(items.map(i => i.textContent)).toEqual([
      'sidePanel.analysisTabworkflow.runViews.analysisHint',
      'actions.logsworkflow.runViews.logsHint',
    ]);
    expect(items.map(i => i.getAttribute('aria-checked'))).toEqual(['false', 'true']);
    // No "Run" entry: the run itself is one click away on the canvas pill.
    expect(option('run')).toBeNull();
    expect(screen.getByText('workflow.runViews.title')).toBeInTheDocument();
  });

  it('opens the Analysis tab through the panel entry point', () => {
    renderButton();

    fireEvent.click(chevron());
    fireEvent.click(option('analysis'));
    expect(openRunPanel).toHaveBeenLastCalledWith({ workflowId: 'wf-1', view: 'run', tab: 'analysis' });
  });

  it('makes the picked view the one-click primary, also after the header remounts', () => {
    const first = renderButton();
    fireEvent.click(chevron());
    fireEvent.click(option('analysis'));
    expect(primary()).toHaveAttribute('data-run-views-primary', 'analysis');
    first.unmount();

    const { onOpenLogs } = renderButton();
    expect(primary()).toHaveAttribute('data-run-views-primary', 'analysis');
    fireEvent.click(primary());
    expect(openRunPanel).toHaveBeenLastCalledWith({ workflowId: 'wf-1', view: 'run', tab: 'analysis' });
    expect(onOpenLogs).not.toHaveBeenCalled();
  });

  it('keeps the desktop and mobile copies of the header on the same view', () => {
    render(
      <>
        <WorkflowRunViewsSplitButton workflowId="wf-1" desktop onOpenLogs={vi.fn()} />
        <WorkflowRunViewsSplitButton workflowId="wf-1" desktop={false} onOpenLogs={vi.fn()} />
      </>,
    );
    const [desktopChevron] = document.querySelectorAll<HTMLElement>('[data-run-views-menu-button]');
    fireEvent.click(desktopChevron);
    fireEvent.click(option('analysis'));

    const primaries = [...document.querySelectorAll('[data-run-views-primary]')].map(b => b.getAttribute('data-run-views-primary'));
    expect(primaries).toEqual(['analysis', 'analysis']);
  });

  it('never shows a play triangle: in run mode this opens a view of a run, it does not launch one', () => {
    renderButton();
    fireEvent.click(chevron());

    expect(document.querySelector('.lucide-play')).toBeNull();
    expect(primary().querySelector('.lucide-file-text')).not.toBeNull();
  });

  it('closes the menu after a pick', () => {
    renderButton();
    fireEvent.click(chevron());
    fireEvent.click(option('logs'));

    expect(document.querySelector('[role="menuitemradio"]')).toBeNull();
  });

  it('moves between the views with the arrow keys, Home and End', () => {
    renderButton();
    fireEvent.click(chevron());
    const menu = document.querySelector('[role="menu"]') as HTMLElement;

    option('analysis').focus();
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(option('logs'));
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement, 'wraps around').toBe(option('analysis'));
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(option('logs'));
    fireEvent.keyDown(menu, { key: 'Home' });
    expect(document.activeElement).toBe(option('analysis'));
    fireEvent.keyDown(menu, { key: 'End' });
    expect(document.activeElement).toBe(option('logs'));
  });

  it('is disabled as a whole when there is no run to look at', () => {
    const { onOpenLogs } = renderButton({ disabled: true });

    expect(primary()).toBeDisabled();
    expect(chevron()).toBeDisabled();
    fireEvent.click(primary());
    expect(onOpenLogs).not.toHaveBeenCalled();
  });

  it('is icon-only on mobile', () => {
    renderButton({ desktop: false });
    expect(primary().textContent).toBe('');
  });
});

describe('WorkflowRunViewsSplitButton - remembering the pick across reloads', () => {
  it('stores the view picked in the menu', () => {
    renderButton();
    fireEvent.click(chevron());
    fireEvent.click(option('analysis'));

    expect(window.localStorage.getItem(HEADER_RUN_VIEW_STORAGE_KEY)).toBe('analysis');
  });

  it('opens on the stored view after a reload', () => {
    window.localStorage.setItem(HEADER_RUN_VIEW_STORAGE_KEY, 'analysis');
    // A reload: the in-memory pick is gone, only the stored one is left.
    resetHeaderRunViewForTests();

    renderButton();

    expect(primary().getAttribute('data-run-views-primary')).toBe('analysis');
  });

  it('falls back to Logs on "run", which an earlier version could store', () => {
    window.localStorage.setItem(HEADER_RUN_VIEW_STORAGE_KEY, 'run');
    resetHeaderRunViewForTests();

    renderButton();

    expect(primary().getAttribute('data-run-views-primary')).toBe('logs');
  });

  it('falls back to Logs on a stored value it does not know', () => {
    window.localStorage.setItem(HEADER_RUN_VIEW_STORAGE_KEY, 'dashboard');
    resetHeaderRunViewForTests();

    renderButton();

    expect(primary().getAttribute('data-run-views-primary')).toBe('logs');
  });

  it('still works, for the session, when the browser refuses storage', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    try {
      resetHeaderRunViewForTests();
      renderButton();
      expect(primary().getAttribute('data-run-views-primary')).toBe('logs');

      fireEvent.click(chevron());
      fireEvent.click(option('analysis'));

      expect(primary().getAttribute('data-run-views-primary')).toBe('analysis');
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});
