/**
 * Trigger kind -> NodeIcon id, and the display order of the trigger kinds.
 *
 * <p>A LEAF module on purpose: it holds constants only and imports nothing.
 * These live here rather than beside the dashboard service that used to own
 * them because pure helpers need them too (the node-type filter maps a
 * `trigger:webhook` token to its glyph), and reaching into a service module for
 * a constant drags the HTTP client into the import graph - which, through the
 * `lib/api/orchestrator` barrel, closes a cycle that leaves the API facade
 * half-initialised at import time.
 *
 * <p>`dashboard.service` re-exports both so existing callers are unaffected.
 */

/** The eight trigger kinds the backend can report. */
export type TriggerType =
  | 'SCHEDULE'
  | 'WEBHOOK'
  | 'MANUAL'
  | 'CHAT'
  | 'FORM'
  | 'DATASOURCE'
  | 'WORKFLOW'
  | 'ERROR';

/**
 * Display order for the filter chip strip + the row sort tiebreak.
 * Schedule first (Tier-1 imminent), webhook second (existing semantic),
 * then the 6 declared kinds. Don't reorder lightly - the chip strip relies
 * on this for the L-to-R reading order users learn.
 *
 * <p>Lives here rather than in `dashboard.service` (which re-exports it) for the
 * reason this whole module exists: pure helpers need it - the agenda's launch-kind
 * filter builds its vocabulary on top of it - and reaching into a service module for
 * a constant drags the HTTP client into their import graph.
 */
export const TRIGGER_KIND_ORDER: readonly TriggerType[] = [
  'SCHEDULE',
  'WEBHOOK',
  'MANUAL',
  'CHAT',
  'FORM',
  'DATASOURCE',
  'WORKFLOW',
  'ERROR',
];

/**
 * Maps backend `TriggerType` → frontend NodeIcon `nodeId` (the key into
 * `NODE_ICON_REGISTRY` in `nodeVisuals.ts`). Two naming quirks:
 *   - `DATASOURCE` → `tables-trigger` (the trigger node has been historically
 *     named after the table it watches, not the data source kind).
 *   - `WORKFLOW`   → `workflows-trigger` (plural - matches the visual node).
 * The other 6 entries are the obvious `<kind>-trigger` form.
 *
 * The same 8 entries exist in `WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID`
 * (Java, both services). Each side is pinned against a canonical fixture by its
 * own test - `WorkflowIconExtractorParityTest` on the Java side, and
 * `lib/api/orchestrator/__tests__/dashboard.service.test.ts` on this one. No
 * test reads across the language boundary, so adding a 9th kind means editing
 * all four artifacts by hand.
 */
export const KIND_TO_NODE_ICON_KEY: Record<TriggerType, string> = {
  SCHEDULE: 'schedule-trigger',
  WEBHOOK: 'webhook-trigger',
  MANUAL: 'manual-trigger',
  CHAT: 'chat-trigger',
  FORM: 'form-trigger',
  DATASOURCE: 'tables-trigger',
  WORKFLOW: 'workflows-trigger',
  ERROR: 'error-trigger',
};
