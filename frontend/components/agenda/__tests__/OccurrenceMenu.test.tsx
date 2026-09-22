/**
 * @vitest-environment jsdom
 *
 * Which actions a chip's menu offers.
 *
 * Every item here is a promise that clicking it will do something. Two of them are only
 * true under conditions the menu has to check: "move" needs a scope that can express the
 * move, and everything needs a schedule to address and a role allowed to write. An item
 * offered outside those conditions does not fail loudly - it opens a dialog with nothing
 * enabled, or sends a call the server refuses.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as React from 'react';
import { OccurrenceMenu } from '../OccurrenceMenu';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

function occurrence(overrides: Partial<AgendaOccurrence> = {}): AgendaOccurrence {
  return {
    id: 'wf-1:sched-1@1',
    kind: 'PLANNED',
    startAt: '2026-09-03T09:00:00Z',
    resourceType: 'WORKFLOW',
    resourceId: 'wf-1',
    name: 'Daily report',
    scheduleId: 'sched-1',
    cronExpression: '0 9 * * *',
    timezone: 'UTC',
    armed: true,
    isNextFire: true,
    overridden: false,
    moveAllSupported: true,
    status: 'PLANNED',
    ...overrides,
  };
}

function renderMenu(o: AgendaOccurrence, canMutate = true) {
  return render(
    <OccurrenceMenu
      occurrence={o}
      anchor={{ x: 10, y: 10 }}
      timezone="UTC"
      busy={false}
      canMutate={canMutate}
      onClose={() => {}}
      onRunNow={() => {}}
      onMove={() => {}}
      onTogglePause={() => {}}
      onOpenResource={() => {}}
    />,
  );
}

const item = (key: string) => screen.queryByText(key);

describe('OccurrenceMenu', () => {
  it('offers run, move and pause on a movable planned occurrence', () => {
    renderMenu(occurrence());

    expect(item('menu.runNow')).toBeTruthy();
    expect(item('menu.move')).toBeTruthy();
    expect(item('menu.pause')).toBeTruthy();
  });

  it.each([
    ['WORKFLOW', 'workflow'],
    ['AGENT', 'agent'],
    ['APPLICATION', 'interface'],
  ] as const)('names the full-resource action for a %s', (resourceType, type) => {
    renderMenu(occurrence({ resourceType, runIdPublic: 'run-1' }));

    expect(item(`menu.pauseResource:{"type":"${type}"}`)).toBeTruthy();
  });

  it('withholds MOVE, but keeps run early, when no scope can move this occurrence', () => {
    // Chips 2..n of an interval schedule: not the pending fire, and the cron cannot be
    // rewritten. Running early still works because it acts on the SCHEDULE, not on this
    // occurrence - so withholding both would take away something that works.
    renderMenu(occurrence({ isNextFire: false, moveAllSupported: false }));

    expect(item('menu.move')).toBeNull();
    expect(item('menu.runNow')).toBeTruthy();
  });

  it('keeps MOVE when only the whole-schedule scope is available', () => {
    renderMenu(occurrence({ isNextFire: false, moveAllSupported: true }));

    expect(item('menu.move')).toBeTruthy();
  });

  it('offers no actions on a PAST fire, but still offers the way in', () => {
    // It already happened: offering to move or run it would be acting on a record. What it
    // MUST still offer is the way to the run - clicking a past chip opens this menu rather
    // than navigating, so if the menu had nothing in it the chip would be a dead end.
    renderMenu(occurrence({ kind: 'PAST', status: 'COMPLETED', runIdPublic: 'run_1' }));

    expect(item('menu.runNow')).toBeNull();
    expect(item('menu.move')).toBeNull();
    expect(item('menu.pause')).toBeNull();
    expect(item('menu.openRun')).toBeTruthy();
  });

  it('names the destination for what it is: a run for a past fire, the resource otherwise', () => {
    // The label is the last thing the user reads before leaving the page, and leaving is
    // the one action on this calendar with no undo. "Open resource" on a past fire named
    // the wrong destination - it opens the RUN, on the epoch that was clicked.
    const { unmount } = renderMenu(occurrence({ kind: 'PAST', status: 'COMPLETED', runIdPublic: 'run_1' }));
    expect(item('menu.openRun')).toBeTruthy();
    expect(item('menu.open')).toBeNull();
    unmount();

    renderMenu(occurrence());
    expect(item('menu.open')).toBeTruthy();
    expect(item('menu.openRun')).toBeNull();
  });

  it('falls back to the resource when a past fire has no run to open', () => {
    // History older than the run retention comes through with no run id; naming a run the
    // menu cannot reach would be a promise it breaks on click.
    renderMenu(occurrence({ kind: 'PAST', status: 'COMPLETED', runIdPublic: undefined }));

    expect(item('menu.open')).toBeTruthy();
    expect(item('menu.openRun')).toBeNull();
  });

  it('offers no actions to a read-only member', () => {
    renderMenu(occurrence(), false);

    expect(item('menu.runNow')).toBeNull();
    expect(item('menu.move')).toBeNull();
    expect(item('menu.pause')).toBeNull();
  });

  it('offers no actions when there is no schedule row to address', () => {
    // A past fire whose schedule was since deleted comes through this way.
    renderMenu(occurrence({ scheduleId: undefined }));

    expect(item('menu.runNow')).toBeNull();
    expect(item('menu.move')).toBeNull();
  });

  it('shows the cron on a planned occurrence and the outcome on a past one', () => {
    // The header answers "why is this here" for a projection and "what happened" for a
    // fire, and they are different questions.
    const { unmount } = render(<div />);
    unmount();

    renderMenu(occurrence());
    expect(screen.getByText(/0 9 \* \* \*/)).toBeTruthy();
    expect(item('status.planned')).toBeNull();
  });

  it('shows the run outcome on a past fire', () => {
    renderMenu(occurrence({ kind: 'PAST', status: 'FAILED' }));

    expect(item('status.failed')).toBeTruthy();
  });
  describe('an occurrence a spending cap is going to refuse', () => {
    // The RESOURCE-level verdict, which is the one "run early" depends on: it runs the
    // schedule at the moment of the click, not the fire being looked at. Both kinds of cap
    // land here, a workflow's period budget and an agent's own credit budget, because the
    // server resolves the verdict and the calendar never learns which kind it is.
    const blocked = () => occurrence({ budgetBlocked: true });

    it('offers run early, but disabled', () => {
      // Hidden would be worse: the action would simply be missing, with no reason given,
      // on a menu where every other occurrence has it.
      renderMenu(blocked());

      const runNow = item('menu.runNow');
      expect(runNow).not.toBeNull();
      expect((runNow!.closest('button') as HTMLButtonElement).disabled).toBe(true);
    });

    it('replaces the hint with the reason', () => {
      renderMenu(blocked());

      expect(item('menu.runNowBudgetBlocked')).not.toBeNull();
      expect(item('menu.runNowHint')).toBeNull();
    });

    it('does not call back when the disabled item is clicked', () => {
      const onRunNow = vi.fn();
      render(
        <OccurrenceMenu
          occurrence={blocked()}
          anchor={{ x: 10, y: 10 }}
          timezone="UTC"
          busy={false}
          canMutate
          onClose={() => {}}
          onRunNow={onRunNow}
          onMove={() => {}}
          onTogglePause={() => {}}
          onOpenResource={() => {}}
        />,
      );
      (item('menu.runNow')!.closest('button') as HTMLButtonElement).click();

      expect(onRunNow).not.toHaveBeenCalled();
    });

    it('disables run early on an occurrence AFTER the cap lifts, because the click is now', () => {
      // The case that keying on `armed` got wrong. A monthly cap lifting on the 1st leaves
      // the occurrence dated the 5th armed, because that fire WILL happen - but clicking
      // run early today runs the schedule today, and today the cap still holds.
      renderMenu(occurrence({ armed: true, budgetBlocked: true }));

      expect((item('menu.runNow')!.closest('button') as HTMLButtonElement).disabled).toBe(true);
      expect(item('menu.runNowBudgetBlocked')).not.toBeNull();
    });

    it('leaves an armed occurrence with a working run early', () => {
      renderMenu(occurrence());

      expect((item('menu.runNow')!.closest('button') as HTMLButtonElement).disabled).toBe(false);
      expect(item('menu.runNowHint')).not.toBeNull();
    });
  });
});
