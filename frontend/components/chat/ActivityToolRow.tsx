'use client';

import React from 'react';
import Image from 'next/image';
import { ArrowUpRight } from 'lucide-react';
import type { ToolActivity } from '@/contexts/StreamingContext';
import { getToolIconType, getToolDescription } from '@/lib/utils/activityGrouping';
import { toolIcons, formatToolName } from '@/components/chat/ActivityFeed';
import { isOpenableVisualization, toAutoOpenDetail } from '@/lib/chat/messageActivity';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';

function formatDurationMs(ms: number): string {
  if (ms < 1000) return '< 1s';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

/** Open the right side panel for an openable tool resource. */
function openResourcePanel(activity: ToolActivity) {
  if (!isOpenableVisualization(activity.visualization)) return;
  window.dispatchEvent(
    new CustomEvent('sidePanelAutoOpen', { detail: toAutoOpenDetail(activity.visualization) }),
  );
}

interface ActivityToolRowProps {
  activity: ToolActivity;
}

/**
 * A single tool row inside the Conversation Activity card.
 *
 * Differs from the chat reasoning feed's TimelineItem: clicking does NOT expand
 * an inline result. Instead:
 *  - `_thinking` rows render the reasoning text (non-interactive).
 *  - tools that produced an openable resource (a visualization the right side
 *    panel can render) become a button that opens that panel on click.
 *  - every other tool (plain web_search, catalog calls with no resource, ...)
 *    is a static row with no body and no affordance.
 */
export function ActivityToolRow({ activity }: ActivityToolRowProps) {
  const isThinking = activity.toolName === '_thinking';

  // Reasoning row - text only, like the chat feed.
  if (isThinking) {
    if (!activity.thinkingTitle && !activity.thinkingMessage) return null;
    return (
      <div className="relative flex gap-2 pl-[7px] mb-2">
        <div className="absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full bg-slate-400 dark:bg-slate-500" />
        <div className="absolute left-[2.5px] top-[14px] bottom-[-8px] w-px bg-slate-200 dark:bg-slate-700" />
        <div className="flex-1 ml-3 min-w-0">
          {activity.thinkingTitle && (
            <div className="text-sm text-slate-600 dark:text-slate-400 mb-0.5">{activity.thinkingTitle}</div>
          )}
          {activity.thinkingMessage && (
            <div className="text-sm text-slate-500 dark:text-slate-400 whitespace-pre-wrap break-words">
              {activity.thinkingMessage}
            </div>
          )}
        </div>
      </div>
    );
  }

  const isError = activity.status === 'error' || !!activity.error;
  const isPending = activity.status === 'pending';
  const openable = isOpenableVisualization(activity.visualization);

  const description = getToolDescription(
    activity.toolName,
    activity.arguments,
    activity.visualization,
    activity.result,
  );
  const iconType = getToolIconType(activity.toolName);
  const icon = iconType ? toolIcons[iconType] : null;
  const hasApiIcon = !!activity.iconSlug;

  const displayName = activity.label
    ? activity.displayToolName
      ? `${activity.label} (${activity.displayToolName.replace(/_/g, ' ')})`
      : activity.label
    : activity.displayToolName
      ? activity.displayToolName.replace(/_/g, ' ')
      : description || formatToolName(activity.toolName);

  const dot = (
    <div
      className={`absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full ${
        isError ? 'bg-red-500' : isPending ? 'bg-blue-500 animate-pulse' : 'bg-slate-400 dark:bg-slate-500'
      }`}
    />
  );

  const inner = (
    <>
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
      ) : (
        icon
      )}
      <span className="text-sm text-slate-700 dark:text-slate-200 leading-5 truncate">{displayName}</span>
      {!isPending && activity.durationMs !== undefined && (
        <span className="text-xs text-slate-400 shrink-0">{formatDurationMs(activity.durationMs)}</span>
      )}
      {openable && <ArrowUpRight className="h-3.5 w-3.5 text-slate-400 shrink-0" />}
    </>
  );

  return (
    <div className="relative flex gap-2 pl-[7px] mb-2">
      {dot}
      <div className="absolute left-[2.5px] top-[14px] bottom-[-8px] w-px bg-slate-200 dark:bg-slate-700" />
      <div className="flex-1 ml-3 min-w-0">
        {openable ? (
          <button
            type="button"
            onClick={() => openResourcePanel(activity)}
            title={displayName}
            className="group/row flex items-center gap-2 text-left w-full hover:text-theme-primary transition-colors"
          >
            {inner}
          </button>
        ) : (
          <div className="flex items-center gap-2 min-w-0">{inner}</div>
        )}
      </div>
    </div>
  );
}

export default ActivityToolRow;
