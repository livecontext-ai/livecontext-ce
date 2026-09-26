'use client';

import type { ReactNode } from 'react';
import { useHorizontalOverflow } from './useHorizontalOverflow';

/**
 * Wrapper that becomes a focusable, labelled region ONLY while its content is
 * wider than the page, so keyboard users can scroll it (axe:
 * scrollable-region-focusable) without every table adding a landmark and a Tab stop.
 *
 * A separate client island on purpose: the table it wraps stays a server component,
 * so its JSX cells are rendered on the server and never cross the client boundary
 * as props.
 */
export function ScrollRegion({ className, label, children }: { className: string; label: string; children: ReactNode }) {
  const [ref, overflowing] = useHorizontalOverflow<HTMLDivElement>();
  return (
    <div
      ref={ref}
      className={className}
      role={overflowing ? 'region' : undefined}
      aria-label={overflowing ? label : undefined}
      tabIndex={overflowing ? 0 : undefined}
    >
      {children}
    </div>
  );
}
