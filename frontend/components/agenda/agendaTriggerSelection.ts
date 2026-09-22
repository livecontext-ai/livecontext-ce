import type { AgendaMarker, AgendaOccurrence } from '@/lib/api/orchestrator/agenda.service';
import { occurrenceKind, type AgendaKind } from './agendaLaunchKinds';
import { TRIGGER_KIND_ORDER } from '@/lib/workflows/triggerNodeIcons';

/** Stable identity for one production trigger in the agenda catalogue. */
export function agendaTriggerKey(trigger: AgendaMarker): string {
  return [
    trigger.resourceType,
    trigger.resourceId,
    trigger.triggerType,
    trigger.triggerId ?? '',
    trigger.scheduleId ?? '',
  ].join(':');
}

/**
 * Whether a dated use belongs to the selected production trigger.
 *
 * @param catalogue every marker on the page. Needed only for the agent branch below,
 *                  which has to know whether the agent has more than one trigger of the
 *                  selected kind. Omitting it makes that branch fall back to matching
 *                  nothing, which is the previous behaviour.
 */
export function occurrenceUsesTrigger(
  occurrence: AgendaOccurrence,
  trigger: AgendaMarker,
  catalogue: readonly AgendaMarker[] = [],
): boolean {
  if (occurrence.resourceType !== trigger.resourceType || occurrence.resourceId !== trigger.resourceId) {
    return false;
  }
  // An AGENT run records the launch KIND but not which schedule or webhook row produced
  // it, so it can never carry a triggerId or a scheduleId and every branch below would
  // refuse it: selecting an agent's trigger used to empty the calendar, and the empty
  // state then said "no results", which reads as "it never ran".
  //
  // It is matched by kind, and ONLY while that is not a guess. With one schedule on the
  // agent, "this agent's schedule-launched runs" and "this trigger's runs" are the same
  // set. With two, they are not, and the honest answer is the one this page gives
  // everywhere else: show nothing rather than attribute a run to the wrong schedule.
  if (occurrence.resourceType === 'AGENT' && occurrence.launchSource) {
    if (occurrence.launchSource !== trigger.triggerType) return false;
    const sameKindOnThisAgent = catalogue.filter(
      (candidate) => candidate.resourceType === 'AGENT'
        && candidate.resourceId === trigger.resourceId
        && candidate.triggerType === trigger.triggerType,
    ).length;
    return sameKindOnThisAgent === 1;
  }
  if (trigger.triggerId) {
    return occurrence.triggerId === trigger.triggerId;
  }
  if (trigger.triggerType === 'SCHEDULE') {
    return Boolean(trigger.scheduleId) && occurrence.scheduleId === trigger.scheduleId;
  }
  return occurrence.triggerType === trigger.triggerType;
}

/**
 * Does this entry survive the launch-kind filter?
 *
 * <p>The kind is read through {@link occurrenceKind}, which folds the two vocabularies
 * into one token: a workflow entry answers with its trigger kind, an agent run with how
 * it was launched. That is what lets a single "Chat" chip cover both a chat-triggered
 * workflow and a conversation turn with an agent.
 *
 * <p>Entries with NO attributable kind - a legacy epoch whose plan snapshot names no
 * trigger, or an agent run whose launch the backend did not recognise - belong only to
 * the view where no WORKFLOW kind has been deselected. Once a user narrows those, an
 * unknown event must not bypass the choice by being unclassifiable.
 *
 * <p>The sentinel counts the workflow kinds only, and that is the point: an unattributed
 * entry is a workflow epoch, so deselecting an AGENT-ONLY kind says nothing about it.
 * Measuring against the full 11-kind vocabulary instead meant that turning off, say,
 * Sub-agent also swept away every legacy epoch on the calendar - a filter silently
 * removing history it has no opinion about.
 */
export function occurrenceMatchesTriggerTypes(
  occurrence: AgendaOccurrence,
  triggerTypes: readonly AgendaKind[],
): boolean {
  const kind = occurrenceKind(occurrence);
  if (kind) return triggerTypes.includes(kind);
  return TRIGGER_KIND_ORDER.every((workflowKind) => triggerTypes.includes(workflowKind));
}
