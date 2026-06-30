/**
 * Org-scoped wrapper around React Query's `useQuery`.
 *
 * F20 (2026-05-18) - automatically prefixes the query key with the active
 * workspace id so the query cache is naturally isolated per workspace.
 * Switching workspace creates a separate cache entry (refetch); switching
 * back hits the prior cache (instant).
 *
 * <h2>When to use</h2>
 * - Use {@link useOrgScopedQuery} for ANY data that depends on the active
 *   workspace (workflows, agents, conversations, tasks, datasources,
 *   credentials, interfaces - anything served from `/api/<service>/...`
 *   under the X-Active-Organization-ID header).
 * - Use plain {@link import('@tanstack/react-query').useQuery} for data that
 *   is globally identical across workspaces (Stripe plans, public
 *   marketplace browse, edition-detection, model catalog).
 *
 * <h2>Same-tab invalidation</h2>
 * On workspace switch, `smart-providers.tsx` fires an `invalidateQueries`
 * scoped to keys whose first segment is NOT `'org'` - i.e. the ~50 legacy
 * hooks (`['workflows']`, `['agents']`, etc.) refetch, but keys produced by
 * THIS helper (prefixed with `['org', <orgId>, ...]`) are deliberately left
 * untouched. Each workspace gets its own cache slice, so switching from A
 * to B fetches B; switching back to A hits A's prior cache instantly
 * instead of round-tripping.
 *
 * @example
 * const { data } = useOrgScopedQuery({
 *   queryKey: ['workflows', 'list'],
 *   queryFn: () => orchestratorApi.listWorkflows(),
 * });
 * // Effective queryKey: ['org', <currentOrgId|'__personal__'>, 'workflows', 'list']
 */
import { useQuery, type UseQueryOptions, type UseQueryResult } from '@tanstack/react-query';
import { useCurrentOrgStore } from '../stores/current-org-store';

type EffectiveKey<TQueryKey extends readonly unknown[]>
    = readonly ['org', string, ...TQueryKey];

export type OrgScopedQueryOptions<TQueryFnData, TError, TData, TQueryKey extends readonly unknown[]>
    = Omit<UseQueryOptions<TQueryFnData, TError, TData, EffectiveKey<TQueryKey>>, 'queryKey'> & {
        queryKey: TQueryKey;
    };

export function useOrgScopedQuery<
    TQueryFnData = unknown,
    TError = Error,
    TData = TQueryFnData,
    TQueryKey extends readonly unknown[] = readonly unknown[],
>(options: OrgScopedQueryOptions<TQueryFnData, TError, TData, TQueryKey>): UseQueryResult<TData, TError> {
    const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
    const orgKeySegment = currentOrgId ?? '__personal__';
    const { queryKey, ...rest } = options;
    const effectiveKey = ['org', orgKeySegment, ...queryKey] as unknown as EffectiveKey<TQueryKey>;
    return useQuery<TQueryFnData, TError, TData, EffectiveKey<TQueryKey>>({
        ...rest,
        queryKey: effectiveKey,
    });
}
