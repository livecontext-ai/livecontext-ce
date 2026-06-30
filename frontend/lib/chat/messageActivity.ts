/**
 * Shared chat-activity helpers.
 *
 * Single source of truth for turning a persisted message's `toolCalls` JSON into
 * `ToolActivity[]` (reused by MessageHistory's inline reasoning feed AND the
 * Conversation Activity card), plus grouping a conversation's messages into
 * per-execution turns and deciding which tool resources open the right side panel.
 */

import type { Message } from '@/lib/api/conversation.types';
import type { ToolActivity, ToolVisualization } from '@/contexts/StreamingContext';

/**
 * Visualization types that the right side panel (AppHeader `handleAutoOpen`) can
 * actually render as a tab. This is the "openable on click" set for the activity
 * card - intentionally BROADER than `AUTO_OPEN_TYPES` (which is the policy for
 * AUTOMATIC opening on tool completion and excludes `interface` / `web_search`).
 * Here the user explicitly clicks, so `interface` IS openable; `web_search`,
 * `credential` and `image_generation` have no panel tab and stay non-openable.
 */
export const OPENABLE_VISUALIZATION_TYPES: readonly string[] = [
  'workflow',
  'workflow_run',
  'table',
  'datasource',
  'interface',
  'application',
  'agent',
  'agent_browse',
];

/** Detail payload accepted by the `sidePanelAutoOpen` CustomEvent (AppHeader handler). */
export interface AutoOpenDetail {
  type: string;
  id: string;
  title?: string;
  runId?: string;
}

/**
 * True when clicking a tool row carrying this visualization should open the right
 * side panel. `workflow_run` additionally requires a runId (the handler no-ops
 * without it). Everything else just needs a non-empty id of an openable type.
 */
export function isOpenableVisualization(v?: ToolVisualization | null): v is ToolVisualization {
  if (!v || !v.id || !OPENABLE_VISUALIZATION_TYPES.includes(v.type)) return false;
  if (v.type === 'workflow_run') return !!v.runId;
  return true;
}

/** Build the `sidePanelAutoOpen` detail from an openable visualization. */
export function toAutoOpenDetail(v: ToolVisualization): AutoOpenDetail {
  return { type: v.type, id: v.id, title: v.title, runId: v.runId };
}

/**
 * `_system_stop` / `_system_error` are live-stream UI markers (the chat feed turns
 * them into a "Stopped"/"Error" indicator). They are not real tools and must never
 * render as a row in the activity card - filter them on both the live and persisted
 * paths so the two stay symmetric.
 */
export function isSystemMarkerActivity(a: { toolName: string }): boolean {
  return a.toolName === '_system_stop' || a.toolName === '_system_error';
}

/**
 * Scroll to a chat message and briefly flash an accent ring on its user bubble
 * (the `rounded-[18px]` element, falling back to the message row) so the user sees
 * which message was jumped to. Used by the Conversation Activity card's "go to
 * message". No-op if the message is not in the DOM. The `.message-jump-highlight`
 * class is removed on `animationend` so a re-jump always restarts the animation.
 */
export function scrollToAndHighlightMessage(messageId: string, doc: Document = document): void {
  const row = doc.getElementById(`message-${messageId}`);
  if (!row) return;
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  const target = (row.querySelector('[class*="rounded-[18px]"]') as HTMLElement | null) ?? row;
  target.classList.remove('message-jump-highlight');
  void target.offsetWidth; // force reflow so re-adding restarts the animation
  target.classList.add('message-jump-highlight');
  target.addEventListener(
    'animationend',
    () => target.classList.remove('message-jump-highlight'),
    { once: true },
  );
}

export interface ParsedMessageActivities {
  tools: ToolActivity[];
  reasoningDurationMs?: number;
}

/**
 * Parse a message's persisted `toolCalls` JSON into deduplicated `ToolActivity[]`
 * plus the reasoning wall-clock duration (from the `_meta` entry).
 *
 * Extracted verbatim from MessageHistory so both surfaces hydrate identically:
 *  - `_meta` carries `reasoningDurationMs` (not a tool row).
 *  - `_thinking` becomes a reasoning row.
 *  - status hydration: explicit `status` wins; persisted `pending` is always
 *    stale here (only persisted rows reach this path) so it coerces to
 *    `interrupted`; legacy rows fall back to the `success` boolean; unknown
 *    defaults to `success` so the header never sticks on "Thinking...".
 *
 * @param toolCalls the raw `message.toolCalls` value (JSON string, array, or undefined)
 * @param fallbackKey a stable key (message id or index) used to synthesize ids when absent
 */
export function parseToolActivitiesFromMessage(
  toolCalls: unknown,
  fallbackKey: string | number,
): ParsedMessageActivities {
  const tools: ToolActivity[] = [];
  let reasoningDurationMs: number | undefined;

  if (toolCalls && toolCalls !== '[]') {
    try {
      const parsedTools = typeof toolCalls === 'string' ? JSON.parse(toolCalls) : toolCalls;
      if (Array.isArray(parsedTools)) {
        for (let idx = 0; idx < parsedTools.length; idx++) {
          const tool = parsedTools[idx];
          const toolName = tool.toolName || tool.name || tool.function?.name || 'Unknown';

          if (toolName === '_meta') {
            reasoningDurationMs = tool.reasoningDurationMs;
            continue;
          }

          if (toolName === '_thinking') {
            const thinkingId = tool.id || `_thinking_${fallbackKey}_${idx}`;
            tools.push({
              id: thinkingId,
              toolName: '_thinking',
              toolId: thinkingId,
              status: 'success',
              thinkingTitle: tool.title || '',
              thinkingMessage: tool.thinkingMessage || '',
              timestamp: tool.timestamp || Date.now(),
            });
            continue;
          }

          const args = tool.arguments || tool.function?.arguments || '';
          const toolCallId = tool.id || `tool-${fallbackKey}-${idx}`;

          const iconSlug: string | undefined = tool.iconSlug;
          const displayToolName: string | undefined = tool.displayToolName;
          const label: string | undefined = tool.label;

          let hydratedStatus: 'success' | 'error' | 'interrupted';
          if (tool.status === 'success' || tool.status === 'error' || tool.status === 'interrupted') {
            hydratedStatus = tool.status;
          } else if (tool.status === 'pending') {
            hydratedStatus = 'interrupted';
          } else if (tool.success === true) {
            hydratedStatus = 'success';
          } else if (tool.success === false) {
            hydratedStatus = 'error';
          } else {
            hydratedStatus = 'success';
          }
          const hydratedError = hydratedStatus === 'interrupted' ? undefined : tool.error;

          tools.push({
            id: toolCallId,
            toolName,
            toolId: toolCallId,
            arguments: typeof args === 'string' ? args : JSON.stringify(args),
            status: hydratedStatus,
            result: tool.result,
            resultId: tool.resultId,
            durationMs: tool.durationMs,
            error: hydratedError,
            visualization: tool.visualization,
            tasksData: tool.tasksData,
            serviceApproval: tool.serviceApproval,
            diff: tool.diff,
            gitStatus: tool.gitStatus,
            timestamp: tool.timestamp || Date.now(),
            iconSlug,
            displayToolName,
            label,
          });
        }
      }
    } catch {
      // Ignore parse errors - a malformed toolCalls payload yields no rows.
    }
  }

  // Deduplicate by id (legacy rows can carry duplicate ids).
  const seenIds = new Set<string>();
  const deduped = tools.filter(tool => {
    if (seenIds.has(tool.id)) return false;
    seenIds.add(tool.id);
    return true;
  });

  return { tools: deduped, reasoningDurationMs };
}

export type ExecutionGroupStatus = 'success' | 'error' | 'running';

/**
 * One execution = one chat turn: the user's sent message plus every message the
 * agent produced answering it, with its reasoning + tool activity aggregated.
 */
export interface ExecutionGroup {
  /** Stable react key: the executionId when known, else a turn-derived key. */
  key: string;
  /** Server execution id (for metrics). Absent for legacy/pre-execution turns. */
  executionId?: string;
  messages: Message[];
  /** The sent user message - the jump-to-message target. */
  firstUserMessage?: { id: string; preview: string; timestamp?: string };
  /** Reasoning + tool rows across the turn's assistant messages, in order. */
  tools: ToolActivity[];
  reasoningDurationMs?: number;
  startedAt?: string;
  endedAt?: string;
  status: ExecutionGroupStatus;
}

function firstLine(text: string, max = 120): string {
  const trimmed = (text || '').replace(/\s+/g, ' ').trim();
  return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

/**
 * Group a conversation's chronological messages into per-execution turns.
 *
 * A new group starts at each `user` message; following non-user messages join it.
 * The turn's executionId is taken from whichever message carries one (the assistant
 * message is authoritative, so the last non-null wins) - robust whether or not the
 * user message itself was stamped. Messages before the first user message form a
 * leading group with no sent message.
 */
export function groupMessagesByExecution(messages: Message[]): ExecutionGroup[] {
  const groups: ExecutionGroup[] = [];
  let current: ExecutionGroup | null = null;

  const startGroup = (seedKey: string): ExecutionGroup => {
    const g: ExecutionGroup = { key: seedKey, messages: [], tools: [], status: 'success' };
    groups.push(g);
    return g;
  };

  for (const msg of messages) {
    if (msg.role === 'user') {
      current = startGroup(`turn-${msg.id}`);
      current.firstUserMessage = {
        id: msg.id,
        preview: firstLine(msg.content || ''),
        timestamp: msg.createdAt || msg.timestamp,
      };
    } else if (!current) {
      current = startGroup(`pre-${msg.id}`);
    }

    current.messages.push(msg);
    if (msg.executionId) {
      current.executionId = msg.executionId;
      current.key = msg.executionId;
    }
  }

  // Aggregate reasoning/tools, timing and status per group.
  for (const g of groups) {
    let reasoningSum = 0;
    let hasReasoning = false;
    for (const msg of g.messages) {
      const { tools, reasoningDurationMs } = parseToolActivitiesFromMessage(msg.toolCalls, msg.id);
      const rows = tools.filter(t => !isSystemMarkerActivity(t));
      if (rows.length > 0) g.tools.push(...rows);
      if (typeof reasoningDurationMs === 'number') {
        reasoningSum += reasoningDurationMs;
        hasReasoning = true;
      }
    }
    if (hasReasoning) g.reasoningDurationMs = reasoningSum;

    const times = g.messages
      .map(m => m.createdAt || m.timestamp)
      .filter((t): t is string => !!t);
    if (times.length > 0) {
      g.startedAt = times[0];
      g.endedAt = times[times.length - 1];
    }
    g.status = g.tools.some(t => t.status === 'error') ? 'error' : 'success';
  }

  return groups;
}
