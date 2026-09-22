/**
 * @vitest-environment jsdom
 *
 * That the trigger picker counts an agent's runs the same way selecting it filters them.
 *
 * These are two calls to one helper, in two files, and only one of them was updated when
 * agent runs joined the calendar. The result is a page that contradicts itself a click
 * apart: the dropdown offers "Support agent - Schedule - 0 uses", and clicking that row
 * fills the calendar with its runs. Nothing fails, nothing logs, and the count is the
 * thing a user trusts to decide whether the row is worth clicking.
 *
 * The default parameter is what makes this invisible to the compiler: the helper's
 * catalogue argument is optional, so a forgotten call site is not a type error.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { TriggerSearch } from '../TriggerSearch';
import type { AgendaMarker, AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

const AGENT_SCHEDULE: AgendaMarker = {
  resourceType: 'AGENT',
  resourceId: 'ag-1',
  name: 'Support agent',
  triggerType: 'SCHEDULE',
  scheduleId: 'sched-1',
  armed: true,
};

const AGENT_RUN: AgendaOccurrence = {
  id: 'agent-run:exec-1',
  kind: 'PAST',
  startAt: '2026-09-14T09:12:00Z',
  resourceType: 'AGENT',
  resourceId: 'ag-1',
  name: 'Support agent',
  armed: true,
  isNextFire: false,
  overridden: false,
  moveAllSupported: false,
  status: 'COMPLETED',
  launchSource: 'SCHEDULE',
};

function renderPicker(triggers: AgendaMarker[], occurrences: AgendaOccurrence[]) {
  const rendered = render(
    <TriggerSearch
      triggers={triggers}
      occurrences={occurrences}
      query="Support"
      selectedKey={null}
      busy={false}
      canMutate
      onQueryChange={() => {}}
      onSelect={() => {}}
      onTogglePause={() => {}}
      onOpen={() => {}}
    />,
  );
  // The list only exists while the input has focus; the counts live on its rows.
  fireEvent.focus(screen.getByLabelText('triggerSearch.label'));
  return rendered;
}

describe('the trigger picker, on an agent trigger', () => {
  it('counts the agent runs that selecting it would show', () => {
    renderPicker([AGENT_SCHEDULE], [AGENT_RUN]);

    // One use, not zero. The count and the filter have to answer the same question, and
    // they only do while both calls pass the catalogue the agent branch needs.
    expect(screen.getByText('triggerSearch.uses:{"n":1}')).toBeTruthy();
  });

  it('counts none when the agent has TWO triggers of that kind', () => {
    // Attribution is refused there, so the count must refuse too rather than claim a
    // run for a schedule that may not have fired it.
    const second: AgendaMarker = { ...AGENT_SCHEDULE, scheduleId: 'sched-2' };

    renderPicker([AGENT_SCHEDULE, second], [AGENT_RUN]);

    expect(screen.getAllByText('triggerSearch.uses:{"n":0}').length).toBeGreaterThan(0);
  });

  it('counts against the FULL catalogue, not the narrowed list it displays', () => {
    // The list this picker shows is already filtered (resource kinds, launch kinds,
    // "show paused"), while the calendar filters against every marker. Counting on the
    // narrowed list answers a different question: an agent with two schedules, one of
    // them paused and hidden, would read "1 use" here and then filter to nothing on
    // click - the self-contradiction this whole file exists to prevent, reached from
    // the other side.
    const hiddenPausedSibling: AgendaMarker = {
      ...AGENT_SCHEDULE, scheduleId: 'sched-2', armed: false,
    };

    render(
      <TriggerSearch
        triggers={[AGENT_SCHEDULE]}
        catalogue={[AGENT_SCHEDULE, hiddenPausedSibling]}
        occurrences={[AGENT_RUN]}
        query="Support"
        selectedKey={null}
        busy={false}
        canMutate
        onQueryChange={() => {}}
        onSelect={() => {}}
        onTogglePause={() => {}}
        onOpen={() => {}}
      />,
    );
    fireEvent.focus(screen.getByLabelText('triggerSearch.label'));

    expect(screen.getAllByText('triggerSearch.uses:{"n":0}').length).toBeGreaterThan(0);
  });
});
