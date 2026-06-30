// @vitest-environment jsdom
/**
 * Tests for the public-profile branch of {@link useBreadcrumbs}. Before this branch
 * existed, /app/u/{handle} produced no breadcrumb items, so `shouldShowBreadcrumb`
 * was false and the header fell back to the MODEL SELECTOR on a profile page.
 * These assert the Home / Profile / @handle trail, that the handle is read from the
 * URL (decoded), and that non-profile chat routes are untouched.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, cleanup } from '@testing-library/react';

let mockPathname = '/en/app/u/ada-lovelace';
let mockView: { view: string; workflowId: string | null; dataSourceId: string | null; interfaceId: string | null; publicationId: string | null } = {
  view: 'chat', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null,
};

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => mockView,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
const navigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => navigate,
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: vi.fn().mockResolvedValue({}),
    getInterface: vi.fn().mockResolvedValue({}),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProject: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { useBreadcrumbs } from '../useBreadcrumbs';

beforeEach(() => {
  mockPathname = '/en/app/u/ada-lovelace';
  mockView = { view: 'chat', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null };
  navigate.mockClear();
});
afterEach(() => cleanup());

describe('useBreadcrumbs - public profile view', () => {
  it('shows Home / Profile / @handle and marks the view as breadcrumb-bearing (model selector hidden)', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.shouldShowBreadcrumb).toBe(true);

    const items = result.current.breadcrumbItems;
    expect(items).toHaveLength(3);
    expect(items[1].label).toBe('Profile');
    expect(items[2].label).toBe('@ada-lovelace');
    expect(items[2].truncate).toBe(true);
    // "Profile" and the handle are the current page - not clickable.
    expect(items[1].onClick).toBeUndefined();
    expect(items[2].onClick).toBeUndefined();
  });

  it('Home crumb navigates to /app (locale preserved by the navigator)', () => {
    const { result } = renderHook(() => useBreadcrumbs());
    result.current.breadcrumbItems[0].onClick!();
    expect(navigate).toHaveBeenCalledWith('/en/app');
  });

  it('decodes a URL-encoded handle from the route segment', () => {
    mockPathname = '/en/app/u/ada%2Dlovelace';
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.breadcrumbItems[2].label).toBe('@ada-lovelace');
  });

  it('still matches under every locale prefix (de/pt/zh now stripped by the shared parseLocalePath)', () => {
    // parseLocalePath is shared and covers ALL routing locales since 2026-06-12;
    // the profile regex additionally stays non-start-anchored, robust either way.
    mockPathname = '/de/app/u/ada-lovelace';
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.shouldShowBreadcrumb).toBe(true);
    expect(result.current.breadcrumbItems[2].label).toBe('@ada-lovelace');
  });

  it('falls back to the raw segment when the handle has a malformed percent encoding', () => {
    mockPathname = '/en/app/u/100%';
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.breadcrumbItems[2].label).toBe('@100%');
  });

  it('does not produce profile breadcrumbs on plain chat routes (regression guard)', () => {
    mockPathname = '/en/app/chat';
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.shouldShowBreadcrumb).toBe(false);
    expect(result.current.breadcrumbItems).toHaveLength(0);
  });

  it('does not match deeper /app/u/* paths (the route is /app/u/{handle} only)', () => {
    mockPathname = '/en/app/u/ada-lovelace/extra';
    const { result } = renderHook(() => useBreadcrumbs());
    expect(result.current.shouldShowBreadcrumb).toBe(false);
    expect(result.current.breadcrumbItems).toHaveLength(0);
  });
});
