// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins the EDIT-mode update behaviour of CreateAgentModal:
 *
 *  - Bug 1 (clearing a field): emptying systemPrompt / description on an existing
 *    agent must EMIT an explicit "" in the update payload so the backend merge
 *    (`if (systemPrompt != null) set...`) CLEARS it. Pre-fix the modal sent
 *    `trim() || undefined`, so the key was omitted → backend treated absent as
 *    "no change" → the old value survived.
 *  - CREATE keeps the omit-when-empty behaviour (so backend defaults apply).
 *  - Task 2: the "shared backlog participation" control is no longer a standalone
 *    integration card - it lives inside the Schedule → Advanced Options, so it is
 *    NOT shown until the schedule is enabled + Advanced Options expanded. The
 *    hydrated `backlogEnabled` flag must still round-trip in the payload even while
 *    the control is hidden.
 *  - Schedule: editing ONLY the schedule prompt must preserve the existing cron
 *    (not reset it to the 9am default).
 *
 * next-intl is stubbed to echo `${ns}.${key}` so labels render as
 * `modals.createAgent.<key>`.
 */

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

const { getScheduleMock, createOrUpdateScheduleMock } = vi.hoisted(() => ({
  getScheduleMock: vi.fn().mockResolvedValue(null),
  createOrUpdateScheduleMock: vi.fn().mockResolvedValue({ id: 'sched-1' }),
}));

vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getSubAgentEdges: vi.fn().mockResolvedValue([]),
    getWebhook: vi.fn().mockResolvedValue(null),
    getSchedule: getScheduleMock,
    createOrUpdateSchedule: createOrUpdateScheduleMock,
    deleteSchedule: vi.fn().mockResolvedValue(undefined),
    createOrUpdateWebhook: vi.fn().mockResolvedValue(undefined),
    deleteWebhook: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/lib/api/orchestrator/schedule-settings.service', () => ({
  scheduleSettingsService: { getConfig: vi.fn().mockResolvedValue(null) },
}));

vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
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
import { orchestratorApi } from '@/lib/api/orchestrator';

interface TestAgent {
  id?: string;
  name?: string;
  systemPrompt?: string;
  description?: string;
  backlogEnabled?: boolean;
  inactivityTimeout?: number;
  toolsConfig?: Record<string, unknown> | null;
  modelProvider?: string;
  modelName?: string;
  compactionEnabled?: boolean | null;
  compactionAfterTurns?: number | null;
  compactionModelProvider?: string | null;
  compactionModelName?: string | null;
}

function renderModal(agent?: TestAgent, initialStep = 1) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} initialStep={initialStep} agent={agent} />
    </QueryClientProvider>,
  );
}

const next = () => fireEvent.click(screen.getByRole('button', { name: 'Next' }));
const save = () => fireEvent.click(screen.getByRole('button', { name: /Update Agent|Create Agent/ }));

beforeEach(() => {
  vi.clearAllMocks();
  modelsCacheMock.value = null;
  getScheduleMock.mockResolvedValue(null);
  updateAgentMock.mockResolvedValue({ id: 'agent-1' });
  createAgentMock.mockResolvedValue({ id: 'created-agent-1' });
  createOrUpdateScheduleMock.mockResolvedValue({ id: 'sched-1' });
});
afterEach(() => cleanup());

describe('CreateAgentModal - EDIT clears systemPrompt / description', () => {
  it('emits systemPrompt:"" and description:"" when both are cleared on an existing agent', async () => {
    renderModal({ id: 'agent-1', name: 'A', systemPrompt: 'OLD PROMPT', description: 'OLD DESC' }, 1);

    // Step 1 - clear the description (hydrated from the agent).
    const desc = await screen.findByPlaceholderText('modals.createAgent.descriptionPlaceholder');
    expect(desc).toHaveValue('OLD DESC');
    fireEvent.change(desc, { target: { value: '' } });

    next(); // → step 2 (Configuration)

    // Step 2 - clear the system prompt.
    const sp = await screen.findByPlaceholderText('modals.createAgent.systemPromptPlaceholder');
    expect(sp).toHaveValue('OLD PROMPT');
    fireEvent.change(sp, { target: { value: '' } });

    next(); // → step 3 (Integration)
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    // The keys must be PRESENT with "" (not omitted) so the backend merge clears them.
    expect(payload).toHaveProperty('systemPrompt', '');
    expect(payload).toHaveProperty('description', '');
  });

  it('emits an edited (non-empty) system prompt verbatim', async () => {
    renderModal({ id: 'agent-1', name: 'A', systemPrompt: 'OLD PROMPT' }, 2);
    const sp = await screen.findByPlaceholderText('modals.createAgent.systemPromptPlaceholder');
    fireEvent.change(sp, { target: { value: '  NEW PROMPT  ' } });
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.systemPrompt).toBe('NEW PROMPT'); // trimmed
  });
});

describe('CreateAgentModal - EDIT inactivity timeout payload (0 = disabled, sent verbatim)', () => {
  it('sends inactivityTimeout: 0 verbatim (disabled), NOT coerced to the 300 default', async () => {
    renderModal({ id: 'agent-1', name: 'A', inactivityTimeout: 90 }, 2); // step 2 = Configuration
    // The tuning knobs (incl. inactivity timeout) live behind the collapsed Advanced section.
    fireEvent.click(await screen.findByText('modals.createAgent.advancedModeLabel'));
    const input = await screen.findByDisplayValue('90');
    fireEvent.change(input, { target: { value: '0' } });
    next(); // → step 3 (Integration)
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    // executionTimeout is `executionTimeout || 3600` (a 0 would become the default), but the
    // inactivity field must reach the backend verbatim so 0 actually disables the watchdog.
    expect(payload).toHaveProperty('inactivityTimeout', 0);
  });

  it('sends a custom inactivityTimeout verbatim', async () => {
    renderModal({ id: 'agent-1', name: 'A', inactivityTimeout: 90 }, 2);
    fireEvent.click(await screen.findByText('modals.createAgent.advancedModeLabel'));
    const input = await screen.findByDisplayValue('90');
    fireEvent.change(input, { target: { value: '120' } });
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('inactivityTimeout', 120);
  });
});

describe('CreateAgentModal - CREATE keeps omit-when-empty', () => {
  it('omits systemPrompt / description from the create payload when left empty', async () => {
    renderModal(undefined, 1); // CREATE (name auto-fills to "Agent")
    next(); // → step 2
    next(); // → step 3
    save();

    await waitFor(() => expect(createAgentMock).toHaveBeenCalledTimes(1));
    const payload = createAgentMock.mock.calls[0][0] as Record<string, unknown>;
    expect(payload.systemPrompt).toBeUndefined();
    expect(payload.description).toBeUndefined();
  });
});

describe('CreateAgentModal - Task 2: backlog lives under Schedule → Advanced Options', () => {
  it('does NOT render the backlog control while the schedule is disabled', async () => {
    renderModal({ id: 'agent-1', name: 'A' }, 3);
    // Wait for the integration step to mount (Schedule card present).
    await screen.findByText('modals.createAgent.scheduleLabel');
    expect(screen.queryByText('modals.createAgent.backlogEnabled')).not.toBeInTheDocument();
  });

  it('reveals the backlog control once the schedule is enabled + Advanced Options expanded', async () => {
    renderModal({ id: 'agent-1', name: 'A' }, 3);
    fireEvent.click(await screen.findByText('modals.createAgent.scheduleLabel')); // enable schedule
    fireEvent.click(await screen.findByText('Advanced Options'));                 // expand advanced
    expect(await screen.findByText('modals.createAgent.backlogEnabled')).toBeInTheDocument();
    // Help text lives in a tooltip (mocked away), NOT as an always-rendered sub-line.
    expect(screen.queryByText('modals.createAgent.backlogEnabledHelp')).not.toBeInTheDocument();
  });

  it('preserves a hydrated backlogEnabled=true in the payload even while its control is hidden', async () => {
    renderModal({ id: 'agent-1', name: 'A', backlogEnabled: true }, 3);
    await screen.findByText('modals.createAgent.scheduleLabel');
    save(); // no schedule interaction - control stays hidden

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.backlogEnabled).toBe(true);
  });

  it('toggling the nested backlog control flips backlogEnabled in the payload', async () => {
    renderModal({ id: 'agent-1', name: 'A', backlogEnabled: false }, 3);
    fireEvent.click(await screen.findByText('modals.createAgent.scheduleLabel')); // enable schedule
    fireEvent.click(await screen.findByText('Advanced Options'));
    fireEvent.click(await screen.findByRole('switch', { name: 'modals.createAgent.backlogEnabled' }));
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.backlogEnabled).toBe(true);
  });
});

describe('CreateAgentModal - compaction summariser-model payload (flat keys, both-or-neither)', () => {
  const openAdvanced = async () => {
    fireEvent.click(await screen.findByText('modals.createAgent.advancedModeLabel'));
  };

  it('untouched hydrated pair → both keys ABSENT from the payload (columns stay as-is)', async () => {
    renderModal(
      {
        id: 'agent-1', name: 'A', compactionEnabled: true,
        compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini',
      },
      2,
    );
    await openAdvanced();
    // Hydrated override → the model toggle is ON without any interaction.
    expect(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }))
      .toHaveAttribute('aria-checked', 'true');
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect('compactionModelProvider' in payload).toBe(false);
    expect('compactionModelName' in payload).toBe(false);
  });

  it('toggling the override OFF sends both halves as "" (explicit clear)', async () => {
    renderModal(
      {
        id: 'agent-1', name: 'A', compactionEnabled: true,
        compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini',
      },
      2,
    );
    await openAdvanced();
    fireEvent.click(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }));
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('compactionModelProvider', '');
    expect(payload).toHaveProperty('compactionModelName', '');
  });

  it('picking a summariser model sends the WHOLE pair as flat keys', async () => {
    renderModal({ id: 'agent-1', name: 'A', compactionEnabled: true }, 2);
    await openAdvanced();
    fireEvent.click(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }));
    // Two pickers render on step 2: [0] = primary model, [1] = compaction override.
    const pickers = screen.getAllByTestId('model-picker');
    expect(pickers).toHaveLength(2);
    fireEvent.click(pickers[1]);
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('compactionModelProvider', 'anthropic');
    expect(payload).toHaveProperty('compactionModelName', 'claude-haiku-4-5');
    // The primary model selection is untouched by the override pick.
    expect(payload.modelProvider).toBeUndefined();
  });

  it('excludes bridge providers on the compaction picker ONLY (primary keeps the full catalog)', async () => {
    renderModal(
      {
        id: 'agent-1', name: 'A', compactionEnabled: true,
        compactionModelProvider: 'openai', compactionModelName: 'gpt-5-mini',
      },
      2,
    );
    await openAdvanced();
    // [0] = primary model picker, [1] = compaction summariser picker.
    const pickers = screen.getAllByTestId('model-picker');
    expect(pickers).toHaveLength(2);
    expect(pickers[0]).toHaveAttribute('data-exclude-bridge', 'false');
    expect(pickers[1]).toHaveAttribute('data-exclude-bridge', 'true');
  });

  it('toggle-on seeds the NON-bridge primary pair as before', async () => {
    renderModal(
      { id: 'agent-1', name: 'A', modelProvider: 'openai', modelName: 'gpt-5-mini', compactionEnabled: true },
      2,
    );
    await openAdvanced();
    fireEvent.click(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }));
    const pickers = screen.getAllByTestId('model-picker');
    expect(pickers[1]).toHaveAttribute('data-provider', 'openai');
    expect(pickers[1]).toHaveAttribute('data-model', 'gpt-5-mini');
  });

  it('toggle-on with a BRIDGE primary model seeds a non-bridge pair instead (never a bridge summariser)', async () => {
    // The summariser is a bare single completion which a CLI bridge can never
    // serve (the backend rejects a bridge-linked pair with 400), so the seed
    // must fall back to the first non-bridge provider's default model.
    modelsCacheMock.value = {
      providers: [
        {
          name: 'claude-code', defaultModel: 'claude-opus-4-6', displayOrder: 1,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'claude-opus-4-6', name: 'claude-opus-4-6', provider: 'claude-code', providerKind: 'bridge' }],
        },
        {
          name: 'deepseek', defaultModel: 'deepseek-chat', displayOrder: 2,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'deepseek-chat', name: 'deepseek-chat', provider: 'deepseek', providerKind: 'cloud' }],
        },
      ],
      defaultProvider: 'claude-code',
      defaultModel: 'claude-opus-4-6',
    };
    renderModal(
      { id: 'agent-1', name: 'A', modelProvider: 'claude-code', modelName: 'claude-opus-4-6', compactionEnabled: true },
      2,
    );
    await openAdvanced();
    fireEvent.click(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }));
    const pickers = screen.getAllByTestId('model-picker');
    expect(pickers[1]).toHaveAttribute('data-provider', 'deepseek');
    expect(pickers[1]).toHaveAttribute('data-model', 'deepseek-chat');
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    expect(payload).toHaveProperty('compactionModelProvider', 'deepseek');
    expect(payload).toHaveProperty('compactionModelName', 'deepseek-chat');
  });

  it('toggle-on with a bridge primary and a bridge-only catalog seeds NOTHING (no bridge pair, ever)', async () => {
    modelsCacheMock.value = {
      providers: [
        {
          name: 'codex', defaultModel: 'gpt-5.4', displayOrder: 1,
          supportsStreaming: true, supportsToolCalling: true,
          models: [{ id: 'gpt-5.4', name: 'gpt-5.4', provider: 'codex', providerKind: 'bridge' }],
        },
      ],
      defaultProvider: 'codex',
      defaultModel: 'gpt-5.4',
    };
    renderModal(
      { id: 'agent-1', name: 'A', modelProvider: 'codex', modelName: 'gpt-5.4', compactionEnabled: true },
      2,
    );
    await openAdvanced();
    fireEvent.click(screen.getByRole('switch', { name: 'chatConfig.compactionModelLabel' }));
    const pickers = screen.getAllByTestId('model-picker');
    expect(pickers[1]).toHaveAttribute('data-provider', '');
    expect(pickers[1]).toHaveAttribute('data-model', '');
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    const payload = updateAgentMock.mock.calls[0][1] as Record<string, unknown>;
    // Unchanged from the '' initials → keys absent, never a bridge pair.
    expect('compactionModelProvider' in payload).toBe(false);
    expect('compactionModelName' in payload).toBe(false);
  });
});

describe('CreateAgentModal - schedule prompt edit preserves the existing cron', () => {
  it('keeps the stored cron (not the 9am default) when only the schedule prompt changes', async () => {
    getScheduleMock.mockResolvedValue({
      id: 'sched-1',
      agentEntityId: 'agent-1',
      cronExpression: '0 18 * * *', // every day 6pm - NOT the 9am default
      timezone: 'UTC',
      maxExecutions: null,
      schedulePrompt: 'OLD TASK',
      withMemory: false,
      enabled: true,
      executionCount: 0,
      createdAt: '2026-01-01T00:00:00Z',
    });
    renderModal({ id: 'agent-1', name: 'A' }, 3);

    // Schedule restores async → the task prompt textarea shows the stored prompt.
    const promptBox = await screen.findByPlaceholderText('modals.createAgent.scheduleTaskPlaceholder');
    await waitFor(() => expect(promptBox).toHaveValue('OLD TASK'));

    fireEvent.change(promptBox, { target: { value: 'NEW TASK' } });
    save();

    await waitFor(() => expect(createOrUpdateScheduleMock).toHaveBeenCalledTimes(1));
    const [, body] = createOrUpdateScheduleMock.mock.calls[0];
    expect(body.cron).toBe('0 18 * * *'); // preserved, NOT reset to '0 9 * * *'
    expect(body.schedulePrompt).toBe('NEW TASK');
  });
});

describe('CreateAgentModal - widget toggle-off on update deactivates it (regression)', () => {
  // An existing, ACTIVE widget the edit modal hydrates from getWidgetConfig.
  const activeWidget = {
    isActive: true,
    position: 'bottom-right', theme: 'light', primaryColor: '#000000',
    welcomeMessage: 'Hi', bubbleText: 'Chat', showAvatar: true, autoOpenDelay: 0,
    widgetToken: 'wid_abc', allowedOrigins: '', widgetScriptUrl: 'https://x/widget.js',
  };

  it('deactivates the widget when toggled OFF (was a silent no-op: it kept serving)', async () => {
    vi.mocked(orchestratorApi.getWidgetConfig).mockResolvedValueOnce(activeWidget as never);
    renderModal({ id: 'agent-1', name: 'A' }, 3); // Integration step
    // Widget hydrates as enabled → its config panel ("Position") renders.
    await screen.findByText('Position');
    // Toggle the widget OFF, then save.
    fireEvent.click(screen.getByText('Chat Widget'));
    save();

    await waitFor(() =>
      expect(orchestratorApi.setWidgetActive).toHaveBeenCalledWith('agent-1', false),
    );
    // It must NOT re-create/update the config (which would flip isActive back on).
    expect(orchestratorApi.createOrUpdateWidgetConfig).not.toHaveBeenCalled();
  });

  it('updates (not deactivates) when the widget stays enabled', async () => {
    vi.mocked(orchestratorApi.getWidgetConfig).mockResolvedValueOnce(activeWidget as never);
    renderModal({ id: 'agent-1', name: 'A' }, 3);
    await screen.findByText('Position'); // enabled
    save(); // no toggle

    await waitFor(() =>
      expect(orchestratorApi.createOrUpdateWidgetConfig).toHaveBeenCalledTimes(1),
    );
    expect(orchestratorApi.setWidgetActive).not.toHaveBeenCalled();
  });

  it('does nothing when there was never a widget and it stays off (no spurious deactivate)', async () => {
    // getWidgetConfig defaults to null → no widget; toggle stays off.
    renderModal({ id: 'agent-1', name: 'A' }, 3);
    await screen.findByText('Chat Widget');
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(orchestratorApi.createOrUpdateWidgetConfig).not.toHaveBeenCalled();
    expect(orchestratorApi.setWidgetActive).not.toHaveBeenCalled();
  });
});
