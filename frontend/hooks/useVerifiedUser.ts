'use client';

import { useEffect, useState } from 'react';
import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { cachedBadges, loadBadges, type BadgeFlags } from '@/lib/api/verifiedUsers';

const NO_BADGE: BadgeFlags = { verified: false, partner: false };

/**
 * Which public badges this user carries: the blue verified check and the gold
 * official-partner seal.
 *
 * <p>Safe to call once per rendered name: every call made in the same frame is
 * answered by ONE backend request, and the answer is then cached per user id for the
 * life of the page ({@code lib/api/verifiedUsers}).
 *
 * <p>No badge, and no request, when the id is missing or on a self-hosted deployment
 * where the badges do not exist. A failed lookup also reads as no badge: a missing
 * badge is the safe way to be wrong.
 *
 * <p>Deliberately plain state plus one effect rather than react-query. The badge is
 * rendered inside cards that mount in trees with no {@code QueryClientProvider}, and
 * {@code useQuery} throws without one - it would take the whole tree down instead of
 * skipping a badge. The effect depends only on the id, so it cannot loop.
 */
export function useUserBadges(userId?: string | number | null): BadgeFlags {
  const id = userId === null || userId === undefined ? '' : String(userId);
  // Seeded from the cache so a badge already resolved on this page paints on the
  // first render, with no flash-in.
  const [flags, setFlags] = useState<BadgeFlags>(() => cachedBadges(id) ?? NO_BADGE);

  useEffect(() => {
    if (!IS_MANAGED_CLOUD || !id) {
      setFlags(NO_BADGE);
      return;
    }
    const known = cachedBadges(id);
    if (known !== undefined) {
      setFlags(known);
      return;
    }
    let alive = true;
    void loadBadges(id).then((answer) => {
      if (alive) setFlags(answer);
    });
    return () => { alive = false; };
  }, [id]);

  return flags;
}

/** Whether this user carries the verified badge (the verified half of {@link useUserBadges}). */
export function useIsVerifiedUser(userId?: string | number | null): boolean {
  return useUserBadges(userId).verified;
}
