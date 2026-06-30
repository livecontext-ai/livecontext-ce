// @vitest-environment jsdom
/**
 * Acquirer/owner preview of a NON-PUBLIC (publisher-deleted INACTIVE / PRIVATE)
 * publication. The marketplace preview page reads such a pub through the auth'd
 * by-id endpoint and publishes `authenticated:true` on the snapshot store, so
 * useInterfaceRender must forward `authenticated:true` to getShowcaseRender ->
 * the receipt-gated AUTH'D /publications/{id}/showcase-render twin. The plain
 * anonymous public preview keeps `authenticated:false` (anonymous /by-id render).
 *
 * The authed path needs a token, so the query must stay gated until auth settles;
 * the anonymous path needs none and fires immediately.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';

const ctx = vi.hoisted(() => ({ value: null as null | Record<string, unknown> }));
const auth = vi.hoisted(() => ({ value: { isLoading: false, isAuthenticated: true } as { isLoading: boolean; isAuthenticated: boolean } }));
const getShowcaseRender = vi.hoisted(() => vi.fn());
const renderInterface = vi.hoisted(() => vi.fn());

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => ctx.value,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => auth.value,
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseRender: (...a: unknown[]) => getShowcaseRender(...a) },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { renderInterface: (...a: unknown[]) => renderInterface(...a) },
}));

import { useInterfaceRender, useInterfaceItemsCount } from '../useInterfaces';

const RUN = 'showcase_run_1';
const PUB = 'pub-acq';
const IFACE = 'iface-9';

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

beforeEach(() => {
  getShowcaseRender.mockReset().mockResolvedValue({ htmlTemplate: '<div/>', items: [], pagination: {} });
  renderInterface.mockReset().mockResolvedValue({ htmlTemplate: '<div/>', items: [] });
  ctx.value = null;
  auth.value = { isLoading: false, isAuthenticated: true };
});

describe('useInterfaceRender - acquirer/owner authenticated showcase render', () => {
  it('forwards authenticated=true to getShowcaseRender for a receipt-gated (non-public) preview', async () => {
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: true };
    renderHook(() => useInterfaceRender(IFACE, RUN, 0, 1), { wrapper: wrap() });

    await waitFor(() => expect(getShowcaseRender).toHaveBeenCalled());
    expect(getShowcaseRender).toHaveBeenCalledWith(
      PUB,
      expect.objectContaining({ interfaceId: IFACE, authenticated: true }),
      false,
    );
    // Never falls through to the auth'd per-run /interfaces/{id}/render path.
    expect(renderInterface).not.toHaveBeenCalled();
  });

  it('forwards authenticated=false for a normal anonymous public preview', async () => {
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: false };
    renderHook(() => useInterfaceRender(IFACE, RUN, 0, 1), { wrapper: wrap() });

    await waitFor(() => expect(getShowcaseRender).toHaveBeenCalled());
    expect(getShowcaseRender).toHaveBeenCalledWith(
      PUB,
      expect.objectContaining({ authenticated: false }),
      false,
    );
  });

  it('keeps the authenticated query gated until auth settles (token required)', async () => {
    auth.value = { isLoading: true, isAuthenticated: false };
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: true };
    renderHook(() => useInterfaceRender(IFACE, RUN, 0, 1), { wrapper: wrap() });

    // The authed showcase read must NOT fire while the token is still resolving.
    await new Promise((r) => setTimeout(r, 30));
    expect(getShowcaseRender).not.toHaveBeenCalled();
  });

  it('runs the anonymous public path even while auth is loading (no token needed)', async () => {
    auth.value = { isLoading: true, isAuthenticated: false };
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: false };
    renderHook(() => useInterfaceRender(IFACE, RUN, 0, 1), { wrapper: wrap() });

    await waitFor(() => expect(getShowcaseRender).toHaveBeenCalled());
  });
});

describe('useInterfaceItemsCount - acquirer/owner authenticated showcase render', () => {
  it('forwards authenticated=true to getShowcaseRender for a receipt-gated preview', async () => {
    getShowcaseRender.mockResolvedValue({ pagination: { totalItems: 3 } });
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: true };
    const { result } = renderHook(() => useInterfaceItemsCount(IFACE, RUN), { wrapper: wrap() });

    await waitFor(() => expect(getShowcaseRender).toHaveBeenCalled());
    expect(getShowcaseRender).toHaveBeenCalledWith(
      PUB,
      expect.objectContaining({ interfaceId: IFACE, authenticated: true }),
      false,
    );
    await waitFor(() => expect(result.current.data).toBe(3));
  });

  it('keeps the authenticated items-count query gated until auth settles', async () => {
    auth.value = { isLoading: true, isAuthenticated: false };
    ctx.value = { publicationId: PUB, showcaseRunId: RUN, remote: false, authenticated: true };
    renderHook(() => useInterfaceItemsCount(IFACE, RUN), { wrapper: wrap() });

    await new Promise((r) => setTimeout(r, 30));
    expect(getShowcaseRender).not.toHaveBeenCalled();
  });
});
