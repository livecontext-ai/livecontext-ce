'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDown, ChevronRight } from 'lucide-react';

/**
 * How many of the most recent entries a reasoning feed renders before hiding the
 * rest behind one row. It bounds BOTH levels: the feed's steps, and the calls
 * inside one grouped tool card. A long agent turn is dozens of tool calls; the
 * reader wants the tail, not a wall.
 */
export const VISIBLE_STEPS = 4;

interface StepHistoryToggleProps {
  /** Steps currently hidden; 0 means the history is already open. */
  hiddenCount: number;
  onToggle: () => void;
  /** Timeline chrome (dot + line). The nested list inside a group has none. */
  withTimelineChrome?: boolean;
  /** Distinct per level: both can be on screen at once, inside one another. */
  testId: string;
}

/**
 * The one control that hides or reveals the older steps of a reasoning feed.
 *
 * <p>It carries no `aria-expanded`: it does not show or hide one element, it
 * changes how many siblings exist, so there is no target an `aria-controls`
 * could name. The label states the action and the state instead ("Show 5
 * previous steps" / "Hide previous steps").
 *
 * Two levels use it and must behave identically: the feed itself (older tool
 * steps) and a grouped tool card (older calls of one tool). It is deliberately
 * two-way - a reader who opened the history can close it again, like every other
 * disclosure in the feed.
 */
export function StepHistoryToggle({ hiddenCount, onToggle, withTimelineChrome = true, testId }: StepHistoryToggleProps) {
  const t = useTranslations('chat.activityFeed');
  const isOpen = hiddenCount === 0;

  const button = (
    <button
      type="button"
      onClick={onToggle}
      data-testid={testId}
      className="text-sm text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 flex items-center gap-1"
    >
      {isOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
      <span>{isOpen ? t('hidePreviousSteps') : t('previousSteps', { count: hiddenCount })}</span>
    </button>
  );

  if (!withTimelineChrome) return <div className="mb-2">{button}</div>;

  return (
    <div className="relative flex gap-2 pl-[7px] mb-3">
      <div className="absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full bg-slate-400 dark:bg-slate-500" />
      <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />
      <div className="flex-1 ml-3">{button}</div>
    </div>
  );
}

export default StepHistoryToggle;
