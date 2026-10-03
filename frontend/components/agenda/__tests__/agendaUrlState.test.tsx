/**
 * @vitest-environment jsdom
 *
 * The agenda keeps what is on screen in the address: the view mode (`view`), the period
 * (`date`), the trigger search (`q`), the selected trigger (`trigger`) and the launch kinds
 * (`kinds`). A reload, a shared link and Back all reopen the same calendar.
 *
 * <p>The period is the delicate one. `?date` used to be seeded once and never read again,
 * deliberately: read live while paging did not write it, it pinned the user to the linked
 * period, every step forward undone by a parameter that had not moved. It is now two-way, so
 * these pin both halves of what makes that safe: paging MOVES the address, and the calendar
 * follows the address when someone else changes it (the notification bell linking into an
 * agenda that is already open).
 *
 * <p>A day in the address is read in the agenda's zone. `2026-03-10` parsed as an instant is
 * UTC midnight, which is still the 9th in New York: the day view would open on the wrong day
 * for everyone west of Greenwich.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as React from 'react';

const getAgenda = vi.fn();
const prefs = vi.hoisted(() => ({
  view: 'week' as string,
  timezone: 'America/New_York',
  update: vi.fn(),
  reset: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));
vi.mock('@/components/views/AuthenticatedView', () => ({
  AuthenticatedView: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { saveWorkflowPlan: vi.fn() } }));
vi.mock('@/lib/workflows/recentWorkflowNames', () => ({ rememberWorkflowName: vi.fn() }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: { validateCron: () => Promise.resolve({ valid: true }) },
}));
vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    getAgenda: (...args: unknown[]) => getAgenda(...args),
    move: vi.fn(), runNow: vi.fn(), toggle: vi.fn(),
  },
  agendaFailureOf: () => ({}),
}));
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/hooks/useAgendaPreferences', () => ({
  ALL_RESOURCE_TYPES: ['WORKFLOW', 'APPLICATION', 'AGENT'],
  useAgendaPreferences: () => {
    // A stable object per stored view, as the real hook hands back the same state between
    // renders: a fresh one each time would re-run everything that depends on it.
    const preferences = React.useMemo(() => ({
      view: prefs.view, timezone: prefs.timezone, weekStartsOn: 1, showWeekends: true,
      resourceTypes: ['WORKFLOW', 'APPLICATION', 'AGENT'], showPast: true, showPaused: true,
      density: 'comfortable', dayStartHour: 9, dayEndHour: 12,
    }), []);
    return {
      preferences, update: prefs.update, reset: prefs.reset, toggleResourceType: () => {}, hydrated: true,
    };
  },
}));

import { AgendaView } from '../../views/AgendaView';

beforeEach(() => {
  // Thursday 3 September 2026, mid-morning in New York.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-09-03T14:30:00Z'));
  fakeFolderRouter.reset('/en/app/agenda');
  prefs.view = 'week';
  prefs.timezone = 'America/New_York';
  prefs.update.mockReset();
  prefs.reset.mockReset();
  getAgenda.mockReset();
  getAgenda.mockResolvedValue({ occurrences: [], markers: [], truncatedScheduleIds: [], pastTruncated: false });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** The window the last request asked for, as ISO instants. */
async function requestedWindow(): Promise<[string, string]> {
  await waitFor(() => expect(getAgenda).toHaveBeenCalled());
  const [from, to] = getAgenda.mock.calls.at(-1) as [Date, Date];
  return [from.toISOString(), to.toISOString()];
}

const pressed = (name: string) =>
  screen.getByRole('button', { name }).getAttribute('aria-pressed');

describe('the agenda opened on an address that carries its view', () => {
  it('opens on the day the address names, read in the agenda zone', async () => {
    fakeFolderRouter.navigate('/en/app/agenda?view=day&date=2026-03-10');
    render(<AgendaView />);

    // 10 March in New York (EDT, UTC-4), not the UTC day, which would start on the 9th there.
    expect(await requestedWindow()).toEqual(['2026-03-10T04:00:00.000Z', '2026-03-11T04:00:00.000Z']);
    expect(pressed('view.day')).toBe('true');
  });

  it('lets the view in the address win over the stored preference', async () => {
    prefs.view = 'week';
    fakeFolderRouter.navigate('/en/app/agenda?view=day');
    render(<AgendaView />);

    expect(await requestedWindow()).toEqual(['2026-09-03T04:00:00.000Z', '2026-09-04T04:00:00.000Z']);
    expect(pressed('view.day')).toBe('true');
    expect(pressed('view.week')).toBe('false');
  });

  it('opens on the stored view and on today when the address says nothing', async () => {
    prefs.view = 'day';
    render(<AgendaView />);

    expect(await requestedWindow()).toEqual(['2026-09-03T04:00:00.000Z', '2026-09-04T04:00:00.000Z']);
    expect(fakeFolderRouter.navigations).toEqual([]);
  });

  it('ignores a view and a date it cannot draw', async () => {
    prefs.view = 'day';
    fakeFolderRouter.navigate('/en/app/agenda?view=year&date=2026-13-40');
    render(<AgendaView />);

    expect(await requestedWindow()).toEqual(['2026-09-03T04:00:00.000Z', '2026-09-04T04:00:00.000Z']);
    expect(pressed('view.day')).toBe('true');
  });

  it('still opens on the instant the notification bell links, and keeps ?focus', async () => {
    // The bell sends the next fire as a full instant, not as a day.
    fakeFolderRouter.navigate('/en/app/agenda?focus=sched-1&date=2026-11-20T15%3A00%3A00Z&view=day');
    render(<AgendaView />);

    // 20 November in New York is EST (UTC-5).
    expect(await requestedWindow()).toEqual(['2026-11-20T05:00:00.000Z', '2026-11-21T05:00:00.000Z']);

    fireEvent.click(screen.getByRole('button', { name: 'nav.next' }));
    expect(fakeFolderRouter.search()).toBe('focus=sched-1&date=2026-11-21&view=day');
  });
});

describe('the agenda writing its view to the address', () => {
  it('writes the day it pages to, so paging is never undone by the address', async () => {
    fakeFolderRouter.navigate('/en/app/agenda?view=day&date=2026-03-10');
    render(<AgendaView />);
    await requestedWindow();

    fireEvent.click(screen.getByRole('button', { name: 'nav.next' }));
    fireEvent.click(screen.getByRole('button', { name: 'nav.next' }));

    expect(fakeFolderRouter.search()).toBe('view=day&date=2026-03-12');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
    // And the calendar is on that day: the regression this guards is the address pulling it back.
    await waitFor(async () => expect(await requestedWindow())
      .toEqual(['2026-03-12T04:00:00.000Z', '2026-03-13T04:00:00.000Z']));
  });

  it('goes back to a clean address on today', async () => {
    fakeFolderRouter.navigate('/en/app/agenda?view=day&date=2026-09-02');
    render(<AgendaView />);
    await requestedWindow();

    fireEvent.click(screen.getByRole('button', { name: 'nav.next' }));

    expect(fakeFolderRouter.search()).toBe('view=day');
  });

  it('follows a date someone else puts in the address while it is open', async () => {
    fakeFolderRouter.navigate('/en/app/agenda?view=day');
    render(<AgendaView />);
    await requestedWindow();

    // The bell's "open in agenda", pressed while the agenda is the page on screen.
    act(() => fakeFolderRouter.navigate('/en/app/agenda?view=day&date=2026-03-10', 'push'));

    await waitFor(async () => expect(await requestedWindow())
      .toEqual(['2026-03-10T04:00:00.000Z', '2026-03-11T04:00:00.000Z']));
  });

  it('writes a picked view to the address and to the stored preference', async () => {
    render(<AgendaView />);
    await requestedWindow();

    fireEvent.click(screen.getByRole('button', { name: 'view.month' }));

    expect(fakeFolderRouter.search()).toBe('view=month');
    expect(prefs.update).toHaveBeenCalledWith({ view: 'month' });
    expect(pressed('view.month')).toBe('true');
  });
});

describe('the agenda embedded in the side panel', () => {
  it('neither reads nor writes the address of the page it sits on', async () => {
    prefs.view = 'day';
    fakeFolderRouter.navigate('/en/app/workflow?view=month&date=2026-03-10&q=invoice');
    render(<AgendaView embedded />);

    // Today and the stored view, whatever the host page's own parameters say.
    expect(await requestedWindow()).toEqual(['2026-09-03T04:00:00.000Z', '2026-09-04T04:00:00.000Z']);
    expect(pressed('view.day')).toBe('true');

    fakeFolderRouter.navigations.length = 0;
    fireEvent.click(screen.getByRole('button', { name: 'nav.next' }));
    fireEvent.click(screen.getByRole('button', { name: 'view.week' }));

    expect(fakeFolderRouter.navigations).toEqual([]);
    expect(pressed('view.week')).toBe('true');
  });
});
