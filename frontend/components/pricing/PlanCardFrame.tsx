'use client';

import React, { useState } from 'react';
import { Check } from 'lucide-react';
import ReferencePrice from '@/components/pricing/ReferencePrice';
import FoundingPriceNote from '@/components/pricing/FoundingPriceNote';
import FeatureLabel from '@/components/pricing/FeatureLabel';
import DeploymentBadge from '@/components/pricing/DeploymentBadge';
import { planCardClasses, PlanCardToggle, PLAN_HEAD_CLASS } from '@/components/pricing/PlanGrid';
import type { ResolvedPricingEvent } from '@/lib/billing/pricing-events';

/**
 * One plan card as {@link PlanGrid} lays them out (the landing's pricing section, a partner's
 * offer page): the name and price header (with the founding reference price and its note), the
 * deployment badge and the feature list, collapsed to its header on mobile until opened. What
 * differs between surfaces is passed in: the frame's border and fill, the badge above the card,
 * and the footer (a link, a button, a note), which receives the class that hides it while the
 * card is collapsed.
 */
export default function PlanCardFrame({
  planId,
  name,
  priceLabel,
  period,
  cycle,
  creditTierIndex,
  event,
  features,
  defaultOpen,
  frameStyle,
  badge,
  footer,
  rootProps,
}: {
  planId: string;
  name: string;
  priceLabel: string;
  /** The words after the price ("/month"); none for a quote-only plan. */
  period?: string;
  cycle: 'monthly' | 'yearly';
  creditTierIndex: number;
  event: ResolvedPricingEvent | null;
  features: string[];
  /** Whether the card starts open on mobile (the recommended one does). */
  defaultOpen: boolean;
  frameStyle: React.CSSProperties;
  badge?: React.ReactNode;
  footer: (collapsedClass: string) => React.ReactNode;
  rootProps?: React.HTMLAttributes<HTMLDivElement> & Record<`data-${string}`, string>;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const collapsed = open ? '' : 'max-md:hidden';

  return (
    <div {...rootProps} className={`relative p-5 rounded-3xl transition-colors duration-300 ${planCardClasses}`} style={frameStyle}>
      {badge}

      {/* Header: name + price. On mobile a single tappable row (name
          left, price right) that opens the card; from md, stacked and centred. */}
      <div data-plan-head className={`relative flex flex-wrap items-center gap-x-3 md:block md:text-center ${PLAN_HEAD_CLASS}`}>
        <h3
          className="min-w-0 flex-1 text-lg md:text-xl font-bold md:mb-3"
          style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif' }}
        >
          {name}
        </h3>
        <div className="text-right md:text-center">
          <div className="flex items-baseline justify-end md:justify-center gap-2">
            <ReferencePrice planId={planId} cycle={cycle} creditTierIndex={creditTierIndex} event={event} />
            <span className="flex items-baseline gap-1.5">
              <span className="text-2xl md:text-3xl font-bold" style={{ color: 'var(--text-primary)' }}>
                {priceLabel}
              </span>
              {period && (
                <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>
                  {period}
                </span>
              )}
            </span>
          </div>
        </div>
        {/* Its own line on mobile (under name and price), so a long caption never
            runs over the plan name. */}
        <div className="max-md:order-last max-md:basis-full">
          <FoundingPriceNote planId={planId} cycle={cycle} creditTierIndex={creditTierIndex} event={event} />
        </div>
        <PlanCardToggle open={open} onToggle={() => setOpen((o) => !o)} label={name} />
      </div>

      {/* The feature list, full card width so every card's checks start on
          the same edge and every "i" sits in one column at the right. */}
      <div className={`mt-4 md:mt-6 ${collapsed}`}>
        <ul className="space-y-2.5 text-sm flex flex-col">
          <DeploymentBadge />
          {features.map((f) => (
            <li key={f} className="flex items-start gap-2">
              <Check className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: '#10b981' }} />
              {/* `flex-1` so FeatureLabel owns the rest of the row and can pin
                  its "i" to the right edge; `items-start` so a label that wraps
                  keeps the check and the "i" on its first line. */}
              <span className="flex flex-1 min-w-0" style={{ color: 'var(--text-secondary)' }}>
                <FeatureLabel feature={f} />
              </span>
            </li>
          ))}
        </ul>
      </div>

      {footer(collapsed)}
    </div>
  );
}
