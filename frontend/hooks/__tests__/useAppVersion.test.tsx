/**
 * @vitest-environment jsdom
 *
 * Tests for {@code useAppVersion}: the query is CE-gated (cloud never fetches) and
 * gated on auth (disabled, no fetch until authenticated), reports a loading state
 * while auth is still resolving (so consumers do not flash the error fallback),
 * exposes the fetched payload, and surfaces fetch errors.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { useAppVersion, type AppVersionInfo } from '../useAppVersion';

const authMock = vi.hoisted(() => ({
  current: { isLoading: false, isAuthenticated: true },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authMock.current,
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn() },
}));
import { apiClient } from '@/lib/api/api-client';

// IS_CE is a build-time constant; mock it (toggleable) to test the CE gate.
const editionMock = vi.hoisted(() => ({ isCe: true }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return editionMock.isCe; },
}));

const CLOUD: AppVersionInfo = {
  version: '1.4.2',
  gitSha: 'abc1234',
  buildTime: '2026-06-25T10:30:00Z',
  edition: 'cloud',
  selfHosted: false,
  managedCloud: true,
  updateAvailable: false,
  latestVersion: null,
  releaseUrl: null,
  securityFix: false,
  checkedAt: null,
};

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useAppVersion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authMock.current = { isLoading: false, isAuthenticated: true };
    editionMock.isCe = true;
  });

  it('does not fetch in cloud (CE-gated), even when authenticated', () => {
    editionMock.isCe = false;

    const { result } = renderHook(() => useAppVersion(), { wrapper: wrapper() });

    expect(result.current.isLoading).toBe(false);
    expect(result.current.version).toBeNull();
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('reports loading (not error) and does not fetch while auth is resolving', () => {
    authMock.current = { isLoading: true, isAuthenticated: false };

    const { result } = renderHook(() => useAppVersion(), { wrapper: wrapper() });

    expect(result.current.isLoading).toBe(true);
    expect(result.current.version).toBeNull();
    expect(result.current.isError).toBe(false);
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('stays disabled (no fetch, not loading) once auth resolves to an anonymous user', () => {
    authMock.current = { isLoading: false, isAuthenticated: false };

    const { result } = renderHook(() => useAppVersion(), { wrapper: wrapper() });

    expect(result.current.isLoading).toBe(false);
    expect(result.current.version).toBeNull();
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('fetches /version once authenticated and exposes the payload', async () => {
    (apiClient.get as any).mockResolvedValueOnce(CLOUD);

    const { result } = renderHook(() => useAppVersion(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.version).toEqual(CLOUD));
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isError).toBe(false);
    expect(apiClient.get).toHaveBeenCalledWith('/version');
  });

  it('surfaces a fetch error without throwing', async () => {
    (apiClient.get as any).mockRejectedValueOnce(new Error('boom'));

    const { result } = renderHook(() => useAppVersion(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.version).toBeNull();
  });
});
