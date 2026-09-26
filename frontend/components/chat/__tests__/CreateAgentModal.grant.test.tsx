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

// Workflows non-empty so the workflow family has rows to show under 'custom'.
// Hoisted so the (hoisted) vi.mock factory below can reference it safely.
// `createAgent` is captured here so the state→payload SEAM test can read back the
// exact toolsConfig the modal built and submitted.
const { WORKFLOWS, getWorkflowsPageMock, createAgentMock, updateAgentMock } = vi.hoisted(() => {
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
    updateAgentMock: vi.fn().mockResolvedValue({ id: 'created-agent-1' }),
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
    updateAgent: updateAgentMock,
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

/**
 * The long-term memory read/write axis.
 *
 * It sits outside the resource-access popover on purpose: memory has no id list
 * to scope, only this axis. Hydrating it matters more than for the families
 * above, because `buildToolsConfigPayload` rebuilds the whole tools_config from
 * the form's state: a mode the form fails to read back is not merely displayed
 * wrong, it is OVERWRITTEN on the next save, so a recall-only agent regains write
 * access because somebody opened it and pressed Update. And a memory write is not
 * scoped to the agent that made it - what one agent saves is injected into every
 * agent in the workspace.
 */
describe('CreateAgentModal - long-term memory read/write axis', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  const submit = async () => {
    // The control lives on step 2; the save button is on step 3.
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: /Update Agent|Create Agent/ }));
  };

  const emittedToolsConfig = () =>
    (updateAgentMock.mock.calls[0][1] as { toolsConfig?: Record<string, unknown> }).toolsConfig ?? {};

  it('hydrates the control from a stored recall-only mode instead of the write default', async () => {
    renderModal({ id: 'a-mem-r', name: 'A', toolsConfig: { mode: 'all', memoryAccessMode: 'read' } });

    await waitFor(() => {
      expect(screen.getByText('modals.createAgent.memoryAccessRead')).toBeInTheDocument();
    });
    expect(screen.queryByText('modals.createAgent.memoryAccessWrite')).not.toBeInTheDocument();
  });

  it('shows full write for an agent saved before the mode existed, matching the backend default', async () => {
    // Absent is not "restricted": the backend allows the write when no mode is set,
    // so showing recall-only here would report a restriction nothing is enforcing.
    renderModal({ id: 'a-mem-w', name: 'A', toolsConfig: { mode: 'all' } });

    await waitFor(() => {
      expect(screen.getByText('modals.createAgent.memoryAccessWrite')).toBeInTheDocument();
    });
  });

  it('carries the hydrated mode back into the update payload, rather than resetting it', async () => {
    renderModal({ id: 'a-mem-rt', name: 'A', toolsConfig: { mode: 'all', memoryAccessMode: 'read' } });
    await screen.findByText('modals.createAgent.memoryAccessRead');

    await submit();

    // The round trip is the point: opening an agent and saving it without touching
    // this control has to leave it exactly as it was.
    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(emittedToolsConfig().memoryAccessMode).toBe('read');
  });

  it('emits the flipped mode once the control is toggled', async () => {
    renderModal({ id: 'a-mem-t', name: 'A', toolsConfig: { mode: 'all', memoryAccessMode: 'read' } });
    fireEvent.click(await screen.findByText('modals.createAgent.memoryAccessRead'));
    await screen.findByText('modals.createAgent.memoryAccessWrite');

    await submit();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(emittedToolsConfig().memoryAccessMode).toBe('write');
  });
});

/**
 * The hop between the modal's state and the payload builder.
 *
 * `toolsConfigAccess.mailbox.test.ts` proves the BUILDER emits both keys; this proves the
 * modal hands them over, which is the half the original break lived in. Both are needed:
 * the builder held the fields and assigned neither, and nothing failed, because
 * `ToolsConfigShape` carries an index signature that makes an unassigned optional legal.
 */
describe('CreateAgentModal - mailbox grant and access mode', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  const submit = async () => {
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    fireEvent.click(await screen.findByRole('button', { name: /Update Agent|Create Agent/ }));
  };

  const emittedToolsConfig = () =>
    (updateAgentMock.mock.calls[0][1] as { toolsConfig?: Record<string, unknown> }).toolsConfig ?? {};

  /** The mailbox controls live in the collapsed Advanced section, so open it first. */
  const openAdvanced = async () => {
    fireEvent.click(await screen.findByText('modals.createAgent.advancedModeLabel'));
  };

  it('hides the permissions control until the tool is switched on', async () => {
    renderModal({ id: 'a-mbx-off', name: 'A', toolsConfig: { mode: 'all' } });
    await openAdvanced();

    await waitFor(() => {
      expect(screen.getByText('chatConfig.mailboxLabel')).toBeInTheDocument();
    });
    // The axis means nothing while the tool is off, and showing it suggests the agent has
    // a mailbox it does not have.
    expect(screen.queryByText('chatConfig.mailboxAccessLabel')).not.toBeInTheDocument();
  });

  it('hydrates both halves from a stored read-only mailbox agent', async () => {
    renderModal({
      id: 'a-mbx-r', name: 'A',
      toolsConfig: { mode: 'all', mailbox: true, mailboxAccessMode: 'read' },
    });
    await openAdvanced();

    await waitFor(() => {
      expect(screen.getByText('chatConfig.mailboxAccessRead')).toBeInTheDocument();
    });
    expect(screen.queryByText('chatConfig.mailboxAccessWrite')).not.toBeInTheDocument();
  });

  it('carries the grant and the mode back into the update payload untouched', async () => {
    renderModal({
      id: 'a-mbx-rt', name: 'A',
      toolsConfig: { mode: 'all', mailbox: true, mailboxAccessMode: 'read' },
    });
    await openAdvanced();
    await screen.findByText('chatConfig.mailboxAccessRead');

    await submit();

    // The round trip is the whole point: opening an agent and saving it without touching
    // either control must not revoke its mailbox nor widen it to full access.
    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    expect(emittedToolsConfig().mailbox).toBe(true);
    expect(emittedToolsConfig().mailboxAccessMode).toBe('read');
  });

  it('explains the mailbox and its access mode through the shared click "i", one per field', async () => {
    renderModal({ id: 'a-mbx-info', name: 'A', toolsConfig: { mode: 'all', mailbox: true } });
    await openAdvanced();
    await screen.findByText('chatConfig.mailboxAccessLabel');

    // Each field names its own "i" and carries its own explanation: a copy-paste slip that
    // wires the access-mode text to the mailbox field (or drops one) fails here.
    const info = (label: string) => document.querySelector(`[data-info-label="${label}"]`);
    expect(info('chatConfig.mailboxLabel')?.getAttribute('data-info-text')).toBe('chatConfig.mailboxInfo');
    expect(info('chatConfig.mailboxAccessLabel')?.getAttribute('data-info-text')).toBe('chatConfig.mailboxAccessInfo');
  });

  it('emits an explicit false when the tool is switched off, since the backend merges', async () => {
    renderModal({ id: 'a-mbx-off2', name: 'A', toolsConfig: { mode: 'all', mailbox: true } });
    await openAdvanced();
    // Scoped to the mailbox block: 'enabled' is the state word every opt-in toggle shows,
    // so an unscoped query matches the generation switch sitting right above it.
    const block = (await screen.findByText('chatConfig.mailboxLabel')).closest('div')!;
    fireEvent.click(within(block).getByRole('button'));
    await within(block).findByText('modals.createAgent.disabled');

    await submit();

    await waitFor(() => expect(updateAgentMock).toHaveBeenCalledTimes(1));
    // Omitting it would let the merge keep the previous value, so a revoked mailbox would
    // stay granted: the one direction where a dropped key hands out access.
    expect(emittedToolsConfig().mailbox).toBe(false);
  });
});
