import React from 'react';
import { cn } from '@/lib/utils';

interface RunningBorderProps {
  /** Only renders when true - driven by a workflow run whose status is 'running'. */
  running: boolean;
  /** Accessible label / tooltip (e.g. "Running"). */
  label: string;
  /** Extra classes for the absolute-fill overlay (e.g. a higher z-index). */
  className?: string;
}

/**
 * Pulsing blue "running" border overlaid on a displayed application interface
 * while its workflow run is executing. Unlike a loading skeleton, the app stays
 * visible underneath - the ring just fades in/out (with a soft glow) to signal
 * "this app's current epoch is running".
 *
 * Rendered once inside the shared `iframeContent` of {@link ApplicationTabContent},
 * so every surface that shows an application (side panel, application detail,
 * carousel, visualize card, fullscreen) gets the same border. Renders nothing
 * when not running, so call-sites can mount it unconditionally. `pointer-events`
 * are disabled via `.app-running-border` so it never intercepts iframe clicks.
 * Honors `prefers-reduced-motion` (static ring) via that class.
 */
export function RunningBorder({ running, label, className }: RunningBorderProps) {
  if (!running) return null;
  return (
    <div
      role="status"
      aria-label={label}
      aria-live="polite"
      title={label}
      data-testid="application-running-border"
      className={cn('app-running-border z-20', className)}
    />
  );
}
