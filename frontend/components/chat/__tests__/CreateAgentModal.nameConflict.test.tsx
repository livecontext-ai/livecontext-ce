// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins what CreateAgentModal does when the server refuses a name another ACTIVE agent of the
 * workspace already holds (409 AGENT_NAME_CONFLICT with the first free name as suggestedName):
 *
 *  - the refusal is shown next to the name field (step 1, wherever the user saved from),
 *    not as a generic "creation failed" toast;
 *  - one click on "Use <suggestedName>" puts the free name in the field, and the next save
 *    sends it;
 *  - the message disappears as soon as the user types another name;
 *  - without a suggestion the message still shows, with no button.
 *
 * The scaffolding (module mocks, renderModal, next/save) is the one of
 * CreateAgentModal.update.test.tsx. next-intl is stubbed to echo `${ns}.${key}`.
 */

// The destination picker reads the workspace's destinations; that is covered in its own test.
// A button per choice stands in for the real select: what matters here is what the modal
// does with the value it is handed.
const trackMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => trackMock(...a) }));
const channelState = vi.hoisted(() => ({ working: true, loading: false, error: false }));
vi.mock('@/components/app/ChannelDestinationPicker', () => ({
  isWorking: () => channelState.working,
  useChatDestinations: () => ({
    destinations: [{ linkId: 'link-ops' }], workspaceDefault: null,
    isLoading: channelState.loading, isError: channelState.error,
  }),
  ChannelDestinationPicker: ({ value, onChange }: { value: string | null; onChange: (v: string | null) => void }) => (
    <div data-testid="channel-destination-picker" data-value={String(value)}>
      <button type="button" onClick={() => onChange('link-finance')}>pick-finance</button>
      <button type="button" onClick={() => onChange(null)}>pick-default</button>
    </div>
  ),
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
// The field "i" (InfoPopover) sits on the stubbed Popover above, which would render its panel
// inline next to the control. Stand it in with a marker that carries its label and its text,
// neither a button nor visible copy, so each field block still holds only its own control
// while a test can assert which "i" is wired to which field.
vi.mock('@/components/ui/info-popover', () => ({
  InfoPopover: ({ label, children }: { label: string; children: React.ReactNode }) => (
    <span data-info-label={label} data-info-text={typeof children === 'string' ? children : undefined} />
  ),
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

const { getScheduleMock, createOrUpdateScheduleMock, setToolAuthorizationMock } = vi.hoisted(() => ({
  getScheduleMock: vi.fn().mockResolvedValue(null),
  createOrUpdateScheduleMock: vi.fn().mockResolvedValue({ id: 'sched-1' }),
  setToolAuthorizationMock: vi.fn().mockResolvedValue({ requireToolAuthorization: true }),
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
    setToolAuthorization: setToolAuthorizationMock,
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
const { addToastMock } = vi.hoisted(() => ({ addToastMock: vi.fn() }));
vi.mock('@/components/Toast', () => ({
  default: () => null,
  useToast: () => ({ toasts: [], addToast: addToastMock, removeToast: vi.fn() }),
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
  requireToolAuthorization?: boolean;
  chatChannelLinkId?: string | null;
  chatChannelEnabled?: boolean;
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
  setToolAuthorizationMock.mockResolvedValue({ requireToolAuthorization: true });
  channelState.working = true;
  channelState.loading = false;
  channelState.error = false;
});
afterEach(() => cleanup());


/** The ApiError apiClient throws for the 409, reduced to the fields the modal reads. */
function nameConflictError(suggestedName?: string) {
  return Object.assign(new Error('An active agent named \'Nova\' already exists in this workspace'), {
    status: 409,
    code: 'AGENT_NAME_CONFLICT',
    details: { error: 'AGENT_NAME_CONFLICT', name: 'Nova', ...(suggestedName ? { suggestedName } : {}) },
  });
}

const nameInput = () => screen.findByPlaceholderText('modals.createAgent.namePlaceholder');
const useSuggestion = () => screen.queryByRole('button', { name: 'modals.createAgent.useSuggestedName' });

describe('CreateAgentModal - agent name already used in the workspace', () => {
  it('shows the conflict next to the name field and one click takes the suggested free name', async () => {
    updateAgentMock.mockRejectedValueOnce(nameConflictError('Nova (2)'));
    renderModal({ id: 'agent-1', name: 'Nova' }, 3);

    save();

    // Back on step 1, with the refusal beside the field instead of a generic failure.
    expect(await screen.findByText('modals.createAgent.duplicateName')).toBeInTheDocument();
    expect(await nameInput()).toHaveValue('Nova');

    fireEvent.click(useSuggestion()!);
    expect(await nameInput()).toHaveValue('Nova (2)');
    expect(screen.queryByText('modals.createAgent.duplicateName')).not.toBeInTheDocument();

    // The next save sends the free name.
    next();
    next();
    save();
    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(2));
    expect((updateAgentMock.mock.calls[1][1] as Record<string, unknown>).name).toBe('Nova (2)');
  });

  it('hides the conflict once the user types another name', async () => {
    updateAgentMock.mockRejectedValueOnce(nameConflictError('Nova (2)'));
    renderModal({ id: 'agent-1', name: 'Nova' }, 3);
    save();
    expect(await screen.findByText('modals.createAgent.duplicateName')).toBeInTheDocument();

    fireEvent.change(await nameInput(), { target: { value: 'Nova Prime' } });

    expect(screen.queryByText('modals.createAgent.duplicateName')).not.toBeInTheDocument();
    expect(useSuggestion()).not.toBeInTheDocument();
  });

  it('without a suggestion, still explains the refusal but offers no button', async () => {
    updateAgentMock.mockRejectedValueOnce(nameConflictError());
    renderModal({ id: 'agent-1', name: 'Nova' }, 3);
    save();

    expect(await screen.findByText('modals.createAgent.duplicateName')).toBeInTheDocument();
    expect(useSuggestion()).not.toBeInTheDocument();
  });

  it('any other failure keeps the generic error and shows no name conflict', async () => {
    updateAgentMock.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500, code: 'INTERNAL_ERROR' }));
    renderModal({ id: 'agent-1', name: 'Nova' }, 3);
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('modals.createAgent.duplicateName')).not.toBeInTheDocument();
    expect(useSuggestion()).not.toBeInTheDocument();
    await waitFor(() => expect(addToastMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'error', message: 'modals.createAgent.createFailed' })));
  });

  it('CREATE with the preset default name that is taken: the suggestion replaces it and the next create sends it', async () => {
    // A new agent opens on the preset's default name ("Agent" here), the collision users hit most.
    createAgentMock.mockRejectedValueOnce(Object.assign(new Error('taken'), {
      status: 409,
      code: 'AGENT_NAME_CONFLICT',
      details: { error: 'AGENT_NAME_CONFLICT', name: 'Agent', suggestedName: 'Agent (2)' },
    }));
    renderModal(undefined, 3);
    save();

    expect(await screen.findByText('modals.createAgent.duplicateName')).toBeInTheDocument();
    expect(await nameInput()).toHaveValue('Agent');
    expect(addToastMock).not.toHaveBeenCalled();

    fireEvent.click(useSuggestion()!);
    expect(await nameInput()).toHaveValue('Agent (2)');

    next();
    next();
    save();
    await waitFor(() => expect(createAgentMock).toHaveBeenCalledTimes(2));
    expect((createAgentMock.mock.calls[1][0] as Record<string, unknown>).name).toBe('Agent (2)');
  });

  it('EDIT: renaming onto a taken name keeps the typed name, shows the conflict and sends the suggestion next', async () => {
    updateAgentMock.mockRejectedValueOnce(nameConflictError('Nova (2)'));
    renderModal({ id: 'agent-1', name: 'Scout' }, 1);

    fireEvent.change(await nameInput(), { target: { value: 'Nova' } });
    next();
    next();
    save();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect((updateAgentMock.mock.calls[0][1] as Record<string, unknown>).name).toBe('Nova');
    expect(await screen.findByText('modals.createAgent.duplicateName')).toBeInTheDocument();
    expect(await nameInput()).toHaveValue('Nova');

    fireEvent.click(useSuggestion()!);
    next();
    next();
    save();
    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(2));
    expect((updateAgentMock.mock.calls[1][1] as Record<string, unknown>).name).toBe('Nova (2)');
  });

  it('a server without the structured answer (mid-rollout) still gets the duplicate-name toast', async () => {
    updateAgentMock.mockRejectedValueOnce(Object.assign(
      new Error("An active agent with name 'Nova' already exists (ID: x)"), { status: 400, code: 'INVALID_ARGUMENT' }));
    renderModal({ id: 'agent-1', name: 'Nova' }, 3);
    save();

    await waitFor(() => expect(addToastMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'error', message: 'modals.createAgent.duplicateName' })));
    expect(useSuggestion()).not.toBeInTheDocument();
  });
});
