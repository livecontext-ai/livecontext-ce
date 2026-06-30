// @vitest-environment jsdom
//
// Perf regression guard for the global Fleet (?view=fleet) loading gate.
//
// fetchAll splits into two waves:
//   1. FAST PATH (gate)  - the STRUCTURAL data that decides which nodes/edges exist:
//      getAgents + getAllAgentSkills + getAllSkillFolders. Resource chip nodes already
//      exist from this wave (their counts come from toolsConfig), so the gate clears as
//      soon as these three land and the canvas paints.
//   2. BACKGROUND        - pure DATA enrichment that never gates the first paint: the 4
//      resource-list GETs (getWorkflows/getInterfaces/getDataSources/fetchApis) +
//      resolveFleetResourceLookups (names/icons, drops gone grants), then getFleetStats
//      (badge counts). The node-building effect lists these maps in its deps, so it
//      re-runs and enriches the already-rendered nodes when they arrive.
//
// What THIS change moved off the gate: the 4 resource-list GETs + resolveFleetResourceLookups
// + getAllAgentSkills. Pre-fix isLoading waited on the slowest of those, so a large workspace
// sat on a multi-second loading gate. (getFleetStats was ALREADY a detached background IIFE
// before this change; the two getFleetStats tests below are retained regression guards for
// that earlier optimization, not for this one.)
//
// These tests pin: isLoading MUST become false while the background resource lists are still
// pending; the gate runs strictly before the background; a background failure neither blocks
// the gate nor drops the structural nodes; and the deferred wave actually patches resolved
// names onto the chips the gate rendered with fallback labels. Mirrors
// useSingleAgentFleet.loading.test.tsx.
import '@testing-library/jest-dom/vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const getAgents = vi.fn();
const getFleetTriggers = vi.fn();
const getFleetStats = vi.fn();
const getAllAgentSkills = vi.fn();
const getAllSkillFolders = vi.fn();
const getWorkflows = vi.fn();
const getWorkflow = vi.fn();
const getInterfaces = vi.fn();
const getDataSources = vi.fn();
const fetchApisSpy = vi.fn();
const apiClientGet = vi.fn(); // backs the /workflow-inspector/tools/by-ids legacy-UUID resolve

// The hook calls each of these with no arguments (bar getWorkflows, which takes the page
// opts), so thin forwarders keep the spies wired without an `any`-typed rest param.
vi.mock('@/lib/api/orchestrator', () => ({
  agentService: {
    getAgents: () => getAgents(),
    getFleetTriggers: () => getFleetTriggers(),
    getFleetStats: () => getFleetStats(),
  },
  skillService: {
    getAllAgentSkills: () => getAllAgentSkills(),
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflows: (opts: unknown) => getWorkflows(opts),
    getWorkflow: (id: string) => getWorkflow(id),
    getInterfaces: () => getInterfaces(),
    getDataSources: () => getDataSources(),
    getAllSkillFolders: () => getAllSkillFolders(),
  },
}));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  fetchApis: (arg: unknown) => fetchApisSpy(arg),
}));
// ApiError is defined INSIDE the factory (not a top-level ref) to avoid vitest hoisting
// TDZ. resolveFleetResourceLookups detects gone grants via `err instanceof ApiError &&
// status === 404`, so the same class must back both the rejection thrown below and that
// check - importing ApiError from this mocked path yields exactly this class.
vi.mock('@/lib/api/api-client', () => {
  // Constructor signature mirrors the real ApiError(message, status, ...) so the test's
  // `new ApiError('gone', 404)` typechecks against the real module's declaration.
  class ApiError extends Error {
    status: number;
    constructor(message: string, status: number) {
      super(message);
      this.status = status;
    }
  }
  return { apiClient: { get: (path: string, opts?: unknown) => apiClientGet(path, opts) }, ApiError };
});
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: vi.fn() }));

import { useAgentFleetState } from '../useAgentFleetState';
import { ApiError } from '@/lib/api/api-client';

// A mode:'all' agent grants no specific workflow/interface/table, so
// resolveFleetResourceLookups issues no per-id GETs - the background wave is just the
// list calls. Keeps the per-id lookup path out of these gate assertions.
const AGENT = { id: 'a1', name: 'Agent One', toolsConfig: { mode: 'all' } };
const EMPTY_STATS = { toolStats: [], resourceStats: [], subAgentStats: [], modelStats: [] };

/** Wire every background spy to a resolved empty value (overridden per-test as needed). */
function primeBackgroundResolved() {
  getFleetTriggers.mockResolvedValue([]);
  getFleetStats.mockResolvedValue(EMPTY_STATS);
  getWorkflows.mockResolvedValue([]);
  getInterfaces.mockResolvedValue([]);
  getDataSources.mockResolvedValue([]);
  fetchApisSpy.mockResolvedValue({ content: [] });
  apiClientGet.mockResolvedValue([]); // no legacy UUIDs to resolve by default
}

describe('useAgentFleetState - structural gate, resource lookups + stats off the gate', () => {
  afterEach(() => vi.clearAllMocks());

  it('clears isLoading from agents + skills + folders alone, without waiting for the resource-list GETs', async () => {
    getAgents.mockResolvedValue([AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    // The first background resource list (getWorkflows) stays pending for the whole window.
    let resolveWorkflows!: (v: unknown) => void;
    getWorkflows.mockReturnValue(new Promise((res) => { resolveWorkflows = res; }));

    const { result } = renderHook(() => useAgentFleetState());
    expect(result.current.isLoading).toBe(true);

    // The gate clears even though the background resource lists have NOT resolved.
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // The agent node is built from the structural wave alone - no resource lists needed.
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === 'agent-a1')).toBe(true));

    expect(getAllAgentSkills).toHaveBeenCalledTimes(1); // structural - on the gate
    expect(getAllSkillFolders).toHaveBeenCalledTimes(1); // structural - on the gate
    expect(getWorkflows).toHaveBeenCalledTimes(1);       // enrichment - kicked off after the gate
    // getWorkflows runs only AFTER the structural skills/folders load (off the blocking path).
    expect(getAllAgentSkills.mock.invocationCallOrder[0])
      .toBeLessThan(getWorkflows.mock.invocationCallOrder[0]);

    resolveWorkflows([]); // drain the background promise so no dangling work
    await waitFor(() => expect(result.current.isLoading).toBe(false));
  });

  it('clears isLoading without waiting for getFleetStats (stats load in background)', async () => {
    getAgents.mockResolvedValue([]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    // getFleetStats stays pending for the whole assertion window.
    let resolveStats!: (v: unknown) => void;
    getFleetStats.mockReturnValue(new Promise((res) => { resolveStats = res; }));

    const { result } = renderHook(() => useAgentFleetState());
    expect(result.current.isLoading).toBe(true);

    // isLoading flips false even though getFleetStats has NOT resolved.
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(getAllAgentSkills).toHaveBeenCalledTimes(1); // skills on the gate (node content)
    expect(getFleetStats).toHaveBeenCalledTimes(1);     // stats kicked off (background)
    // skills resolve before stats are launched (gate-then-background ordering).
    expect(getAllAgentSkills.mock.invocationCallOrder[0])
      .toBeLessThan(getFleetStats.mock.invocationCallOrder[0]);

    resolveStats(EMPTY_STATS); // drain the background promise so no dangling work
    await waitFor(() => expect(result.current.isLoading).toBe(false));
  });

  it('still clears isLoading and keeps the agent node when a background resource list rejects', async () => {
    getAgents.mockResolvedValue([AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    getWorkflows.mockRejectedValue(new Error('workflows backend down'));

    const { result } = renderHook(() => useAgentFleetState());

    // A failing background enrichment fetch must not block the gate or drop the node
    // already built from the structural wave.
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === 'agent-a1')).toBe(true));
    expect(getWorkflows).toHaveBeenCalledTimes(1);
  });

  it('still clears isLoading when the background getFleetStats rejects', async () => {
    getAgents.mockResolvedValue([]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    getFleetStats.mockRejectedValue(new Error('stats backend down'));

    const { result } = renderHook(() => useAgentFleetState());

    // A failing background stats fetch must not block (or un-block) the canvas.
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(getFleetStats).toHaveBeenCalledTimes(1);
  });

  it('builds the fleet from the structural gate commit (non-empty agents → nodes populated)', async () => {
    // The fast-path wave commits agents + skills + folders in one batch, feeding the
    // node-building effect. This pins that the gate commit actually builds the fleet
    // (the no-legacy-UUID path: a mode:all agent has no UUID tools).
    getAgents.mockResolvedValue([AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();

    const { result } = renderHook(() => useAgentFleetState());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    // The agent node exists → the gate structural state reached the build effect.
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === 'agent-a1')).toBe(true));
  });

  it('patches a granted resource chip from its fallback id-label (gate) to the resolved name (background)', async () => {
    // This is the core of the change: the gate paints the workflow chip with a fallback
    // label (id slice) because the name maps are empty, then the BACKGROUND wave resolves
    // the workflow list and the node-building effect re-runs to upgrade the label - same
    // node id, converging to the final graph. A long id makes the fallback distinct.
    const WF_ID = 'wfid-abcdef-1234';
    const CUSTOM_AGENT = {
      id: 'a1',
      name: 'Agent One',
      toolsConfig: { mode: 'custom', tools: [], agents: [], workflows: [WF_ID], interfaces: [], tables: [], files: [] },
    };
    getAgents.mockResolvedValue([CUSTOM_AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    // Hold the workflow LIST pending so the fallback label is observable before it resolves.
    let resolveWf!: (v: unknown) => void;
    getWorkflows.mockReturnValue(new Promise((res) => { resolveWf = res; }));

    const { result } = renderHook(() => useAgentFleetState());
    const wfNodeId = `res-a1-workflow-${WF_ID}`;

    // Gate: the chip exists with the FALLBACK label (id slice), background still pending.
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await waitFor(() => {
      const n = result.current.nodes.find((x) => x.id === wfNodeId);
      expect(n).toBeTruthy();
      expect((n!.data as { label: string }).label).toBe(WF_ID.slice(0, 8));
    });

    // Background resolves the workflow list (the grant is in it → no per-id lookup needed) →
    // the chip label upgrades to the resolved name on the SAME node id.
    resolveWf([{ id: WF_ID, name: 'My Workflow' }]);
    await waitFor(() => {
      const n = result.current.nodes.find((x) => x.id === wfNodeId);
      expect((n!.data as { label: string }).label).toBe('My Workflow');
    });
  });

  it('drops a confirmed-gone grant chip on the background wave (ghost visible on the gate, then removed)', async () => {
    // The structure-changing minority case the enrichment comment documents: a grant whose
    // entity 404s is built as a ghost chip on the gate (missingResourceIds still empty), then
    // resolveFleetResourceLookups marks it gone and the effect drops it - a self-healing
    // transient. Pins that the chip is visible first, then disappears (not lost from the start).
    const GONE_ID = 'wf-gone-99887766';
    const CUSTOM_AGENT = {
      id: 'a1',
      name: 'Agent One',
      toolsConfig: { mode: 'custom', tools: [], agents: [], workflows: [GONE_ID], interfaces: [], tables: [], files: [] },
    };
    getAgents.mockResolvedValue([CUSTOM_AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    // The grant is NOT in the (empty) workflow list, so resolveFleetResourceLookups does a
    // per-id GET; a 404 marks it gone. Hold the list pending to observe the ghost first.
    let resolveWf!: (v: unknown) => void;
    getWorkflows.mockReturnValue(new Promise((res) => { resolveWf = res; }));
    getWorkflow.mockRejectedValue(new ApiError('gone', 404));

    const { result } = renderHook(() => useAgentFleetState());
    const ghostNodeId = `res-a1-workflow-${GONE_ID}`;

    // Gate: the ghost chip is present (missingResourceIds is empty until enrichment runs).
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await waitFor(() =>
      expect(result.current.nodes.some((n) => n.id === ghostNodeId)).toBe(true));

    // Background resolves → per-id GET 404s → grant marked gone → chip dropped.
    resolveWf([]);
    await waitFor(() =>
      expect(result.current.nodes.some((n) => n.id === ghostNodeId)).toBe(false));
    expect(getWorkflow).toHaveBeenCalledWith(GONE_ID);
  });

  it('keeps a legacy-UUID tool ugly on the gate, then regroups it once tools/by-ids resolves', async () => {
    // The OTHER documented structure-changing minority case: a raw api_tools.id UUID in
    // tools_config.tools[] renders as an ugly UUID-id chip on the gate (toolUuidMap empty),
    // then the background tools/by-ids resolve maps it to apiSlug:toolSlug - the chip's node
    // id changes, so the effect rebuilds it under the resolved id (a correct relayout).
    const UUID = '0123abcd-4567-89ab-cdef-0123456789ab';
    const CUSTOM_AGENT = {
      id: 'a1',
      name: 'Agent One',
      toolsConfig: { mode: 'custom', tools: [UUID], agents: [], workflows: [], interfaces: [], tables: [], files: [] },
    };
    getAgents.mockResolvedValue([CUSTOM_AGENT]);
    getAllAgentSkills.mockResolvedValue([]);
    getAllSkillFolders.mockResolvedValue([]);
    primeBackgroundResolved();
    // Hold the workflow list pending so the gate's raw-UUID node is observable before the
    // legacy-UUID resolve (which runs after the background Promise.all) lands.
    let resolveWf!: (v: unknown) => void;
    getWorkflows.mockReturnValue(new Promise((res) => { resolveWf = res; }));
    apiClientGet.mockResolvedValue([{ toolId: UUID, apiSlug: 'github', slug: 'create-issue', iconSlug: 'github' }]);

    const { result } = renderHook(() => useAgentFleetState());
    const rawNodeId = `res-a1-tool-${UUID}`;
    const resolvedNodeId = 'res-a1-tool-github:create-issue';

    // Gate: the tool renders with its raw UUID id (toolUuidMap empty until enrichment).
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === rawNodeId)).toBe(true));

    // Background resolves → toolUuidMap maps the UUID → the chip rebuilds under the resolved id.
    resolveWf([]);
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === resolvedNodeId)).toBe(true));
    expect(result.current.nodes.some((n) => n.id === rawNodeId)).toBe(false);
  });

  it('does not fetch anything when skip is set', async () => {
    renderHook(() => useAgentFleetState({ skip: true }));
    // give any stray effect a tick
    await Promise.resolve();
    expect(getAgents).not.toHaveBeenCalled();
    expect(getAllAgentSkills).not.toHaveBeenCalled();
    expect(getFleetStats).not.toHaveBeenCalled();
  });
});
