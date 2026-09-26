'use client';

import React, { useState, useEffect, useLayoutEffect, useId, useRef, useMemo, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { useExpandedState } from '@/hooks/useExpandedState';
import Image from 'next/image';
import { Check, StopCircle, AlertCircle, PauseCircle, ChevronDown, ChevronRight, Bot, Loader2 } from 'lucide-react';
import { AvatarDisplay } from '@/components/agents';
import { GroupedToolCard } from './GroupedToolCard';
import { StepHistoryToggle, VISIBLE_STEPS } from './StepHistoryToggle';
import { TasksPreviewBlock } from './TasksPreviewBlock';
import { ThinkingGlyph } from './ThinkingGlyph';
import { AskUserAnsweredBlock, parseAskUserQuestions } from './AskUserAnsweredBlock';
import DiffView from './DiffView';
import GitStatusView from './GitStatusView';
import MarkdownRender from '@/components/MarkdownRender';
import { apiClient } from '@/lib/api';
import { useResourceQuery } from '@/lib/hooks/useResourceQuery';
import { isGroupedTool, getToolDescription, getToolIconType } from '@/lib/utils/activityGrouping';
import { useStableGroupedActivities } from '@/hooks/useStableGroupedActivities';
import type { ToolActivity, ToolVisualization } from '@/contexts/StreamingContext';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';
import { toolIcons } from './toolIcons';
import { compactJsonForDisplay } from '@/lib/utils/compactJsonForDisplay';
import { ServiceLogo } from '@/components/ui/service-logo';

// Re-export so existing imports `from '@/components/chat/ActivityFeed'` keep
// working. Single source of truth lives in StreamingContext (live state owns
// the type) - both places used to define ToolActivity / ToolVisualization
// independently and the unions drifted. Re-exporting eliminates the divergence.
export type { ToolActivity, ToolVisualization };

// Tool icon mapping lives in ./toolIcons (one map for every tool renderer);
// re-exported here for existing importers.
export { toolIcons };

// Loading skeleton component
function LoadingSkeleton() {
  return (
    <div className="space-y-2 animate-pulse">
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-3/4" />
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-1/2" />
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-2/3" />
    </div>
  );
}

interface ActivityFeedProps {
  activities: ToolActivity[];
  className?: string;
  thinkingMessage?: string;
  isStreaming?: boolean; // When true, expanded by default. When false (refresh), collapsed by default.
  storedReasoningDurationMs?: number; // Total reasoning duration stored in DB (wall-clock time)
  awaitingApproval?: boolean; // Show "Awaiting approval" instead of "Done" (for service approval flow)
}

// Check if activity is the _system_stop tool (indicates user stopped the stream)
function isSystemStop(activity: ToolActivity): boolean {
  return activity.toolName === '_system_stop';
}

// Check if activity is the _thinking tool (model reasoning from thinking models like Gemini 2.5+/3, o1)
function isThinkingTool(activity: ToolActivity): boolean {
  return activity.toolName === '_thinking';
}

// Check if activity is the _system_error tool (indicates stream error)
function isSystemError(activity: ToolActivity): boolean {
  return activity.toolName === '_system_error';
}


export function ActivityFeed({ activities, className = '', thinkingMessage: externalThinkingMessage, isStreaming = false, storedReasoningDurationMs, awaitingApproval = false }: ActivityFeedProps) {
  const t = useTranslations('chat.activityFeed');
  // Collapsed by default (stored history on refresh); streaming / awaiting
  // approval opens it, exactly as before the bounded-viewport experiment.
  const [isExpanded, setIsExpanded] = useState(isStreaming || awaitingApproval);
  // Older steps stay behind one inline row until the reader asks for them.
  const [showOlderSteps, setShowOlderSteps] = useState(false);
  // A collapse the reader performed themselves outranks the auto-expand below,
  // until the next turn clears the feed. Without it, the feed springs back open
  // the moment a pending call resolves.
  const userCollapsedRef = useRef(false);
  const headerRef = useRef<HTMLButtonElement>(null);
  const timelineId = useId();
  const [duration, setDuration] = useState<number | null>(null);
  const prevHasPendingRef = useRef(false);
  const startTimeRef = useRef<number | null>(null);

  // Memoize filtered activities to prevent unnecessary recalculations
  // Note: _thinking is NOT filtered out - it's displayed as a regular tool in the timeline
  const regularActivities = useMemo(
    () => activities.filter(a => !isSystemStop(a) && !isSystemError(a)),
    [activities]
  );
  const hasStopTool = activities.some(isSystemStop);
  const hasErrorTool = activities.some(isSystemError);
  const hasAwaitingApprovalTool = awaitingApproval;
  const errorActivity = activities.find(isSystemError);

  // Group consecutive tool calls with stable references (prevents unmount/remount)
  const groupedActivities = useStableGroupedActivities(regularActivities);

  // Get the current thinking message from the last pending activity
  const lastPendingActivity = [...regularActivities].reverse().find(a => a.status === 'pending');
  const hasPending = !!lastPendingActivity || !!externalThinkingMessage;

  // Auto-expand when streaming starts, awaiting approval, or has pending activities
  useEffect(() => {
    if ((isStreaming || hasPending || awaitingApproval) && !userCollapsedRef.current) {
      setIsExpanded(true);
    }
  }, [isStreaming, hasPending, awaitingApproval]);

  // Track duration. Capture the elapsed time on either:
  //   1) hasPending flipping false (the natural "completed" path), or
  //   2) a terminal marker arriving (_system_stop / _system_error) - the user
  //      cancelled mid-stream, so hasPending may still be true (the parent
  //      keeps passing externalThinkingMessage) but the run is over from the
  //      user's POV. Without this, the header showed "Reasoning for < 1s"
  //      after a stop because no duration was ever captured.
  useEffect(() => {
    if (hasPending && !prevHasPendingRef.current) {
      startTimeRef.current = Date.now();
      setDuration(null);
    }
    const isTerminated = hasStopTool || hasErrorTool;
    if ((!hasPending || isTerminated) && prevHasPendingRef.current && startTimeRef.current) {
      const elapsed = Math.round((Date.now() - startTimeRef.current) / 1000);
      setDuration(elapsed);
    }
    prevHasPendingRef.current = hasPending && !isTerminated;
  }, [hasPending, hasStopTool, hasErrorTool]);

  // Reset when activities cleared. Every piece of per-turn reader state belongs
  // here: a revealed history or a deliberate collapse must not outlive the turn
  // it was about.
  useEffect(() => {
    if (activities.length === 0) {
      setDuration(null);
      startTimeRef.current = null;
      setShowOlderSteps(false);
      userCollapsedRef.current = false;
    }
  }, [activities.length]);

  // Calculate total duration from activities (for history/refresh)
  // Duration = time from first activity to last activity completion
  const totalDurationMs = useMemo(() => {
    if (hasPending) return null;
    if (regularActivities.length === 0) return null;

    // Find earliest and latest timestamps
    let earliestTimestamp = Infinity;
    let latestEndTime = 0;
    let sumDurationMs = 0;

    for (const activity of regularActivities) {
      if (activity.timestamp < earliestTimestamp) {
        earliestTimestamp = activity.timestamp;
      }
      // End time = timestamp + duration (or just timestamp if no duration)
      const endTime = activity.timestamp + (activity.durationMs || 0);
      if (endTime > latestEndTime) {
        latestEndTime = endTime;
      }
      sumDurationMs += activity.durationMs || 0;
    }

    if (earliestTimestamp === Infinity || latestEndTime === 0) return null;

    const calculatedDuration = latestEndTime - earliestTimestamp;

    // If all timestamps are the same (old messages without stored timestamps),
    // the calculated duration will be very small. Fall back to sum of durations.
    // Threshold: if calculated < 100ms but we have durationMs data, use sum instead.
    if (calculatedDuration < 100 && sumDurationMs > 0) {
      return sumDurationMs;
    }

    return calculatedDuration;
  }, [regularActivities, hasPending]);

  // Display duration priority:
  // 1. Live duration (tracked during streaming)
  // 2. Stored reasoning duration from DB (wall-clock time)
  // 3. Calculated from individual tool durations (fallback)
  const displayDuration = duration
    ?? (storedReasoningDurationMs !== undefined ? Math.round(storedReasoningDurationMs / 1000) : null)
    ?? (totalDurationMs !== null && totalDurationMs > 0 ? Math.max(0, Math.round(totalDurationMs / 1000)) : null);

  const handleToggle = () => {
    userCollapsedRef.current = isExpanded;
    setIsExpanded(!isExpanded);
  };

  // The Done row sits INSIDE the region it collapses, so activating it destroys
  // the focused element. Hand focus back to the header, which is the same
  // control by another name, instead of dropping it on the document body.
  const collapseFromDone = () => {
    handleToggle();
    headerRef.current?.focus();
  };

  // The rendered slice, derived once: the indicators below have to reason about
  // what is actually on screen, not about the whole list.
  const hiddenCount = showOlderSteps ? 0 : Math.max(0, groupedActivities.length - VISIBLE_STEPS);
  const visibleItems = groupedActivities.slice(hiddenCount);
  // A pending call that the cap hid carries no dot of its own, so the standalone
  // one below is the only live cue left. A grouped step counts as visible: its
  // header shows the spinner and the pending count.
  const hasVisiblePendingRow = visibleItems.some(item =>
    isGroupedTool(item)
      ? item.calls.some(call => call.status === 'pending')
      : item.status === 'pending');

  // Don't render if no activities and no thinking message
  if (activities.length === 0 && !externalThinkingMessage) {
    return null;
  }

  const thinkingMessage = externalThinkingMessage
    || lastPendingActivity?.thinkingMessage
    || (lastPendingActivity ? `Using ${formatToolName(lastPendingActivity.toolName)}...` : 'Thinking...');

  const formatDuration = (seconds: number) => {
    if (seconds === 0) return '< 1s';
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}m ${secs}s`;
  };

  return (
    <div className={`group/feed w-full min-w-0 max-w-full ${className}`}>
      {/* Header */}
      <button
        type="button"
        ref={headerRef}
        onClick={handleToggle}
        aria-expanded={isExpanded}
        aria-controls={isExpanded ? timelineId : undefined}
        className="flex min-w-0 flex-wrap items-center gap-2 text-sm text-theme-muted hover:text-theme-secondary transition-colors mb-3"
      >
        {/* Once a terminal marker (_system_stop / _system_error) lands, the
            shimmer must give way to the static "Reasoning for …" label -
            otherwise the user sees the "Stopped" indicator below AND a still-
            running "Thinking…" shimmer above, which contradicts the stop. */}
        {hasPending && !hasStopTool && !hasErrorTool ? (
          <span className="font-medium shimmer-text" data-testid="activity-feed-thinking">
            {/* Inside the shimmer element so one gradient paints the glyph and the word. */}
            <ThinkingGlyph className="mr-1.5" />
            {t('thinking')}
          </span>
        ) : (
          <span className="font-medium text-slate-600 dark:text-slate-300">
            {t('duration', { duration: formatDuration(displayDuration ?? 0) })}
          </span>
        )}
        <div className="shrink-0 opacity-0 group-hover/feed:opacity-100 group-focus-within/feed:opacity-100 [@media(pointer:coarse)]:opacity-100 transition-opacity">
          {isExpanded ? (
            <ChevronDown className="h-4 w-4" />
          ) : (
            <ChevronRight className="h-4 w-4" />
          )}
        </div>
      </button>

      {/* Timeline content - unmounted while collapsed, so a stored conversation
          stays one line per message and pays nothing to render its tools. */}
      {isExpanded && (
        <div
          id={timelineId}
          role="region"
          aria-label={t('timeline')}
          className="relative flex min-w-0 flex-col [overflow-wrap:anywhere]"
        >
          {/* All tool states stay visible, including a pending call, but only
              the most recent VISIBLE_STEPS of them: everything older sits
              behind one row, live feed and stored history alike. */}
          {groupedActivities.length > VISIBLE_STEPS && (
            <StepHistoryToggle
              hiddenCount={hiddenCount}
              onToggle={() => setShowOlderSteps(!showOlderSteps)}
              testId="step-history-toggle"
            />
          )}
          {visibleItems.map(item => (
            isGroupedTool(item)
              ? <GroupedToolCard key={item.id} group={item} isStreaming={isStreaming} />
              : <TimelineItem key={item.id} activity={item} showLine={true} isStreaming={isStreaming} />
          ))}

          {/* Pending indicator - blue pulsing dot when the stream is still working
              and no VISIBLE row already carries one (an external thinking
              message, or a pending call the step cap hid). */}
          {hasPending && !hasStopTool && !hasErrorTool && !hasAwaitingApprovalTool && !hasVisiblePendingRow && regularActivities.length > 0 && (
            <div className="relative flex gap-2 pl-[7px] mb-3">
              <div className="absolute left-[2.5px] top-[-12px] h-[18px] w-px bg-slate-200 dark:bg-slate-700" />
              <div className="absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full bg-blue-500 animate-pulse" />
            </div>
          )}

          {/* Awaiting approval indicator - amber pause icon when stream is paused for user action */}
          {hasAwaitingApprovalTool && (
            <div className="relative flex gap-2 pl-[7px] mb-3">
              {regularActivities.length > 0 && (
                <div className="absolute left-[2.5px] top-[-12px] h-[14px] w-px bg-slate-200 dark:bg-slate-700" />
              )}
              <div className="absolute left-[-3px] top-[2px]">
                <PauseCircle className="h-4 w-4 text-amber-500" />
              </div>
              <div className="flex-1 ml-3">
                <div className="text-sm leading-5 font-medium shimmer-text-amber">
                  {t('awaitingApproval')}
                </div>
              </div>
            </div>
          )}

          {/* Stopped indicator - rendered when _system_stop tool is present */}
          {hasStopTool && (
            <div className="relative flex gap-2 pl-[7px] mb-3">
              {regularActivities.length > 0 && (
                <div className="absolute left-[2.5px] top-[-12px] h-[14px] w-px bg-slate-200 dark:bg-slate-700" />
              )}
              <div className="absolute left-[-3px] top-[2px]">
                <StopCircle className="h-4 w-4 text-red-500" />
              </div>
              <div className="flex-1 ml-3">
                <div className="text-sm leading-5 text-red-600 dark:text-red-400">
                  {t('stopped')}
                </div>
              </div>
            </div>
          )}

          {/* Error indicator - rendered when _system_error tool is present */}
          {hasErrorTool && (
            <div className="relative flex gap-2 pl-[7px] mb-3">
              {regularActivities.length > 0 && (
                <div className="absolute left-[2.5px] top-[-12px] h-[14px] w-px bg-slate-200 dark:bg-slate-700" />
              )}
              <div className="absolute left-[-3px] top-[2px]">
                <AlertCircle className="h-4 w-4 text-red-500" />
              </div>
              <div className="flex-1 ml-3">
                <div className="text-sm leading-5 text-red-600 dark:text-red-400">
                  {t('error')}{errorActivity?.error ? `: ${errorActivity.error}` : ''}
                </div>
              </div>
            </div>
          )}

          {/* Done indicator - only when complete (no pending, no stop, no error, no awaiting approval) */}
          {!hasPending && !hasStopTool && !hasErrorTool && !hasAwaitingApprovalTool && regularActivities.length > 0 && (
            <div
              className="relative flex gap-2 pl-[7px] mb-3 cursor-pointer"
              role="button"
              tabIndex={0}
              aria-label={t('collapse')}
              onClick={collapseFromDone}
              onKeyDown={event => {
                // A click handler with no key handler is a control a keyboard
                // cannot reach.
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  collapseFromDone();
                }
              }}
            >
              <div className="absolute left-[2.5px] top-[-12px] h-[14px] w-px bg-slate-200 dark:bg-slate-700" />
              <div className="absolute left-[-3px] top-[2px]">
                <Check className="h-4 w-4 text-slate-500 dark:text-slate-400" />
              </div>
              <div className="flex-1 ml-3">
                <div className="text-sm text-slate-700 dark:text-slate-200 leading-5">
                  {t('done')}
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface TimelineItemProps {
  activity: ToolActivity;
  showLine: boolean;
  /** During streaming, items are expanded by default */
  isStreaming?: boolean;
}

interface FullToolResult {
  id: string;
  toolName: string;
  success: boolean;
  durationMs: number;
  content: string;
  error: string;
  createdAt: string;
}

function TimelineItem({ activity, showLine, isStreaming = false }: TimelineItemProps) {
  // TimelineItem renders independently of ActivityFeed, so it needs its own
  // translator: the tool no-content fallback below calls t('tool.noContent').
  const t = useTranslations('chat');
  // Pass toolName to check against TOOLS_EXPANDED_BY_DEFAULT allowlist
  const [isExpanded, toggleExpanded] = useExpandedState(activity.id, isStreaming, activity.toolName);
  const [showFullThinking, setShowFullThinking] = useState(false);
  const thinkingRef = useRef<HTMLDivElement>(null);
  const [thinkingOverflows, setThinkingOverflows] = useState(false);

  // Check if this is a thinking tool (from thinking models like Gemini 2.5+/3, o1)
  const isThinking = isThinkingTool(activity);

  // Whether the clamp actually hides anything is a question about LAYOUT, not
  // about character count: a 700-character paragraph can wrap to four lines, and
  // a length threshold would offer a "Show more" that visibly does nothing.
  const measureThinking = useCallback(() => {
    const el = thinkingRef.current;
    if (!el || showFullThinking) return;
    setThinkingOverflows(el.scrollHeight > el.clientHeight + 1);
  }, [showFullThinking]);

  // The chat pane changes width when the side panel opens, which changes the
  // answer. The subscription deliberately does NOT depend on the message: that
  // text grows one streamed chunk at a time, and re-creating the observer per
  // chunk would churn the hottest render path in the chat.
  useLayoutEffect(() => {
    const el = thinkingRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measureThinking);
    observer.observe(el);
    return () => observer.disconnect();
  }, [measureThinking]);

  // A clamped box stops growing at six lines, so the observer alone can never
  // see the overflow start: the message itself has to trigger a measurement.
  useLayoutEffect(measureThinking, [measureThinking, activity.thinkingMessage]);

  // Get user-friendly description of the tool action
  const description = getToolDescription(activity.toolName, activity.arguments, activity.visualization, activity.result);
  const iconType = getToolIconType(activity.toolName);
  const icon = iconType ? toolIcons[iconType] : null;
  const isPending = activity.status === 'pending';
  const isError = activity.status === 'error' || !!activity.error;
  const tasksData = activity.tasksData;
  const hasResult = activity.result || activity.resultId;

  // Fetch result via React Query, the same way a grouped call row does it. The
  // load is driven by the EXPANDED STATE, not by the click that produced it:
  // `useExpandedState` persists a row's expanded flag across unmounts
  // (module-level) while a fetched body would not survive one, so a row the
  // reader opened, then collapsed the feed on, used to come back expanded and
  // EMPTY - rendering the "no content" fallback over a result that exists.
  // useResourceQuery also caches, so re-opening the feed does not re-request.
  const { data: fetchedResult, isLoading: isLoadingResult, error: loadError } = useResourceQuery<FullToolResult, string>({
    queryKey: ['tool-result', activity.resultId || activity.toolId || ''],
    queryFn: () => activity.resultId
      ? apiClient.get<FullToolResult>(`/tool-results/${activity.resultId}`)
      : apiClient.get<FullToolResult>(`/tool-results/by-tool-call/${activity.toolId}`),
    enabled: isExpanded && !!hasResult && !activity.result && !!(activity.resultId || activity.toolId),
    select: (data) => data.content || '',
  });

  // For catalog: use displayToolName and iconSlug from result metadata
  const hasApiIcon = !!activity.iconSlug;
  // For _thinking: no header needed, content shown directly
  const displayName = activity.label
    ? (activity.displayToolName
        ? `${activity.label} (${activity.displayToolName.replace(/_/g, ' ')})`
        : activity.label)
    : activity.displayToolName
    ? activity.displayToolName.replace(/_/g, ' ')
    : description || formatToolName(activity.toolName);

  const handleToggle = useCallback(() => {
    toggleExpanded();
  }, [toggleExpanded]);

  const displayContent = activity.result || fetchedResult;

  // For _thinking: render with title and content (parsed by backend)
  if (isThinking) {
    return (
      <div className={`relative flex gap-2 pl-[7px] mb-3 ${isStreaming ? 'animate-tool-call-in' : ''}`}>
        <div className={`absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full ${
          isPending ? 'bg-slate-400 animate-pulse' : 'bg-slate-400 dark:bg-slate-500'
        }`} />

        {showLine && (
          <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />
        )}

        <div className="flex-1 min-w-0 ml-3">
          {activity.thinkingTitle && (
            <div className="text-sm text-slate-600 dark:text-slate-400 mb-0.5">
              {activity.thinkingTitle}
            </div>
          )}
          {activity.thinkingMessage && (
            <>
              <div
                ref={thinkingRef}
                className={`text-sm text-slate-500 dark:text-slate-400 whitespace-pre-wrap ${showFullThinking ? '' : 'line-clamp-6'}`}
              >
                {activity.thinkingMessage}
              </div>
              {/* A single reasoning block can be thousands of characters, so it
                  is clamped - but never behind a one-way door, and the control
                  appears only when the clamp really hides something. */}
              {(thinkingOverflows || showFullThinking) && (
                <button
                  type="button"
                  aria-expanded={showFullThinking}
                  onClick={() => setShowFullThinking(!showFullThinking)}
                  className="mt-0.5 text-sm text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                >
                  {t(showFullThinking ? 'activityFeed.showLess' : 'activityFeed.showMore')}
                </button>
              )}
            </>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className={`relative flex gap-2 pl-[7px] mb-3 ${isStreaming ? 'animate-tool-call-in' : ''}`}>
      <div className={`absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full ${
        isError ? 'bg-red-500' : isPending ? 'bg-blue-500 animate-pulse' : 'bg-slate-400 dark:bg-slate-500'
      }`} />

      {showLine && (
        <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />
      )}

      <div className="flex-1 ml-3 min-w-0">
        {/* Header - clickable to expand/collapse */}
        <button
          onClick={handleToggle}
          className="group/tool flex items-center gap-2 text-left"
        >
          {/* API icon from iconSlug (for catalog) or fallback to generic icon */}
          {hasApiIcon ? (
            <ServiceLogo as={Image}
              src={`/icons/services/${normalizeIconSlug(activity.iconSlug)}.svg`}
              alt=""
              width={14}
              height={14}
              className="shrink-0"
              onError={(e) => {
                e.currentTarget.style.display = 'none';
              }}
            />
          ) : icon}
          {/* Show displayToolName if available, otherwise description or formatted tool name */}
          <span className="text-sm text-slate-700 dark:text-slate-200 leading-5">
            {displayName}
          </span>
          {/* Duration */}
          {!isPending && activity.durationMs !== undefined && (
            <span className="text-xs text-slate-400">
              {formatDurationMs(activity.durationMs)}
            </span>
          )}
          <div className="opacity-0 group-hover/tool:opacity-100 transition-opacity">
            {isExpanded ? (
              <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
            )}
          </div>
        </button>

        {/* Sub-agent nested activities */}
        {activity.subAgent && (activity.subActivities?.length || activity.subAgentContent || activity.subAgentThinking) && (
          <div className="mt-2 ml-1 pl-3 border-l-2 border-slate-200 dark:border-slate-700">
            <div className="flex items-center gap-1.5 mb-1.5">
              <AvatarDisplay avatarUrl={activity.subAgent.avatarUrl} name={activity.subAgent.name} size="sm" className="!w-4 !h-4" />
              <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">{activity.subAgent.name}</span>
              {activity.subAgentStatus === 'running' && (
                <Loader2 className="h-3 w-3 text-blue-500 animate-spin" />
              )}
            </div>
            {/* Sub-agent thinking */}
            {activity.subAgentThinking && (
              <div className="text-xs text-slate-400 dark:text-slate-500 italic mb-1 line-clamp-2">
                {activity.subAgentThinking.length > 150
                  ? activity.subAgentThinking.slice(-150) + '...'
                  : activity.subAgentThinking}
              </div>
            )}
            {/* Sub-agent streaming content */}
            {activity.subAgentContent && activity.subAgentStatus === 'running' && (
              <div className="text-xs text-slate-600 dark:text-slate-300 mb-1 line-clamp-3">
                {activity.subAgentContent.length > 200
                  ? '...' + activity.subAgentContent.slice(-200)
                  : activity.subAgentContent}
              </div>
            )}
            {/* Sub-agent tool activities */}
            {activity.subActivities?.map((sub) => {
              const subIcon = getToolIconType(sub.toolName);
              const subIconEl = subIcon ? toolIcons[subIcon] : null;
              return (
                <div key={sub.id} className="flex items-center gap-1.5 py-0.5">
                  <div className={`h-1 w-1 rounded-full shrink-0 ${
                    sub.status === 'error' ? 'bg-red-500' :
                    sub.status === 'pending' ? 'bg-blue-400 animate-pulse' :
                    'bg-green-500'
                  }`} />
                  {subIconEl || <Bot className="w-3 h-3 text-slate-400 shrink-0" />}
                  <span className="text-xs text-slate-600 dark:text-slate-300">
                    {sub.label || getToolDescription(sub.toolName, sub.arguments, sub.visualization, sub.result) || formatToolName(sub.toolName)}
                  </span>
                  {sub.durationMs !== undefined && sub.status !== 'pending' && (
                    <span className="text-[10px] text-slate-400">{formatDurationMs(sub.durationMs)}</span>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Expanded content */}
        {isExpanded && (
          tasksData ? (
            <div className="mt-2">
              <TasksPreviewBlock tasksData={tasksData} />
            </div>
          ) : activity.toolName === 'ask_user' && !isLoadingResult && !isError && !loadError
              && parseAskUserQuestions(activity.arguments).length > 0 ? (
            <div className="mt-2">
              <AskUserAnsweredBlock argumentsJson={activity.arguments} resultJson={displayContent} />
            </div>
          ) : activity.diff ? (
            <div className="mt-2">
              <DiffView diff={activity.diff} />
            </div>
          ) : activity.gitStatus ? (
            <div className="mt-2">
              <GitStatusView status={activity.gitStatus} />
            </div>
          ) : (
          <div className="mt-2 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg border border-slate-200 dark:border-slate-700 max-h-48 overflow-y-auto overflow-x-auto w-full break-words">
            {isLoadingResult ? (
              <LoadingSkeleton />
            ) : loadError ? (
              <div className="text-sm text-red-500">{t('tool.loadFailed')}</div>
            ) : displayContent ? (
              <div className="text-sm">
                <MarkdownRender text={formatResultForMarkdown(displayContent)} />
              </div>
            ) : (
              <div className="text-sm text-slate-400 italic">{t('tool.noContent')}</div>
            )}
          </div>
          )
        )}
      </div>
    </div>
  );
}

export function formatToolName(name: string): string {
  // Don't format system tools
  if (name.startsWith('_system_')) {
    return name;
  }
  // Map tool names to display names
  const displayNames: Record<string, string> = {
    datasource: 'Table',
    interface: 'Interface',
    workflow: 'Workflow',
    catalog: 'Catalog',
  };
  const normalized = name.toLowerCase();
  if (displayNames[normalized]) {
    return displayNames[normalized];
  }
  return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function formatDurationMs(ms: number): string {
  if (ms < 1000) return '< 1s';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

function formatJson(str: string): string {
  try {
    const parsed = JSON.parse(str);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return str;
  }
}

/**
 * Format result content for markdown display.
 */
function formatResultForMarkdown(content: string): string {
  try {
    const parsed = JSON.parse(content);

    if (typeof parsed === 'string') {
      return parsed;
    }

    const messageFields = ['message', 'content', 'result', 'summary', 'description', 'text', 'output'];
    for (const field of messageFields) {
      if (parsed[field] && typeof parsed[field] === 'string') {
        const otherKeys = Object.keys(parsed).filter(k => k !== field && k !== 'display' && k !== 'success');
        if (otherKeys.length > 0) {
          const additionalData = otherKeys.reduce((acc, k) => {
            acc[k] = parsed[k];
            return acc;
          }, {} as Record<string, unknown>);
          // Bounded display: huge extras (verbatim configs, prompts...) are
          // compacted so the message never drowns under a wall of JSON.
          // Kept in sync with GroupedToolCard.formatResultForMarkdown.
          return `${parsed[field]}\n\n\`\`\`json\n${compactJsonForDisplay(additionalData)}\n\`\`\``;
        }
        return parsed[field];
      }
    }

    if (Array.isArray(parsed) && parsed.length > 0) {
      const firstItem = parsed[0];
      if (typeof firstItem === 'object' && firstItem !== null && !Array.isArray(firstItem)) {
        const keys = Object.keys(firstItem).slice(0, 5);
        if (keys.length > 0) {
          const header = `| ${keys.join(' | ')} |`;
          const separator = `| ${keys.map(() => '---').join(' | ')} |`;
          const rows = parsed.slice(0, 10).map(item => {
            return `| ${keys.map(k => String(item[k] ?? '')).join(' | ')} |`;
          });
          const tableMarkdown = [header, separator, ...rows].join('\n');
          if (parsed.length > 10) {
            return `${tableMarkdown}\n\n*...et ${parsed.length - 10} autres lignes*`;
          }
          return tableMarkdown;
        }
      }
    }

    return `\`\`\`json\n${compactJsonForDisplay(parsed)}\n\`\`\``;
  } catch {
    return content;
  }
}

export default ActivityFeed;
