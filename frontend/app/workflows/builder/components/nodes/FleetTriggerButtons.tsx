'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Webhook, CalendarClock, Copy, Check } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { BTN_CLS, ShimmerOverlay } from './NodeBottomBar';

export interface FleetTriggers {
  hasWebhook: boolean;
  hasSchedule: boolean;
  webhookUrl?: string;
  cronExpression?: string;
  timezone?: string;
}

// Amber accent marks the "this agent has a trigger" affordance.
const FLEET_TRIGGER_SHIMMER = 'rgba(245, 158, 11, 0.3)';

/**
 * Fleet trigger bottom buttons (webhook / schedule).
 *
 * Rendered inside the agent node's NodeBottomBar (below the agent), using the exact
 * same round button + shimmer as the workflow node bottom buttons / trigger launcher
 * (BTN_CLS + ShimmerOverlay). Each button reveals an upward hover tooltip with the
 * webhook URL (copyable) or the cron schedule, preserving the old corner-badge
 * functionality while matching the workflow node bottom-button look.
 */
export function FleetTriggerButtons({ triggers, borderColor }: {
  triggers: FleetTriggers;
  borderColor: string;
}) {
  const triggerPanelT = useTranslations('triggerPanel');
  const scheduleT = useTranslations('workflowBuilder.inspector.scheduleTrigger');
  const [copied, setCopied] = React.useState(false);
  const copyUrl = React.useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (triggers.webhookUrl) {
      await navigator.clipboard.writeText(triggers.webhookUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, [triggers.webhookUrl]);

  const borderStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor };
  const webhookLabel = triggerPanelT('webhookTriggerTitle');
  const scheduleLabel = scheduleT('scheduleLabel');

  // JS-state reveal (not pure CSS :hover). The old FleetTriggerBadge toggled the tooltip via
  // onMouseEnter/onFocus; we keep that so the tooltip also reveals on keyboard focus (a11y) and is
  // deterministically testable (pure `group-hover` :hover can't be exercised in jsdom, and a synthetic
  // hover on a node clipped at a panel edge does not reliably activate it). The `group-hover`/
  // `group-focus-within` classes stay as a CSS fallback.
  const [webhookOpen, setWebhookOpen] = React.useState(false);
  const [scheduleOpen, setScheduleOpen] = React.useState(false);

  return (
    <div data-testid="fleet-trigger-badge" className="flex items-center gap-1.5">
      {triggers.hasWebhook && (
        <div
          className="relative group/wh"
          onMouseEnter={() => setWebhookOpen(true)}
          onMouseLeave={() => setWebhookOpen(false)}
        >
          <button
            type="button"
            onClick={copyUrl}
            onMouseDown={(e) => e.stopPropagation()}
            onFocus={() => setWebhookOpen(true)}
            onBlur={() => setWebhookOpen(false)}
            className={clsx(BTN_CLS, 'cursor-pointer nodrag nopan')}
            style={borderStyle}
            title={webhookLabel}
            aria-label={webhookLabel}
          >
            <ShimmerOverlay color={FLEET_TRIGGER_SHIMMER} />
            <span className="relative z-10"><Webhook className="h-3.5 w-3.5" /></span>
          </button>
          {/* Hover/focus tooltip anchored above the button because buttons sit below the node. */}
          <div
            data-testid="fleet-trigger-tooltip"
            className={clsx(
              'absolute bottom-full mb-2 left-1/2 -translate-x-1/2 w-64 z-[60] group-hover/wh:block group-focus-within/wh:block',
              webhookOpen ? 'block' : 'hidden',
            )}
          >
            <div className="bg-white dark:bg-gray-800 border border-slate-200 dark:border-slate-600 rounded-xl shadow-xl p-3 space-y-1.5 text-sm">
              <div className="flex items-center gap-1.5 text-slate-500 dark:text-slate-400 font-medium">
                <Webhook className="h-3.5 w-3.5" />
                {webhookLabel}
              </div>
              {triggers.webhookUrl && (
                <div className="w-full min-w-0 flex items-center gap-1.5 px-2 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-700/50">
                  <code className="flex-1 min-w-0 text-sm text-slate-600 dark:text-slate-300 truncate font-mono">{triggers.webhookUrl}</code>
                  {copied
                    ? <Check className="h-3.5 w-3.5 text-emerald-500 flex-shrink-0" />
                    : <Copy className="h-3.5 w-3.5 text-slate-400 flex-shrink-0" />
                  }
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      {triggers.hasSchedule && (
        <div
          className="relative group/sch"
          onMouseEnter={() => setScheduleOpen(true)}
          onMouseLeave={() => setScheduleOpen(false)}
        >
          <button
            type="button"
            onClick={(e) => e.stopPropagation()}
            onMouseDown={(e) => e.stopPropagation()}
            onFocus={() => setScheduleOpen(true)}
            onBlur={() => setScheduleOpen(false)}
            className={clsx(BTN_CLS, 'cursor-default nodrag nopan')}
            title={scheduleLabel}
            aria-label={scheduleLabel}
            style={borderStyle}
          >
            <ShimmerOverlay color={FLEET_TRIGGER_SHIMMER} />
            <span className="relative z-10"><CalendarClock className="h-3.5 w-3.5" /></span>
          </button>
          {/* Hover/focus tooltip anchored above the button. */}
          <div
            data-testid="fleet-trigger-tooltip"
            className={clsx(
              'absolute bottom-full mb-2 left-1/2 -translate-x-1/2 w-64 z-[60] group-hover/sch:block group-focus-within/sch:block',
              scheduleOpen ? 'block' : 'hidden',
            )}
          >
            <div className="bg-white dark:bg-gray-800 border border-slate-200 dark:border-slate-600 rounded-xl shadow-xl p-3 space-y-1.5 text-sm">
              <div className="flex items-center gap-1.5 text-slate-500 dark:text-slate-400 font-medium">
                <CalendarClock className="h-3.5 w-3.5" />
                {scheduleLabel}
              </div>
              <div className="px-2 py-1.5 rounded-lg bg-slate-50 dark:bg-slate-700/50">
                <code className="text-sm text-slate-600 dark:text-slate-300 font-mono">{triggers.cronExpression}</code>
                {triggers.timezone && (
                  <span className="text-sm text-slate-400 dark:text-slate-500 ml-1.5">{triggers.timezone}</span>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
