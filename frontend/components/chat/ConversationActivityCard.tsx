'use client';

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight, Loader2, Check, AlertCircle } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import type { Message } from '@/lib/api/conversation.types';
import type { ToolActivity } from '@/contexts/StreamingContext';
import { groupMessagesByExecution, isSystemMarkerActivity, type ExecutionGroup } from '@/lib/chat/messageActivity';
import { ActivityToolRow } from '@/components/chat/ActivityToolRow';
import { GroupedToolCard } from '@/components/chat/GroupedToolCard';
import { useStableGroupedActivities } from '@/hooks/useStableGroupedActivities';
import { isGroupedTool } from '@/lib/utils/activityGrouping';
import { useAgentExecution } from '@/components/agent-fleet/hooks/useAgentExecutionData';
import { getClientLocale } from '@/lib/utils/locale';
import { IS_CE } from '@/lib/edition';
import { formatCost } from '@/lib/format-cost';
import { formatUtcTime } from '@/lib/utils/dateFormatters';

function firstLine(text: string, max = 120): string {
  const trimmed = (text || '').replace(/\s+/g, ' ').trim();
  return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

function formatDurationMs(ms: number): string {
  if (ms < 1000) return '< 1s';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

export interface ConversationActivityCardProps {
  messages: Message[];
  /** Live tool activities for the in-flight turn (from StreamingContext). */
  liveToolActivities: ToolActivity[];
  isStreaming: boolean;
  hasMoreMessages: boolean;
  loadingOlderMessages: boolean;
  onLoadOlderMessages: () => void;
  onJumpToMessage: (messageId: string) => void;
  /** Close the card. Used by the tablet/mobile focus backdrop (the AppHeader
   *  toggle is the primary close on desktop). */
  onClose: () => void;
  /** Center the card (top-middle) instead of docking it top-right. Set when the
   *  right side panel is open so the card sits in the middle of the (shrunken)
   *  conversation area instead of colliding with the panel. */
  centered?: boolean;
}

/**
 * Conversation Activity card - a RunInfo-style floating panel (top-right of the
 * conversation) that lists the conversation's activity aggregated by execution.
 *
 * The panel matches the workflow RunInfo surface (rounded, bordered, no shadow);
 * there is no in-card header or close button - the user toggles it from the
 * focused AppHeader icon. Each execution is borderless and rendered in the chat
 * reasoning style: a collapsible timeline of tool rows that ends in a terminal
 * marker (Done / Working / Failed). Tool rows open the right side panel for any
 * resource they produced.
 */
export function ConversationActivityCard({
  messages,
  liveToolActivities,
  isStreaming,
  hasMoreMessages,
  loadingOlderMessages,
  onLoadOlderMessages,
  onJumpToMessage,
  onClose,
  centered = false,
}: ConversationActivityCardProps) {
  const t = useTranslations();
  const scrollRef = useRef<HTMLDivElement>(null);

  const groups = useMemo(() => groupMessagesByExecution(messages), [messages]);

  // Overlay the live (in-flight) turn onto the newest group when streaming so the
  // card shows real-time tool activity; fall back to a synthetic "live" group if
  // the newest persisted group is not the turn being answered.
  const display = useMemo<ExecutionGroup[]>(() => {
    if (!isStreaming) return groups;
    const liveTools = liveToolActivities.filter(a => !isSystemMarkerActivity(a));
    const lastUser = [...messages].reverse().find(m => m.role === 'user');
    const cloned = groups.slice();
    const newestIdx = cloned.length - 1;
    const newest = cloned[newestIdx];
    if (newest && lastUser && newest.firstUserMessage?.id === lastUser.id) {
      cloned[newestIdx] = {
        ...newest,
        tools: liveTools.length > 0 ? liveTools : newest.tools,
        status: 'running',
      };
    } else if (liveTools.length > 0 || lastUser) {
      cloned.push({
        key: 'live',
        messages: [],
        tools: liveTools,
        status: 'running',
        firstUserMessage: lastUser
          ? { id: lastUser.id, preview: firstLine(lastUser.content || ''), timestamp: lastUser.timestamp }
          : undefined,
      });
    }
    return cloned;
  }, [groups, isStreaming, messages, liveToolActivities]);

  // Expansion: newest (last) group expanded by default; user can override per group.
  // Keyed on the SENT-message id (stable) rather than `key`, which flips from
  // `turn-<id>` to the executionId once the in-flight turn persists - so a user's
  // collapse choice survives that transition.
  const stableId = (g: ExecutionGroup) => g.firstUserMessage?.id ?? g.key;
  const [overrides, setOverrides] = useState<Record<string, boolean>>({});
  const lastId = display.length > 0 ? stableId(display[display.length - 1]) : undefined;
  const isExpanded = (g: ExecutionGroup) => overrides[stableId(g)] ?? stableId(g) === lastId;
  const toggle = (g: ExecutionGroup) => {
    const id = stableId(g);
    setOverrides(prev => ({ ...prev, [id]: !(prev[id] ?? id === lastId) }));
  };

  // Keep the newest activity in view: scroll to bottom on open and as the in-flight
  // turn streams, but only when the user is already near the bottom.
  const nearBottomRef = useRef(true);
  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    nearBottomRef.current = el.scrollHeight - (el.scrollTop + el.clientHeight) < 120;
  };
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, []);
  useEffect(() => {
    const el = scrollRef.current;
    if (el && nearBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [display.length, liveToolActivities.length]);

  return (
    <>
      {/* Tablet/mobile focus backdrop - dims the conversation behind the
          top-centered card (desktop docks it top-right with no backdrop). Tap to
          close, mirroring the side panel's mobile overlay. */}
      <div
        className="absolute inset-0 z-10 bg-black/50 lg:hidden"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        className={`absolute top-4 z-20 flex max-h-[min(70vh,420px)] w-[min(92vw,340px)] flex-col overflow-hidden rounded-[18px] border border-theme bg-theme-secondary ${
          centered
            ? 'left-1/2 -translate-x-1/2'
            : 'left-1/2 -translate-x-1/2 lg:left-auto lg:right-4 lg:translate-x-0 lg:border-0'
        }`}
        role="dialog"
        aria-label={t('conversationActivity.title')}
      >
      <div ref={scrollRef} onScroll={onScroll} className="model-selector-scroll flex-1 overflow-y-auto px-3 py-3">
        {hasMoreMessages && (
          <div className="flex justify-center pb-2">
            <button
              type="button"
              onClick={onLoadOlderMessages}
              disabled={loadingOlderMessages}
              title={t('conversationActivity.loadOlder')}
              className="text-xs text-theme-secondary hover:text-theme-primary transition-colors disabled:opacity-60"
            >
              {loadingOlderMessages ? (
                <LoadingSpinner size="sm" />
              ) : (
                t('conversationActivity.loadOlder')
              )}
            </button>
          </div>
        )}

        {display.length === 0 ? (
          <div className="py-8 text-center text-sm text-theme-secondary">
            {t('conversationActivity.empty')}
          </div>
        ) : (
          display.map((group, i) => (
            <ActivityExecutionGroup
              key={group.key}
              group={group}
              expanded={isExpanded(group)}
              onToggle={() => toggle(group)}
              onJump={onJumpToMessage}
              isLast={i === display.length - 1}
            />
          ))
        )}
      </div>
      </div>
    </>
  );
}

interface ActivityExecutionGroupProps {
  group: ExecutionGroup;
  expanded: boolean;
  onToggle: () => void;
  onJump: (messageId: string) => void;
  /** Last execution in the list - suppresses the trailing connector line. */
  isLast: boolean;
}

function ActivityExecutionGroup({ group, expanded, onToggle, onJump, isLast }: ActivityExecutionGroupProps) {
  const t = useTranslations();
  const locale = getClientLocale();

  // Group consecutive same-name tool calls exactly like the chat reasoning feed
  // (e.g. every `workflow` action collapses into one "Workflow" card); _thinking /
  // agent / system tools stay ungrouped. Identical while streaming and on refresh.
  const grouped = useStableGroupedActivities(group.tools);

  // Full observability metrics for this execution (cached, 60s). Disabled until an
  // executionId exists (live/legacy turns have none yet).
  const { data: metrics } = useAgentExecution(group.executionId ?? null);

  // Status: the execution record is authoritative (a model-level failure such as
  // BUDGET_EXHAUSTED / a provider error has no failed TOOL row, so tool-derived
  // status alone would render it green). Fall back to the tool-derived status when
  // no metrics are loaded yet.
  const metricStatus = metrics?.status?.toUpperCase();
  const isRunning = group.status === 'running' || metricStatus === 'RUNNING';
  const isError =
    !isRunning &&
    (group.status === 'error' ||
      metricStatus === 'FAILED' ||
      metricStatus === 'CANCELLED' ||
      metricStatus === 'TIMEOUT');

  const durationMs =
    metrics?.durationMs ??
    (group.startedAt && group.endedAt
      ? Math.max(0, new Date(group.endedAt).getTime() - new Date(group.startedAt).getTime())
      : undefined);

  const startLabel = group.startedAt ? formatUtcTime(group.startedAt, { locale }) : null;
  const tokens = metrics?.totalTokens;
  const credits = metrics?.creditsConsumed;
  const iterations = metrics?.iterationCount;

  const title =
    group.firstUserMessage?.preview || t('conversationActivity.executionFallback');

  return (
    <div className="relative flex gap-2 pl-[7px]">
      {/* Execution dot - reasoning-style timeline node (colored by status). */}
      <div
        className={`absolute left-0 top-[7px] h-1.5 w-1.5 rounded-full ${
          isRunning ? 'animate-pulse bg-blue-500' : isError ? 'bg-red-500' : 'bg-slate-400 dark:bg-slate-500'
        }`}
      />
      {/* Connector line down to the next execution (tree); suppressed on the last. */}
      {!isLast && (
        <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />
      )}

      <div className="ml-3 min-w-0 flex-1 pb-3">
        {/* Header row - title (jump) then spinner, chevron on the RIGHT. */}
        <div className="group/exec flex items-center gap-1.5">
          {group.firstUserMessage ? (
            <button
              type="button"
              onClick={() => onJump(group.firstUserMessage!.id)}
              title={t('conversationActivity.jumpToMessage')}
              className="flex-1 truncate text-left text-sm font-medium text-theme-primary hover:underline"
            >
              {title}
            </button>
          ) : (
            <span className="flex-1 truncate text-sm font-medium text-theme-secondary">{title}</span>
          )}
          {isRunning && <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-blue-500" />}
          <button
            type="button"
            onClick={onToggle}
            className="flex shrink-0 items-center justify-center text-theme-secondary hover:text-theme-primary"
            aria-expanded={expanded}
            title={expanded ? t('conversationActivity.collapse') : t('conversationActivity.expand')}
          >
            {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </button>
        </div>

      {/* Meta line - RunInfo-style: gray, dot-separated (started / duration / metrics). */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 pt-0.5 text-xs">
        {startLabel && <span className="tabular-nums text-gray-600 dark:text-gray-400">{startLabel}</span>}
        {durationMs !== undefined && (
          <>
            <span className="text-gray-400 dark:text-gray-500">·</span>
            <span className="text-gray-600 dark:text-gray-400">{formatDurationMs(durationMs)}</span>
          </>
        )}
        {typeof tokens === 'number' && tokens > 0 && (
          <>
            <span className="text-gray-400 dark:text-gray-500">·</span>
            <span className="text-gray-600 dark:text-gray-400">{t('conversationActivity.tokens', { count: tokens.toLocaleString(locale) })}</span>
          </>
        )}
        {typeof iterations === 'number' && iterations > 0 && (
          <>
            <span className="text-gray-400 dark:text-gray-500">·</span>
            <span className="text-gray-600 dark:text-gray-400">{t('conversationActivity.iterations', { count: iterations })}</span>
          </>
        )}
        {typeof credits === 'number' && credits > 0 && (
          <>
            <span className="text-gray-400 dark:text-gray-500">·</span>
            {/* Credits are a Cloud-only billing unit. CE uses the user's own LLM keys,
                so show the dollar equivalent ($) instead (formatCost handles both). */}
            <span className="text-gray-600 dark:text-gray-400">
              {IS_CE
                ? formatCost(credits)
                : t('conversationActivity.credits', { count: credits.toLocaleString(locale) })}
            </span>
          </>
        )}
      </div>

      {/* Body - reasoning-style grouped timeline: consecutive same-tool calls
          collapse into one GroupedToolCard (like the chat reasoning feed, both
          while streaming and after refresh); _thinking / agent / system tools
          render as single rows. Ends with a terminal marker. */}
      {/* Body - indented one tab under the message; reasoning-style grouped timeline. */}
      {expanded && (
        <div className="relative flex flex-col pl-4 pt-2">
          {grouped.map(item =>
            isGroupedTool(item) ? (
              <GroupedToolCard key={item.id} group={item} isStreaming={isRunning} />
            ) : (
              <ActivityToolRow key={item.id} activity={item} />
            ),
          )}
          <ActivityTerminalMarker isRunning={isRunning} isError={isError} hasTools={group.tools.length > 0} />
        </div>
      )}
      </div>
    </div>
  );
}

/**
 * Reasoning-style terminal marker closing an execution's timeline: a pulsing
 * spinner while running, a red Failed when the execution record reports a
 * failure, otherwise a Done check (mirrors the chat reasoning feed).
 */
function ActivityTerminalMarker({
  isRunning,
  isError,
  hasTools,
}: {
  isRunning: boolean;
  isError: boolean;
  hasTools: boolean;
}) {
  const t = useTranslations();

  if (isRunning) {
    // Reasoning-style running indicator: a blue pulsing dot connected by the tree
    // line to the row above (mirrors the chat reasoning feed's pending dot) instead
    // of a spinner + "Working" text row. The label is kept for screen readers.
    return (
      <div className="relative flex h-3 gap-2 pl-[7px]">
        {hasTools && <div className="absolute left-[2.5px] top-[-12px] h-[18px] w-px bg-slate-200 dark:bg-slate-700" />}
        <div className="absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full bg-blue-500 animate-pulse" />
        <span className="sr-only">{t('conversationActivity.working')}</span>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="relative flex gap-2 pl-[7px]">
        {hasTools && <div className="absolute left-[2.5px] top-[-10px] h-[12px] w-px bg-slate-200 dark:bg-slate-700" />}
        <AlertCircle className="absolute left-[-3px] top-[1px] h-4 w-4 text-red-500" />
        <div className="ml-3 flex-1">
          <span className="text-sm text-red-600 dark:text-red-400">{t('conversationActivity.failed')}</span>
        </div>
      </div>
    );
  }

  // No tools and a completed turn: render nothing (no "Done", no placeholder).
  if (!hasTools) {
    return null;
  }

  return (
    <div className="relative flex gap-2 pl-[7px]">
      <div className="absolute left-[2.5px] top-[-10px] h-[12px] w-px bg-slate-200 dark:bg-slate-700" />
      <Check className="absolute left-[-3px] top-[1px] h-4 w-4 text-slate-500 dark:text-slate-400" />
      <div className="ml-3 flex-1">
        <span className="text-sm text-slate-700 dark:text-slate-200">{t('conversationActivity.done')}</span>
      </div>
    </div>
  );
}

export default ConversationActivityCard;
