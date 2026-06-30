import type { QueryClient } from '@tanstack/react-query';

/**
 * Refresh every credentials cache entry after the current user creates or
 * changes a credential.
 *
 * <p>There are THREE distinct cache shapes for the user's credentials:
 * <ul>
 *   <li>plain {@code ['user-credentials']} - the inspector {@code CredentialSection},
 *       the acquired-app setup gate, and {@code PublicationInfoPanel}'s
 *       "Setup required" badge (via {@code useMissingCredentials}).</li>
 *   <li>org-scoped {@code ['org', <orgId>, 'user-credentials']} - the run
 *       {@code ValidationContext} (resolved through {@code useOrgScopedQuery}).</li>
 *   <li>{@code ['user-credentials-all']} - {@code useCredentialCheck} (chat /
 *       service-approval cards).</li>
 * </ul>
 *
 * <p>A plain {@code invalidateQueries({ queryKey: ['user-credentials'] })} is NOT
 * enough, for two reasons:
 * <ol>
 *   <li>{@code queryKey} filters match from array index 0, so a
 *       {@code ['user-credentials']} prefix MISSES the org-scoped entry
 *       (it starts with {@code 'org'}). We match with a PREDICATE on the
 *       {@code user-credentials} key prefix instead.</li>
 *   <li>{@code invalidateQueries} only auto-refetches ACTIVE observers; a
 *       consumer that is inactive or about to mount with
 *       {@code refetchOnMount: false} (e.g. {@code PublicationInfoPanel} right
 *       after the setup gate dismisses) is merely marked stale and stays stale
 *       until a hard reload - the reported "still asks for the credential I just
 *       entered" bug. {@code refetchQueries({ type: 'all' })} refetches the
 *       cached queries immediately, active or not.</li>
 * </ol>
 *
 * <p>Mirrors the OAuth-redirect fix in {@code useOAuthCredentialCallback};
 * this helper applies the same refresh to the direct-save (API key / basic /
 * bearer / custom) path so both flows behave identically.
 */
export function invalidateCredentialCaches(queryClient: QueryClient): Promise<void> {
  return queryClient.refetchQueries({
    predicate: (q) =>
      q.queryKey.some((k) => typeof k === 'string' && k.startsWith('user-credentials')),
    type: 'all',
  });
}
