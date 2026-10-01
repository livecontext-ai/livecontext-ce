import { PartnerBadgeIcon } from './PartnerBadgeIcon';
import { VerifiedBadgeIcon, type VerifiedBadgeSize } from './VerifiedBadgeIcon';

interface IdentityBadgeIconProps {
  verified?: boolean | null;
  partner?: boolean | null;
  size?: VerifiedBadgeSize;
  /** Accessible names; English defaults for the provider-less server-rendered pages. */
  verifiedLabel?: string;
  partnerLabel?: string;
  className?: string;
}

/**
 * The one badge shown next to a public name: the gold partner seal when the account is an
 * official partner, the blue verified check otherwise, never both. The two are the same seal,
 * so side by side they would read as a stutter; the partner seal is the stronger claim (the
 * team reviewed and approved the business), so it wins.
 *
 * <p>Every place that renders a name with its badge goes through this, so the rule lives in one
 * place. Presentational and hook-free: renders in server components.
 */
export function IdentityBadgeIcon({
  verified,
  partner,
  size = 'sm',
  verifiedLabel,
  partnerLabel,
  className,
}: IdentityBadgeIconProps) {
  if (partner) {
    return <PartnerBadgeIcon partner size={size} label={partnerLabel} className={className} />;
  }
  return <VerifiedBadgeIcon verified={verified} size={size} label={verifiedLabel} className={className} />;
}

export default IdentityBadgeIcon;
