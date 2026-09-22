'use client';

import { useTranslations } from 'next-intl';
import { useIsVerifiedUser } from '@/hooks/useVerifiedUser';
import { VerifiedBadgeIcon, type VerifiedBadgeSize } from './VerifiedBadgeIcon';

interface VerifiedBadgeProps {
  /**
   * The user whose badge to resolve, against THIS install's auth-service. Leave it out
   * when {@code verified} is already known (a profile payload carries the flag, so
   * there is nothing to look up).
   *
   * <p>Never pass a CLOUD user id from a remote marketplace listing: the siblings in
   * these rows ({@code PublisherAvatar}, {@code UserActionMenu}) take a {@code remote}
   * prop for exactly that case. There is no {@code remote} mode here because the badge
   * does not exist on a self-hosted install, which is the only place remote ids are
   * rendered - so today the question cannot arise. If a managed-cloud surface ever
   * renders foreign ids, this needs a remote path rather than a silent wrong answer.
   */
  userId?: string | number | null;
  /**
   * Known verified state. When provided it wins and no lookup happens; when omitted
   * the badge resolves {@code userId} instead.
   */
  verified?: boolean | null;
  size?: VerifiedBadgeSize;
  className?: string;
}

/**
 * The blue verified check, placed immediately to the RIGHT of a public name.
 *
 * <p>Self-sufficient by design: give it a user id and it resolves the flag itself,
 * batched with every other badge on screen, so a name can be decorated anywhere
 * without the surrounding list having to fetch and thread anything through.
 *
 * <p>Renders nothing when the user is not verified, and nothing at all on a
 * self-hosted deployment.
 */
export function VerifiedBadge({ userId, verified, size = 'sm', className }: VerifiedBadgeProps) {
  const t = useTranslations('profile');
  // Skip the lookup entirely when the caller already knows the answer.
  const resolved = useIsVerifiedUser(verified === undefined || verified === null ? userId : null);
  const isVerified = verified === undefined || verified === null ? resolved : verified;

  return (
    <VerifiedBadgeIcon
      verified={isVerified}
      size={size}
      label={t('verifiedAccount')}
      className={className}
    />
  );
}

export default VerifiedBadge;
