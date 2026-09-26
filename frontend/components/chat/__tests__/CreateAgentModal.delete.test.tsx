// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins the DELETE affordance of CreateAgentModal (footer, edit mode only):
 *
 *  - The footer offers "Delete agent" ONLY while editing an existing agent, and
 *    deleting always goes through a confirmation dialog (never a one-click delete).
 *  - Confirming calls orchestratorApi.deleteAgent(id) once, then refreshes the
 *    parent list (onAgentCreated) and closes the modal (onClose).
 *  - Cancelling the confirmation deletes nothing.
 *  - A failed delete keeps the modal open so the user can retry.
 *
 * next-intl is stubbed to echo `${ns}.${key}` so labels render as
 * `modals.createAgent.<key>`.
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

const { updateAgentMock, createAgentMock, deleteAgentMock } = vi.hoisted(() => ({
  updateAgentMock: vi.fn().mockResolvedValue({ id: 'agent-1' }),
  createAgentMock: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
  deleteAgentMock: vi.fn().mockResolvedValue(undefined),
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
    deleteAgent: deleteAgentMock,
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

interface TestAgent {
  id?: string;
  name?: string;
}

function renderModal(agent: TestAgent | undefined, handlers: { onClose?: () => void; onAgentCreated?: () => void } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CreateAgentModal
        onClose={handlers.onClose ?? (() => {})}
        onAgentCreated={handlers.onAgentCreated ?? (() => {})}
        agent={agent}
      />
    </QueryClientProvider>,
  );
}

const deleteButton = () => screen.getByRole('button', { name: 'modals.createAgent.deleteAgent' });
const confirmButton = () => screen.getByRole('button', { name: 'common.delete' });

beforeEach(() => {
  vi.clearAllMocks();
  deleteAgentMock.mockResolvedValue(undefined);
});
afterEach(() => cleanup());

describe('CreateAgentModal - delete an existing agent from the footer', () => {
  it('shows the delete action only when editing an existing agent', async () => {
    renderModal({ id: 'agent-1', name: 'Nova' });
    expect(await screen.findByRole('button', { name: 'modals.createAgent.deleteAgent' })).toBeInTheDocument();
  });

  it('does not offer delete while CREATING an agent (nothing to delete yet)', async () => {
    renderModal(undefined);
    await screen.findByRole('button', { name: 'Cancel' });
    expect(screen.queryByRole('button', { name: 'modals.createAgent.deleteAgent' })).not.toBeInTheDocument();
  });

  it('asks for confirmation instead of deleting on the first click', async () => {
    renderModal({ id: 'agent-1', name: 'Nova' });
    fireEvent.click(await screen.findByRole('button', { name: 'modals.createAgent.deleteAgent' }));

    // The dialog names the agent so the user knows exactly what is going away.
    expect(screen.getByText('modals.createAgent.deleteConfirmation')).toBeInTheDocument();
    expect(deleteAgentMock).not.toHaveBeenCalled();
  });

  it('deletes, refreshes the parent list and closes once confirmed', async () => {
    const onClose = vi.fn();
    const onAgentCreated = vi.fn();
    renderModal({ id: 'agent-1', name: 'Nova' }, { onClose, onAgentCreated });

    fireEvent.click(await screen.findByRole('button', { name: 'modals.createAgent.deleteAgent' }));
    fireEvent.click(confirmButton());

    await waitFor(() => expect(deleteAgentMock).toHaveBeenCalledTimes(1));
    expect(deleteAgentMock).toHaveBeenCalledWith('agent-1');
    await waitFor(() => expect(onAgentCreated).toHaveBeenCalledTimes(1));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('deletes nothing when the confirmation is cancelled', async () => {
    const onClose = vi.fn();
    renderModal({ id: 'agent-1', name: 'Nova' }, { onClose });

    fireEvent.click(await screen.findByRole('button', { name: 'modals.createAgent.deleteAgent' }));
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }));

    await waitFor(() => expect(screen.queryByText('modals.createAgent.deleteConfirmation')).not.toBeInTheDocument());
    expect(deleteAgentMock).not.toHaveBeenCalled();
    // Cancelling the CONFIRMATION must not close the agent modal behind it.
    expect(onClose).not.toHaveBeenCalled();
    expect(deleteButton()).toBeEnabled();
  });

  it('keeps the modal open when the delete call fails, so the user can retry', async () => {
    const onClose = vi.fn();
    const onAgentCreated = vi.fn();
    deleteAgentMock.mockRejectedValueOnce(new Error('boom'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    renderModal({ id: 'agent-1', name: 'Nova' }, { onClose, onAgentCreated });

    fireEvent.click(await screen.findByRole('button', { name: 'modals.createAgent.deleteAgent' }));
    fireEvent.click(confirmButton());

    await waitFor(() => expect(deleteAgentMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(deleteButton()).toBeEnabled());
    expect(onAgentCreated).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
