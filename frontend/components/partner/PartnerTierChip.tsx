import { cn } from '@/lib/utils';
import { TIER_STYLE, type PartnerTierKey } from '@/lib/partners/tiers';

/**
 * A partner's tier as a small metal chip ("Gold partner"). Presentational and hook-free:
 * renders on the server-rendered profile and marketing pages as well as in the app. The
 * translated label arrives through props.
 */
export function PartnerTierChip({ tier, label, className }: { tier: PartnerTierKey; label: string; className?: string }) {
  const style = TIER_STYLE[tier];
  return (
    <span
      className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold', className)}
      style={{ background: style.gradient, color: style.ink, boxShadow: `inset 0 0 0 1px ${style.ring}` }}
      data-tier={tier}
    >
      {label}
    </span>
  );
}

export default PartnerTierChip;
