// @vitest-environment jsdom
import React from 'react';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const auth = vi.hoisted(() => ({ isAuthenticated: true }));
const edition = vi.hoisted(() => ({ ce: false }));
const me = vi.fn();
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => auth }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return edition.ce; } }));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  PARTNER_DASHBOARD_QUERY_KEY: ['partner-program', 'me'],
  partnerProgramApi: { me: () => me() },
}));

import { hasPartnerSpace, useHasPartnerSpace } from '../usePartnerSpace';

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('hasPartnerSpace', () => {
  it('a partner (code live or disabled) and an applicant under review have a space to come back to', () => {
    expect(hasPartnerSpace('active')).toBe(true);
    expect(hasPartnerSpace('inactive')).toBe(true);
    expect(hasPartnerSpace('pending')).toBe(true);
  });

  it('someone who never applied, was refused, or whose state is unknown has none', () => {
    expect(hasPartnerSpace('none')).toBe(false);
    expect(hasPartnerSpace('rejected')).toBe(false);
    expect(hasPartnerSpace(undefined)).toBe(false);
  });
});

describe('useHasPartnerSpace', () => {
  beforeEach(() => {
    auth.isAuthenticated = true;
    edition.ce = false;
    me.mockReset();
  });
  afterEach(cleanup);

  it('reads the partner state and answers true for an applicant', async () => {
    me.mockResolvedValue({ state: 'pending' });
    const { result } = renderHook(() => useHasPartnerSpace(), { wrapper });

    await waitFor(() => expect(result.current).toBe(true));
  });

  it('answers false while the state loads and when the endpoint fails', async () => {
    me.mockRejectedValue(new Error('503'));
    const { result } = renderHook(() => useHasPartnerSpace(), { wrapper });

    expect(result.current).toBe(false);
    await waitFor(() => expect(me).toHaveBeenCalled());
    expect(result.current).toBe(false);
  });

  it('never asks when signed out, on a self-hosted install, or when the caller disables it', () => {
    auth.isAuthenticated = false;
    renderHook(() => useHasPartnerSpace(), { wrapper });
    auth.isAuthenticated = true;
    edition.ce = true;
    renderHook(() => useHasPartnerSpace(), { wrapper });
    edition.ce = false;
    renderHook(() => useHasPartnerSpace({ enabled: false }), { wrapper });

    expect(me).not.toHaveBeenCalled();
  });
});
