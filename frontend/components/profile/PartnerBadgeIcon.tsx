import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { cn } from '@/lib/utils';
import { CHECK_PATH, SEAL_PATH, type VerifiedBadgeSize } from './VerifiedBadgeIcon';

/** Same pixel scale as the verified check: the two are one badge in two colours. */
const SIZE_PX: Record<VerifiedBadgeSize, number> = { xs: 12, sm: 14, lg: 20 };

interface PartnerBadgeIconProps {
  /** Renders nothing when false - callers may pass a flag straight through. */
  partner?: boolean | null;
  size?: VerifiedBadgeSize;
  /** An explicit pixel size for a display use (the /partners page), overriding `size`. */
  px?: number;
  /**
   * Accessible name. Defaults to English because this also renders in the server-rendered
   * marketplace pages, which have no `NextIntlClientProvider`. In-app callers go through
   * {@code VerifiedBadge}, which passes the translated string.
   */
  label?: string;
  className?: string;
}

/**
 * The official-partner badge: the verified badge itself (same seal, same white check) in gold,
 * the way X shows a gold check for organisations. A name carries one badge, never both
 * ({@code IdentityBadgeIcon}), and the accessible name tells the two apart. The same badge for
 * every partner tier; only the partner's profile names the tier. The light-mode gold is deep
 * enough for 3:1 against white (the check inside, and the page around it).
 *
 * <p>Presentational and hook-free, so it renders in server components too. Renders nothing on
 * a self-hosted deployment, a second lock behind the backend, which never reports a partner
 * there.
 */
export function PartnerBadgeIcon({
  partner,
  size = 'sm',
  px: explicitPx,
  label = 'Official LiveContext partner',
  className,
}: PartnerBadgeIconProps) {
  if (!partner || !IS_MANAGED_CLOUD) return null;
  const px = explicitPx ?? SIZE_PX[size];
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox="0 0 24 24"
      width={px}
      height={px}
      className={cn('shrink-0 text-[#bf8410] dark:text-[#f2b640]', className)}
      style={{ width: px, height: px }}
      data-badge="partner"
    >
      <title>{label}</title>
      <path d={SEAL_PATH} fill="currentColor" />
      {/* White on the deep light-mode gold, dark ink on the bright dark-mode gold: 3:1 either way. */}
      <path d={CHECK_PATH} fill="none" className="stroke-white dark:stroke-[#3a2600]" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export default PartnerBadgeIcon;
