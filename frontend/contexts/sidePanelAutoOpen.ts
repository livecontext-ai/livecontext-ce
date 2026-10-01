// Pure logic for the side-panel auto-open debounce queue. Kept out of
// StreamingContext.tsx so the ordering/dedup rules are unit-testable without
// importing the whole React/streaming module graph.

export interface AutoOpenVisualization {
  type: string;
  id: string;
  title?: string;
  runId?: string;
  /** type 'workflow': the action wrote the stored plan (an edit or a save, never a load). */
  planChanged?: boolean;
  /** type 'workflow_run': the plan version the run executes. */
  planVersion?: number;
  /** The conversation whose agent produced it, so a surface can tell its own chat's actions apart. */
  conversationId?: string;
  /** Set by a click on a tool row (toAutoOpenDetail), never by the stream: open in front. */
  userInitiated?: boolean;
  liveCoords?: {
    sessionId: string;
    cdpToken: string;
    cdpWsUrl: string;
    currentUrl: string;
    runId: string;
    nodeId: string;
  };
}

// Visualization types that auto-open a side-panel tab. 'web_search' is intentionally
// excluded: its results are shown as a favicon stack on the tool-call row
// (GroupedToolCard); no side panel at all.
// 'interface' travels, but only a chat living IN the side panel opens it (as a background tab,
// beside the conversation): a full-page chat renders the card inline (InterfacePreviewBlock)
// and opens the panel only when the user clicks it (AppHeader handleAutoOpen).
export const AUTO_OPEN_TYPES: readonly string[] = [
  'workflow', 'table', 'datasource', 'interface', 'application', 'agent', 'workflow_run', 'agent_browse', 'image_generation',
  // Emitted only by action='present' (workflow, table, interface, agent, files): the agent choosing what the user
  // looks at. Handled on EVERY page (AppHeader). The types above open on chat pages, and on
  // every page when a side-panel chat produced them (in the background, beside that chat).
  'present_application', 'present_run', 'present_table', 'present_workflow', 'present_interface', 'present_agent', 'present_file',
];

/** Stable dedup key - one queued entry per resource. */
export function autoOpenKey(viz: AutoOpenVisualization): string {
  return `${viz.type}:${viz.id}`;
}

/**
 * Decide whether an incoming auto-open visualization should replace the one
 * already queued under the same key during the debounce window.
 *
 * Rule: never DOWNGRADE a live-run marker (runId present) to a showcase marker
 * (no runId) for the SAME resource. The agent commonly emits an "open" marker
 * (showcase, no runId) alongside an "execute" marker (the actual run, runId
 * set); the execute marker is the one whose interface shows results, so it must
 * win regardless of arrival order. In every other case the latest wins.
 */
export function shouldReplaceAutoOpen(
  existing: AutoOpenVisualization | undefined,
  incoming: AutoOpenVisualization,
): boolean {
  if (!existing) return true;
  if (existing.runId && !incoming.runId) return false;
  return true;
}

/**
 * Merge a visualization into the pending-flush map (mutates `pending`).
 * Non-auto-open types are ignored. Returns the map for chaining/clarity.
 *
 * This is the heart of the multi-app fix: the previous single-timer impl kept
 * only the LAST visualization, so a burst that opened several apps left every
 * tab but the last one un-opened. Keying by resource lets EVERY distinct app
 * survive to the flush while same-resource markers collapse to the best one.
 */
export function enqueueAutoOpen(
  pending: Map<string, AutoOpenVisualization>,
  viz: AutoOpenVisualization,
): Map<string, AutoOpenVisualization> {
  if (!AUTO_OPEN_TYPES.includes(viz.type)) return pending;
  const key = autoOpenKey(viz);
  const existing = pending.get(key);
  if (shouldReplaceAutoOpen(existing, viz)) {
    // A plan change stays a plan change for the rest of the window: an edit followed by a
    // load or a present of the same workflow must still bring the page back to editing.
    // Same conversation only: another chat's marker must not inherit (or carry away) this edit.
    const keepEdit = existing?.planChanged && !viz.planChanged && existing.conversationId === viz.conversationId;
    pending.set(key, keepEdit ? { ...viz, planChanged: true } : viz);
  }
  return pending;
}

/**
 * Drain the pending map (mutates it empty) and invoke `emit` once per queued
 * resource, in insertion order - i.e. ONE side-panel-open per distinct app.
 * This is the flush half of the multi-app fix: the old single-timer impl could
 * only ever emit the last visualization. Returns the count emitted.
 */
export function flushAutoOpen(
  pending: Map<string, AutoOpenVisualization>,
  emit: (viz: AutoOpenVisualization) => void,
): number {
  const queued = Array.from(pending.values());
  pending.clear();
  for (const viz of queued) emit(viz);
  return queued.length;
}

/**
 * The `sidePanelAutoOpen` event detail for a flushed marker. Every field a listener reads must be
 * listed here: the workflow page decides edit vs run from planChanged / planVersion /
 * conversationId, and a field left out is dropped silently, never an error.
 */
export function autoOpenEventDetail(v: AutoOpenVisualization): AutoOpenVisualization {
  return {
    type: v.type,
    id: v.id,
    title: v.title,
    runId: v.runId,
    planChanged: v.planChanged,
    planVersion: v.planVersion,
    conversationId: v.conversationId,
    liveCoords: v.liveCoords,
  };
}
