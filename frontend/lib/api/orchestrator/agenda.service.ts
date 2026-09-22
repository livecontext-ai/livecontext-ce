/**
 * Agenda Service
 *
 * The scheduled side of the workspace over a date window: what will run, what already
 * ran, and what is armed but has no date. Also the three writes the calendar performs.
 */

import { apiClient } from '../api-client';
import type { ResourceType, TriggerType } from './dashboard.service';

export type { ResourceType, TriggerType };

/** Whether an entry is a projection of the future or a record of the past. */
export type OccurrenceKind = 'PLANNED' | 'PAST';

/**
 * `PLANNED` for anything still to come. A past entry carries its real outcome:
 * `RUNNING` while it is open, then `COMPLETED` / `FAILED`, or `FIRED` when the trigger
 * fired but nothing downstream executed.
 *
 * `CANCELLED` reaches this type from AGENT runs only - an agent execution records that
 * a user stopped it, where a workflow epoch has no such verdict to report.
 */
export type OccurrenceStatus =
  | 'PLANNED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'FIRED';

/**
 * How an AGENT run was launched.
 *
 * A separate vocabulary from `TriggerType`, which is the eight trigger NODE kinds a
 * workflow plan can declare: an agent has no trigger nodes. Four names coincide and mean
 * the same thing, which is why one filter control covers both (see `agendaLaunchKinds`),
 * but `SUB_AGENT` / `TASK` / `WIDGET` exist only here.
 */
export type AgentLaunchSource =
  | 'CHAT'
  | 'SCHEDULE'
  | 'WEBHOOK'
  | 'WORKFLOW'
  | 'SUB_AGENT'
  | 'TASK'
  | 'WIDGET';

export interface AgendaOccurrence {
  /** Stable across refetches, so a drag is not interrupted by a background reload. */
  id: string;
  kind: OccurrenceKind;
  /** ISO-8601 instant. */
  startAt: string;
  /** Past fires only, and only once their epoch has closed. */
  endAt?: string;
  resourceType: ResourceType;
  resourceId: string;
  name: string;
  avatarUrl?: string;
  /** The row to address for a move, an early run or a pause. Absent on past fires. */
  scheduleId?: string;
  /** Plan-level trigger label, e.g. `trigger:daily`. Present on past fires. */
  triggerId?: string;
  /**
   * Trigger kind that produced this occurrence. Workflow and application entries only:
   * an agent run says how it started with `launchSource` instead, and the two are never
   * both present.
   */
  triggerType?: TriggerType;
  /**
   * How an AGENT run was launched. Absent when the backend does not recognise the launch
   * kind, which is deliberate: the page then draws no kind rather than the nearest one.
   */
  launchSource?: AgentLaunchSource;
  /**
   * The conversation an agent run happened in, when it had one. It is the click target:
   * a run is read in its conversation, whereas the agent page only says the agent exists.
   */
  conversationId?: string;
  cronExpression?: string;
  timezone?: string;
  /**
   * Will this occurrence actually fire?
   *
   * On a PLANNED occurrence, false means one thing only: the schedule is armed,
   * with a live cron, but its workflow has reached a spending cap still in force
   * at this date. A PAUSED schedule never reaches this type at all, it is drawn
   * on the undated rail instead, so there is no second meaning to tell apart.
   *
   * It is per occurrence rather than per schedule, which is the point: the fires
   * before the allowance starts again are refused and the ones after it are not,
   * so the same schedule is greyed for part of the window and normal for the
   * rest. False on every future fire means a cap that never resets.
   */
  armed: boolean;
  /**
   * Whether a spending cap is refusing this resource's fires RIGHT NOW.
   *
   * Not the same question as `armed`, and the difference decides what the menu offers. A
   * monthly cap that lifts on the 1st leaves the occurrence dated the 5th armed, because
   * that fire will happen. "Run early" does not run that fire: it runs the schedule at the
   * moment of the click, and the cap still holds then. Keyed on `armed`, the menu would
   * offer an action whose only outcome is a failure toast.
   */
  budgetBlocked?: boolean;
  /**
   * True for the ONE occurrence the schedule is currently pointing at. Only this one can
   * be moved on its own: the schedule holds a single pending fire, so writing a later
   * occurrence's time into it would skip every run in between.
   */
  isNextFire: boolean;
  /** True when the user has moved this single occurrence off its cron slot. */
  overridden: boolean;
  /**
   * Whether the cron names one unambiguous time of day. False means the "all
   * occurrences" choice must not be offered: see the move dialog.
   */
  moveAllSupported: boolean;
  status: OccurrenceStatus;
  runIdPublic?: string;
  /**
   * Which fire of the run a PAST occurrence was. Absent on a projection, and on an agent
   * run, which is not a workflow run and has no epochs.
   *
   * A run is a sequence of fires and its surfaces open on the cumulative view of all of
   * them, which is right when you open a run and wrong when you clicked one dot on a
   * calendar: the user pointed at Tuesday 09:00 and got every Tuesday at once.
   *
   * It was declared on `AgendaMarker` until a `Pick<AgendaOccurrence, ...>` made the
   * compiler say so. The backend puts it on the OCCURRENCE record and nowhere near the
   * marker one, so `marker.epoch` was permanently undefined and the page's epoch
   * hand-off typechecked only because the callback took an inline shape of its own.
   */
  epoch?: number;
  resourcePaused?: boolean;
  publicationId?: string;
}

/**
 * An armed resource with no date: a webhook, chat, form, table or manual trigger, or a
 * schedule that is currently paused. Deliberately carries no `startAt` - putting a
 * webhook on a day would state something false about when it runs.
 */
export interface AgendaMarker {
  resourceType: ResourceType;
  resourceId: string;
  name: string;
  avatarUrl?: string;
  triggerType: TriggerType;
  /** Exact normalized key from the published plan. */
  triggerId?: string;
  /** Human-readable label authored on this trigger. */
  triggerLabel?: string;
  scheduleId?: string;
  cronExpression?: string;
  timezone?: string;
  /** Paused schedules only: the fire time they would resume at. */
  nextFireAt?: string;
  lastRunAt?: string;
  armed: boolean;
  resourcePaused?: boolean;
  /**
   * Why a schedule is paused, absent when it is armed or has no schedule.
   *
   * Only `USER` can be resumed. `CAP_REACHED` means it ran its configured number of times,
   * and `PLATFORM` means the platform suspended it (its trigger left the published version)
   * - on both, the resume call is accepted, writes nothing, and reports success, so
   * offering the action announced a change that never happened.
   */
  pausedReason?: PausedReason;
  runIdPublic?: string;
  publicationId?: string;
}

export type PausedReason = 'USER' | 'CAP_REACHED' | 'PLATFORM';

export interface Agenda {
  from: string;
  to: string;
  occurrences: AgendaOccurrence[];
  markers: AgendaMarker[];
  /**
   * Schedules whose expansion hit the server cap, so the calendar holds a PREFIX of
   * their occurrences. Surface it: silently showing the first N makes a once-a-minute
   * job look like it stops mid-month.
   */
  truncatedScheduleIds: string[];
  /** True when older fires inside the window were cut from the history lookup. */
  pastTruncated: boolean;
  /**
   * The earliest instant the history in this window is COMPLETE from, absent when nothing
   * was truncated.
   *
   * Say it, do not just flag it. A capped scan keeps the NEWEST rows, which points at the
   * days being read only while the window ends at "now". Page back to a past month and
   * the kept rows are the END of it, so the first weeks are drawn empty while the banner
   * says only "some older runs are not shown" - and a user reads the grid, not the banner.
   */
  pastCoveredFrom?: string;
  /**
   * True when the AGENT half of the history could not be read at all.
   *
   * A different sentence from truncation, which is why it is a different field:
   * truncation means older runs are missing, this means EVERY agent run in the window
   * is missing, including today's. Told apart because "could not ask" and "nothing ran"
   * are both zero rows, and a calendar must not draw them the same way.
   */
  agentHistoryUnavailable?: boolean;
}

/** `NEXT` moves one fire; `ALL` rewrites the schedule's cron. */
export type MoveScope = 'NEXT' | 'ALL';

/**
 * Why the platform refused. Each maps to its own sentence in the UI - collapsing them
 * into "failed" would leave the user with no idea what to do differently.
 */
export type AgendaFailureReason =
  | 'NOT_FOUND'
  | 'PATTERN_NOT_SHIFTABLE'
  | 'WEEKDAY_SET_NOT_MATCHED'
  | 'DAY_OF_MONTH_UNSAFE'
  | 'NOT_ARMED'
  | 'NOT_THE_NEXT_OCCURRENCE'
  | 'EXECUTION_REFUSED'
  | 'VIEWER_ROLE'
  | 'SCHEDULE_REJECTED';

export interface AgendaActionResponse {
  success: boolean;
  reason?: AgendaFailureReason;
  detail?: string;
  /**
   * Early runs asked to REPLACE the scheduled one, and only those: whether THIS call gave
   * the pending occurrence up, i.e. whether it moved the fire time.
   *
   * Deliberately not "whether the occurrence is gone". The platform declines to write in
   * several ordinary situations: the row no longer points at that occurrence (the daemon
   * claimed the fire, or the schedule was edited in the interval), the cron has no later
   * slot, the timezone does not resolve, or the write was refused after the run had already
   * started. On some of those the occurrence IS gone, consumed by something else; what the
   * caller has to decide is whether IT may claim the replacement, and the answer there is
   * no. The schedule alone cannot tell you, since the row carries the same fire time whether
   * nothing was written or something else wrote it. Absent on every other action, so an
   * older backend reads as "did not", which is the safe direction.
   */
  occurrenceConsumed?: boolean;
  schedule?: {
    id: string;
    cronExpression: string;
    timezone: string;
    nextExecutionAt?: string;
    enabled: boolean;
  };
}

export class AgendaService {
  /**
   * Everything dated inside `[from, to]`, plus the undated markers.
   *
   * @param includePast skip the history lookup for a window entirely in the future,
   *                    where it could only ever return nothing
   */
  async getAgenda(from: Date, to: Date, includePast = true): Promise<Agenda> {
    return apiClient.get<Agenda>('/agenda', {
      params: {
        from: from.toISOString(),
        to: to.toISOString(),
        includePast: String(includePast),
      },
    });
  }

  /**
   * Move an occurrence.
   *
   * `NEXT` writes a new fire time and leaves the cron alone, so later occurrences return
   * to their normal slot. `ALL` rewrites the cron and can be refused (HTTP 422) when the
   * expression has no single time of day to move - the caller must then fall back to
   * `NEXT` rather than pretending the move happened.
   */
  async move(
    scheduleId: string,
    startAt: Date,
    scope: MoveScope,
    occurrenceAt?: Date,
  ): Promise<AgendaActionResponse> {
    return apiClient.post<AgendaActionResponse>(`/agenda/schedules/${scheduleId}/move`, {
      startAt: startAt.toISOString(),
      scope,
      // Names the occurrence the user acted on. The server refuses a "this occurrence"
      // move that is not the pending fire, because honouring it would cancel the runs
      // between now and it.
      ...(occurrenceAt ? { occurrenceAt: occurrenceAt.toISOString() } : {}),
    });
  }

  /**
   * Run a scheduled resource now, ahead of its slot.
   *
   * `keepNextOccurrence` defaults to true: running early does not consume the run the
   * user can see on the calendar.
   */
  async runNow(scheduleId: string, keepNextOccurrence = true): Promise<AgendaActionResponse> {
    return apiClient.post<AgendaActionResponse>(
      `/agenda/schedules/${scheduleId}/run-now`,
      { keepNextOccurrence },
      // NEVER retried. This is the one non-idempotent call in the feature: it starts a run
      // that spends credits and has real side effects. apiClient retries a 5xx or a network
      // failure by default, and both can arrive AFTER the orchestrator has already started
      // the run (a pod recycling, a proxy timing out while the execution queue blocks), so
      // the default would turn one click into two runs and still report success. A move is
      // idempotent - writing the same fire time or cron twice changes nothing - so only
      // this one opts out.
      { retries: 0 },
    );
  }

  /**
   * Pause or resume a schedule. Routed through the existing schedules endpoint rather
   * than a second implementation - it is the same toggle the Triggers settings page uses.
   */
  async toggle(scheduleId: string, enabled: boolean): Promise<{ success: boolean; enabled: boolean }> {
    return apiClient.post<{ success: boolean; enabled: boolean }>(
      `/schedules/${scheduleId}/toggle`,
      { enabled },
    );
  }
}

export const agendaService = new AgendaService();

/**
 * The platform's refusal reason, read off a thrown error.
 *
 * `apiClient` throws `ApiError` for ANY non-2xx, so the refusal never arrives as a
 * resolved `{success:false}` body - it arrives as an exception whose `details` holds the
 * payload. Reading `error.message` instead yields "HTTP 422: Unprocessable Entity", which
 * is exactly what the user must not be shown when the server took the trouble to explain
 * itself.
 */
export function agendaFailureOf(error: unknown): { reason?: AgendaFailureReason; detail?: string } {
  const details = (error as { details?: { reason?: AgendaFailureReason; detail?: string } })?.details;
  if (details && typeof details === 'object') {
    return { reason: details.reason, detail: details.detail };
  }
  return {};
}
