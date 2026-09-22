import { AppWindow, Bot, Workflow, type LucideIcon } from 'lucide-react';
import type {
  AgendaOccurrence,
  OccurrenceStatus,
  ResourceType,
} from '@/lib/api/orchestrator/agenda.service';

/**
 * The agenda's visual vocabulary, in one place so a chip, a list row and the unscheduled
 * rail cannot drift into describing the same thing three different ways.
 *
 * Icons are deliberately the SAME lucide set the sidebar and the notification bell use
 * for these resource kinds. A calendar that invents its own icon for "application" makes
 * the user learn the app twice.
 */
export function resourceIcon(type: ResourceType): LucideIcon {
  switch (type) {
    case 'AGENT':
      return Bot;
    case 'APPLICATION':
      return AppWindow;
    case 'WORKFLOW':
    default:
      return Workflow;
  }
}

/**
 * Accent per resource kind. Hue carries the kind, so a glance at a busy month reads as
 * "mostly workflows, one app" without any legend.
 */
export const RESOURCE_ACCENT: Record<ResourceType, { chip: string; dot: string; ring: string }> = {
  WORKFLOW: {
    chip: 'bg-blue-50 text-blue-900 dark:bg-blue-950/50 dark:text-blue-100',
    dot: 'bg-blue-500',
    ring: 'ring-blue-500/40',
  },
  APPLICATION: {
    chip: 'bg-violet-50 text-violet-900 dark:bg-violet-950/50 dark:text-violet-100',
    dot: 'bg-violet-500',
    ring: 'ring-violet-500/40',
  },
  AGENT: {
    chip: 'bg-emerald-50 text-emerald-900 dark:bg-emerald-950/50 dark:text-emerald-100',
    dot: 'bg-emerald-500',
    ring: 'ring-emerald-500/40',
  },
};

/**
 * Past occurrences are drawn in their OUTCOME's colour rather than their resource's.
 * On a past day the question is no longer "what kind of thing is this" but "did it
 * work", so the outcome takes over the chip.
 */
export const STATUS_ACCENT: Partial<Record<OccurrenceStatus, { chip: string; dot: string }>> = {
  RUNNING: {
    chip: 'bg-amber-50 text-amber-900 dark:bg-amber-950/50 dark:text-amber-100',
    dot: 'bg-amber-500 animate-pulse',
  },
  COMPLETED: {
    chip: 'bg-gray-100 text-gray-700 dark:bg-gray-800/70 dark:text-gray-300',
    dot: 'bg-emerald-500',
  },
  FAILED: {
    chip: 'bg-red-50 text-red-900 dark:bg-red-950/50 dark:text-red-100',
    dot: 'bg-red-500',
  },
  // A stopped run is neither a success nor a failure, and the difference matters on a
  // calendar: red would send someone looking for a fault in a run that a person chose to
  // end. Slate reads as "deliberately not finished". Reachable from AGENT runs only - a
  // workflow epoch has no cancelled verdict to report.
  CANCELLED: {
    chip: 'bg-slate-100 text-slate-700 dark:bg-slate-800/70 dark:text-slate-300',
    dot: 'bg-slate-400',
  },
  FIRED: {
    chip: 'bg-gray-100 text-gray-600 dark:bg-gray-800/70 dark:text-gray-400',
    dot: 'bg-gray-400',
  },
};

/** The chip and dot classes an occurrence should wear. */
export function occurrenceAccent(occurrence: AgendaOccurrence): { chip: string; dot: string } {
  if (occurrence.kind === 'PAST') {
    const accent = STATUS_ACCENT[occurrence.status];
    if (accent) return accent;
  }
  const resource = RESOURCE_ACCENT[occurrence.resourceType] ?? RESOURCE_ACCENT.WORKFLOW;
  return { chip: resource.chip, dot: resource.dot };
}

/**
 * Where clicking an occurrence should land.
 *
 * Applications are keyed by PUBLICATION id, not workflow id - routing an application row
 * on its resourceId is a 404. Agents have no per-agent page at all: they open in the
 * right-side panel through the `?openAgent=<id>` deep link AgentTable handles. A resolved
 * production run takes precedence for workflows so the user lands in run mode, which is
 * where the occurrence they clicked actually shows.
 */
export function occurrenceHref(occurrence: {
  resourceType: ResourceType;
  resourceId: string;
  runIdPublic?: string;
  publicationId?: string;
  conversationId?: string;
}): string {
  if (occurrence.resourceType === 'AGENT') {
    // A past agent run is READ in the conversation it happened in - that is where its
    // prompt, its answer and its tool calls are. The agent panel only says the agent
    // exists, which is the right landing place for a planned fire (nothing has happened
    // yet) and the wrong one for a run someone clicked to inspect.
    //
    // The fallback is live, not theoretical: a CLI-provider agent node inside a workflow
    // records no conversation at all (778 of 778 such rows on a real install), so those
    // runs land on the agent panel.
    if (occurrence.conversationId) {
      return `/app/c/${occurrence.conversationId}`;
    }
    return `/app/agent?openAgent=${occurrence.resourceId}`;
  }
  if (occurrence.resourceType === 'APPLICATION' && occurrence.publicationId) {
    return `/app/applications/${occurrence.publicationId}`;
  }
  if (occurrence.runIdPublic) {
    return `/app/workflow/${occurrence.resourceId}/run/${occurrence.runIdPublic}`;
  }
  return `/app/workflow/${occurrence.resourceId}`;
}

/** Whether the agenda can act on this entry: only a live schedule can be moved or run. */
export function isActionable(occurrence: AgendaOccurrence): boolean {
  return occurrence.kind === 'PLANNED'
    && Boolean(occurrence.scheduleId)
    && !occurrence.resourcePaused;
}

/**
 * Whether this occurrence can be DRAGGED to a new date.
 *
 * <p>Narrower than {@link isActionable}, and the gap is real rather than pedantic. A
 * schedule points at exactly one pending fire, so "move this one only" is available on the
 * next fire and nowhere else; "move them all" needs a cron simple enough to rewrite. An
 * occurrence that is neither - chips 2..n of any interval schedule, which is a very common
 * shape - can be moved by no scope at all. It stayed draggable anyway, so the gesture
 * opened a dialog with both scopes disabled and Confirm greyed: the UI taught a gesture and
 * then took it away, with no explanation on screen.
 *
 * <p>Running early is unaffected: that acts on the schedule, not on the occurrence, so it
 * stays available from the menu on every planned chip.
 */
export function isMovable(occurrence: AgendaOccurrence): boolean {
  return isActionable(occurrence) && (occurrence.isNextFire || occurrence.moveAllSupported);
}
