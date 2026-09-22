import { Bot, ListChecks, MessageSquareCode, type LucideIcon } from 'lucide-react';
import type { AgentLaunchSource, TriggerType } from '@/lib/api/orchestrator/agenda.service';
import { KIND_TO_NODE_ICON_KEY, TRIGGER_KIND_ORDER } from '@/lib/workflows/triggerNodeIcons';

/**
 * The calendar's single vocabulary for "how did this start".
 *
 * <p>Two vocabularies reach this page and they overlap. A workflow or application entry
 * carries a `TriggerType`: one of the eight trigger NODE kinds a plan can declare, pinned
 * to a node-icon map in four artifacts. An agent entry carries an `AgentLaunchSource`: an
 * agent has no trigger nodes, so a run starts from a conversation, a schedule, a webhook,
 * a workflow node, another agent, a task or the embedded widget.
 *
 * <p>Four of those names are the same word for the same thing, so the page treats the two
 * as ONE token space rather than drawing two filter controls that both say "Chat". What
 * the union adds is the three kinds only an agent can have.
 *
 * <p>A leaf module on purpose (constants and pure functions, no HTTP client, no JSX): the
 * filter helpers and the header both read it, and `agendaTriggerSelection` must stay
 * importable from a unit test without pulling the API facade in.
 */
export type AgendaKind = TriggerType | AgentLaunchSource;

/**
 * The launch kinds no workflow trigger can express. Listed separately because they are
 * the ones with no trigger node, and therefore no node icon.
 */
export const AGENT_ONLY_KINDS = ['SUB_AGENT', 'TASK', 'WIDGET'] as const;

export type AgentOnlyKind = (typeof AGENT_ONLY_KINDS)[number];

/**
 * Display order for the filter list. The eight trigger kinds keep the left-to-right order
 * users have learned from the notification bell, and the agent-only kinds are appended
 * rather than interleaved - they are a different family, and re-sorting the first eight
 * would move chips under the cursor of anyone who already knows where they are.
 */
export const AGENDA_KIND_ORDER: readonly AgendaKind[] = [
  ...TRIGGER_KIND_ORDER,
  ...AGENT_ONLY_KINDS,
];

/** Whether a kind is one of the three an agent alone can report. */
export function isAgentOnlyKind(kind: AgendaKind): kind is AgentOnlyKind {
  return (AGENT_ONLY_KINDS as readonly string[]).includes(kind);
}

/**
 * How this entry started, whichever vocabulary said so.
 *
 * `launchSource` first because only an agent run has one, and an agent run never carries
 * a `triggerType`. Undefined means the platform could not attribute the entry - a legacy
 * epoch with no trigger in its plan snapshot, or a launch kind the backend did not
 * recognise - and the caller must treat that as "unknown", never as a default kind.
 */
export function occurrenceKind(occurrence: {
  triggerType?: TriggerType;
  launchSource?: AgentLaunchSource;
}): AgendaKind | undefined {
  return occurrence.launchSource ?? occurrence.triggerType;
}

/**
 * The NodeIcon id for a kind that has a trigger node, or undefined for the three that do
 * not. Callers pair this with {@link AGENT_ONLY_KIND_ICON}: exactly one of the two answers
 * for any kind.
 */
export function kindNodeIconId(kind: AgendaKind): string | undefined {
  return (KIND_TO_NODE_ICON_KEY as Record<string, string | undefined>)[kind];
}

/**
 * Lucide glyphs for the agent-only kinds. Reused from the vocabulary the rest of the app
 * already draws these things with - a sub-agent is an agent, a task is a checklist, the
 * widget is an embedded chat - rather than inventing three icons the user has to learn.
 */
export const AGENT_ONLY_KIND_ICON: Record<AgentOnlyKind, LucideIcon> = {
  SUB_AGENT: Bot,
  TASK: ListChecks,
  WIDGET: MessageSquareCode,
};
