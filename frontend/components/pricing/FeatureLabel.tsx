'use client';

import { Info } from 'lucide-react';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '@/components/ui/tooltip';

/**
 * Renders a single pricing-card feature label. A feature string may embed an
 * optional tooltip via the "label||tooltip" delimiter, the shared convention
 * across the landing pricing section, the settings pricing page (PlanSelector)
 * and the insufficient-credits modal. When a tooltip is present a small info
 * "i" icon reveals it on hover / keyboard focus (Radix tooltip, portalled so it
 * is never clipped by a card or a scrolling modal).
 */
export default function FeatureLabel({ feature }: { feature: string }) {
  const [label, tooltip] = feature.split('||');
  return (
    <span className="inline-flex items-center gap-1">
      <span>{label}</span>
      {tooltip ? (
        <TooltipProvider delayDuration={150}>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-label={label}
                className="inline-flex items-center text-theme-muted hover:text-theme-secondary cursor-help"
              >
                <Info className="h-3.5 w-3.5 shrink-0" />
              </button>
            </TooltipTrigger>
            <TooltipContent className="max-w-[16rem] whitespace-normal text-xs leading-snug">
              {tooltip}
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      ) : null}
    </span>
  );
}
