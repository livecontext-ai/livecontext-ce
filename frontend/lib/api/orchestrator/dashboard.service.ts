/**
 * Dashboard Service
 *
 * Read-only home-page widgets - distinct from the activity audit log.
 * Single Responsibility: surfaces "what's running right now".
 */

import { apiClient } from '../api-client';
import { KIND_TO_NODE_ICON_KEY, TRIGGER_KIND_ORDER, type TriggerType } from '@/lib/workflows/triggerNodeIcons';

export type ResourceType = 'WORKFLOW' | 'APPLICATION' | 'AGENT';

/**
 * Trigger kind, 1:1 with the 8 backend `ActiveAutomationDto.TriggerType` enum
 * values. Drives row icon + the chip filter strip at the top of the bell's
 * Triggers tab. Declared in `lib/workflows/triggerNodeIcons` and re-exported
 * here so existing callers are unaffected.
 */
export type { TriggerType };

/**
 * Re-exported from `lib/workflows/triggerNodeIcons`, which owns the map and the
 * reasoning behind its two naming quirks. It lives in a leaf module so pure
 * helpers (the node-type filter maps a `trigger:webhook` token to its glyph)
 * can read it without importing this service, and with it the HTTP client.
 */
export { KIND_TO_NODE_ICON_KEY };

/**
 * Re-exported from `lib/workflows/triggerNodeIcons`, beside the icon map it orders.
 * It moved there so pure helpers can read it without importing this service, and with
 * it the HTTP client - the agenda's launch-kind vocabulary is built on it. Callers that
 * already read it from here are unaffected.
 */
export { TRIGGER_KIND_ORDER };

export interface ActiveAutomationSchedule {
  cronExpression: string;
  timezone: string;
  /** ISO-8601. Precomputed server-side from the cron expression - render directly. */
  nextFireAt?: string;
  executionCount: number;
  /**
   * The `scheduled_executions` row id. The bell needed none while it only navigated;
   * the row actions (run early, open in the agenda) address the SCHEDULE, not the
   * workflow, so they need this.
   */
  scheduleId?: string;
  /**
   * Whether the schedule will fire again: enabled AND under its max-executions cap.
   * Always true on bell rows, which the server filters upstream - the agenda is what
   * asks for the disabled ones.
   *
   * Deliberately NOT widened to include `budgetBlocked`: this field answers "is the
   * schedule still armed", which stays true while a cap holds it back, and the two
   * conditions are undone by different actions. Read both.
   */
  armed?: boolean;
  /**
   * Whether a spending cap is currently refusing this schedule's fires. Set for a
   * workflow's period budget and for an agent's own credit budget alike, so a reader
   * never has to know which kind of resource it is looking at.
   *
   * The schedule keeps its cadence while this is true, which is why it is a separate
   * field: `nextFireAt` is still the next tick, it just will not run.
   */
  budgetBlocked?: boolean;
  /**
   * ISO-8601 instant at which `budgetBlocked` lifts on its own.
   *
   * Absent while blocked means the cap never resets (a cumulative agent budget), so
   * the block is indefinite rather than a window. Absent while not blocked is simply
   * "nothing to say".
   */
  budgetBlockedUntil?: string;
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
  /** Exact normalized key from the published plan, for example `trigger:daily_report`. */
  triggerId?: string;
  /** Human-readable label authored on this trigger. */
  triggerLabel?: string;
  schedule?: ActiveAutomationSchedule;
  webhook?: ActiveAutomationWebhook;
  lastRunAt?: string;
  /**
   * How that last fire ENDED, upper-case, for the badge drawn next to
   * `lastRunAt`: `COMPLETED`, `FAILED`, `RUNNING`, or a terminal run status
   * (`CANCELLED` / `TIMEOUT` / ...) when it was killed mid-flight. Absent when
   * the backend has nothing honest to say (never fired, no production run to
   * read, or a last epoch that ran nothing but its trigger) - `EpochStatusIcon`
   * then keeps the slot's width and draws nothing, rather than a guessed verdict.
   */
  lastRunStatus?: string;
  /** Workflows / applications carry this; agents do not (no pin concept). */
  isPinned?: boolean;
  /** Whether the workflow/application production run or agent itself is paused. */
  resourcePaused?: boolean;
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
