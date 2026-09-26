/**
 * @vitest-environment jsdom
 *
 * The agenda's three mutations each report one product event, AFTER the server accepted the
 * change: pausing (a schedule, or the whole resource), running now, and moving a fire. A refused
 * mutation reports nothing, since nothing changed.
 *
 * The occurrence menu and the move dialog are replaced by plain buttons that call the page's own
 * callbacks: what is under test is the page's glue between the server call and the event, not
 * the menu's layout (covered by its own tests).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';
import type { AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { AgendaView } from '../../views/AgendaView';

const mocks = vi.hoisted(() => ({
  track: vi.fn(),
  move: vi.fn(),
  runNow: vi.fn(),
  toggle: vi.fn(),
  setResourcePaused: vi.fn(),
}));

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
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => mocks.track(...args) }));
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: { validateCron: () => Promise.resolve({ valid: true, description: 'ok' }) },
}));
vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    getAgenda: () => Promise.resolve({ occurrences: [], truncatedScheduleIds: [], pastTruncated: false }),
    move: (...args: unknown[]) => mocks.move(...args),
    runNow: (...args: unknown[]) => mocks.runNow(...args),
    toggle: (...args: unknown[]) => mocks.toggle(...args),
  },
  agendaFailureOf: () => ({}),
}));
vi.mock('@/lib/api/orchestrator/resource-control', () => ({
  productionResourceKind: () => 'workflow',
  setProductionResourcePaused: (...args: unknown[]) => mocks.setResourcePaused(...args),
}));
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => () => {} }));
vi.mock('@/hooks/useAgendaPreferences', () => ({
  ALL_RESOURCE_TYPES: ['WORKFLOW', 'APPLICATION', 'AGENT'],
  useAgendaPreferences: () => ({
    preferences: {
      view: 'day', timezone: 'UTC', weekStartsOn: 1, showWeekends: true,
      resourceTypes: ['WORKFLOW', 'APPLICATION', 'AGENT'], showPast: false, showPaused: true,
      density: 'comfortable', dayStartHour: 9, dayEndHour: 11,
    },
    update: () => {}, reset: () => {}, toggleResourceType: () => {}, hydrated: true,
  }),
}));

const OCCURRENCE = {
  scheduleId: 'sched-1',
  startAt: '2026-09-03T10:00:00Z',
  resourceType: 'AGENT',
  resourcePaused: false,
} as unknown as AgendaOccurrence;

// The menu, always open on one occurrence: each action calls the page's callback directly.
vi.mock('@/components/agenda/OccurrenceMenu', () => ({
  OccurrenceMenu: (props: {
    onRunNow: (o: AgendaOccurrence) => void;
    onMove: (o: AgendaOccurrence) => void;
    onTogglePause: (o: AgendaOccurrence) => void;
    onToggleResourcePause: (o: AgendaOccurrence) => void;
  }) => (
    <div>
      <button onClick={() => props.onRunNow(OCCURRENCE)}>menu-run-now</button>
      <button onClick={() => props.onMove(OCCURRENCE)}>menu-move</button>
      <button onClick={() => props.onTogglePause(OCCURRENCE)}>menu-pause</button>
      <button onClick={() => props.onToggleResourcePause(OCCURRENCE)}>menu-pause-resource</button>
    </div>
  ),
}));
vi.mock('@/components/agenda/MoveOccurrenceDialog', () => ({
  MoveOccurrenceDialog: (props: {
    occurrence: AgendaOccurrence | null;
    onConfirm: (startAt: Date, scope: 'NEXT' | 'ALL') => void;
  }) => props.occurrence ? (
    <div>
      <button onClick={() => props.onConfirm(new Date('2026-09-03T11:00:00Z'), 'ALL')}>move-all</button>
      <button onClick={() => props.onConfirm(new Date('2026-09-03T11:00:00Z'), 'NEXT')}>move-next</button>
    </div>
  ) : null,
}));

const NOW = new Date('2026-09-03T09:30:00Z');

beforeEach(() => {
  vi.setSystemTime(NOW);
  Object.values(mocks).forEach((fn) => fn.mockReset());
  mocks.move.mockResolvedValue(undefined);
  mocks.runNow.mockResolvedValue(undefined);
  mocks.toggle.mockResolvedValue(undefined);
  mocks.setResourcePaused.mockResolvedValue(undefined);
});

async function openAgenda() {
  render(<AgendaView />);
  await screen.findByText('menu-run-now');
}

const callsOf = (event: string) => mocks.track.mock.calls.filter(([name]) => name === event);

describe('agenda mutation analytics', () => {
  it('agenda_run_now is reported once the run was accepted', async () => {
    await openAgenda();
    fireEvent.click(screen.getByText('menu-run-now'));

    await waitFor(() => expect(callsOf('agenda_run_now')).toEqual([['agenda_run_now']]));
    expect(mocks.runNow).toHaveBeenCalledWith('sched-1', true);
  });

  it('a refused run now reports nothing', async () => {
    mocks.runNow.mockRejectedValue(new Error('refused'));
    await openAgenda();
    fireEvent.click(screen.getByText('menu-run-now'));

    await waitFor(() => expect(mocks.runNow).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 10));
    expect(callsOf('agenda_run_now')).toEqual([]);
  });

  it('pausing a schedule reports agenda_schedule_paused with scope schedule and the lowercased type', async () => {
    await openAgenda();
    fireEvent.click(screen.getByText('menu-pause'));

    await waitFor(() => expect(callsOf('agenda_schedule_paused')).toEqual([
      ['agenda_schedule_paused', { paused: true, scope: 'schedule', resource_type: 'agent' }],
    ]));
    expect(mocks.toggle).toHaveBeenCalledWith('sched-1', false);
  });

  it('pausing the whole resource reports agenda_schedule_paused with scope resource', async () => {
    await openAgenda();
    fireEvent.click(screen.getByText('menu-pause-resource'));

    await waitFor(() => expect(callsOf('agenda_schedule_paused')).toEqual([
      ['agenda_schedule_paused', { paused: true, scope: 'resource', resource_type: 'agent' }],
    ]));
  });

  it('a refused pause reports nothing', async () => {
    mocks.toggle.mockRejectedValue(new Error('refused'));
    await openAgenda();
    fireEvent.click(screen.getByText('menu-pause'));

    await waitFor(() => expect(mocks.toggle).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 10));
    expect(callsOf('agenda_schedule_paused')).toEqual([]);
  });

  it.each([
    ['move-all', 'all'],
    ['move-next', 'next'],
  ])('a confirmed move (%s) reports agenda_schedule_moved with scope %s', async (button, scope) => {
    await openAgenda();
    fireEvent.click(screen.getByText('menu-move'));
    fireEvent.click(await screen.findByText(button));

    await waitFor(() => expect(callsOf('agenda_schedule_moved')).toEqual([
      ['agenda_schedule_moved', { scope }],
    ]));
  });

  it('a refused move reports nothing', async () => {
    mocks.move.mockRejectedValue(new Error('refused'));
    await openAgenda();
    fireEvent.click(screen.getByText('menu-move'));
    fireEvent.click(await screen.findByText('move-all'));

    await waitFor(() => expect(mocks.move).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 10));
    expect(callsOf('agenda_schedule_moved')).toEqual([]);
  });
});
