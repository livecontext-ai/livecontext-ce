'use client';

import { useTranslations } from 'next-intl';
import { useUserBadges } from '@/hooks/useVerifiedUser';
import type { VerifiedBadgeSize } from './VerifiedBadgeIcon';
import { IdentityBadgeIcon } from './IdentityBadgeIcon';

interface VerifiedBadgeProps {
  /**
   * The user whose badges to resolve, against THIS install's auth-service. Leave it out
   * when the flags are already known (a profile payload carries them, so there is
   * nothing to look up).
   *
   * <p>Never pass a CLOUD user id from a remote marketplace listing: the siblings in
   * these rows ({@code PublisherAvatar}, {@code UserActionMenu}) take a {@code remote}
   * prop for exactly that case. There is no {@code remote} mode here because the badges
   * do not exist on a self-hosted install, which is the only place remote ids are
   * rendered - so today the question cannot arise. If a managed-cloud surface ever
   * renders foreign ids, this needs a remote path rather than a silent wrong answer.
   */
  userId?: string | number | null;
  /**
   * Known verified state. When provided it wins for that badge and no lookup happens
   * for it; when omitted the badge resolves {@code userId} instead.
   */
  verified?: boolean | null;
  /** Known official-partner state, with the same precedence as {@code verified}. */
  partner?: boolean | null;
  size?: VerifiedBadgeSize;
  className?: string;
}

/**
 * The public badge placed immediately to the RIGHT of a name: the gold official-partner
 * seal for a partner, the blue verified check otherwise (never both, see
 * {@code IdentityBadgeIcon}).
 *
 * <p>Self-sufficient by design: give it a user id and it resolves both flags itself,
 * batched with every other badge on screen, so a name can be decorated anywhere
 * without the surrounding list having to fetch and thread anything through.
 *
 * <p>Renders nothing when the user carries neither badge, and nothing at all on a
 * self-hosted deployment.
 */
export function VerifiedBadge({ userId, verified, partner, size = 'sm', className }: VerifiedBadgeProps) {
  const t = useTranslations('profile');
  const knowsVerified = verified !== undefined && verified !== null;
  const knowsPartner = partner !== undefined && partner !== null;
  // Skip the lookup entirely when the caller already knows both answers.
  const resolved = useUserBadges(knowsVerified && knowsPartner ? null : userId);
  const isVerified = knowsVerified ? verified : resolved.verified;
  const isPartner = knowsPartner ? partner : resolved.partner;

  return (
    <IdentityBadgeIcon
      verified={isVerified}
      partner={isPartner}
      size={size}
      verifiedLabel={t('verifiedAccount')}
      partnerLabel={t('partnerAccount')}
      className={className}
    />
  );
}

export default VerifiedBadge;
