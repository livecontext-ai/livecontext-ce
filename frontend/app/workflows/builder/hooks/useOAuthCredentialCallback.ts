import * as React from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';

interface OAuthCallbackToast {
  type: 'success' | 'error';
  title: string;
  message: string;
  duration?: number;
}

interface UseOAuthCredentialCallbackParams {
  /** App toast emitter (already wired to the toast store). */
  addToast: (toast: OAuthCallbackToast) => void;
  /** `credentials` next-intl namespace translator. */
  tCredentials: (key: string) => string;
}

/**
 * Handles the OAuth credential redirect callback (`?success=true` / `?error=…`).
 *
 * Hoisted out of CredentialSection so the toast fires as soon as the user
 * returns from the provider - even when no node is selected.
 *
 * On success it forces a ONE-TIME refresh of the credentials cache that feeds
 * the inspector (CredentialSection - plain `['user-credentials']`) AND the run
 * validator (ValidationContext - org-scoped `['org', <orgId>, 'user-credentials']`
 * via useOrgScopedQuery). These are two SEPARATE cache entries, so we match them
 * with a PREDICATE on the `'user-credentials'` key segment, not a `queryKey`
 * prefix: React Query matches `queryKey` filters from array index 0, so
 * `{ queryKey: ['user-credentials'] }` would refetch the inspector entry but
 * miss the org-scoped validator entry - and the validator is what raises the
 * "required credential" warning. We use `refetchQueries({ type: 'all' })`
 * rather than `invalidateQueries` on purpose:
 *
 *   - The OAuth provider redirect returns with no CredentialSection mounted, so
 *     the inspector's `['user-credentials']` query is *inactive* at that moment.
 *   - `invalidateQueries` only auto-refetches *active* observers; inactive ones
 *     are merely marked stale and wait for their next mount - which the query's
 *     `refetchOnMount: false` then suppresses. The freshly-connected credential
 *     therefore stayed invisible until the user reconnected (the reported
 *     "required credential" right after connecting).
 *   - `refetchQueries({ type: 'all' })` refetches the cached queries immediately,
 *     active or not, so the new credential is visible to the inspector and the
 *     run validator without a reconnect.
 *
 * Crucially this leaves `refetchOnMount` / `refetchOnWindowFocus` untouched, so
 * the lazy-loading behaviour (no refetch on every inspector panel open) is
 * preserved - only this single, explicit, OAuth-scoped refetch is added.
 */
export function useOAuthCredentialCallback({
  addToast,
  tCredentials,
}: UseOAuthCredentialCallbackParams): void {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const oauthHandledRef = React.useRef(false);

  React.useEffect(() => {
    const success = searchParams.get('success');
    const errorParam = searchParams.get('error');
    if (!success && !errorParam) {
      oauthHandledRef.current = false;
      return;
    }
    if (oauthHandledRef.current) return;
    oauthHandledRef.current = true;

    if (success === 'true') {
      addToast({
        type: 'success',
        title: tCredentials('toasts.credentialCreated'),
        message: tCredentials('toasts.credentialConfigured'),
        duration: 5000,
      });
      // One-time forced refresh of every credentials cache entry (inspector's
      // plain key + the org-scoped validator key). Predicate, not a queryKey
      // prefix - see the doc comment above for why. type:'all' so inactive
      // queries (no mounted observer on OAuth return) are refetched too.
      queryClient.refetchQueries({
        predicate: (query) => query.queryKey.includes('user-credentials'),
        type: 'all',
      });
    } else if (errorParam) {
      addToast({
        type: 'error',
        title: tCredentials('toasts.connectionFailed'),
        message: decodeURIComponent(errorParam),
        duration: 7000,
      });
    }
    router.replace(pathname, { scroll: false });
  }, [searchParams, addToast, tCredentials, router, pathname, queryClient]);
}
