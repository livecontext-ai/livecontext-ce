// @vitest-environment jsdom
/**
 * Regression tests for the OAuth credential redirect refresh bug
 * (user-reported 2026-05-30) on {@link useOAuthCredentialCallback}.
 *
 * Bug: after connecting a credential through the Google OAuth redirect, the
 * very next workflow run reported "required credential"; only reconnecting
 * fixed it. Two root causes:
 *   (a) the callback called `invalidateQueries`, which does not refetch the
 *       *inactive* credentials query left behind by the redirect (no mounted
 *       observer) under `refetchOnMount: false`; and
 *   (b) the validator registers its credentials query via useOrgScopedQuery, so
 *       its effective key is ['org', <orgId>, 'user-credentials'] - a SEPARATE
 *       cache entry that a `queryKey: ['user-credentials']` prefix filter does
 *       NOT match. The validator is what raises the "required credential"
 *       warning, so missing it leaves the symptom in place.
 *
 * Fix: force a one-time `refetchQueries({ type: 'all' })` matched by a PREDICATE
 * on the 'user-credentials' segment, refreshing BOTH the inspector and the
 * org-scoped validator entry - WITHOUT touching `refetchOnMount` (lazy loading
 * preserved).
 *
 * These tests run against a REAL QueryClient so they assert the actual key
 * matching (a mock-stub client could not catch root cause (b)).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const replace = vi.fn();
let currentSearch = new URLSearchParams();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/app/workflows/builder',
  useSearchParams: () => currentSearch,
}));

import { useOAuthCredentialCallback } from '../useOAuthCredentialCallback';

const addToast = vi.fn();
const tCredentials = (key: string) => key;

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { refetchOnMount: false, refetchOnWindowFocus: false, retry: false },
    },
  });
}

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

/**
 * Seed the two credential cache entries that exist in prod, both inactive
 * (no mounted observer) - exactly the state on OAuth redirect return:
 *   - inspector: plain ['user-credentials'] (CredentialSection)
 *   - validator: org-scoped ['org','__personal__','user-credentials'] (ValidationContext)
 */
async function seedCredentialQueries(client: QueryClient) {
  const inspectorFn = vi.fn().mockResolvedValue([{ id: 1 }]);
  const validatorFn = vi.fn().mockResolvedValue([{ id: 1 }]);
  await client.prefetchQuery({ queryKey: ['user-credentials'], queryFn: inspectorFn });
  await client.prefetchQuery({
    queryKey: ['org', '__personal__', 'user-credentials'],
    queryFn: validatorFn,
  });
  inspectorFn.mockClear();
  validatorFn.mockClear();
  return { inspectorFn, validatorFn };
}

beforeEach(() => {
  replace.mockClear();
  addToast.mockClear();
  currentSearch = new URLSearchParams();
});

describe('useOAuthCredentialCallback', () => {
  it('on ?success=true refetches BOTH the inspector key and the org-scoped validator key so "required credential" clears without reconnecting', async () => {
    const client = makeClient();
    const { inspectorFn, validatorFn } = await seedCredentialQueries(client);
    currentSearch = new URLSearchParams('success=true');

    renderHook(() => useOAuthCredentialCallback({ addToast, tCredentials }), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => {
      expect(inspectorFn).toHaveBeenCalledTimes(1);
      expect(validatorFn).toHaveBeenCalledTimes(1); // the guard root cause (b) would fail
    });
    expect(addToast).toHaveBeenCalledWith(expect.objectContaining({ type: 'success' }));
    expect(replace).toHaveBeenCalledWith('/app/workflows/builder', { scroll: false });
  });

  it('on ?error=… shows an error toast and refetches neither credential query', async () => {
    const client = makeClient();
    const { inspectorFn, validatorFn } = await seedCredentialQueries(client);
    currentSearch = new URLSearchParams('error=connection_denied');

    renderHook(() => useOAuthCredentialCallback({ addToast, tCredentials }), {
      wrapper: makeWrapper(client),
    });

    expect(addToast).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'error', message: 'connection_denied' }),
    );
    await Promise.resolve();
    expect(inspectorFn).not.toHaveBeenCalled();
    expect(validatorFn).not.toHaveBeenCalled();
    expect(replace).toHaveBeenCalled();
  });

  // Lazy-loading guarantee, from the hook's side: mounting it on a normal page
  // (no OAuth params) must NOT refetch either credential query. The hook adds
  // exactly one refetch, and only on the OAuth return - never on plain mount.
  it('does nothing on a normal mount with neither success nor error param (no on-mount credentials refetch)', async () => {
    const client = makeClient();
    const { inspectorFn, validatorFn } = await seedCredentialQueries(client);

    renderHook(() => useOAuthCredentialCallback({ addToast, tCredentials }), {
      wrapper: makeWrapper(client),
    });

    await Promise.resolve();
    expect(addToast).not.toHaveBeenCalled();
    expect(inspectorFn).not.toHaveBeenCalled();
    expect(validatorFn).not.toHaveBeenCalled();
    expect(replace).not.toHaveBeenCalled();
  });

  it('handles the callback only once per return even if the component re-renders', async () => {
    const client = makeClient();
    const { inspectorFn, validatorFn } = await seedCredentialQueries(client);
    currentSearch = new URLSearchParams('success=true');

    const { rerender } = renderHook(
      () => useOAuthCredentialCallback({ addToast, tCredentials }),
      { wrapper: makeWrapper(client) },
    );
    await waitFor(() => expect(inspectorFn).toHaveBeenCalledTimes(1));
    rerender();
    await Promise.resolve();

    expect(inspectorFn).toHaveBeenCalledTimes(1);
    expect(validatorFn).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledTimes(1);
  });
});
