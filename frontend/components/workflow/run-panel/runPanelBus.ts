'use client';

/**
 * Cross-tree bridge for the side-panel Run tab.
 *
 * The run state (run info, streamed steps, epochs, pinned version) is owned by
 * `WorkflowRunCanvas`, which lives in the page tree. The Run tab lives in the
 * SidePanel, mounted from the app layout - a different React tree with no common
 * provider. Same situation as the trigger / application configs, so we reuse the
 * exact same pattern those already use: a window CustomEvent plus a module-level
 * per-surface cache so a panel that mounts late (or remounts when the user
 * reopens it) immediately has its own latest snapshot instead of an empty one.
 *
 * Actions travel the other way through {@link requestRunAction}: the panel cannot
 * call the canvas' stop/cancel/reactivate handlers directly, so it names the
 * action and the canvas performs it.
 */

import type { EpochTimestamp, StepEntry } from './runFormatting';

export const RUN_PANEL_DATA_EVENT = 'workflowPanelRunDataChange' as const;
export const RUN_PANEL_ACTION_EVENT = 'workflowRunAction' as const;
/** Focus the Run tab of the workflow/application panel (and open the panel). */
export const OPEN_RUN_PANEL_EVENT = 'workflowOpenRunPanel' as const;
/** Focus the Add Node tab of the workflow panel (and open the panel). */
export const OPEN_NODE_CREATOR_EVENT = 'workflowOpenNodeCreator' as const;
/** Bind the canvas to another run of the same workflow, IN PLACE (no navigation). */
export const BIND_RUN_EVENT = 'workflowBindRun' as const;

export interface RunPanelData {
  workflowId: string;
  /**
   * Identity of the canvas/panel pair publishing this snapshot. Omitted for the
   * route-owned workflow page. A workflow can be mounted in several keep-alive
   * side-panel tabs at once, so workflowId alone cannot identify its run.
   */
  surfaceId?: string;
  /** Run currently bound to the canvas (null in edit mode). */
  runId: string | null;
  runInfo: any | null;
  isStepByStep: boolean;
  /**
   * The engine's epoch CURSOR, not a count. It moves to N+1 as soon as an epoch
   * closes, to prepare the next cycle - that epoch is dormant and has no row.
   * To say how many epochs a run has, count `epochTimestamps`; printing this
   * made a run fired once announce two.
   */
  currentEpoch: number;
  epochTimestamps: EpochTimestamp[];
  /** undefined = steps not streamed yet (loading), [] = streamed and empty. */
  streamedSteps?: StepEntry[];
  pinnedVersion: number | null;
  /** Marketplace preview: the run is frozen, no history navigation. */
  isPreviewOnly: boolean;
}

export type RunPanelAction = 'stop' | 'cancel' | 'reactivate';

export interface RunPanelActionDetail {
  action: RunPanelAction;
  workflowId?: string;
  runId?: string | null;
  /** Route the action to the canvas paired with this side-panel surface. */
  surfaceId?: string;
  /**
   * Set by the canvas that claimed the request, so the caller knows the action
   * was actually taken.
   *
   * A CustomEvent is fire-and-forget: before this flag, a stop pressed on a
   * surface no canvas was listening on did NOTHING, silently - no error, no
   * status change, and a run the user could not stop. The flag lets
   * {@link performRunAction} fall back to the REST call instead of dropping it.
   *
   * A canvas that claims the run sets it even when it DECLINES (preview mode),
   * so a deliberate refusal is never re-tried behind its back. A canvas that
   * could not carry the action out does NOT set it, so the fallback runs rather
   * than the click dying inside a listener that swallowed it.
   */
  handled?: boolean;
  /**
   * The claiming listener's own promise, when it has one.
   *
   * Without it the caller only knows the request was accepted, never whether it
   * WORKED: `pending` cleared in the next microtask and a failure surfaced
   * nowhere but the canvas' toast, which the application page and the share link
   * do not host.
   */
  result?: Promise<void>;
}

export interface OpenRunPanelDetail {
  workflowId?: string;
  /** Which level of the Run tab to land on. Defaults to the run detail. */
  view?: 'history' | 'run';
}

export interface RunPanelViewRequest {
  workflowId?: string;
  view: 'history' | 'run';
  /** Increments per request so re-asking for the SAME level still applies. */
  seq: number;
}

export function makeEmptyRunPanelData(workflowId: string, surfaceId?: string): RunPanelData {
  return {
    workflowId,
    surfaceId,
    runId: null,
    runInfo: null,
    isStepByStep: false,
    currentEpoch: 0,
    epochTimestamps: [],
    streamedSteps: undefined,
    pinnedVersion: null,
    isPreviewOnly: false,
  };
}

const cacheByWorkflow = new Map<string, RunPanelData>();

function snapshotCacheKey(workflowId: string, surfaceId?: string): string {
  return JSON.stringify([workflowId, surfaceId ?? null]);
}

/** Latest snapshot for one workflow surface (empty defaults before publication). */
export function getCachedRunPanelData(workflowId: string, surfaceId?: string): RunPanelData {
  return cacheByWorkflow.get(snapshotCacheKey(workflowId, surfaceId))
    ?? makeEmptyRunPanelData(workflowId, surfaceId);
}

/**
 * The run the canvas of this workflow surface is bound to.
 *
 * This is the id every run surface keys its per-run state off (the epoch the
 * user picked, above all), and the canvas resolves it from more than the
 * provider knows: the run info it fetched, then the URL run, then the in-place
 * one. A component deep in the canvas that only has the provider's run id would
 * key the SAME state under a different id - or under none at all where the
 * provider was mounted without one - so it reads it back from here instead.
 */
export function boundRunId(
  workflowId: string | null | undefined,
  fallback?: string | null,
  surfaceId?: string,
): string | null {
  if (!workflowId) return fallback ?? null;
  return getCachedRunPanelData(workflowId, surfaceId).runId ?? fallback ?? null;
}

/** Publish a fresh snapshot (canvas → panel). Also fills the late-mount cache. */
export function publishRunPanelData(data: RunPanelData): void {
  if (typeof window === 'undefined' || !data.workflowId) return;
  cacheByWorkflow.set(snapshotCacheKey(data.workflowId, data.surfaceId), data);
  window.dispatchEvent(new CustomEvent<RunPanelData>(RUN_PANEL_DATA_EVENT, { detail: data }));
}

/** Subscribe to snapshots for one workflow surface. Returns the unsubscribe function. */
export function subscribeRunPanelData(
  workflowId: string,
  onData: (data: RunPanelData) => void,
  surfaceId?: string,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (event: Event) => {
    const detail = (event as CustomEvent<RunPanelData>).detail;
    if (!detail || detail.workflowId !== workflowId) return;
    if ((detail.surfaceId ?? null) !== (surfaceId ?? null)) return;
    onData(detail);
  };
  window.addEventListener(RUN_PANEL_DATA_EVENT, handler);
  return () => window.removeEventListener(RUN_PANEL_DATA_EVENT, handler);
}

/**
 * Ask the canvas to stop / cancel / reactivate the run (panel → canvas).
 *
 * Returns the dispatched detail, carrying whatever the listeners wrote on it:
 * `handled` (a canvas claimed it) and `result` (its promise). `dispatchEvent` is
 * synchronous, so both are readable the moment it returns - which is what lets a
 * caller fall back to the REST call when no canvas is mounted, and await the
 * canvas when one is. Prefer {@link performRunAction}, which does both for you.
 */
export function requestRunAction(detail: RunPanelActionDetail): RunPanelActionDetail {
  if (typeof window === 'undefined') return { ...detail, handled: false };
  const payload: RunPanelActionDetail = { ...detail, handled: false };
  window.dispatchEvent(new CustomEvent<RunPanelActionDetail>(RUN_PANEL_ACTION_EVENT, { detail: payload }));
  return payload;
}

/**
 * Last requested level, kept at module scope because the panel body is UNMOUNTED
 * while the side panel is closed: the click that opens the panel would otherwise
 * have no listener, and the Run tab would mount on the wrong level.
 */
let lastViewRequest: RunPanelViewRequest | null = null;
let viewRequestSeq = 0;

/** Open (and focus) the Run tab of the workflow / application panel. */
export function openRunPanel(detail: OpenRunPanelDetail = {}): void {
  if (typeof window === 'undefined') return;
  lastViewRequest = {
    workflowId: detail.workflowId,
    view: detail.view ?? 'run',
    seq: ++viewRequestSeq,
  };
  window.dispatchEvent(new CustomEvent<OpenRunPanelDetail>(OPEN_RUN_PANEL_EVENT, { detail }));
}

/**
 * The pending level request for a workflow, or null when it targets another one.
 *
 * An UNADDRESSED level request applies to whichever panel asks - deliberately,
 * and unlike {@link subscribeBindRun}. The asymmetry is the stake: a level says
 * "show the history rather than the run detail", which is right on any panel; a
 * bind names a RUN, and a run belongs to exactly one workflow.
 */
export function getRunPanelViewRequest(workflowId: string): RunPanelViewRequest | null {
  if (!lastViewRequest) return null;
  if (lastViewRequest.workflowId && lastViewRequest.workflowId !== workflowId) return null;
  return lastViewRequest;
}

/**
 * Open (and focus) the Add Node tab of the workflow panel.
 *
 * <p>Unlike the Run tab, the palette needs no module-level pending request: it has
 * a single level, so "which sub-tab" is the whole state and the page-level handler
 * already carries it across an unmounted panel via `setPendingActivateTab`. What
 * this event exists for is the case that handler CANNOT serve - the panel already
 * showing the workflow tab, where nothing remounts and only an in-panel listener
 * can react.
 */
export function openNodeCreatorPanel(detail: { workflowId?: string } = {}): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(OPEN_NODE_CREATOR_EVENT, { detail }));
}

export interface BindRunDetail {
  /**
   * Required: several surfaces showing DIFFERENT workflows listen at once (the
   * page plus every keepMounted workflow tab), and an unaddressed request would
   * bind a foreign run id into all of them.
   */
  workflowId: string;
  runId: string;
  /**
   * Which SURFACE asked. The workflow alone is not enough to route this: the
   * page and a side-panel tab can show the SAME workflow at once (a
   * self-referencing sub-workflow node, or opening the current workflow from the
   * tab picker), and then a pick made in the tab would rewrite the page's URL
   * and move the canvas behind it. Absent means the page, which owns the route.
   */
  surfaceId?: string;
}

/**
 * Show another run of this workflow WITHOUT navigating.
 *
 * Picking a run used to `router.push('/app/workflow/<id>/run/<runId>')`, which
 * tears down and rebuilds the whole route - the canvas, the panel and every
 * fetch - so the app visibly "refreshes" just to look at a sibling run. The page
 * already knows how to bind a run in place (that is how an agent-launched run
 * takes over the canvas), so the panel asks for that instead and the URL is
 * realigned silently.
 */
export function requestBindRun(detail: BindRunDetail): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<BindRunDetail>(BIND_RUN_EVENT, { detail }));
}

/**
 * Listen for bind requests this surface may follow. Returns the unsubscribe fn.
 *
 * Several surfaces are mounted at once (the page, a sub-workflow tab, an
 * application tab) and they all hear the same window event, so the "is this for
 * me?" rule has to be ONE rule, not one per listener. A request must name BOTH
 * the workflow and the surface: the workflow alone routes a pick made in a
 * side-panel tab to the page whenever the two show the same workflow, and an
 * unaddressed one would bind a foreign run id into every mounted surface.
 *
 * `surfaceId` is omitted for the page, which owns the route.
 */
export function subscribeBindRun(
  workflowId: string | undefined,
  onBind: (runId: string) => void,
  surfaceId?: string,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (event: Event) => {
    const detail = (event as CustomEvent<BindRunDetail>).detail;
    if (!detail?.runId) return;
    // An unaddressed request matches nothing: every subscriber names a workflow.
    if (detail.workflowId !== workflowId) return;
    if ((detail.surfaceId ?? null) !== (surfaceId ?? null)) return;
    onBind(detail.runId);
  };
  window.addEventListener(BIND_RUN_EVENT, handler);
  return () => window.removeEventListener(BIND_RUN_EVENT, handler);
}

/**
 * Workspace switch must not leak one org's run snapshots into the next. Also
 * drops the pending level request: replaying one minted before the switch would
 * yank the freshly-mounted panel to a level the user never asked for here.
 */
export function clearRunPanelCache(): void {
  cacheByWorkflow.clear();
  lastViewRequest = null;
}

/**
 * Forget the pending level request once a panel has acted on it, so a much later
 * remount does not replay a stale "history" (or "run") the user asked for one
 * navigation ago.
 */
export function consumeRunPanelViewRequest(workflowId: string): void {
  if (!lastViewRequest) return;
  if (lastViewRequest.workflowId && lastViewRequest.workflowId !== workflowId) return;
  lastViewRequest = null;
}
