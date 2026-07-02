// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Infinite-scroll for the WORKFLOWS (and FILES) resource pickers in CreateAgentModal.
 *
 * The bug: workflows/files were fetched as a single capped page (size:100), so a tenant
 * with >100 workflows could not see/select all of them. They now use useInfiniteQuery +
 * a bottom IntersectionObserver sentinel (useLazyLoadObserver).
 *
 * These tests pin:
 *   1) getWorkflowsPage is called with page:0 + size:100 (paged, not the old getWorkflows),
 *   2) the workflows load-more SENTINEL renders when hasNextPage is true
 *      (totalCount 150 > size 100 ⇒ a second page exists),
 *   3) triggering the observer's onLoadMore fetches page 1.
 *
 * `useLazyLoadObserver` is mocked to (a) return a real ref so the sentinel mounts and
 * (b) capture each call's onLoadMore so the test can fire the "scrolled into view" event
 * deterministically (jsdom has no real IntersectionObserver).
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
vi.mock('@/components/ui/tooltip', () => ({
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipContent: () => null,
  TooltipProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TooltipTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// 100 workflows on page 0, totalCount 150 ⇒ a second page exists ⇒ hasNextPage true.
const { WF_PAGE_0, getWorkflowsPageMock } = vi.hoisted(() => {
  const page0 = Array.from({ length: 100 }, (_, i) => ({ id: `wf-${i}`, name: `Workflow ${i}` }));
  return {
    WF_PAGE_0: page0,
    getWorkflowsPageMock: vi.fn(),
  };
});

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }) },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getSkills: vi.fn().mockResolvedValue([]),
    getSkillFolders: vi.fn().mockResolvedValue([]),
    getAllSkillFolders: vi.fn().mockResolvedValue([]),
    getWorkflows: vi.fn().mockResolvedValue(WF_PAGE_0),
    getWorkflowsPage: getWorkflowsPageMock,
    getInterfaces: vi.fn().mockResolvedValue([]),
    getAgents: vi.fn().mockResolvedValue([]),
    getDataSources: vi.fn().mockResolvedValue([]),
    getAgentSkills: vi.fn().mockResolvedValue([]),
    getWidgetConfig: vi.fn().mockResolvedValue(null),
    createAgent: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
    updateAgent: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
    setAgentSkills: vi.fn().mockResolvedValue(undefined),
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
  },
}));
vi.mock('@/lib/api/orchestrator/schedule-settings.service', () => ({
  scheduleSettingsService: { getConfig: vi.fn().mockResolvedValue(null) },
}));

vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => false }) }));
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

// Capture every useLazyLoadObserver call so the test can drive onLoadMore deterministically.
// Each call returns a freshly-created real ref so the sentinel <div ref> mounts.
const { observerCalls } = vi.hoisted(() => ({
  observerCalls: [] as Array<{ enabled: boolean; hasMore: boolean; onLoadMore: () => void }>,
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: (params: { enabled: boolean; hasMore: boolean; onLoadMore: () => void }) => {
    observerCalls.push(params);
    return React.createRef<HTMLDivElement>();
  },
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

/** Minimal agent shape the modal reads (id + name + toolsConfig). */
type TestAgent = { id: string; name: string; toolsConfig: Record<string, unknown> };

function renderModal(agent?: TestAgent) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      {/* initialStep=2 lands on the Configuration step where the resource-access popover lives */}
      <CreateAgentModal onClose={() => {}} onAgentCreated={() => {}} initialStep={2} agent={agent} />
    </QueryClientProvider>,
  );
}

// workflows grant='custom' ⇒ the workflows id list (and its load-more sentinel) renders.
// A grant-less or 'none' family does not render its list, so the sentinel needs 'custom'.
const EDIT_AGENT: TestAgent = {
  id: 'a-scroll', name: 'A',
  toolsConfig: { mode: 'all', workflowsGrant: 'custom', workflows: ['wf-0'] },
};

async function openResourcePopover() {
  await screen.findByText('modals.createAgent.resourceAccessLabel');
  const btn = await screen.findByRole('button', { name: /modals\.createAgent\.(allResources|noResources|resourcesSelected|noResourcesAvailable)/ });
  fireEvent.click(btn);
}

describe('CreateAgentModal - workflows/files infinite scroll', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    observerCalls.length = 0;
    // Page 0: 100 workflows, totalCount 150 ⇒ getNextPageParam yields page 1.
    getWorkflowsPageMock.mockImplementation(({ page }: { page: number }) =>
      Promise.resolve({
        workflows: page === 0 ? WF_PAGE_0 : [{ id: 'wf-100', name: 'Workflow 100' }],
        count: 100,
        totalCount: 150,
        page,
        size: 100,
      }),
    );
  });
  afterEach(() => cleanup());

  it('loads workflows via getWorkflowsPage (paged) with page:0 + size:100', async () => {
    renderModal();
    await waitFor(() =>
      expect(getWorkflowsPageMock).toHaveBeenCalledWith({ page: 0, size: 100 }),
    );
  });

  it('renders the workflows load-more sentinel when a second page exists (totalCount > size)', async () => {
    renderModal(EDIT_AGENT);
    await openResourcePopover();
    // workflowsGrant='custom' ⇒ the workflows id list renders ⇒ its sentinel is present.
    await waitFor(() =>
      expect(screen.getByTestId('resource-cat-workflows-load-more')).toBeInTheDocument(),
    );
  });

  it('triggers getWorkflowsPage(page:1) when the workflows observer onLoadMore fires', async () => {
    renderModal(EDIT_AGENT);
    await openResourcePopover();
    await waitFor(() => expect(getWorkflowsPageMock).toHaveBeenCalledWith({ page: 0, size: 100 }));

    // The workflows observer is the only one with hasMore=true (its page-0 totalCount 150
    // > size 100 ⇒ a next page; the mcp + files observers report hasMore=false). Wait until
    // that observer has been created with a live next page, then drive its onLoadMore.
    await waitFor(() =>
      expect(observerCalls.some((c) => c.hasMore)).toBe(true),
    );
    // Use the most recent matching observer so its onLoadMore closes over the freshest query state.
    const wfObserver = [...observerCalls].reverse().find((c) => c.hasMore)!;

    // Simulate the sentinel scrolling into view.
    await act(async () => {
      wfObserver.onLoadMore();
    });

    await waitFor(() =>
      expect(getWorkflowsPageMock).toHaveBeenCalledWith({ page: 1, size: 100 }),
    );
  });
});
