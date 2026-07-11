/**
 * Switch - the single on/off toggle used across the whole app.
 *
 * Clean, sober, square-rounded (rounded-md track + rounded handle), one shape
 * and one padding everywhere. ON = accent fill; OFF = neutral track. Do NOT
 * hand-roll toggles elsewhere: import this one so every switch reads identically.
 */

'use client';

import React from 'react';
import { cn } from '../../lib/utils';

interface SwitchProps {
  checked: boolean;
  /** Toggle handler. Optional only when {@code presentational} is set. */
  onCheckedChange?: (checked: boolean) => void;
  disabled?: boolean;
  className?: string;
  /** Accessible label announced by screen readers (e.g. "Activate application"). */
  'aria-label'?: string;
  /**
   * Visual size. {@code 'sm'} (default) is the standard control; {@code 'md'} is
   * a slightly larger peer for h-9 header controls. Same square-rounded shape.
   */
  size?: 'sm' | 'md';
  /**
   * Render as a non-interactive {@code <span>} instead of a {@code <button>}.
   * Use ONLY when the switch sits inside a parent that already owns the click
   * (e.g. a clickable card row) - it keeps the identical look without nesting a
   * button in a button. The parent drives {@code checked}; no handler needed.
   */
  presentational?: boolean;
  /** Optional test id set as {@code data-testid} on the rendered element. */
  testId?: string;
}

const SIZE_CLASSES: Record<NonNullable<SwitchProps['size']>, {
  track: string;
  handle: string;
  translateOn: string;
}> = {
  sm: {
    // p-0.5 gutter: h-5 track, 4x4 handle, travels the remaining width.
    track: 'h-5 w-9 p-0.5',
    handle: 'h-4 w-4',
    translateOn: 'translate-x-4',
  },
  md: {
    track: 'h-6 w-11 p-0.5',
    handle: 'h-5 w-5',
    translateOn: 'translate-x-5',
  },
};

export const Switch: React.FC<SwitchProps> = ({
  checked,
  onCheckedChange,
  disabled = false,
  className,
  'aria-label': ariaLabel,
  size = 'sm',
  presentational = false,
  testId,
}) => {
  const dims = SIZE_CLASSES[size];
  // Concentric radii: the track is rounded-lg (8px) and the handle rounded-md
  // (6px) so, with the 2px p-0.5 gutter, their corners nest cleanly and the
  // track never looks less rounded than the handle.
  const trackClasses = cn(
    'relative inline-flex flex-shrink-0 items-center rounded-lg border border-transparent transition-colors duration-150',
    dims.track,
    checked ? 'bg-[var(--accent-primary)]' : 'bg-[var(--bg-tertiary)] border-[var(--border-color)]',
    disabled && 'opacity-50',
    className,
  );
  const handle = (
    <span
      className={cn(
        'pointer-events-none inline-block transform rounded-md bg-white shadow-sm transition-transform duration-150 dark:bg-white',
        dims.handle,
        checked ? dims.translateOn : 'translate-x-0',
      )}
    />
  );

  // Presentational: a plain span with the same look, for use inside a parent
  // that already owns the click (no nested <button> in <button>).
  if (presentational) {
    return <span aria-hidden="true" data-testid={testId} className={trackClasses}>{handle}</span>;
  }

  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      data-testid={testId}
      disabled={disabled}
      onClick={() => onCheckedChange?.(!checked)}
      className={cn(
        trackClasses,
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)]',
        disabled && 'cursor-not-allowed',
      )}
    >
      {handle}
    </button>
  );
};
