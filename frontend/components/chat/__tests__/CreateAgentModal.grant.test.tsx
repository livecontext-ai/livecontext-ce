// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pins the per-family GRANT selector (None/All/Custom) and the DECOUPLED R/W pill
 * in the CreateAgentModal resource-access popover:
 *   - a 3-state grant selector renders per grant family,
 *   - the custom id list shows ONLY when grant==='custom',
 *   - the R/W pill renders whenever grant!=='none' (NOT gated on selectedCount>0),
 *     so "All + read/write" is expressible - the bug the decoupling fixes.
 *
 * next-intl is stubbed to echo `${ns}.${key}`, so e.g. the grant labels render as
 * `modals.createAgent.grant_none` / `…grant_all` / `…grant_custom`.
 */

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (key: string) => `${ns}.${key}`,
}));
vi.mock('next/image', () => ({ default: () => null }));

// jsdom lacks ResizeObserver (Radix needs it). Polyfill before any component mounts.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
const g = globalThis as unknown as { ResizeObserver?: typeof ResizeObserverStub };
g.ResizeObserver = g.ResizeObserver || ResizeObserverStub;

// Render the Radix Popover content INLINE (open-state controlled by the modal's own
// `open` prop) so the real grant UI inside renderResourceCategory is under test
// without jsdom portal/pointer-capture flakiness.
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ open, children }: { open?: boolean; children: React.ReactNode }) => (
    <div data-popover-open={open ? 'true' : 'false'}>{children}</div>
  ),
  PopoverTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PopoverContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
// Tooltip is Radix too - render it inert.
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

// Workflows non-empty so the workflow family has rows to show under 'custom'.
// Hoisted so the (hoisted) vi.mock factory below can reference it safely.
// `createAgent` is captured here so the state→payload SEAM test can read back the
// exact toolsConfig the modal built and submitted.
const { WORKFLOWS, getWorkflowsPageMock, createAgentMock } = vi.hoisted(() => {
  const WORKFLOWS = [{ id: 'wf-1', name: 'Daily Report' }];
  return {
    WORKFLOWS,
    // Workflows are now fetched via useInfiniteQuery → getWorkflowsPage. Single page,
    // totalCount === count ⇒ no further pages (hasNextPage false).
    getWorkflowsPageMock: vi.fn().mockResolvedValue({
      workflows: WORKFLOWS,
      count: WORKFLOWS.length,
      totalCount: WORKFLOWS.length,
      page: 0,
      size: 100,
    }),
    createAgentMock: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
  };
});

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getSkills: vi.fn().mockResolvedValue([]),
    getSkillFolders: vi.fn().mockResolvedValue([]),
    getAllSkillFolders: vi.fn().mockResolvedValue([]),
    getWorkflows: vi.fn().mockResolvedValue(WORKFLOWS),
    getWorkflowsPage: getWorkflowsPageMock,
    getInterfaces: vi.fn().mockResolvedValue([]),
    getAgents: vi.fn().mockResolvedValue([]),
    getDataSources: vi.fn().mockResolvedValue([]),
    getAgentSkills: vi.fn().mockResolvedValue([]),
    getWidgetConfig: vi.fn().mockResolvedValue(null),
    // Submit path (CREATE): handleSave → createAgent(payload) → setAgentSkills(id, []).
    createAgent: createAgentMock,
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

/** Minimal agent shape the modal reads in these tests (id + name + toolsConfig). */
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

// Open the unified resource-access popover. Its trigger button carries the summary
// label (`allResources` / `noResources` / `resourcesSelected` / `noResourcesAvailable`).
async function openResourcePopover() {
  await screen.findByText('modals.createAgent.resourceAccessLabel');
  const btn = await screen.findByRole('button', { name: /modals\.createAgent\.(allResources|noResources|resourcesSelected|noResourcesAvailable)/ });
  fireEvent.click(btn);
}

describe('CreateAgentModal - per-family grant selector + decoupled R/W pill', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('renders a 3-state None/All/Custom grant selector for grant families', async () => {
    renderModal({ id: 'a-1', name: 'A', toolsConfig: { mode: 'all' } });
    await openResourcePopover();

    // Each grant family contributes a None/All/Custom radio trio. With 5 families
    // (workflows/tables/interfaces/agents/applications) there are 5 of each label.
    await waitFor(() => {
      expect(screen.getAllByText('modals.createAgent.grant_none').length).toBeGreaterThanOrEqual(5);
    });
    expect(screen.getAllByText('modals.createAgent.grant_all').length).toBeGreaterThanOrEqual(5);
    expect(screen.getAllByText('modals.createAgent.grant_custom').length).toBeGreaterThanOrEqual(5);

    // The radios expose aria-checked - the hydrated grant for a mode:'all'-only
    // toolsConfig (no per-family grants) is 'none' for every family.
    const noneRadios = screen.getAllByRole('radio', { name: 'modals.createAgent.grant_none' });
    expect(noneRadios.length).toBeGreaterThanOrEqual(5);
    for (const r of noneRadios) expect(r).toHaveAttribute('aria-checked', 'true');
  });

  it('shows the R/W pill for an "all"-granted family WITHOUT any selected ids (decoupled from count)', async () => {
    // workflowsGrant='all' but NO workflow ids selected - pre-fix the pill was gated
    // on selectedCount>0 and would NOT render; post-fix it renders on grant!=='none'.
    renderModal({ id: 'a-2', name: 'A', toolsConfig: { mode: 'all', workflowsGrant: 'all', workflows: [] } });
    await openResourcePopover();

    // R/W default is 'write' → the pill reads `accessModeReadWrite`. At least the
    // workflows family (granted 'all') shows it even with 0 selected ids.
    await waitFor(() => {
      expect(screen.getAllByText('modals.createAgent.accessModeReadWrite').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('does NOT show the R/W pill for a "none"-granted family (the only pill is the grant-less files one)', async () => {
    // Every GRANT family hydrates to 'none' (mode-only toolsConfig) → no grant-family pill.
    renderModal({ id: 'a-3', name: 'A', toolsConfig: { mode: 'all' } });
    await openResourcePopover();

    await waitFor(() => {
      expect(screen.getAllByText('modals.createAgent.grant_none').length).toBeGreaterThanOrEqual(5);
    });
    // No grant family shows a pill. Files have NO grant and are always accessible, so they
    // ALWAYS show their own R/W pill (default 'write' = ReadWrite) - so exactly ONE ReadWrite
    // pill (the files one) and zero Read pills.
    expect(screen.getAllByText('modals.createAgent.accessModeReadWrite')).toHaveLength(1);
    expect(screen.queryByText('modals.createAgent.accessModeRead')).not.toBeInTheDocument();
  });

  it('renders the custom id list ONLY when a family grant is "custom"', async () => {
    renderModal({ id: 'a-4', name: 'A', toolsConfig: { mode: 'all', workflowsGrant: 'custom', workflows: ['wf-1'] } });
    await openResourcePopover();

    // workflows grant='custom' → its id list renders (the seeded workflow name shows).
    await waitFor(() => {
      expect(screen.getByText('Daily Report')).toBeInTheDocument();
    });
  });

  it('hydrates the R/W pill from the agent on EDIT (workflowAccessMode="read" → Read, not the write default)', async () => {
    // Regression for "R/W not mapped on update": the access modes default to 'write' but
    // must be overridden by the agent's persisted mode. workflowsGrant='all' renders the
    // pill; workflowAccessMode='read' must make it read 'accessModeRead', not 'ReadWrite'.
    renderModal({ id: 'a-rw', name: 'A', toolsConfig: { mode: 'all', workflowsGrant: 'all', workflows: [], workflowAccessMode: 'read' } });
    await openResourcePopover();

    await waitFor(() => {
      expect(screen.getAllByText('modals.createAgent.accessModeRead').length).toBeGreaterThanOrEqual(1);
    });
    // The granted-read workflows family shows Read (not a stale 'write' default). Files always
    // show their own pill (default 'write' = ReadWrite), so exactly ONE ReadWrite remains - the
    // grant-less files pill, NOT a stray workflows ReadWrite (a regression would make it 2).
    expect(screen.getAllByText('modals.createAgent.accessModeReadWrite')).toHaveLength(1);
  });

  it('EDIT default: granted families are expanded, "none" families are collapsed', async () => {
    // workflows='custom' (granted) → expanded → its id list ("Daily Report") shows by default
    // WITHOUT the user opening it. Every other family is 'none' → collapsed (chevron-right).
    renderModal({
      id: 'a-exp', name: 'A',
      toolsConfig: { mode: 'all', workflowsGrant: 'custom', workflows: ['wf-1'] },
    });
    await openResourcePopover();

    // Granted custom family auto-expanded → list visible with no manual toggle.
    await waitFor(() => expect(screen.getByText('Daily Report')).toBeInTheDocument());

    // workflows (custom = granted) is expanded; the 'none' families are collapsed.
    // (The modal renders via a portal, so query document-wide, not the render container.)
    expect(screen.getByTestId('resource-cat-workflows-expanded')).toBeInTheDocument();
    expect(screen.getByTestId('resource-cat-tables-collapsed')).toBeInTheDocument();
    expect(screen.getByTestId('resource-cat-interfaces-collapsed')).toBeInTheDocument();
    // Exactly one expanded (workflows); the 5 'none' families collapsed
    // (applications/tables/interfaces/agents/files).
    expect(document.querySelectorAll('[data-testid$="-expanded"]').length).toBe(1);
    expect(document.querySelectorAll('[data-testid$="-collapsed"]').length).toBe(5);
  });

  it('hides the custom id list when the family grant is switched away from "custom"', async () => {
    // Start at 'custom' (workflow row visible), then switch every family to "None"
    // → the workflow row (only rendered under 'custom') disappears.
    renderModal({ id: 'a-5', name: 'A', toolsConfig: { mode: 'all', workflowsGrant: 'custom', workflows: ['wf-1'] } });
    await openResourcePopover();
    await waitFor(() => expect(screen.getByText('Daily Report')).toBeInTheDocument());

    const noneRadios = screen.getAllByRole('radio', { name: 'modals.createAgent.grant_none' });
    for (const r of noneRadios) fireEvent.click(r);

    await waitFor(() => expect(screen.queryByText('Daily Report')).not.toBeInTheDocument());
  });

  // GAP 1 - CREATE-mode expand-all default. On CREATE (no agent / no toolsConfig) the
  // init seed expands ALL 6 resource categories so the user picks from scratch; the
  // grant-aware EDIT default (only granted families expanded) must NOT apply. The
  // existing suite only covered the EDIT default - this pins the CREATE branch.
  it('CREATE default: ALL 6 resource categories are expanded (no grant-aware collapse)', async () => {
    // No `agent` prop ⇒ CREATE mode ⇒ expandedResourceCategories seeds to all 6.
    renderModal();
    await openResourcePopover();

    // The 6 categories: workflows / applications / tables / interfaces / agents / files.
    // (Portal render - query document-wide, not the render container.)
    await waitFor(() =>
      expect(document.querySelectorAll('[data-testid$="-expanded"]').length).toBe(6),
    );
    expect(document.querySelectorAll('[data-testid$="-collapsed"]').length).toBe(0);
  });

  // GAP 2 - R/W hydration on EDIT for MORE THAN ONE family/mode. The "restore access
  // modes" effect must hydrate every family's *AccessMode from the agent, not just
  // workflows. tables→read AND interfaces→write, each granted 'all', must surface
  // BOTH an `accessModeRead` pill (tables) and an `accessModeReadWrite` pill (interfaces).
  it('EDIT hydration: restores access modes for MULTIPLE families (tables=read + interfaces=write)', async () => {
    renderModal({
      id: 'a-multi', name: 'A',
      toolsConfig: {
        mode: 'all',
        tablesGrant: 'all', tableAccessMode: 'read',
        interfacesGrant: 'all', interfaceAccessMode: 'write',
      },
    });
    await openResourcePopover();

    // tables granted 'all' with mode 'read' → a Read pill; interfaces granted 'all'
    // with mode 'write' → a Read/Write pill. Both must be present → the restore effect
    // hydrated more than one family. (Pre-fix single-family restore would miss one.)
    await waitFor(() => {
      expect(screen.getAllByText('modals.createAgent.accessModeRead').length).toBeGreaterThanOrEqual(1);
    });
    expect(screen.getAllByText('modals.createAgent.accessModeReadWrite').length).toBeGreaterThanOrEqual(1);
  });

  // GAP 3 - the state→payload SEAM (highest value). Switching a grant in the modal must
  // change the EMITTED payload. CREATE flow: open popover (step 2) → click the workflows
  // family's "All" radio → fill the required name (step 1) → advance to step 3 → click
  // "Create Agent" → assert orchestratorApi.createAgent was called with a toolsConfig
  // carrying workflowsGrant:'all' (and the empty placeholder list for an 'all' grant).
  it('SEAM: switching the workflows grant to "All" emits workflowsGrant:"all" in the create payload', async () => {
    createAgentMock.mockClear();
    createAgentMock.mockResolvedValue({ id: 'created-agent-1' });
    renderModal(); // CREATE mode
    await openResourcePopover();

    // Workflows hydrate to 'none' on CREATE → flip ONLY the workflows family to 'All'.
    // Scope to the workflows category container (the grant radiogroup is a descendant
    // of its `resource-cat-workflows-*` header) - categories render in a fixed order
    // (agents/applications/workflows/…), so a positional index would target the wrong
    // family; the data-testid is the stable anchor.
    const workflowsCat = screen.getByTestId('resource-cat-workflows-expanded');
    const workflowsAll = within(workflowsCat).getByRole('radio', { name: 'modals.createAgent.grant_all' });
    fireEvent.click(workflowsAll); // workflows = 'all'
    await waitFor(() => expect(workflowsAll).toHaveAttribute('aria-checked', 'true'));

    // Name lives on step 1 - go Back, fill it, then advance to the final step (3).
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    const nameInput = await screen.findByPlaceholderText('modals.createAgent.namePlaceholder');
    fireEvent.change(nameInput, { target: { value: 'Seam Agent' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Next' })); // → step 2
    fireEvent.click(await screen.findByRole('button', { name: 'Next' })); // → step 3

    // Submit. The grant state set on step 2 persists (component state, not unmounted).
    fireEvent.click(await screen.findByRole('button', { name: /Create Agent/ }));

    await waitFor(() => expect(createAgentMock).toHaveBeenCalledTimes(1));
    const payload = createAgentMock.mock.calls[0][0] as { toolsConfig: Record<string, unknown> };
    expect(payload.toolsConfig.workflowsGrant).toBe('all');
    // 'all' grant emits the placeholder empty list (the grant is the source of truth).
    expect(payload.toolsConfig.workflows).toEqual([]);
    // Untouched families stay denied - proves the switch was scoped to workflows only.
    expect(payload.toolsConfig.tablesGrant).toBe('none');
  });
});
