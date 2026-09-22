// @vitest-environment jsdom
/**
 * Which model a brand-new agent opens on (V494).
 *
 * <p>The catalogue default is the admin's global #1, which is the right answer
 * for an account with a wallet. On the Free plan an agent's turns are paid by
 * the separate monthly AI allowance, and that pot only covers the models a cloud
 * admin opened to the free tier - so a free account building an agent on the
 * global #1 gets refused on its first run, at the exact moment the allowance
 * exists to serve. Nothing errors when this regresses: an agent is created and
 * saved, and it simply cannot run.
 *
 * <p>Two halves are pinned here, and the second is the one that bites. The first
 * is the preference itself. The second is the TIMING: the plan verdict starts
 * false while the balance request is in flight, which is indistinguishable from
 * a paid account, and a model written in that window is persisted onto the agent
 * for good.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
  Popover: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PopoverTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: () => null,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getExplorerEntries: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
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
    createAgent: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
    updateAgent: vi.fn().mockResolvedValue({ id: 'agent-1' }),
    setAgentSkills: vi.fn().mockResolvedValue(undefined),
    createOrUpdateWidgetConfig: vi.fn().mockResolvedValue(undefined),
    setWidgetActive: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn().mockResolvedValue({}), post: vi.fn() },
}));
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

/**
 * The catalogue as an admin ranked it: the global #1 is a frontier model the
 * free allowance does not cover, and the model opened to the free tier sits
 * below it. That ordering IS the test - a catalogue whose #1 happened to be
 * free-tier would pass with the preference deleted.
 */
const OPUS = { id: 'claude-opus-5', name: 'Opus 5', provider: 'anthropic', displayOrder: 1 };
const HAIKU = {
  id: 'claude-haiku-4-5',
  name: 'Haiku 4.5',
  provider: 'anthropic',
  displayOrder: 5,
  freeTierEnabled: true,
};
const MISTRAL = { id: 'mistral-small', name: 'Mistral Small', provider: 'mistral', displayOrder: 9 };

const catalog = vi.hoisted(() => ({
  models: [] as Array<Record<string, unknown>>,
  defaultModel: null as string | null,
  defaultProvider: null as string | null,
}));
const verdict = vi.hoisted(() => ({
  prefersFreeTierModels: false,
  verdictReady: true,
}));

vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      models: catalog.models,
      // The provider list only has to be non-empty: the effect waits on it, and
      // the picker itself is stubbed below.
      providers: [{ name: 'anthropic', models: catalog.models }],
      defaultModel: catalog.defaultModel,
      defaultProvider: catalog.defaultProvider,
      isLoading: false,
    }),
    getModelsCache: () => null,
  };
});

// The verdict is stubbed rather than the balance query: what this file is about
// is the decision the modal makes, and useMonthlyCreditsCannotPay has its own
// suite for how the two flags are derived.
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false,
    blockedForModel: () => false,
    freeTierForModel: () => false,
    prefersFreeTierModels: verdict.prefersFreeTierModels,
    verdictReady: verdict.verdictReady,
  }),
}));

vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  useMcpApis: () => ({
    data: { pages: [] },
    isLoading: false,
    isFetching: false,
    fetchNextPage: vi.fn(),
    hasNextPage: false,
  }),
  fetchApiTools: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: () => {},
}));
vi.mock('@/components/ai/ModelPicker', () => ({
  ModelPicker: (props: { value: { provider: string; id: string } }) => (
    <button
      type="button"
      data-testid="model-picker"
      data-provider={props.value.provider}
      data-model={props.value.id}
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

/**
 * Step 2 is where the model picker lives, so every test starts there. The
 * default it asserts is written by an effect that does not care which step is
 * on screen, so opening on step 2 tests the same code as walking to it.
 */
function renderModal() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} initialStep={2} />
    </QueryClientProvider>,
  );
}

/** The primary model picker. The second one, when present, is the compaction override. */
const picker = () => screen.getAllByTestId('model-picker')[0];
const chosen = () => ({
  provider: picker().getAttribute('data-provider'),
  id: picker().getAttribute('data-model'),
});

beforeEach(() => {
  vi.clearAllMocks();
  catalog.models = [OPUS, HAIKU, MISTRAL];
  catalog.defaultModel = 'claude-opus-5';
  catalog.defaultProvider = 'anthropic';
  verdict.prefersFreeTierModels = false;
  verdict.verdictReady = true;
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('CreateAgentModal - which model a new agent opens on', () => {
  it('opens a free-tier account on a model its AI allowance covers', async () => {
    verdict.prefersFreeTierModels = true;

    renderModal();

    await waitFor(() => expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-haiku-4-5' }));
  });

  it('writes the provider and the id from the SAME model, never a mixed pair', async () => {
    // The free-tier model belongs to another provider than the catalogue
    // default. Filling the two fields from two different sources would name a
    // model the chosen provider does not serve.
    verdict.prefersFreeTierModels = true;
    catalog.models = [OPUS, { ...MISTRAL, freeTierEnabled: true }];

    renderModal();

    await waitFor(() => expect(chosen()).toEqual({ provider: 'mistral', id: 'mistral-small' }));
  });

  it('leaves a paid account on the catalogue default, free-tier model or not', async () => {
    // The change has to be a no-op for everyone it is not for. A paid plan's
    // credits pay for every model, so steering it away from the admin's #1
    // would be downgrading an account that never asked.
    renderModal();

    await waitFor(() => expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-opus-5' }));
  });

  it('keeps the catalogue pair when the default is already open to the free tier', async () => {
    // Nothing to correct, so nothing is touched - and in particular the pair
    // stays the catalogue's own, rather than being rebuilt from a lookup that
    // can land on another provider serving the same model id.
    verdict.prefersFreeTierModels = true;
    catalog.defaultModel = 'claude-haiku-4-5';

    renderModal();

    await waitFor(() => expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-haiku-4-5' }));
  });

  it('keeps the catalogue default when nothing at all is open to the free tier', async () => {
    // An admin who has opened no model leaves a free account with no covered
    // choice. Blanking the field would be worse than a model it must top up for.
    verdict.prefersFreeTierModels = true;
    catalog.models = [OPUS, MISTRAL];

    renderModal();

    await waitFor(() => expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-opus-5' }));
  });

  it('falls back to the catalogue default when the verdict never answers at all', async () => {
    // The verdict is NOT guaranteed to arrive: `hasAnswered` is "the payload
    // exists", and a balance request that exhausts its retries leaves it
    // undefined for good. Waiting forever would save an agent with no model
    // while the picker still displayed one, so the wait is bounded and giving up
    // means doing what this form did before the verdict existed.
    vi.useFakeTimers();
    verdict.prefersFreeTierModels = false;
    verdict.verdictReady = false;

    renderModal();
    expect(chosen()).toEqual({ provider: '', id: '' });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-opus-5' });
  });

  it('writes nothing until the plan verdict has actually answered', async () => {
    // THE regression. The verdict reads "not a free account" while the balance
    // request is in flight. Writing then pins the catalogue default, the fields
    // stop being empty, and the free-tier answer arriving a tick later never
    // applies - on an agent that is about to be saved with that model.
    verdict.prefersFreeTierModels = false;
    verdict.verdictReady = false;

    const view = renderModal();

    expect(chosen()).toEqual({ provider: '', id: '' });

    verdict.prefersFreeTierModels = true;
    verdict.verdictReady = true;
    view.rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} initialStep={2} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(chosen()).toEqual({ provider: 'anthropic', id: 'claude-haiku-4-5' }));
  });
});
