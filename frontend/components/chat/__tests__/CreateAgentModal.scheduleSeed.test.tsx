// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins what `initialSchedule` does, which is the contract the agenda's calendar relies on.
 *
 * <p>The agenda lets a user click an empty Wednesday 16:00 and say "an agent". That click is
 * the only place the time was ever chosen, so it has to survive into the agent that gets
 * saved. If the seed is dropped nothing FAILS: the form opens on its own default of every
 * day at 09:00, the agent is created, and it runs at a time nobody picked - which is why
 * this is pinned on the SAVED payload rather than on the fields.
 *
 * <p>The other half is the edit trap. On an edit the form reads the agent's real schedule
 * back from the server, so honouring a seed there would switch the schedule ON over an
 * agent that has none, and the next save would create one nobody asked for.
 *
 * <p>next-intl is stubbed to echo `${ns}.${key}`; the harness below is the one the other
 * CreateAgentModal suites use.
 */

// The destination picker reads the workspace's destinations; that is covered in its own test.
vi.mock('@/components/app/ChannelDestinationPicker', () => ({
  ChannelDestinationPicker: () => null,
  isWorking: () => true,
  useChatDestinations: () => ({ destinations: [], workspaceDefault: null, isLoading: false, isError: false }),
}));
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));
vi.mock('next/image', () => ({ default: () => null }));

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
const g = globalThis as unknown as { ResizeObserver?: typeof ResizeObserverStub };
g.ResizeObserver = g.ResizeObserver || ResizeObserverStub;

vi.mock('@/components/ui/popover', () => ({
  Popover: ({ open, children }: { open?: boolean; children: React.ReactNode }) => (
    <div data-popover-open={open ? 'true' : 'false'}>{children}</div>
  ),
  PopoverTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
// Tooltip is Radix - render the trigger inert and DROP the content. This also lets
// us assert that the backlog HELP text lives in a tooltip (absent from the DOM here)
// rather than as an always-rendered sub-line (the old standalone-card layout).
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: () => null,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }) },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

const { updateAgentMock, createAgentMock } = vi.hoisted(() => ({
  updateAgentMock: vi.fn().mockResolvedValue({ id: 'agent-1' }),
  createAgentMock: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getSkills: vi.fn().mockResolvedValue([]),
    getSkillFolders: vi.fn().mockResolvedValue([]),
    getAllSkillFolders: vi.fn().mockResolvedValue([]),
    getWorkflows: vi.fn().mockResolvedValue([]),
    getWorkflowsPage: vi.fn().mockResolvedValue({ workflows: [], count: 0, totalCount: 0, page: 0, size: 100 }),
    getInterfaces: vi.fn().mockResolvedValue([]),
    getAgents: vi.fn().mockResolvedValue([]),
    getDataSources: vi.fn().mockResolvedValue([]),
    getAgentSkills: vi.fn().mockResolvedValue([]),
    getWidgetConfig: vi.fn().mockResolvedValue(null),
    createAgent: createAgentMock,
    updateAgent: updateAgentMock,
    setAgentSkills: vi.fn().mockResolvedValue(undefined),
    createOrUpdateWidgetConfig: vi.fn().mockResolvedValue(undefined),
    setWidgetActive: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: vi.fn().mockResolvedValue({}), post: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({
  fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() },
  getFileUrlById: () => 'url',
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getMyPublications: vi.fn().mockResolvedValue({ publications: [] }),
    getAcquiredApplications: vi.fn().mockResolvedValue({ applications: [] }),
  },
}));

const { getScheduleMock, createOrUpdateScheduleMock, deleteScheduleMock } = vi.hoisted(() => ({
  getScheduleMock: vi.fn().mockResolvedValue(null),
  createOrUpdateScheduleMock: vi.fn().mockResolvedValue({ id: 'sched-1' }),
  deleteScheduleMock: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getSubAgentEdges: vi.fn().mockResolvedValue([]),
    getWebhook: vi.fn().mockResolvedValue(null),
    getSchedule: getScheduleMock,
    createOrUpdateSchedule: createOrUpdateScheduleMock,
    deleteSchedule: deleteScheduleMock,
    createOrUpdateWebhook: vi.fn().mockResolvedValue(undefined),
    deleteWebhook: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/lib/api/orchestrator/schedule-settings.service', () => ({
  scheduleSettingsService: { getConfig: vi.fn().mockResolvedValue(null) },
}));

vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
// The modal asks which pot pays for this agent's turns, to pick a default model
// its plan can actually run (V494). Underneath, that hook reads the credit
// balance through the auth context these suites do not mount. Stubbed to the
// PAID answer with the verdict already in, which is exactly how the modal
// behaved before the question existed - so nothing below changes meaning.
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false,
    blockedForModel: () => false,
    freeTierForModel: () => false,
    prefersFreeTierModels: false,
    verdictReady: true,
  }),
}));

const modelsCacheMock = vi.hoisted(() => ({ value: null as unknown }));
// Partial mock: the hook + catalog cache are test-controlled while the compaction
// seed guard (toNonBridgeSelectedModel / isEmptySelectedModel) stays REAL so the
// "never seed a bridge pair" behaviour is exercised, not stubbed.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({ providers: [], defaultModel: null, defaultProvider: null, isLoading: false }),
    getModelsCache: () => modelsCacheMock.value,
  };
});
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  useMcpApis: () => ({ data: { pages: [] }, isLoading: false, isFetching: false, fetchNextPage: vi.fn(), hasNextPage: false }),
  fetchApiTools: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: () => {},
}));
// Renders once for the primary model and once for the compaction summariser
// override (when toggled on). Clicking it emits a fixed pick so payload tests
// can drive onChange; existing tests query buttons by NAME so the extra
// nameless buttons are inert for them. data-exclude-bridge surfaces the
// bridge-exclusion flag (must be set on the compaction picker ONLY).
vi.mock('@/components/ai/ModelPicker', () => ({
  ModelPicker: (props: {
    value: { provider: string; id: string };
    onChange: (next: { provider: string; id: string }) => void;
    excludeBridgeProviders?: boolean;
  }) => (
    <button
      type="button"
      data-testid="model-picker"
      data-provider={props.value.provider}
      data-model={props.value.id}
      data-exclude-bridge={props.excludeBridgeProviders ? 'true' : 'false'}
      onClick={() => props.onChange({ provider: 'anthropic', id: 'claude-haiku-4-5' })}
    />
  ),
}));
vi.mock('@/components/skills/SkillFolderTree', () => ({ SkillFolderTree: () => null }));
vi.mock('@/components/agents', () => ({
  AvatarDisplay: () => null,
  AvatarPicker: () => null,
  getPresetDefaultName: () => 'Agent',
  isPresetDefaultName: () => false,
}));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

import { CreateAgentModal } from '../CreateAgentModal';

/** Wednesday at 16:00 - the shape an hour-grid click produces, and no preset's cron. */
const SLOT_CRON = '0 16 * * 3';

function renderModal(props: {
  agent?: Record<string, unknown>;
  initialSchedule?: { cron: string; timezone: string; description?: string };
  onAgentCreated?: (agentId?: string, result?: { scheduleSaved?: boolean }) => void;
}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CreateAgentModal
        onClose={() => {}}
        onAgentCreated={props.onAgentCreated ?? (() => {})}
        initialStep={1}
        agent={props.agent as never}
        initialSchedule={props.initialSchedule}
      />
    </QueryClientProvider>,
  );
}

const next = () => fireEvent.click(screen.getByRole('button', { name: 'Next' }));
const save = () => fireEvent.click(screen.getByRole('button', { name: /Update Agent|Create Agent/ }));

/** Walk to the Integration step, where the schedule lives. */
function goToIntegration() {
  next();
  next();
}

beforeEach(() => {
  vi.clearAllMocks();
  modelsCacheMock.value = null;
  getScheduleMock.mockResolvedValue(null);
  updateAgentMock.mockResolvedValue({ id: 'agent-1' });
  createAgentMock.mockResolvedValue({ id: 'created-agent-1' });
  createOrUpdateScheduleMock.mockResolvedValue({ id: 'sched-1' });
  deleteScheduleMock.mockResolvedValue(undefined);
});
afterEach(() => cleanup());

describe('CreateAgentModal - seeded schedule (create mode)', () => {
  it('saves the seeded cron and zone without the user opening the schedule at all', async () => {
    // The whole point: the time was chosen on a calendar, and the user may well press
    // Create Agent without ever reading step 3.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'Europe/Paris' } });
    goToIntegration();
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock).toHaveBeenCalledWith('created-agent-1', expect.objectContaining({
      cron: SLOT_CRON,
      timezone: 'Europe/Paris',
    }));
  });

  it('creates no schedule at all when nothing seeded one', async () => {
    // The control for the assertion above: same steps, no seed, no schedule. If the toggle
    // merely defaulted to on, every agent created anywhere in the product would start firing
    // every day at 09:00 - and the seeded test would pass without the seed doing anything.
    renderModal({ agent: { name: 'Briefing' } });
    goToIntegration();
    save();

    await waitFor(() => expect(createAgentMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock).not.toHaveBeenCalled();
  });

  it('shows the raw expression under a frequency it has no preset for', () => {
    // Wednesday 16:00 is not one of the eleven presets, so the dropdown falls to Custom and
    // the cron has to be visible - a Custom row over a hidden expression would leave the
    // user no way to see what they are about to save.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' } });
    goToIntegration();

    expect(screen.getByPlaceholderText('* * * * *')).toHaveValue(SLOT_CRON);
  });

  it('reads that expression back in words, using the description it was handed', () => {
    // `0 16 * * 3` is not something a user typed, so it is not something they can check.
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC', description: 'Every Wednesday at 16:00' },
    });
    goToIntegration();

    expect(screen.getByText('Every Wednesday at 16:00')).toBeTruthy();
  });

  it('drops that sentence the moment the expression is edited', () => {
    // It described the cron it came with. Left standing over a different one, the panel is
    // confidently reading out a schedule that is no longer there.
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC', description: 'Every Wednesday at 16:00' },
    });
    goToIntegration();

    fireEvent.change(screen.getByPlaceholderText('* * * * *'), { target: { value: '0 8 * * 1' } });

    expect(screen.queryByText('Every Wednesday at 16:00')).toBeNull();
  });

  it('keeps a zone the seed brought in, even one the dropdown never listed', async () => {
    // The agenda can be read in any zone one of the workspace's schedules uses.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'Australia/Sydney' } });
    goToIntegration();
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock.mock.calls[0][1].timezone).toBe('Australia/Sydney');
  });
});

describe('CreateAgentModal - an armed schedule with no instruction', () => {
  const saveButton = () => screen.queryByRole('button', { name: /Update Agent|Create Agent/ });

  it('warns, naming the only condition under which it does anything', async () => {
    // A wakeup resolves the agent's assigned inbox and pending reviews FIRST and only falls
    // back to this field when it has neither. So a blank instruction is a real setup for a
    // delegated-work agent, and for every other one it is a schedule that advances, draws a
    // chip on the calendar and is skipped on every fire. The warning says which.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' } });
    goToIntegration();

    expect(screen.getByText('modals.createAgent.scheduleTaskEmptyWarning')).toBeTruthy();
  });

  it('does NOT refuse the save, because that setup is one the platform supports', async () => {
    // Refusing would block a delegated-work agent outright, and would block it hardest on an
    // EDIT: Save disabled for any unrelated change, and the obvious escape - switching the
    // schedule off - deletes a schedule that is live.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' } });
    goToIntegration();

    expect(saveButton()).toBeEnabled();
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock.mock.calls[0][1].schedulePrompt).toBe('');
  });

  it('drops the warning once an instruction is given, and saves it', async () => {
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' } });
    goToIntegration();
    fireEvent.change(screen.getByPlaceholderText('modals.createAgent.scheduleTaskPlaceholder'), {
      target: { value: "Summarise yesterday's tickets" },
    });

    expect(screen.queryByText('modals.createAgent.scheduleTaskEmptyWarning')).toBeNull();
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock.mock.calls[0][1].schedulePrompt).toBe("Summarise yesterday's tickets");
  });

  it('treats whitespace as no instruction at all', () => {
    // Spaces are stored verbatim and resolve to nothing at fire time, so they buy the same
    // silence an empty field would, without the warning if it read the raw value.
    renderModal({ agent: { name: 'Briefing' }, initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' } });
    goToIntegration();
    fireEvent.change(screen.getByPlaceholderText('modals.createAgent.scheduleTaskPlaceholder'), {
      target: { value: '   ' },
    });

    expect(screen.getByText('modals.createAgent.scheduleTaskEmptyWarning')).toBeTruthy();
  });

  it('says nothing about an instruction while there is no schedule to run one', () => {
    // The warning is about an ARMED schedule. On an agent with none it would be advice about
    // a control the user has not touched.
    renderModal({ agent: { name: 'Briefing' } });
    goToIntegration();

    expect(screen.queryByText('modals.createAgent.scheduleTaskEmptyWarning')).toBeNull();
  });
});

describe('CreateAgentModal - what the save reports back', () => {
  it('says the schedule was written when it was', async () => {
    const onAgentCreated = vi.fn();
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
      onAgentCreated,
    });
    goToIntegration();
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(onAgentCreated).toHaveBeenCalledWith('created-agent-1', {
      scheduleSaved: true, scheduleHasPrompt: false,
    });
  });

  it('says whether that schedule can do anything unprompted', async () => {
    // A blank instruction is answered at fire time by the agent's assigned tasks, and a
    // brand-new agent has none - so "written" and "will do something" are different answers
    // and a caller that reads only the first announces a schedule that is skipped every time.
    const onAgentCreated = vi.fn();
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
      onAgentCreated,
    });
    goToIntegration();
    fireEvent.change(screen.getByPlaceholderText('modals.createAgent.scheduleTaskPlaceholder'), {
      target: { value: 'Summarise the day' },
    });
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(onAgentCreated).toHaveBeenCalledWith('created-agent-1', {
      scheduleSaved: true, scheduleHasPrompt: true,
    });
  });

  it('counts a whitespace instruction as none, the way the runtime will', async () => {
    const onAgentCreated = vi.fn();
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
      onAgentCreated,
    });
    goToIntegration();
    fireEvent.change(screen.getByPlaceholderText('modals.createAgent.scheduleTaskPlaceholder'), {
      target: { value: '   ' },
    });
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(onAgentCreated.mock.calls[0][1].scheduleHasPrompt).toBe(false);
  });

  it('says it was NOT written when the schedule request was refused', async () => {
    // The agent is already saved by then and this does not undo it, so the caller is told
    // two different things: the agent exists, its schedule does not. A caller reading the
    // id alone would announce a scheduled agent that has no schedule.
    createOrUpdateScheduleMock.mockRejectedValue(new Error('cron refused'));
    const onAgentCreated = vi.fn();
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
      onAgentCreated,
    });
    goToIntegration();
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(onAgentCreated.mock.calls[0][1].scheduleSaved).toBe(false);
  });

  it('does not report a REFUSAL when it was a delete that failed', async () => {
    // The other side of the same guard. A failed delete leaves the agent's old schedule
    // running, which is a different problem from "your new schedule was refused" - reporting
    // it as `false` would make the caller announce the opposite of what happened.
    getScheduleMock.mockResolvedValue({
      id: 'sched-1', agentEntityId: 'agent-1', cronExpression: '0 7 * * 1', timezone: 'UTC',
      schedulePrompt: 'Do the thing', withMemory: false, enabled: true, executionCount: 0,
      createdAt: '2026-09-01T00:00:00Z',
    });
    deleteScheduleMock.mockRejectedValue(new Error('gone'));
    const onAgentCreated = vi.fn();
    renderModal({ agent: { id: 'agent-1', name: 'Briefing' }, onAgentCreated });
    goToIntegration();
    await waitFor(() => expect(screen.getByPlaceholderText('* * * * *')).toHaveValue('0 7 * * 1'));
    fireEvent.click(screen.getByText('modals.createAgent.scheduleLabel')); // switch it off
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(deleteScheduleMock).toHaveBeenCalledTimes(1);
    expect(onAgentCreated.mock.calls[0][1].scheduleSaved).toBeUndefined();
  });

  it('says nothing when a seeded schedule was switched off before saving', async () => {
    // Neither written nor refused: the form takes no schedule branch at all. Reported as
    // `undefined`, which is what lets a caller tell "turned it off" apart from "it failed"
    // and from "there never was one" - all three of which must not read as "scheduled".
    const onAgentCreated = vi.fn();
    renderModal({
      agent: { name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
      onAgentCreated,
    });
    goToIntegration();
    fireEvent.click(screen.getByText('modals.createAgent.scheduleLabel'));
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(createOrUpdateScheduleMock).not.toHaveBeenCalled();
    expect(onAgentCreated).toHaveBeenCalledWith('created-agent-1', { scheduleSaved: undefined });
  });

  it('says nothing about a schedule nobody asked for', async () => {
    // Absent, not false: "no schedule was requested" and "the schedule failed" are different
    // answers, and a caller that conflates them reports a failure on every plain agent.
    const onAgentCreated = vi.fn();
    renderModal({ agent: { name: 'Briefing' }, onAgentCreated });
    goToIntegration();
    save();

    await waitFor(() => expect(onAgentCreated).toHaveBeenCalled());
    expect(onAgentCreated).toHaveBeenCalledWith('created-agent-1', { scheduleSaved: undefined });
  });
});

describe('CreateAgentModal - a seed is ignored on an edit', () => {
  it('does not create a schedule for an agent that has none', async () => {
    // The restore effect is the authority on an existing agent's schedule. A seed honoured
    // here would leave the toggle on over an empty answer and mint a schedule on save.
    getScheduleMock.mockResolvedValue(null);
    renderModal({
      agent: { id: 'agent-1', name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'UTC' },
    });
    goToIntegration();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock).not.toHaveBeenCalled();
  });

  it('keeps the cron the agent already has', async () => {
    getScheduleMock.mockResolvedValue({
      id: 'sched-1', agentEntityId: 'agent-1', cronExpression: '0 7 * * 1', timezone: 'UTC',
      schedulePrompt: 'Do the thing', withMemory: false, enabled: true, executionCount: 0,
      createdAt: '2026-09-01T00:00:00Z',
    });
    renderModal({
      agent: { id: 'agent-1', name: 'Briefing' },
      initialSchedule: { cron: SLOT_CRON, timezone: 'Europe/Paris' },
    });
    goToIntegration();
    // The restore lands asynchronously; reading the field before it does would assert
    // against the seed and pass for the wrong reason.
    await waitFor(() => expect(screen.getByPlaceholderText('* * * * *')).toHaveValue('0 7 * * 1'));
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    expect(createOrUpdateScheduleMock.mock.calls[0][1]).toEqual(expect.objectContaining({
      cron: '0 7 * * 1',
      timezone: 'UTC',
    }));
  });
});
