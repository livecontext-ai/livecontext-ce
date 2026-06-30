'use client';

import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { useExpandedState } from '@/hooks/useExpandedState';
import Image from 'next/image';
import { Check, StopCircle, AlertCircle, PauseCircle, ChevronDown, ChevronRight, Table, Monitor, Workflow, Bot, Search, HelpCircle, KeyRound, ListChecks, Eye, Code, Plug, Play, Pencil, FolderOpen, Terminal, FileText, Globe, Loader2 } from 'lucide-react';
import { AvatarDisplay } from '@/components/agents';
import { GroupedToolCard } from './GroupedToolCard';
import { TasksPreviewBlock } from './TasksPreviewBlock';
import DiffView from './DiffView';
import GitStatusView from './GitStatusView';
import MarkdownRender from '@/components/MarkdownRender';
import { apiClient } from '@/lib/api';
import { isGroupedTool, getToolDescription, getToolIconType } from '@/lib/utils/activityGrouping';
import { useStableGroupedActivities } from '@/hooks/useStableGroupedActivities';
import type { ToolActivity, ToolVisualization } from '@/contexts/StreamingContext';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

// Re-export so existing imports `from '@/components/chat/ActivityFeed'` keep
// working. Single source of truth lives in StreamingContext (live state owns
// the type) - both places used to define ToolActivity / ToolVisualization
// independently and the unions drifted. Re-exporting eliminates the divergence.
export type { ToolActivity, ToolVisualization };

// Tool icon mapping. Exported so the Conversation Activity card renders tool
// rows with the exact same iconography without duplicating this map.
export const toolIcons: Record<string, React.ReactNode> = {
  table: <Table className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  interface: <Monitor className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  workflow: <Workflow className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  agent: <Bot className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  search: <Search className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  help: <HelpCircle className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  key: <KeyRound className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  tasks: <ListChecks className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  eye: <Eye className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  code: <Code className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  files: <FolderOpen className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  api: <Plug className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  play: <Play className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  pencil: <Pencil className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  // Native Claude Code tools (full agent toolset over the bridge).
  terminal: <Terminal className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  file: <FileText className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  // 'globe' is returned by getToolIconType for web_search/WebFetch/WebSearch; it had
  // no icon entry before, so those tools rendered without one - added here.
  globe: <Globe className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
};

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
  // Start collapsed by default (for history on refresh), expand during streaming or awaiting approval
  const [isExpanded, setIsExpanded] = useState(isStreaming || awaitingApproval);
  const [showOldThinking, setShowOldThinking] = useState(false);
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
    if (isStreaming || hasPending || awaitingApproval) {
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

  // Reset when activities cleared
  useEffect(() => {
    if (activities.length === 0) {
      setDuration(null);
      startTimeRef.current = null;
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
    setIsExpanded(!isExpanded);
  };

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
    <div className={`group/feed ${className}`}>
      {/* Header */}
      <button
        onClick={handleToggle}
        className="flex items-center gap-2 text-base text-theme-muted hover:text-theme-secondary transition-colors mb-3"
      >
        {/* Once a terminal marker (_system_stop / _system_error) lands, the
            shimmer must give way to the static "Reasoning for …" label -
            otherwise the user sees the "Stopped" indicator below AND a still-
            running "Thinking…" shimmer above, which contradicts the stop. */}
        {hasPending && !hasStopTool && !hasErrorTool ? (
          <span className="font-medium shimmer-text">Thinking...</span>
        ) : (
          <span className="font-medium text-slate-600 dark:text-slate-300">
            Reasoning for {formatDuration(displayDuration ?? 0)}
          </span>
        )}
        <div className="opacity-0 group-hover/feed:opacity-100 transition-opacity">
          {isExpanded ? (
            <ChevronDown className="h-4 w-4" />
          ) : (
            <ChevronRight className="h-4 w-4" />
          )}
        </div>
      </button>

      {/* Timeline content */}
      <div className="relative flex flex-col">
        {isExpanded && (
          <>
            {/* Grouped tools (not pending, not _system_stop) - includes _thinking */}
            {(() => {
              // Step 1: filter pending items (existing logic)
              const filteredItems = groupedActivities.filter(item => {
                if (isGroupedTool(item)) {
                  if (item.toolName === '_thinking') return true;
                  if (item.toolName === 'agent' && item.calls?.some(c => c.subAgent)) return true;
                  return item.overallStatus !== 'pending' || item.calls?.some(c => c.status !== 'pending');
                }
                if (item.toolName === '_thinking') return true;
                if (item.toolName === 'agent' && item.subAgent) return true;
                return item.status !== 'pending';
              });

              // Step 2: during streaming, keep only the last VISIBLE_LIMIT items
              // Everything older is hidden behind "Show more"
              const VISIBLE_LIMIT = 4;
              const shouldTruncate = isStreaming && !showOldThinking && filteredItems.length > VISIBLE_LIMIT;
              const hiddenCount = shouldTruncate ? filteredItems.length - VISIBLE_LIMIT : 0;
              const startIdx = shouldTruncate ? hiddenCount : 0;

              // Step 3: render
              const elements: React.ReactNode[] = [];

              // "Show more" button for hidden items
              if (hiddenCount > 0) {
                elements.push(
                  <div key="__show-old-items" className="relative flex gap-2 pl-[7px] mb-3">
                    <div className="absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full bg-slate-400 dark:bg-slate-500" />
                    <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />
                    <div className="flex-1 ml-3">
                      <button
                        onClick={() => setShowOldThinking(true)}
                        className="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 flex items-center gap-1"
                      >
                        <ChevronRight className="h-3 w-3" />
                        <span>Show {hiddenCount} previous {hiddenCount > 1 ? 'steps' : 'step'}</span>
                      </button>
                    </div>
                  </div>
                );
              }

              // Render visible items
              for (let i = startIdx; i < filteredItems.length; i++) {
                const item = filteredItems[i];
                if (isGroupedTool(item)) {
                  elements.push(<GroupedToolCard key={item.id} group={item} isStreaming={isStreaming} />);
                } else {
                  elements.push(
                    <TimelineItem
                      key={item.id}
                      activity={item}
                      showLine={true}
                      isStreaming={isStreaming}
                    />
                  );
                }
              }

              return elements;
            })()}

            {/* Pending indicator - blue pulsing dot when tools are pending */}
            {hasPending && !hasStopTool && !hasErrorTool && !hasAwaitingApprovalTool && regularActivities.filter(a => a.status !== 'pending').length > 0 && (
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
                  <div className="text-base leading-5 font-medium shimmer-text-amber">
                    Awaiting approval
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
                  <div className="text-base leading-5 text-red-600 dark:text-red-400">
                    Stopped
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
                  <div className="text-base leading-5 text-red-600 dark:text-red-400">
                    Error{errorActivity?.error ? `: ${errorActivity.error}` : ''}
                  </div>
                </div>
              </div>
            )}

            {/* Done indicator - only when complete (no pending, no stop, no error, no awaiting approval) */}
            {!hasPending && !hasStopTool && !hasErrorTool && !hasAwaitingApprovalTool && regularActivities.length > 0 && (
              <div
                className="relative flex gap-2 pl-[7px] mb-3 cursor-pointer"
                onClick={handleToggle}
              >
                <div className="absolute left-[2.5px] top-[-12px] h-[14px] w-px bg-slate-200 dark:bg-slate-700" />
                <div className="absolute left-[-3px] top-[2px]">
                  <Check className="h-4 w-4 text-slate-500 dark:text-slate-400" />
                </div>
                <div className="flex-1 ml-3">
                  <div className="text-base text-slate-700 dark:text-slate-200 leading-5">
                    Done
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>
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
  const [fetchedResult, setFetchedResult] = useState<string | null>(null);
  const [isLoadingResult, setIsLoadingResult] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Check if this is a thinking tool (from thinking models like Gemini 2.5+/3, o1)
  const isThinking = isThinkingTool(activity);

  // Get user-friendly description of the tool action
  const description = getToolDescription(activity.toolName, activity.arguments, activity.visualization, activity.result);
  const iconType = getToolIconType(activity.toolName);
  const icon = iconType ? toolIcons[iconType] : null;
  const isPending = activity.status === 'pending';
  const isError = activity.status === 'error' || !!activity.error;
  const tasksData = activity.tasksData;
  const hasResult = activity.result || activity.resultId;

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

  // Fetch result content when expanding (on demand)
  const handleToggle = useCallback(async () => {
    const willExpand = !isExpanded;
    toggleExpanded();

    // Load result content when expanding (if not already loaded)
    if (willExpand && hasResult && !activity.result && !fetchedResult) {
      if (activity.resultId || activity.toolId) {
        setIsLoadingResult(true);
        setLoadError(null);
        try {
          let response: FullToolResult;
          if (activity.resultId) {
            response = await apiClient.get<FullToolResult>(`/tool-results/${activity.resultId}`);
          } else {
            response = await apiClient.get<FullToolResult>(`/tool-results/by-tool-call/${activity.toolId}`);
          }
          setFetchedResult(response.content || '');
        } catch (err) {
          console.error('Failed to load result:', err);
          setLoadError('Failed to load result');
        } finally {
          setIsLoadingResult(false);
        }
      }
    }
  }, [isExpanded, toggleExpanded, hasResult, activity.result, activity.resultId, activity.toolId, fetchedResult]);

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

        <div className="flex-1 ml-3">
          {activity.thinkingTitle && (
            <div className="text-sm text-slate-600 dark:text-slate-400 mb-0.5">
              {activity.thinkingTitle}
            </div>
          )}
          {activity.thinkingMessage && (
            <div className="text-sm text-slate-500 dark:text-slate-400 whitespace-pre-wrap">
              {activity.thinkingMessage}
            </div>
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
            <Image
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
              <div className="text-sm text-red-500">{loadError}</div>
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
          return `${parsed[field]}\n\n\`\`\`json\n${JSON.stringify(additionalData, null, 2)}\n\`\`\``;
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

    return `\`\`\`json\n${JSON.stringify(parsed, null, 2)}\n\`\`\``;
  } catch {
    return content;
  }
}

export default ActivityFeed;
