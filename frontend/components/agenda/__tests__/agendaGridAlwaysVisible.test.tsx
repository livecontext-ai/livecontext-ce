/**
 * @vitest-environment jsdom
 *
 * That the calendar is still there when there is nothing on it.
 *
 * The page used to render its "nothing to show" message INSTEAD of the grid, which took
 * the one creation gesture the agenda has away exactly when it was most needed: a fresh
 * workspace has no schedules, so it got no grid, so it had no empty slot to click, so the
 * first schedule could not be made from the calendar at all. The emptier the agenda, the
 * less it could do about being empty.
 *
 * <p>A search that matches nothing is the same shape: the grid is also the answer to "then
 * when IS free?", and blanking it leaves the user with a sentence and no way forward.
 *
 * <p>The one case that must NOT be quietly empty is a load that FAILED - an agenda that
 * could not be read must never look like an agenda with nothing in it - so that message
 * stays, and stays loud, above a grid that is still usable.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import * as React from 'react';
import { AgendaView } from '../../views/AgendaView';

const getAgenda = vi.fn();

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams() }));
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
// The agenda's mutations ask for the bell's automation rows again, since pausing a schedule or
// moving an occurrence changes what the bell lists as armed. The real hook reaches for a
// QueryClient this suite has no provider for; the ask itself is pinned by the call-site guard
// in lib/api/orchestrator/__tests__/automationRowMutations.callSites.test.ts.
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/hooks/useAgendaPreferences', () => ({
  ALL_RESOURCE_TYPES: ['WORKFLOW', 'APPLICATION', 'AGENT'],
  useAgendaPreferences: () => ({
    preferences: {
      view: 'day', timezone: 'UTC', weekStartsOn: 1, showWeekends: true,
      resourceTypes: ['WORKFLOW', 'APPLICATION', 'AGENT'], showPast: true, showPaused: true,
      density: 'comfortable', dayStartHour: 9, dayEndHour: 12,
    },
    update: () => {}, reset: () => {}, toggleResourceType: () => {}, hydrated: true,
  }),
}));

beforeEach(() => {
  vi.setSystemTime(new Date('2026-09-03T09:30:00Z'));
  getAgenda.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

/** The hour rows are the calendar; if they are there, so is every empty slot on them. */
const hourCells = () => screen.queryAllByRole('button', { name: /create.slotAction/ });

/**
 * The notice, found by role AND by what it says.
 *
 * <p>Neither half is enough on its own: the bar carries a live clock that is also a
 * `status`, a failed load additionally raises a toast carrying the same words, and the
 * banner is the one element that is both.
 */
async function notice(key: string) {
  return waitFor(() => {
    const found = screen.getAllByRole('status')
      .find((el) => el.textContent?.includes(key) && el.className.includes('rounded-lg'));
    if (!found) throw new Error(`no agenda notice saying ${key}`);
    return found;
  });
}

describe('an agenda with nothing on it', () => {
  it('still draws the grid, so the first schedule can be made from it', async () => {
    // The report: "you cannot add a scheduled workflow by clicking, so leave the grid
    // visible even when there is nothing". Three empty hours, three offers.
    getAgenda.mockResolvedValue({
      occurrences: [], truncatedScheduleIds: [], pastTruncated: false,
    });
    render(<AgendaView />);

    expect(await notice('empty.title')).toBeTruthy();
    expect(hourCells()).toHaveLength(3);
  });

  it('says what is going on above the grid rather than in place of it', async () => {
    getAgenda.mockResolvedValue({
      occurrences: [], truncatedScheduleIds: [], pastTruncated: false,
    });
    render(<AgendaView />);
    const banner = await notice('empty.title');

    expect(banner.textContent).toContain('empty.title');
    // And the calendar is a SIBLING that follows it, not something it replaced.
    expect(hourCells().length).toBeGreaterThan(0);
  });

  it('keeps the grid when a load FAILED, and keeps saying it failed', async () => {
    // The honesty contract: an agenda that could not be read must not look like an agenda
    // with nothing in it. The message stays and stays loud - but blanking the calendar was
    // never what made it honest.
    getAgenda.mockRejectedValue(new Error('nope'));
    render(<AgendaView />);
    const banner = await notice('errors.loadTitle');

    expect(banner.textContent).toContain('errors.loadTitle');
    expect(banner.className).toContain('bg-red-50');
    expect(hourCells().length).toBeGreaterThan(0);
  });
});
