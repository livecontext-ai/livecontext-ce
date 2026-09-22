/**
 * @vitest-environment jsdom
 *
 * That clicking a past fire opens its menu instead of leaving the page.
 *
 * A past fire used to navigate on the first click: its menu holds no actions, so the second
 * click looked like pure cost. What that missed is which click is expensive. Leaving is the
 * only thing this calendar does that cannot be undone, the chips are small and dense on a
 * month grid, and a mis-click took the user out of the agenda and into a run they were not
 * looking for - then back, re-fetching the window.
 *
 * The menu is the confirmation: it names the run, the day and the outcome, and offers the
 * way in. This drives the real page rather than the handler, because the whole defect lived
 * in the branch between the chip and the menu.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { AgendaView } from '../../views/AgendaView';

const push = vi.fn();

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
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

const PAST_FIRE = {
  id: 'wf-1:sched-1@past',
  kind: 'PAST' as const,
  startAt: '2026-09-03T09:00:00Z',
  resourceType: 'WORKFLOW' as const,
  resourceId: 'wf-1',
  runIdPublic: 'run_public_7',
  epoch: 2,
  name: 'Daily report',
  scheduleId: 'sched-1',
  cronExpression: '0 9 * * *',
  timezone: 'UTC',
  armed: true,
  isNextFire: false,
  overridden: false,
  moveAllSupported: true,
  status: 'COMPLETED' as const,
};

vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    getAgenda: () => Promise.resolve({
      occurrences: [PAST_FIRE], truncatedScheduleIds: [], pastTruncated: false,
    }),
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
      density: 'comfortable', dayStartHour: 9, dayEndHour: 11,
    },
    update: () => {}, reset: () => {}, toggleResourceType: () => {}, hydrated: true,
  }),
}));

beforeEach(() => {
  vi.setSystemTime(new Date('2026-09-03T12:00:00Z'));
  push.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

async function clickTheFire() {
  render(<AgendaView />);
  const chip = await screen.findByRole('button', { name: /Daily report/ });
  fireEvent.click(chip);
}

describe('clicking a fire that already happened', () => {
  it('opens the menu and does NOT navigate', async () => {
    await clickTheFire();

    expect(push).not.toHaveBeenCalled();
    expect(screen.getByText('status.completed')).toBeTruthy();
  });

  it('offers the run, and goes there only when the user asks', async () => {
    // The second click is the one that leaves, and by then the user has read what they are
    // opening: the run's name, its day and how it ended.
    await clickTheFire();

    fireEvent.click(screen.getByText('menu.openRun'));

    expect(push).toHaveBeenCalledWith('/app/workflow/wf-1/run/run_public_7');
  });
});
