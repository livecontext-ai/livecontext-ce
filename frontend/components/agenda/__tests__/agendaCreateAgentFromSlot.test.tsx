/**
 * @vitest-environment jsdom
 *
 * The agent half of the empty-slot gesture, end to end on the page.
 *
 * <p>The dialog's own behaviour is covered next door. What only a test of the page can
 * catch is that this branch does something FUNDAMENTALLY different from the workflow one:
 * it writes nothing. A workflow is created on confirm and the user is sent to the builder;
 * an agent is handed to the agent form with the schedule already filled in, and exists only
 * once that form is saved. Getting that wrong in either direction is expensive - creating
 * an agent here would leave a half-configured one behind every time someone changed their
 * mind, and dropping the seed would silently discard the slot the user clicked, which is
 * the entire reason to schedule from a calendar.
 *
 * <p>The other thing only the page can prove: an agent's schedule is armed the moment it is
 * saved, unlike a workflow's, so the calendar must be re-read rather than left showing the
 * gap the user just filled.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';
import { AgendaView } from '../../views/AgendaView';

const push = vi.fn();
const saveWorkflowPlan = vi.fn();
const track = vi.fn();
const getAgenda = vi.fn();
const refreshAutomations = vi.fn();

/** The props the agent form was opened with, captured by the mock below. */
let modalProps: Record<string, any> | null = null;

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams() }));
// ONE function, not a new one per render. The page's window fetch lists its translator in
// the effect's dependencies (the real `useTranslations` returns a stable one), so a mock
// that mints a fresh closure on every call refetches the calendar forever - and the counts
// this file reads to prove a reload happened would then measure the mock, not the page.
const { translate } = vi.hoisted(() => ({
  translate: (key: string, vars?: Record<string, unknown>) =>
    (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock('next-intl', () => ({ useTranslations: () => translate }));
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
vi.mock('@/lib/workflows/recentWorkflowNames', () => ({ rememberWorkflowName: () => {} }));
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));
vi.mock('@/lib/api/orchestrator', () => ({
  scheduleSettingsService: { validateCron: () => Promise.resolve({ valid: true, description: 'Every Thursday at 09:00' }) },
}));
vi.mock('@/lib/api/orchestrator/agenda.service', () => ({
  agendaService: {
    getAgenda: (...args: unknown[]) => getAgenda(...args),
    move: vi.fn(),
    runNow: vi.fn(),
    toggle: vi.fn(),
  },
  agendaFailureOf: () => ({}),
}));
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => refreshAutomations }));
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

// Stands in for the real agent form, which is 3 500 lines behind a lazy import and would
// drag the model, tool and skill catalogues into a test about wiring. It records what it
// was opened with and exposes the one callback the page reacts to.
vi.mock('@/components/chat/CreateAgentModal', () => ({
  CreateAgentModal: (props: Record<string, any>) => {
    modalProps = props;
    return (
      <>
        <button
          type="button"
          onClick={() => props.onAgentCreated('agent-77', { scheduleSaved: true, scheduleHasPrompt: true })}
        >
          save-agent
        </button>
        {/* Written, but with nothing to run: a new agent has no assigned tasks to fall back
            on, so every fire of this one is skipped. */}
        <button
          type="button"
          onClick={() => props.onAgentCreated('agent-77', { scheduleSaved: true, scheduleHasPrompt: false })}
        >
          save-agent-no-instruction
        </button>
        {/* The real form saves the agent and its schedule in two requests, and a refused
            schedule does not fail the save - so this is a state the page has to handle. */}
        <button type="button" onClick={() => props.onAgentCreated('agent-77', { scheduleSaved: false })}>
          save-agent-schedule-refused
        </button>
        {/* And the third answer: the user switched the seeded schedule off in the form, so
            none was written and none was refused. */}
        <button type="button" onClick={() => props.onAgentCreated('agent-77', { scheduleSaved: undefined })}>
          save-agent-schedule-dropped
        </button>
      </>
    );
  },
}));

const NOW = new Date('2026-09-03T09:30:00Z');

/** What the agenda returns once the agent's schedule exists: one planned fire on the slot. */
const AGENT_FIRE = {
  id: 'occ-1',
  scheduleId: 'sched-1',
  resourceType: 'AGENT',
  resourceId: 'agent-77',
  // The chip reads `name`. It also has to land INSIDE the window on screen (this suite's
  // preferences open a single day cropped to 09:00-11:00), which is the other half of why
  // "the chip appears" is a weaker promise than it sounds and the page does not make it.
  name: 'Morning briefing',
  triggerType: 'SCHEDULE',
  kind: 'PLANNED',
  status: 'PENDING',
  startAt: '2026-09-03T10:00:00Z',
  timezone: 'UTC',
  armed: true,
  isNextFire: true,
  overridden: false,
  moveAllSupported: true,
  cronExpression: '0 10 * * 4',
};

beforeEach(() => {
  vi.setSystemTime(NOW);
  modalProps = null;
  push.mockClear();
  saveWorkflowPlan.mockClear();
  track.mockClear();
  refreshAutomations.mockClear();
  getAgenda.mockClear();
  getAgenda.mockResolvedValue({ occurrences: [], truncatedScheduleIds: [], pastTruncated: false });
});

afterEach(() => {
  vi.useRealTimers();
});

/** Click the first empty hour, pick the agent card, name it and confirm. */
async function continueAsAgent(name = 'Morning briefing') {
  render(<AgendaView />);
  fireEvent.click((await screen.findAllByRole('button', { name: /create.slotAction/ }))[0]);
  fireEvent.click(await screen.findByText('create.kindAgent'));
  fireEvent.change(screen.getByLabelText('create.nameLabel'), { target: { value: name } });
  const confirm = screen.getByText('create.agentConfirm').closest('button')!;
  // Confirm stays disabled until the debounced cron check has answered, exactly as it does
  // for a workflow: the cron travels to the agent form, so an unchecked one must not.
  await waitFor(() => expect(confirm.hasAttribute('disabled')).toBe(false));
  fireEvent.click(confirm);
  await waitFor(() => expect(modalProps).not.toBeNull());
}

describe('scheduling an agent from an empty slot', () => {
  it('creates nothing on confirm: it opens the agent form instead', async () => {
    // The whole difference with the workflow branch. A row written here would be an agent
    // with no model, no prompt and no tools, left behind by anyone who then closed the form.
    await continueAsAgent();

    expect(saveWorkflowPlan).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByText('save-agent')).toBeTruthy();
  });

  it('carries the clicked slot into the agent form as its schedule', async () => {
    // Thursday 09:00 in the day grid's cropped range; weekday 4 is Thursday. Losing this is
    // the silent failure the feature exists to prevent: the form would open on its own
    // default of 09:00 every day and nobody would be told the click was discarded.
    await continueAsAgent();

    expect(modalProps!.initialSchedule).toEqual({
      cron: '0 9 * * 4',
      timezone: 'UTC',
      description: 'Every Thursday at 09:00',
    });
  });

  it('opens the form on CREATE, with the name already typed and no agent id', async () => {
    // `agent.id` is what the form reads to decide it is editing. An id here would open it
    // against an agent that does not exist and save over nothing.
    await continueAsAgent('Morning briefing');

    expect(modalProps!.agent).toEqual({ name: 'Morning briefing' });
    expect(modalProps!.agent.id).toBeUndefined();
  });

  it('re-reads the calendar once the agent is saved, so the new fire is drawn', async () => {
    // A workflow created from a slot cannot appear here until it is set as production, so
    // that branch navigates away. An agent's schedule fires from the next occurrence, so
    // there is something to show - and this asserts it is DRAWN, not merely that a fetch was
    // issued: a page that refetched and threw the answer away would pass a spy count.
    await continueAsAgent();
    getAgenda.mockResolvedValue({
      occurrences: [AGENT_FIRE], truncatedScheduleIds: [], pastTruncated: false,
    });

    fireEvent.click(screen.getByText('save-agent'));

    // By accessible name, which is the chip's `aria-label` ("<time> <name>") - the visible
    // label is inside a container-query span the calendar hides at narrow widths.
    expect(await screen.findByRole('button', { name: /Morning briefing/ })).toBeTruthy();
    expect(refreshAutomations).toHaveBeenCalled();
    expect(track).toHaveBeenCalledWith('agent_created', expect.objectContaining({
      agent_id: 'agent-77', source: 'agenda_slot',
    }));
  });

  it('says nothing about a next run when the schedule was refused', async () => {
    // The agent exists, its schedule does not, and the form has already said so in its own
    // words. "Check the calendar for its next run" here would send the user to look at an
    // empty slot - the expensive kind of wrong, because it reads as success.
    await continueAsAgent();

    fireEvent.click(screen.getByText('save-agent-schedule-refused'));

    await waitFor(() => expect(screen.queryByText('save-agent')).toBeNull());
    expect(screen.queryByText('create.agentCreatedTitle')).toBeNull();
    // Still counted and still refetched: an agent WAS created, and the calendar must not be
    // left drawing a gap that may or may not still be one.
    expect(track).toHaveBeenCalledWith('agent_created', expect.objectContaining({ agent_id: 'agent-77' }));
    expect(refreshAutomations).toHaveBeenCalled();
  });

  it('says nothing about a next run when the user switched the schedule off', async () => {
    // Not a refusal, so the form said nothing either: this is the only announcement the
    // user would get, and the agent it describes has no next run to check for.
    await continueAsAgent();

    fireEvent.click(screen.getByText('save-agent-schedule-dropped'));

    await waitFor(() => expect(screen.queryByText('save-agent')).toBeNull());
    expect(screen.queryByText('create.agentCreatedTitle')).toBeNull();
    expect(track).toHaveBeenCalledWith('agent_created', expect.objectContaining({ agent_id: 'agent-77' }));
  });

  it('confirms in words when the schedule was written', async () => {
    await continueAsAgent();

    fireEvent.click(screen.getByText('save-agent'));

    expect(await screen.findByText('create.agentCreatedTitle')).toBeTruthy();
    expect(screen.getByText('create.agentCreatedMessage')).toBeTruthy();
  });

  it('does not promise a next run for a schedule with no instruction', async () => {
    // The state this whole change set out to close, on its own primary flow: the schedule is
    // real and armed, and it is skipped on every fire until someone sends the agent work.
    // Saying "check the calendar for its next run" over it is the failure wearing a success
    // toast, so the page says the condition instead.
    await continueAsAgent();

    fireEvent.click(screen.getByText('save-agent-no-instruction'));

    expect(await screen.findByText('create.agentCreatedNoPromptMessage')).toBeTruthy();
    expect(screen.queryByText('create.agentCreatedMessage')).toBeNull();
  });

  it('closes the form without touching the calendar when the user backs out', async () => {
    // Nothing was created, so there is nothing to re-read - and a refetch here would make
    // an abandoned form look like an action that happened.
    await continueAsAgent();
    const before = getAgenda.mock.calls.length;

    // Through `act`: this is a state update on the page from outside React's own event
    // dispatch, and left bare it updates after the assertions it is supposed to cause.
    act(() => modalProps!.onClose());

    await waitFor(() => expect(screen.queryByText('save-agent')).toBeNull());
    expect(getAgenda.mock.calls.length).toBe(before);
    expect(track).not.toHaveBeenCalled();
  });

  it('ignores a save that carried no id, which is what an EDIT reports', async () => {
    // The same callback fires after an edit and carries nothing. Treating that as a creation
    // would track an `agent_created` with an undefined id and toast over an agent nobody made.
    await continueAsAgent();
    const before = getAgenda.mock.calls.length;

    act(() => modalProps!.onAgentCreated(undefined));

    await waitFor(() => expect(screen.queryByText('save-agent')).toBeNull());
    expect(track).not.toHaveBeenCalled();
    expect(getAgenda.mock.calls.length).toBe(before);
  });

  it('still creates a workflow when that is the kind picked', async () => {
    // The default path must survive the fork: the agent branch returns early, and an early
    // return placed one line too high would swallow every workflow creation silently. The
    // `modalProps` assertion is what the sibling workflow suite cannot make - that this
    // branch does not ALSO open the agent form.
    saveWorkflowPlan.mockResolvedValue({ workflowId: 'wf-9' });
    render(<AgendaView />);
    fireEvent.click((await screen.findAllByRole('button', { name: /create.slotAction/ }))[0]);
    fireEvent.change(await screen.findByLabelText('create.nameLabel'), { target: { value: 'Report' } });
    const confirm = screen.getByText('create.confirm').closest('button')!;
    await waitFor(() => expect(confirm.hasAttribute('disabled')).toBe(false));
    fireEvent.click(confirm);

    await waitFor(() => expect(saveWorkflowPlan).toHaveBeenCalledTimes(1));
    expect(modalProps).toBeNull();
    expect(push).toHaveBeenCalledWith('/app/workflow/wf-9');
  });
});
