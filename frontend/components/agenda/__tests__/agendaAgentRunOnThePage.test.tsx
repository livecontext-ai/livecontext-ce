/**
 * @vitest-environment jsdom
 *
 * That an agent run actually reaches the calendar, and leads to its conversation.
 *
 * Everything else about this feature is covered one piece at a time: the helper that folds
 * the two launch vocabularies, the chip, the menu, the href. None of that proves the whole
 * chain holds. A run has to survive the resource-kind filter, the launch-kind filter, the
 * day bucketing and the grid before anyone sees it, and every one of those was written for
 * workflows: an agent run carries no `triggerType`, no `scheduleId`, no `runIdPublic` and
 * no `epoch`, so it is exactly the shape those filters were never asked about.
 *
 * The failure this guards is silent. A wrong default in `triggerTypes`, or a filter reading
 * `triggerType` where it should read the launch kind, drops every agent run from the page
 * with no error anywhere - which is indistinguishable from "no agent ran".
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

/** A sub-agent spawn: the launch kind no workflow trigger can express. */
const AGENT_RUN = {
  id: 'agent-run:exec-1',
  kind: 'PAST' as const,
  startAt: '2026-09-03T09:12:00Z',
  endAt: '2026-09-03T09:12:08Z',
  resourceType: 'AGENT' as const,
  resourceId: 'ag-1',
  name: 'Support agent',
  armed: true,
  isNextFire: false,
  overridden: false,
  moveAllSupported: false,
  status: 'COMPLETED' as const,
  launchSource: 'SUB_AGENT' as const,
  conversationId: 'conv-5',
};

const COVERED_FROM = '2026-09-03T09:00:00Z';
let pastTruncated = false;
let agentHistoryUnavailable = false;

vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    getAgenda: () => Promise.resolve({
      occurrences: [AGENT_RUN],
      truncatedScheduleIds: [],
      pastTruncated,
      agentHistoryUnavailable,
      ...(pastTruncated && !agentHistoryUnavailable ? { pastCoveredFrom: COVERED_FROM } : {}),
    }),
    move: vi.fn(), runNow: vi.fn(), toggle: vi.fn(),
  },
  agendaFailureOf: () => ({}),
}));

beforeEach(() => {
  vi.setSystemTime(new Date('2026-09-03T12:00:00Z'));
  push.mockClear();
  pastTruncated = false;
  agentHistoryUnavailable = false;
});

afterEach(() => {
  vi.useRealTimers();
});

async function openTheRun() {
  render(<AgendaView />);
  const chip = await screen.findByRole('button', { name: /Support agent/ });
  fireEvent.click(chip);
}

describe('an agent run on the real page', () => {
  it('survives every default filter and is drawn', async () => {
    render(<AgendaView />);

    // The default launch-kind selection has to include the agent-only kinds, and the
    // resource-kind filter has to keep AGENT. Seeding `triggerTypes` from the eight
    // workflow kinds instead of the eleven would make this chip vanish.
    expect(await screen.findByRole('button', { name: /Support agent/ })).toBeTruthy();
  });

  it('says how it was launched, in the menu, before offering the way in', async () => {
    await openTheRun();

    expect(screen.getByText('launchedBy:{"kind":"kind.sub_agent"}')).toBeTruthy();
    expect(push).not.toHaveBeenCalled();
  });

  it('opens the CONVERSATION it happened in, not the agent panel', async () => {
    await openTheRun();

    fireEvent.click(screen.getByText('menu.openConversation'));

    // The seam this covers is the page's own open handler: it used to declare a
    // parameter shape that omitted conversationId, and worked only because the menu
    // happens to hand over the whole occurrence.
    expect(push).toHaveBeenCalledWith('/app/c/conv-5');
  });

  it('says the agent source is MISSING rather than blaming older runs', async () => {
    // Not "some older runs are not shown": when agent-service could not be read, the
    // missing rows include today's, and pointing the user backwards is the wrong
    // instruction. This is also the branch that would vanish if the page went back to
    // nesting it inside the truncation condition.
    agentHistoryUnavailable = true;
    pastTruncated = true;

    render(<AgendaView />);

    expect(await screen.findByText('truncated.agentHistoryUnavailable')).toBeTruthy();
    expect(screen.queryByText(/truncated\.pastFrom/)).toBeNull();
  });

  it('still says it when the backend stops folding that fact into pastTruncated', async () => {
    // The two are documented as separate facts and the page reads them as separate
    // facts, so the sentence survives the day the backend stops ORing them.
    agentHistoryUnavailable = true;
    pastTruncated = false;

    render(<AgendaView />);

    expect(await screen.findByText('truncated.agentHistoryUnavailable')).toBeTruthy();
  });

  it('names where coverage starts when the history was cut short', async () => {
    pastTruncated = true;

    render(<AgendaView />);

    // Not just "some older runs are not shown": the date AND the time, because the
    // boundary is an instant and a bare date claims a partially covered day is whole.
    const banner = await screen.findByText(/truncated\.pastFrom/);
    expect(banner.textContent).toContain('09:00');
  });
});
