/**
 * @vitest-environment jsdom
 *
 * The whole gesture, end to end in one render: click an empty slot, name it, and land in
 * the builder on the workflow that was created.
 *
 * The pieces are covered separately - the affordance, the dialog's proposal, the plan shape.
 * What only a test of the page can catch is the GLUE, and the glue is where the two sibling
 * creation call sites both carry warning comments: the workflow id has to travel as a
 * top-level request field (the backend ignores `plan.id` and mints its own, so the redirect
 * lands on a builder for a workflow that does not exist), and the redirect has to prefer the
 * id the server echoed back.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';
import { AgendaView } from '../../views/AgendaView';

const push = vi.fn();
const saveWorkflowPlan = vi.fn();
const rememberWorkflowName = vi.fn();
const track = vi.fn();

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
vi.mock('@/lib/api', () => ({
  orchestratorApi: { saveWorkflowPlan: (...args: unknown[]) => saveWorkflowPlan(...args) },
}));
vi.mock('@/lib/workflows/recentWorkflowNames', () => ({
  rememberWorkflowName: (...args: unknown[]) => rememberWorkflowName(...args),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: { validateCron: () => Promise.resolve({ valid: true, description: 'ok' }) },
}));
vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    // An empty workspace: every slot on the calendar is a gap, which is the case this
    // gesture is for.
    getAgenda: () => Promise.resolve({ occurrences: [], truncatedScheduleIds: [], pastTruncated: false }),
    move: vi.fn(),
    runNow: vi.fn(),
    toggle: vi.fn(),
  },
  // The page turns a refusal into a sentence through this; a mock that omits it makes the
  // error PATH throw, which is the one path this file needs to reach.
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
      resourceTypes: ['WORKFLOW', 'APPLICATION', 'AGENT'], showPast: false, showPaused: true,
      density: 'comfortable', dayStartHour: 9, dayEndHour: 11,
    },
    update: () => {}, reset: () => {}, toggleResourceType: () => {}, hydrated: true,
  }),
}));

const NOW = new Date('2026-09-03T09:30:00Z');

beforeEach(() => {
  // Real timers: the page's fetch, the dialog's debounced preview and the save all resolve
  // through the microtask queue, and driving that by hand under fake timers deadlocks.
  vi.setSystemTime(NOW);
  push.mockClear();
  saveWorkflowPlan.mockClear();
  rememberWorkflowName.mockClear();
  track.mockClear();
  saveWorkflowPlan.mockResolvedValue({ workflowId: 'server-id-42' });
});

afterEach(() => {
  vi.useRealTimers();
});

async function openAgenda() {
  render(<AgendaView />);
  // The window fetch resolves before the grid is drawn.
  await screen.findAllByRole('button', { name: /create.slotAction/ });
}

/** Click the first empty hour, fill the name, and confirm. */
async function createFromFirstSlot(name = 'Nightly report') {
  fireEvent.click(screen.getAllByRole('button', { name: /create.slotAction/ })[0]);
  fireEvent.change(await screen.findByLabelText('create.nameLabel'), { target: { value: name } });
  // Create is disabled until the debounced cron check has answered - deliberately, so an
  // unchecked expression cannot be submitted. Waiting for it is what a user does too.
  const confirm = screen.getByText('create.confirm').closest('button')!;
  await waitFor(() => expect(confirm.hasAttribute('disabled')).toBe(false));
  fireEvent.click(confirm);
  await waitFor(() => expect(saveWorkflowPlan).toHaveBeenCalled());
}

describe('creating a scheduled workflow from an empty slot', () => {
  it('sends the workflow id as a top-level field, not only inside the plan', async () => {
    // The backend takes the id from the request column and ignores `plan.id`. Without this
    // the row is created under a server UUID nobody holds, and the redirect below opens a
    // builder for a workflow that does not exist.
    await openAgenda();
    await createFromFirstSlot();

    expect(saveWorkflowPlan).toHaveBeenCalledTimes(1);
    const body = saveWorkflowPlan.mock.calls[0][0];
    expect(typeof body.workflowId).toBe('string');
    expect(body.workflowId).toHaveLength(36);
    expect(JSON.parse(body.planJson).triggers[0].params.cron).toBe('0 9 * * 4');
  });

  it('lands on the id the server echoed back, not the one the client guessed', async () => {
    await openAgenda();
    await createFromFirstSlot();

    expect(push).toHaveBeenCalledWith('/app/workflow/server-id-42');
  });

  it('falls back to its own id when the response carries none', async () => {
    saveWorkflowPlan.mockResolvedValue({});
    await openAgenda();
    await createFromFirstSlot();

    const body = saveWorkflowPlan.mock.calls[0][0];
    expect(push).toHaveBeenCalledWith(`/app/workflow/${body.workflowId}`);
  });

  it('primes the breadcrumb so the builder does not open on "Workflow {uuid}"', async () => {
    await openAgenda();
    await createFromFirstSlot('Nightly report');

    expect(rememberWorkflowName).toHaveBeenCalledWith('server-id-42', 'Nightly report');
    // The empty slot click itself, with the view it was made in.
    expect(track).toHaveBeenCalledWith('agenda_slot_opened', { view: 'day' });
    expect(track).toHaveBeenCalledWith('workflow_created', expect.objectContaining({
      workflow_id: 'server-id-42', source: 'agenda_slot',
    }));
  });

  it('keeps the dialog open and says why when the save is refused', async () => {
    // Navigating away on a failure would lose what the user typed and land them on nothing.
    saveWorkflowPlan.mockRejectedValue(new Error('boom'));
    await openAgenda();
    await createFromFirstSlot();

    // Awaited, not read one microtask after the save: the message is rendered by the
    // rejection handler, and asserting synchronously passes on ordering rather than on the
    // dialog actually saying anything.
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(push).not.toHaveBeenCalled();
  });
});
