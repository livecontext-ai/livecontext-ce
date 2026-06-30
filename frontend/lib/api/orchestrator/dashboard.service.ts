/**
 * Dashboard Service
 *
 * Read-only home-page widgets - distinct from the activity audit log.
 * Single Responsibility: surfaces "what's running right now".
 */

import { apiClient } from '../api-client';

export type ResourceType = 'WORKFLOW' | 'APPLICATION' | 'AGENT';

/**
 * Trigger kind, 1:1 with the 8 backend `ActiveAutomationDto.TriggerType` enum
 * values. Drives row icon + the chip filter strip at the top of the bell's
 * Triggers tab. Mirrors `WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID` (Java)
 * keys via the {@link KIND_TO_NODE_ICON_KEY} normalization map below.
 */
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
 * Maps backend `TriggerType` → frontend NodeIcon `nodeId` (the key into
 * `NODE_ICON_REGISTRY` in `nodeVisuals.ts`). Two naming quirks:
 *   - `DATASOURCE` → `tables-trigger` (the trigger node has been historically
 *     named after the table it watches, not the data source kind).
 *   - `WORKFLOW`   → `workflows-trigger` (plural - matches the visual node).
 * The other 6 entries are the obvious `<kind>-trigger` form.
 *
 * Pinned cross-layer by `WorkflowIconExtractorParityTest` (Java) which asserts
 * the canonical 8-entry map; any drift in either direction fails the build.
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

/**
 * Display order for the filter chip strip + the row sort tiebreak.
 * Schedule first (Tier-1 imminent), webhook second (existing semantic),
 * then the 6 declared kinds. Don't reorder lightly - the chip strip relies
 * on this for the L-to-R reading order users learn.
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

export interface ActiveAutomationSchedule {
  cronExpression: string;
  timezone: string;
  /** ISO-8601. Precomputed server-side from the cron expression - render directly. */
  nextFireAt?: string;
  executionCount: number;
}

export interface ActiveAutomationWebhook {
  /** Set for agent webhooks (one method per agent). Undefined for workflows
   *  whose multiple per-trigger tokens may carry mixed methods. */
  httpMethod?: string;
}

/**
 * One row in the home-page "Active automations" strip. Exactly one of
 * `schedule` / `webhook` is set, never both.
 */
export interface ActiveAutomation {
  resourceType: ResourceType;
  resourceId: string;
  name: string;
  avatarUrl?: string;
  triggerType: TriggerType;
  schedule?: ActiveAutomationSchedule;
  webhook?: ActiveAutomationWebhook;
  lastRunAt?: string;
  /** Workflows / applications carry this; agents do not (no pin concept). */
  isPinned?: boolean;
  /**
   * Pinned workflow's / application's current production run public id, if a
   * trusted run resolved server-side. Lets the bell route the row directly to
   * /app/workflow/{id}/run/{productionRunIdPublic} (run mode) instead of
   * landing on the edit canvas. Absent for agents and for pinned workflows
   * with no trusted run yet.
   */
  productionRunIdPublic?: string;
  /**
   * APPLICATION rows only: the workflow's `source_publication_id`. The
   * /app/applications/[publicationId] route is keyed by publication id, NOT
   * by workflow id - so the bell must route APPLICATION rows on this field
   * to avoid a 404. Absent on WORKFLOW and AGENT rows.
   * Added v5 for the F4 PUB-HIJACK observability bundle.
   */
  publicationId?: string;
}

export class DashboardService {
  /**
   * Pinned workflows + applications + agents that have at least one armed
   * trigger (enabled schedule or active webhook). Sorted server-side by
   * nextFireAt ASC NULLS LAST - imminent fires come first, webhooks tail.
   */
  async getActiveAutomations(): Promise<ActiveAutomation[]> {
    return apiClient.get<ActiveAutomation[]>('/dashboard/active-automations');
  }
}

export const dashboardService = new DashboardService();
