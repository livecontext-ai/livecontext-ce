'use client';

import { useTranslations } from 'next-intl';
import {
  AlertCircle,
  AlertTriangle,
  Ban,
  CheckCircle2,
  Clock,
  CreditCard,
  HandIcon,
  Repeat,
  TimerOff,
  Wrench,
} from 'lucide-react';

import {
  AgentStopReason,
  STOP_REASON_META,
  parseStopReason,
} from '@/types/agentStopReason';

/**
 * Visual badge for an agent execution stop reason.
 *
 * <p>Drives icon + colour from the contract-generated TerminalCategory:
 * SUCCESS → green, PARTIAL → orange, FAILURE → red. Translation keys live
 * under {@code agentStopReason.<NAME>} in messages/{en,fr}.json.</p>
 *
 * <p>For BUDGET_EXHAUSTED, an optional {@code scope} prop ("tenant" | "agent")
 * is appended to the tooltip so the user can tell which budget level was hit.</p>
 */
export interface StopReasonBadgeProps {
  /** Raw stopReason string from the backend. Unknown values fall back to ERROR. */
  stopReason?: string | null;
  /** Optional scope for BUDGET_EXHAUSTED ("tenant" | "agent"). */
  scope?: string | null;
  /** Render the label next to the icon. Defaults to false (icon-only). */
  showLabel?: boolean;
  className?: string;
}

const ICONS: Record<AgentStopReason, React.ComponentType<{ className?: string }>> = {
  COMPLETED: CheckCircle2,
  MAX_ITERATIONS: Repeat,
  TIMEOUT: Clock,
  BUDGET_EXHAUSTED: CreditCard,
  LOOP_DETECTED: AlertTriangle,
  STOPPED_BY_USER: HandIcon,
  CANCELLED: Ban,
  NO_TOOLS: Wrench,
  ERROR: AlertCircle,
  INACTIVITY_TIMEOUT: TimerOff,
};

const COLOURS: Record<'SUCCESS' | 'PARTIAL' | 'FAILURE', string> = {
  SUCCESS: 'text-emerald-500',
  PARTIAL: 'text-amber-500',
  FAILURE: 'text-red-500',
};

// Per-reason colour overrides take precedence over the terminal-category colour.
// CANCELLED is technically a FAILURE in the contract (not a partial-success result),
// but visually we want to distinguish "system cancelled" from real errors so users
// don't get a sea of red when a deploy or scale-down kills runs in flight.
const COLOUR_OVERRIDES: Partial<Record<AgentStopReason, string>> = {
  CANCELLED: 'text-amber-500',
};

export function StopReasonBadge({
  stopReason,
  scope,
  showLabel = false,
  className = '',
}: StopReasonBadgeProps) {
  const t = useTranslations('agentStopReason');

  // Hide entirely when there is nothing to show.
  if (!stopReason) return null;

  const reason = parseStopReason(stopReason);
  const meta = STOP_REASON_META[reason];
  const Icon = ICONS[reason];
  const colour = COLOUR_OVERRIDES[reason] ?? COLOURS[meta.terminal];

  // Translation key per value; fall back to the contract's userVisible string
  // when the i18n bundle has not been regenerated yet.
  let label: string;
  try {
    label = t(reason);
  } catch {
    label = meta.userVisible;
  }

  let tooltip = label;
  if (reason === 'BUDGET_EXHAUSTED' && scope) {
    tooltip = `${label} (${scope})`;
  }

  return (
    <span
      className={`inline-flex items-center gap-1 ${colour} ${className}`}
      title={tooltip}
      aria-label={tooltip}
    >
      <Icon className="h-3.5 w-3.5 flex-shrink-0" />
      {showLabel && <span className="text-xs truncate">{label}</span>}
    </span>
  );
}
