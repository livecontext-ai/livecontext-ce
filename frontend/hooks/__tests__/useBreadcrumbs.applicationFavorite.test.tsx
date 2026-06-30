// @vitest-environment jsdom
/**
 * Favorite toggle on the application-detail breadcrumb (/app/applications/{id}).
 *
 * The last breadcrumb item carries a `favorite` object once the title + favorite
 * state resolve for a signed-in user. Its onToggle optimistically flips the star
 * and calls add/removeFavorite, reverting on failure.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act, cleanup } from '@testing-library/react';

const APP_ID = '11111111-2222-3333-4444-555555555555';

vi.mock('next/navigation', () => ({
  usePathname: () => `/en/app/applications/${APP_ID}`,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({ view: 'applications', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: APP_ID }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => vi.fn(),
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: vi.fn().mockResolvedValue({}),
    getInterface: vi.fn().mockResolvedValue({}),
  },
}));

const favMocks = vi.hoisted(() => ({
  getPublicationById: vi.fn(),
  getFavoriteIds: vi.fn(),
  addFavorite: vi.fn(),
  removeFavorite: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPublicationById: favMocks.getPublicationById,
    getFavoriteIds: favMocks.getFavoriteIds,
    addFavorite: favMocks.addFavorite,
    removeFavorite: favMocks.removeFavorite,
  },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({
  projectService: { getProject: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { useBreadcrumbs } from '../useBreadcrumbs';

// The app breadcrumb is [Home, Applications, <app title + favorite>]; the favorite lives on the last item.
const favOf = (items: Array<{ favorite?: { isFavorite: boolean; onToggle: () => void } }>) =>
  items[items.length - 1]?.favorite;

beforeEach(() => {
  favMocks.getPublicationById.mockResolvedValue({ title: 'My App', publicationType: 'WORKFLOW' });
  favMocks.getFavoriteIds.mockResolvedValue([]);
  favMocks.addFavorite.mockResolvedValue(undefined);
  favMocks.removeFavorite.mockResolvedValue(undefined);
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('useBreadcrumbs - application favorite toggle', () => {
  it('exposes a favorite affordance reflecting the resolved state (not favorited)', async () => {
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)).toBeDefined());
    expect(favOf(result.current.breadcrumbItems)!.isFavorite).toBe(false);
  });

  it('reflects an already-favorited app', async () => {
    favMocks.getFavoriteIds.mockResolvedValue([APP_ID]);
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)?.isFavorite).toBe(true));
  });

  it('onToggle optimistically stars the app and calls addFavorite', async () => {
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)).toBeDefined());

    act(() => favOf(result.current.breadcrumbItems)!.onToggle());

    expect(favMocks.addFavorite).toHaveBeenCalledWith(APP_ID);
    expect(favMocks.removeFavorite).not.toHaveBeenCalled();
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)?.isFavorite).toBe(true));
  });

  it('onToggle on a favorited app calls removeFavorite and unstars', async () => {
    favMocks.getFavoriteIds.mockResolvedValue([APP_ID]);
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)?.isFavorite).toBe(true));

    act(() => favOf(result.current.breadcrumbItems)!.onToggle());

    expect(favMocks.removeFavorite).toHaveBeenCalledWith(APP_ID);
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)?.isFavorite).toBe(false));
  });

  it('reverts the optimistic star when the server call fails', async () => {
    favMocks.addFavorite.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)).toBeDefined());

    act(() => favOf(result.current.breadcrumbItems)!.onToggle());

    // Flips optimistically, then reverts to NOT favorited once the failure resolves.
    await waitFor(() => expect(favOf(result.current.breadcrumbItems)?.isFavorite).toBe(false));
    expect(favMocks.addFavorite).toHaveBeenCalledWith(APP_ID);
  });
});
