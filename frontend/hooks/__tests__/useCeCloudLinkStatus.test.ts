// @vitest-environment jsdom
/**
 * CE cloud-parity gate primitive (2026-06-10): `useCeCloudLinkStatus` decides
 * whether a CE install renders the cloud marketplace UI (linked AND registered)
 * or the legacy reduced CE experience. These tests pin the gate semantics:
 * - "cloud-linked" requires BOTH linked and registered (a linked-but-unregistered
 *   install is mid-onboarding and must stay on the legacy UI),
 * - loading is reported until auth AND the status request resolve (no flash of
 *   the wrong marketplace), but never for anonymous visitors or cloud builds,
 * - cloud builds never fire the request at all.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';

const editionState = vi.hoisted(() => ({ isCe: true }));
const authState = vi.hoisted(() => ({ isLoading: false, isAuthenticated: true }));
const queryState = vi.hoisted(() => ({
  data: undefined as unknown,
  isPending: true,
  lastOptions: null as null | { enabled?: boolean; queryKey?: unknown[]; retry?: unknown },
}));

vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return editionState.isCe;
  },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ ...authState }),
}));

vi.mock('@tanstack/react-query', () => ({
  useQuery: (options: { enabled?: boolean; queryKey?: unknown[]; retry?: unknown }) => {
    queryState.lastOptions = options;
    return { data: queryState.data, isPending: queryState.isPending };
  },
}));

vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getStatus: vi.fn() },
}));

import { useCeCloudLinkStatus } from '../useCeCloudLinkStatus';

beforeEach(() => {
  editionState.isCe = true;
  authState.isLoading = false;
  authState.isAuthenticated = true;
  queryState.data = undefined;
  queryState.isPending = true;
  queryState.lastOptions = null;
});

describe('useCeCloudLinkStatus', () => {
  it('reports cloud-linked when the CE install is linked AND registered', () => {
    queryState.data = { linked: true, registered: true, llmSource: 'CLOUD' };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isCloudLinked).toBe(true);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.status?.llmSource).toBe('CLOUD');
  });

  it('reports isInstallCloudLinked from the install-global field, independent of the per-user link', () => {
    // Inheriting member: per-user link is false (cannot manage), but the install
    // has the admin's active link → install-global visibility is true.
    queryState.data = { linked: false, installLinked: true, installCloudPlanCode: 'TEAM' };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isCloudLinked).toBe(false);
    expect(result.current.isInstallCloudLinked).toBe(true);
  });

  it('reports isInstallCloudLinked false when no user on the install is linked', () => {
    queryState.data = { linked: false, registered: false };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isInstallCloudLinked).toBe(false);
  });

  it('the link owner reports BOTH isCloudLinked and isInstallCloudLinked true', () => {
    queryState.data = { linked: true, registered: true, installLinked: true, cloudPlanCode: 'TEAM' };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isCloudLinked).toBe(true);
    expect(result.current.isInstallCloudLinked).toBe(true);
  });

  it('does NOT report cloud-linked when linked but not yet registered (mid-onboarding)', () => {
    queryState.data = { linked: true, registered: false };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isCloudLinked).toBe(false);
    expect(result.current.isLoading).toBe(false);
  });

  it('reports loading while the status request is in flight (no flash of the wrong UI)', () => {
    queryState.data = undefined;
    queryState.isPending = true;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isLoading).toBe(true);
    expect(result.current.isCloudLinked).toBe(false);
    expect(queryState.lastOptions?.enabled).toBe(true);
  });

  it('reports loading while auth is resolving and keeps the query disabled', () => {
    authState.isLoading = true;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isLoading).toBe(true);
    expect(queryState.lastOptions?.enabled).toBe(false);
  });

  it('resolves immediately as unlinked for anonymous CE visitors (never linked, never loading)', () => {
    authState.isAuthenticated = false;
    queryState.isPending = true; // disabled queries stay pending in react-query

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isLoading).toBe(false);
    expect(result.current.isCloudLinked).toBe(false);
    expect(queryState.lastOptions?.enabled).toBe(false);
  });

  it('resolves as unlinked (not loading) when the status request errored', () => {
    // react-query: error → data undefined, isPending false.
    queryState.data = undefined;
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(result.current.isLoading).toBe(false);
    expect(result.current.isCloudLinked).toBe(false);
    expect(result.current.status).toBeNull();
  });

  it('cloud builds never enable the query and always report a resolved, unlinked state', () => {
    editionState.isCe = false;
    // Even with stale linked data in the shared cache the cloud build ignores it.
    queryState.data = { linked: true, registered: true };
    queryState.isPending = false;

    const { result } = renderHook(() => useCeCloudLinkStatus());

    expect(queryState.lastOptions?.enabled).toBe(false);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isCloudLinked).toBe(false);
    expect(result.current.isInstallCloudLinked).toBe(false);
    expect(result.current.status).toBeNull();
  });

  it('shares the AppSidebar cache key so the status is fetched once per session', () => {
    renderHook(() => useCeCloudLinkStatus());

    expect(queryState.lastOptions?.queryKey).toEqual(['cloud-link', 'status']);
  });
});
