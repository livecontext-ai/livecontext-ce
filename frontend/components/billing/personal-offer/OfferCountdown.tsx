'use client';

import React, { useEffect, useId, useState } from 'react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';

export interface RemainingParts {
  days: number;
  hours: number;
  minutes: number;
  seconds: number;
}

/**
 * The time left before {@code deadline}, as whole days, hours, minutes and seconds, rounded up:
 * the last second shows until the deadline itself, never "ended" early. Null once reached.
 */
export function remainingParts(deadline: number, now: number): RemainingParts | null {
  const left = Math.ceil((deadline - now) / 1000);
  if (!Number.isFinite(left) || left <= 0) return null;
  return {
    days: Math.floor(left / 86_400),
    hours: Math.floor((left % 86_400) / 3_600),
    minutes: Math.floor((left % 3_600) / 60),
    seconds: left % 60,
  };
}

/**
 * The time left on an offer, ticking every second: days, hours, minutes and seconds in boxes.
 * {@code onElapsed} fires once when it reaches zero (the page then reads the offer again, so the
 * server says what the offer has become, never the clock alone).
 */
export function OfferCountdown({
  expiresAt,
  onElapsed,
  className,
}: {
  expiresAt: string;
  onElapsed?: () => void;
  className?: string;
}) {
  const t = useTranslations('personalOfferPage.countdown');
  const labelId = useId();
  const deadline = Date.parse(expiresAt);
  // Read the clock after mount only: the server and the first client render must agree.
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(id);
  }, []);
  const parts = now == null ? null : remainingParts(deadline, now);
  // An unreadable deadline is not one that passed: nothing to say, nothing to re-read.
  const elapsed = now != null && Number.isFinite(deadline) && parts == null;
  useEffect(() => {
    if (elapsed) onElapsed?.();
    // Fires on the transition only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [elapsed]);

  if (Number.isNaN(deadline)) return null;
  if (elapsed) {
    return <p role="status" className={cn('text-sm font-medium text-amber-600 dark:text-amber-400', className)} data-testid="offer-countdown-ended">{t('ended')}</p>;
  }
  const cells: Array<[keyof RemainingParts, string]> = [['days', t('days')], ['hours', t('hours')], ['minutes', t('minutes')], ['seconds', t('seconds')]];
  return (
    <div className={className} data-testid="offer-countdown" role="timer" aria-live="off" aria-labelledby={labelId}>
      <div id={labelId} className="text-sm font-medium text-theme-secondary">{t('endsIn')}</div>
      <div className="mt-2 flex gap-2">
        {cells.map(([key, label]) => (
          <div
            key={key}
            className="flex min-w-16 flex-col items-center rounded-xl border border-theme bg-[var(--bg-primary)]/80 px-2.5 py-2 shadow-sm backdrop-blur"
          >
            <span className="text-2xl font-bold tabular-nums text-theme-primary" data-testid={`offer-countdown-${key}`}>
              {parts ? String(parts[key]).padStart(2, '0') : '--'}
            </span>
            <span className="text-xs text-theme-secondary">{label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default OfferCountdown;
