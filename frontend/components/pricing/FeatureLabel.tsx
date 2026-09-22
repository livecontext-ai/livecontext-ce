'use client';

import { Info } from 'lucide-react';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';
import { renderBoldMarkup } from '@/lib/utils/boldMarkup';

/**
 * Renders a single pricing-card feature label. A feature string may embed an
 * optional tooltip via the "label||tooltip" delimiter, the shared convention
 * across the landing pricing section, the settings pricing page (PlanSelector),
 * the plan-comparison table and the insufficient-credits modal. When a tooltip
 * is present a small info "i" icon reveals it on hover / keyboard focus (Radix
 * tooltip, portalled so it is never clipped by a card or a scrolling modal).
 *
 * <p><b>The "i" is pinned to the RIGHT of the row, always.</b> It used to sit
 * immediately after the label, which reads as right-aligned only while every
 * label fits on one line: the moment one wraps, its "i" lands mid-row after the
 * last word while its neighbours' sit at the edge, and a column of features
 * shows the icons scattered at three different offsets. The row is therefore a
 * full-width flex line with the icon pushed out by `justify-between`, so the
 * icons form one column whatever the labels do. A caller that places this
 * INSIDE a flex row (a bullet list with a check icon) must let it take the
 * remaining width; the `flex-1` below does that, and a caller that gives it a
 * block context (a table cell) gets the full cell width for free. Two of the
 * four call sites already granted it (the modal makes this a flex item itself,
 * the comparison table a table cell); the two plan-card lists wrapped it in a
 * shrink-to-fit span and had to be widened.
 *
 * <p>The callers that changed with this are the ones that render a FEATURE, and
 * only those. A bullet list of short labels with no "i" anywhere (the PAYG tier
 * cards on the pricing page) keeps its own centred rows: there is no icon to
 * pin, nothing there wraps, and top-aligning it would be churn dressed as
 * consistency.
 *
 * <p>The tooltip text may mark a figure with `**...**` (see `renderBoldMarkup`):
 * the price is the one thing a reader is scanning for, and the sentence around
 * it is what qualifies it.
 */
export default function FeatureLabel({ feature }: { feature: string }) {
  const [label, tooltip] = feature.split('||');
  return (
    <span className="flex flex-1 min-w-0 items-start justify-between gap-1.5">
      <span>{label}</span>
      {tooltip ? (
        <TooltipProvider delayDuration={150}>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={label}
                // `mt-0.5` keeps the icon optically centred on the FIRST line of
                // a label that wraps, rather than floating above its cap height.
                className="inline-flex items-center mt-0.5 shrink-0 text-theme-muted hover:text-theme-secondary cursor-help"
              >
                <Info className="h-3.5 w-3.5 shrink-0" />
              </button>
            </TooltipTrigger>
            <TooltipContent className="max-w-[16rem] whitespace-normal text-xs leading-snug">
              {renderBoldMarkup(tooltip)}
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      ) : null}
    </span>
  );
}
