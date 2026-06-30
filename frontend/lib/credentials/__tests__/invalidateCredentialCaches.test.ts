/**
 * @vitest-environment jsdom
 *
 * Tests for {@code invalidateCredentialCaches}.
 *
 * Pins the two properties that actually fix the "still asks for the credential I
 * just entered" bug:
 *  1. PREDICATE breadth - it refreshes ALL three credential cache shapes (plain
 *     `['user-credentials']`, org-scoped `['org', <orgId>, 'user-credentials']`,
 *     and `['user-credentials-all']`) and leaves unrelated queries untouched.
 *  2. refetch-INACTIVE - it refetches cached queries even with no mounted
 *     observer (the PublicationInfoPanel-after-the-gate case). A naive
 *     `invalidateQueries({ queryKey: ['user-credentials'] })` would refetch
 *     neither the inactive query nor the org-scoped / -all entries, so this test
 *     fails on that implementation.
 */
import { describe, it, expect, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { invalidateCredentialCaches } from '../invalidateCredentialCaches';

describe('invalidateCredentialCaches', () => {
  it('refetches every credential cache shape (plain, org-scoped, -all), even inactive, and leaves unrelated queries untouched', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    const plainFn = vi.fn(async () => ['plain']);
    const orgScopedFn = vi.fn(async () => ['org']);
    const allFn = vi.fn(async () => ['all']);
    const unrelatedFn = vi.fn(async () => ['x']);

    // Seed the cache. prefetchQuery leaves each query with NO active observer,
    // reproducing the inactive-consumer case that invalidateQueries cannot
    // refresh while refetchOnMount:false.
    await client.prefetchQuery({ queryKey: ['user-credentials'], queryFn: plainFn });
    await client.prefetchQuery({
      queryKey: ['org', '__personal__', 'user-credentials'],
      queryFn: orgScopedFn,
    });
    await client.prefetchQuery({ queryKey: ['user-credentials-all'], queryFn: allFn });
    await client.prefetchQuery({ queryKey: ['workflows'], queryFn: unrelatedFn });

    expect(plainFn).toHaveBeenCalledTimes(1);
    expect(orgScopedFn).toHaveBeenCalledTimes(1);
    expect(allFn).toHaveBeenCalledTimes(1);
    expect(unrelatedFn).toHaveBeenCalledTimes(1);

    await invalidateCredentialCaches(client);

    // Every credential cache entry refetched (count went 1 -> 2)...
    expect(plainFn).toHaveBeenCalledTimes(2);
    expect(orgScopedFn).toHaveBeenCalledTimes(2);
    expect(allFn).toHaveBeenCalledTimes(2);
    // ...and the unrelated query was not touched.
    expect(unrelatedFn).toHaveBeenCalledTimes(1);
  });
});
