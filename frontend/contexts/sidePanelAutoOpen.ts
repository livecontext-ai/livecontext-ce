// Pure logic for the side-panel auto-open debounce queue. Kept out of
// StreamingContext.tsx so the ordering/dedup rules are unit-testable without
// importing the whole React/streaming module graph.

export interface AutoOpenVisualization {
  type: string;
  id: string;
  title?: string;
  runId?: string;
  liveCoords?: {
    sessionId: string;
    cdpToken: string;
    cdpWsUrl: string;
    currentUrl: string;
    runId: string;
    nodeId: string;
  };
}

// Visualization types that auto-open a side-panel tab. 'interface' and
// 'web_search' are intentionally excluded:
//   - interface visualize → InterfacePreviewBlock renders the card inline; the
//     side panel only opens when the user clicks that card.
//   - web_search → results are shown as a favicon stack on the tool-call row
//     (GroupedToolCard); no side panel at all.
export const AUTO_OPEN_TYPES: readonly string[] = [
  'workflow', 'table', 'datasource', 'application', 'agent', 'workflow_run', 'agent_browse', 'image_generation',
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
  if (shouldReplaceAutoOpen(pending.get(key), viz)) {
    pending.set(key, viz);
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
