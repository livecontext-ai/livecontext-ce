// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins the `generation` grant (unified image / video / audio / voice / music tool) on
 * the AGENT creation surface:
 *   - the Configuration > Advanced section carries its own toggle (pre-change there was
 *     none, so the tool could never be granted to an agent from any UI),
 *   - the emitted toolsConfig carries `generation` for BOTH true and false (the backend
 *     MERGES toolsConfig, so an omitted key would keep the stored grant and a revoke
 *     would silently no-op),
 *   - it hydrates from the agent on EDIT,
 *   - it is never inherited from the RETIRED `imageGeneration` grant: an old image
 *     grant must not grant video,
 *     which costs an order of magnitude more per call.
 *
 * next-intl is stubbed to echo `${ns}.${key}`; the shared chat-config strings therefore
 * render as `chatConfig.generationLabel`.
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
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getSubAgentEdges: vi.fn().mockResolvedValue([]),
    getWebhook: vi.fn().mockResolvedValue(null),
    getSchedule: vi.fn().mockResolvedValue(null),
    createOrUpdateSchedule: vi.fn().mockResolvedValue({ id: 'sched-1' }),
    deleteSchedule: vi.fn().mockResolvedValue(undefined),
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

vi.mock('@/hooks/useModels', () => ({
  useVisibleModels: () => ({ providers: [], defaultModel: null, defaultProvider: null, isLoading: false }),
  getModelsCache: () => null,
  isEmptySelectedModel: (sel: { id?: string } | null | undefined) => !sel || !sel.id,
  toNonBridgeSelectedModel: (seed: unknown) => seed,
}));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  useMcpApis: () => ({ data: { pages: [] }, isLoading: false, isFetching: false, fetchNextPage: vi.fn(), hasNextPage: false }),
  fetchApiTools: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: () => {},
}));
vi.mock('@/components/ai/ModelPicker', () => ({ ModelPicker: () => null }));
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

type TestAgent = { id?: string; name?: string; toolsConfig?: Record<string, unknown> };

function renderModal(agent?: TestAgent) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      {/* Step 2 = Configuration, where the Advanced section holds the two grant toggles. */}
      <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} initialStep={2} agent={agent} />
    </QueryClientProvider>,
  );
}

/** Expand the collapsed Advanced section of the Configuration step. */
async function openAdvanced() {
  fireEvent.click(await screen.findByText('modals.createAgent.advancedModeLabel'));
}

/**
 * The toggle row is a `<label>` + a clickable `<button>` inside one wrapper div, so
 * anchor on the label text (unique per grant) and take the button next to it.
 */
async function grantToggle(labelKey: string): Promise<HTMLElement> {
  const label = await screen.findByText(labelKey);
  const row = label.parentElement as HTMLElement;
  return within(row).getByRole('button');
}

const next = () => fireEvent.click(screen.getByRole('button', { name: 'Next' }));
const save = () => fireEvent.click(screen.getByRole('button', { name: /Update Agent|Create Agent/ }));

async function submittedToolsConfig(
  mock: typeof createAgentMock | typeof updateAgentMock,
): Promise<Record<string, unknown>> {
  await waitFor(() => expect(mock).toHaveBeenCalledTimes(1));
  const call = mock.mock.calls[0];
  // createAgent(payload) vs updateAgent(id, payload)
  const payload = (call.length > 1 ? call[1] : call[0]) as { toolsConfig: Record<string, unknown> };
  return payload.toolsConfig;
}

beforeEach(() => {
  vi.clearAllMocks();
  createAgentMock.mockResolvedValue({ id: 'created-agent-1' });
  updateAgentMock.mockResolvedValue({ id: 'agent-1' });
});
afterEach(() => cleanup());

describe('CreateAgentModal - generation grant (opt-in, never inherited from the retired image grant)', () => {
  it('renders its own toggle in Configuration > Advanced, OFF by default on CREATE', async () => {
    renderModal();
    await openAdvanced();

    const toggle = await grantToggle('chatConfig.generationLabel');
    expect(toggle).toHaveTextContent('modals.createAgent.disabled');
  });

  it('CREATE emits generation:false when untouched (an explicit deny, never an omission)', async () => {
    renderModal();
    await openAdvanced();
    next(); // → step 3 (Integration)
    save();

    const toolsConfig = await submittedToolsConfig(createAgentMock);
    expect(toolsConfig).toHaveProperty('generation', false);
  });

  it('CREATE emits generation:true once the toggle is switched on', async () => {
    renderModal();
    await openAdvanced();
    fireEvent.click(await grantToggle('chatConfig.generationLabel'));
    next();
    save();

    const toolsConfig = await submittedToolsConfig(createAgentMock);
    expect(toolsConfig).toHaveProperty('generation', true);
    // The retired image grant is never written back, in either state.
    expect(toolsConfig).not.toHaveProperty('imageGeneration');
  });

  it('no longer renders a toggle for the retired image grant', async () => {
    renderModal();
    await openAdvanced();

    expect(screen.queryByText('chatConfig.imageGenerationLabel')).toBeNull();
  });

  it('EDIT hydrates the toggle from the stored grant and round-trips it untouched', async () => {
    renderModal({ id: 'agent-1', name: 'A', toolsConfig: { mode: 'all', generation: true } });
    await openAdvanced();

    expect(await grantToggle('chatConfig.generationLabel')).toHaveTextContent('modals.createAgent.enabled');
    next();
    save();

    const toolsConfig = await submittedToolsConfig(updateAgentMock);
    expect(toolsConfig).toHaveProperty('generation', true);
  });

  it('EDIT revoking sends generation:false, which the backend toolsConfig MERGE needs to see', async () => {
    // Omitting the key would leave the stored `true` in place: an agent that had the
    // grant could never be switched off.
    renderModal({ id: 'agent-1', name: 'A', toolsConfig: { mode: 'all', generation: { enabled: true } } });
    await openAdvanced();
    fireEvent.click(await grantToggle('chatConfig.generationLabel'));
    next();
    save();

    const toolsConfig = await submittedToolsConfig(updateAgentMock);
    expect(toolsConfig).toHaveProperty('generation', false);
  });

  it('EDIT of an agent still carrying the RETIRED image grant does NOT hydrate generation on', async () => {
    renderModal({ id: 'agent-1', name: 'A', toolsConfig: { mode: 'all', imageGeneration: true } });
    await openAdvanced();

    // The stale grant is inert: it neither renders a toggle nor turns generation on.
    expect(screen.queryByText('chatConfig.imageGenerationLabel')).toBeNull();
    expect(await grantToggle('chatConfig.generationLabel')).toHaveTextContent('modals.createAgent.disabled');
  });
});
