// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins what a TEMPLATE PREFILL actually produces.
 *
 * A template hands CreateAgentModal an `agent` prop carrying a toolsConfig but
 * NO id, so `isEditMode` is false. Both toolsConfig restore effects were gated
 * on isEditMode, so a template's `mode` and `*AccessMode` were dropped and fell
 * back to the create defaults: 'all' tools and 'write' access, the exact
 * opposite of what a locked-down template declares.
 *
 * That was not cosmetic. The data-analyst template tells the model, in six
 * languages, "You have READ access only" while the agent actually created had
 * write access: a false security assurance written into the prompt itself.
 *
 * Per-family GRANTS were never affected (their useState initializers call
 * getGrant unconditionally), which is why only these two axes are pinned here.
 *
 * These tests fail on the pre-fix modal.
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

/** Mirrors what lib/templates/agent/data-analyst.json declares. */
const DATA_ANALYST_PREFILL = {
  name: 'Data analyst',
  description: 'Reads your tables',
  systemPrompt: 'You are a data analyst.',
  temperature: 0.2,
  maxTokens: 16000,
  maxIterations: 30,
  avatarUrl: 'preset:teal',
  toolsConfig: { mode: 'none', webSearch: false, tablesGrant: 'all', tableAccessMode: 'read' },
};

async function createFromPrefill(prefill: Record<string, unknown>) {
  renderModal(prefill as any, 1);
  next(); // → step 2 (Configuration)
  next(); // → step 3 (Integration)
  save();
  await waitFor(() => expect(createAgentMock).toHaveBeenCalledTimes(1));
  return createAgentMock.mock.calls[0][0] as any;
}

describe('CreateAgentModal - template prefill (create mode, no id)', () => {
  it('opens in CREATE mode, not edit, when the prefill has no id', async () => {
    renderModal(DATA_ANALYST_PREFILL as any, 1);
    await waitFor(() => expect(screen.queryByRole('button', { name: /Update Agent/ })).toBeNull());
  });

  it('honours the template tools mode instead of defaulting to all tools', async () => {
    const payload = await createFromPrefill(DATA_ANALYST_PREFILL);
    // Pre-fix this was 'all': the template asked for none and got the catalogue.
    expect(payload.toolsConfig.mode).toBe('none');
  });

  it('honours a read-only access mode instead of defaulting to write', async () => {
    const payload = await createFromPrefill(DATA_ANALYST_PREFILL);
    // The shipped system prompt promises read-only. The payload must agree.
    expect(payload.toolsConfig.tableAccessMode).toBe('read');
  });

  it('still carries the per-family grant the template declares', async () => {
    const payload = await createFromPrefill(DATA_ANALYST_PREFILL);
    expect(payload.toolsConfig.tablesGrant).toBe('all');
  });

  it('carries the template name and system prompt through to the payload', async () => {
    const payload = await createFromPrefill(DATA_ANALYST_PREFILL);
    expect(payload.name).toBe('Data analyst');
    expect(payload.systemPrompt).toBe('You are a data analyst.');
  });

  it('turns web search OFF when the template asks for no tools', async () => {
    const payload = await createFromPrefill(DATA_ANALYST_PREFILL);
    // webSearch defaults to TRUE in create mode, so a template advertising
    // "no tools" would otherwise ship an agent that can still search the web,
    // contradicting its own description in six languages.
    expect(payload.toolsConfig.webSearch).toBe(false);
  });

  it('honours a read-only workflow access mode too, not just tables', async () => {
    // Deliberately NOT asserting 'write': write is already the create default,
    // so a write assertion passes on the pre-fix modal and proves nothing.
    const payload = await createFromPrefill({
      ...DATA_ANALYST_PREFILL,
      toolsConfig: { mode: 'none', workflowsGrant: 'all', workflowAccessMode: 'read' },
    });
    expect(payload.toolsConfig.workflowAccessMode).toBe('read');
    expect(payload.toolsConfig.workflowsGrant).toBe('all');
  });
});
