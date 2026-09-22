import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { cn } from '@/lib/utils';

/** Pixel size per slot, matched to the typography scale it sits next to. */
const SIZE_PX = {
  /** Alongside text-xs (badges, timestamps, card footers). */
  xs: 12,
  /** Alongside text-sm - the default everywhere else. */
  sm: 14,
  /** Alongside a page title (the profile header). */
  lg: 20,
} as const;

export type VerifiedBadgeSize = keyof typeof SIZE_PX;

/**
 * The seal outline, a quatrefoil badge. Drawn inline rather than pulled from an
 * icon set because the two paths need different paint: the seal is FILLED with the
 * badge colour and the check is STROKED in white on top of it. An icon component
 * paints every path the same way, which turns the open check path into a filled
 * wedge.
 */
const SEAL_PATH =
  'M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 '
  + '4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z';

interface VerifiedBadgeIconProps {
  /** Renders nothing when false - callers may pass a flag straight through. */
  verified?: boolean | null;
  size?: VerifiedBadgeSize;
  /**
   * Accessible name. Defaults to English because this component also renders in the
   * server-rendered marketplace pages, which live outside the `[locale]` tree and have
   * no `NextIntlClientProvider`. In-app callers should use {@code VerifiedBadge}, which
   * passes the translated string.
   */
  label?: string;
  className?: string;
}

/**
 * The blue verified check shown immediately to the RIGHT of a public name, the way
 * Instagram and X place it.
 *
 * <p>Presentational and hook-free, so it renders in server components as happily as
 * in client ones. It never resolves anything: pass {@code verified}. The in-app
 * sibling {@code VerifiedBadge} resolves the flag from a user id instead.
 *
 * <p>Renders nothing on a self-hosted deployment. The backend already refuses to mark
 * anyone verified there, so this is the second of two independent locks: the badge
 * cannot appear in Community Edition even if a payload somehow claimed it.
 */
export function VerifiedBadgeIcon({
  verified,
  size = 'sm',
  label = 'Verified account',
  className,
}: VerifiedBadgeIconProps) {
  if (!verified || !IS_MANAGED_CLOUD) return null;

  const px = SIZE_PX[size];
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox="0 0 24 24"
      width={px}
      height={px}
      className={cn('shrink-0 text-[#1d9bf0] dark:text-[#4aa8ef]', className)}
      style={{ width: px, height: px }}
    >
      <title>{label}</title>
      <path d={SEAL_PATH} fill="currentColor" />
      <path
        d="m9 12 2 2 4-4"
        fill="none"
        stroke="#fff"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export default VerifiedBadgeIcon;
