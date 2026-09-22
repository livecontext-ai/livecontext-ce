'use client';

import { useEffect, useState } from 'react';
import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { cachedVerifiedFlag, loadVerifiedFlag } from '@/lib/api/verifiedUsers';

/**
 * Whether this user carries the verified badge.
 *
 * <p>Safe to call once per rendered name: every call made in the same frame is
 * answered by ONE backend request, and the answer is then cached per user id for the
 * life of the page ({@code lib/api/verifiedUsers}).
 *
 * <p>Returns false without issuing a request when the id is missing, or on a
 * self-hosted deployment where the badge does not exist. A failed lookup also reads
 * as false: a missing badge is the safe way to be wrong.
 *
 * <p>Deliberately plain state plus one effect rather than react-query. The badge is
 * rendered inside cards that mount in trees with no {@code QueryClientProvider}, and
 * {@code useQuery} throws without one - it would take the whole tree down instead of
 * skipping a badge. The effect depends only on the id, so it cannot loop.
 */
export function useIsVerifiedUser(userId?: string | number | null): boolean {
  const id = userId === null || userId === undefined ? '' : String(userId);
  // Seeded from the cache so a badge already resolved on this page paints on the
  // first render, with no flash-in.
  const [verified, setVerified] = useState<boolean>(() => cachedVerifiedFlag(id) === true);

  useEffect(() => {
    if (!IS_MANAGED_CLOUD || !id) {
      setVerified(false);
      return;
    }
    const known = cachedVerifiedFlag(id);
    if (known !== undefined) {
      setVerified(known);
      return;
    }
    let alive = true;
    void loadVerifiedFlag(id).then((flag) => {
      if (alive) setVerified(flag);
    });
    return () => { alive = false; };
  }, [id]);

  return verified;
}
