'use client';

import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { cn } from '@/lib/utils';

export interface AdaptiveTab<T extends string> {
  id: T;
  label: string;
  /** Drawn at 16px; takes the tab's text colour (currentColor). */
  icon: React.ReactNode;
}

/** Which labels the bar shows: every one, the active tab's only, or none (icons). */
export type TabLabelMode = 'all' | 'active' | 'none';

/** Shared by the bar and its hidden measuring copies, so a copy measures what is drawn. */
const BAR_CLASS = 'inline-flex w-max items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl';
const BUTTON_CLASS = 'flex h-9 items-center gap-2 px-3 rounded-xl text-sm font-medium';

/**
 * The settings pages' pill tab bar, with an animated highlight behind the active tab.
 *
 * <p><b>It shows what fits its column, not what a window breakpoint promises.</b> Each page used
 * to carry its own copy that switched labels on at the `sm` WINDOW width, while the app sidebar,
 * the settings menu and a longer locale decide how much room the bar really has: AI Providers
 * (eight tabs) hid "Execution links" and "Your keys" past the right edge up to a 1440px screen,
 * behind a scroll nobody could see. The bar now measures hidden copies against its own width and
 * shows every label if they fit, else only the active tab's, else icons alone. Every tab keeps
 * its name for a screen reader (`sr-only`) and as a tooltip whenever it is hidden.
 *
 * <p>Buttons, not ARIA tabs: the pages' e2e specs select them by button role and `data-tab-id`.
 */
export function AdaptiveTabBar<T extends string>({
  tabs,
  value,
  onChange,
  className,
  'data-testid': testId = 'settings-tab-bar',
}: {
  // T is inferred from `value` alone: a page's state setter then fits `onChange` as is.
  tabs: ReadonlyArray<AdaptiveTab<NoInfer<T>>>;
  value: T;
  onChange: (id: NoInfer<T>) => void;
  className?: string;
  'data-testid'?: string;
}) {
  const barRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const allLabelsRef = useRef<HTMLDivElement>(null);
  const activeLabelRef = useRef<HTMLDivElement>(null);
  const [labels, setLabels] = useState<TabLabelMode>('all');
  const [slider, setSlider] = useState<{ left: number; width: number }>({ left: 0, width: 0 });
  const tabKey = tabs.map((tab) => `${tab.id}:${tab.label}`).join('|');

  // Layout effect: decided before the first paint, so a narrow column never flashes the
  // overflowing labelled bar.
  useLayoutEffect(() => {
    const bar = barRef.current;
    const all = allLabelsRef.current;
    const active = activeLabelRef.current;
    if (!bar || !all || !active) return;
    const update = () => {
      const room = bar.clientWidth;
      setLabels(
        all.getBoundingClientRect().width <= room ? 'all'
          : active.getBoundingClientRect().width <= room ? 'active'
            : 'none',
      );
    };
    update();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(update);
    observer.observe(bar);
    observer.observe(all);
    observer.observe(active);
    return () => observer.disconnect();
  }, [value, tabKey]);

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    const updateSlider = () => {
      const activeButton = track.querySelector<HTMLElement>(`[data-tab-id="${value}"]`);
      if (!activeButton) return;
      const trackRect = track.getBoundingClientRect();
      const buttonRect = activeButton.getBoundingClientRect();
      setSlider({ left: buttonRect.left - trackRect.left, width: buttonRect.width });
    };
    updateSlider();
    // The bar's OWN size, not the window's: a label appears or hides, a late web font widens
    // every tab, a tab arrives after the first paint. None of those is a window resize.
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(updateSlider);
    observer.observe(track);
    return () => observer.disconnect();
  }, [value, labels, tabKey]);

  const labelHidden = (id: T) => labels === 'none' || (labels === 'active' && id !== value);

  return (
    // w-full min-w-0, a guard: the bar's width must come from its column, never from its tabs,
    // whatever parent it lands in (Public Access centres it in a flex row). If the tabs set it,
    // the bar would measure itself as always fitting.
    <div className={cn('relative w-full min-w-0', className)}>
      <div
        ref={barRef}
        className="relative flex max-w-full overflow-x-auto scrollbar-hide"
        data-testid={testId}
        data-labels={labels}
      >
        <div ref={trackRef} className={cn(BAR_CLASS, 'relative mx-auto')}>
          <div
            className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
            style={{ left: slider.left, width: slider.width, opacity: slider.width ? 1 : 0 }}
          />
          {tabs.map((tab) => {
            const active = tab.id === value;
            return (
              <button
                key={tab.id}
                data-tab-id={tab.id}
                type="button"
                onClick={() => onChange(tab.id)}
                aria-current={active ? 'true' : undefined}
                title={labelHidden(tab.id) ? tab.label : undefined}
                className={cn(
                  BUTTON_CLASS,
                  'relative z-10 flex-shrink-0 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none',
                  active
                    ? 'text-[var(--text-primary)]'
                    : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50',
                )}
              >
                <span className="flex h-4 w-4 flex-shrink-0 items-center justify-center">{tab.icon}</span>
                {/* sr-only, not removed: an icon-only tab keeps its name for a screen reader. */}
                <span className={cn('whitespace-nowrap', labelHidden(tab.id) && 'sr-only')}>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </div>
      {/* The bar with every label, then with the active one only, invisible, at their natural
          widths: what the real bar would need in each form. AFTER the real bar and out of the
          flow (absolute, no height): a text query's `.first()` still finds the real tab, and a
          parent's `space-y-*` sees one child, not an extra gap. */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-0 h-0 overflow-hidden">
        {([['all', allLabelsRef], ['active', activeLabelRef]] as const).map(([mode, ref]) => (
          <div key={mode} ref={ref} data-testid={`${testId}-measure-${mode}`} className={cn(BAR_CLASS, 'invisible')}>
            {tabs.map((tab) => (
              <span key={tab.id} className={BUTTON_CLASS}>
                <span className="flex h-4 w-4 flex-shrink-0 items-center justify-center">{tab.icon}</span>
                {(mode === 'all' || tab.id === value) && <span className="whitespace-nowrap">{tab.label}</span>}
              </span>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

export default AdaptiveTabBar;
