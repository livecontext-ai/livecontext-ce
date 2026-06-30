// @vitest-environment jsdom
/**
 * Native-resource favorite star on the workflow / table / interface detail
 * breadcrumbs (the counterpart to the application-favorite breadcrumb, backed by
 * the generic favorites store). Pins the wiring + the non-trivial gates:
 *  - the resource-name crumb carries a `favorite` once its name + favorite state
 *    resolve for a signed-in user, and onToggle add/removes through favoriteService;
 *  - the table crumb gets the star ONLY when it is the table itself, NOT when
 *    drilled into a JSON subpath (the isLastDataItem gate);
 *  - the star is suppressed while the favorite state is still loading and when
 *    the user is unauthenticated.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act, cleanup } from '@testing-library/react';

const state = vi.hoisted(() => ({
  pathname: '/en/app/workflow/wf-1',
  view: { view: 'workflow', workflowId: 'wf-1', dataSourceId: null, interfaceId: null, publicationId: null } as any,
  auth: { isAuthenticated: true, isLoading: false },
}));

vi.mock('next/navigation', () => ({
  usePathname: () => state.pathname,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => state.view }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => state.auth }));
vi.mock('@/contexts/NavigationGuardContext', () => ({ useSafeNavigate: () => vi.fn() }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn().mockResolvedValue({ name: 'My Workflow' }),
    getInterface: vi.fn().mockResolvedValue({ name: 'My Interface' }),
    getDataSources: vi.fn().mockResolvedValue([{ id: '5', name: 'My Table' }]),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: vi.fn().mockResolvedValue({}), getFavoriteIds: vi.fn().mockResolvedValue([]) },
}));
vi.mock('@/lib/api/orchestrator/project.service', () => ({ projectService: { getProject: vi.fn().mockResolvedValue({}) } }));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

const fav = vi.hoisted(() => ({ getFavoriteIds: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn() }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: fav }));

import { useBreadcrumbs } from '../useBreadcrumbs';

type Crumb = { label: string; favorite?: { isFavorite: boolean; onToggle: () => void } };
const last = (items: Crumb[]) => items[items.length - 1];
const byLabel = (items: Crumb[], label: string) => items.find((i) => i.label === label);

beforeEach(() => {
  state.pathname = '/en/app/workflow/wf-1';
  state.view = { view: 'workflow', workflowId: 'wf-1', dataSourceId: null, interfaceId: null, publicationId: null };
  state.auth = { isAuthenticated: true, isLoading: false };
  fav.getFavoriteIds.mockResolvedValue([]);
  fav.addFavorite.mockResolvedValue(undefined);
  fav.removeFavorite.mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('useBreadcrumbs - workflow crumb favorite', () => {
  it('exposes the favorite on the workflow crumb and fetches WORKFLOW ids', async () => {
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeDefined());
    expect(last(result.current.breadcrumbItems as Crumb[]).favorite!.isFavorite).toBe(false);
    expect(fav.getFavoriteIds).toHaveBeenCalledWith('WORKFLOW');
  });

  it('reflects an already-favorited workflow', async () => {
    fav.getFavoriteIds.mockResolvedValue(['wf-1']);
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite?.isFavorite).toBe(true));
  });

  it('onToggle stars the workflow through favoriteService and reverts on failure', async () => {
    fav.addFavorite.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeDefined());
    act(() => last(result.current.breadcrumbItems as Crumb[]).favorite!.onToggle());
    expect(fav.addFavorite).toHaveBeenCalledWith('WORKFLOW', 'wf-1');
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite?.isFavorite).toBe(false));
  });

  it('suppresses the star while the favorite state is still loading', async () => {
    let release!: (v: string[]) => void;
    fav.getFavoriteIds.mockReturnValue(new Promise<string[]>((res) => { release = res; }));
    const { result } = renderHook(() => useBreadcrumbs());
    // Workflow name resolves, but favorite state is pending → no star yet.
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).label).toBe('My Workflow'));
    expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeUndefined();
    await act(async () => { release([]); });
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeDefined());
  });

  it('suppresses the star for an unauthenticated user', async () => {
    state.auth = { isAuthenticated: false, isLoading: false };
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(result.current.breadcrumbItems.length).toBeGreaterThan(0));
    // Give the (disabled) effect a chance; it must never fetch or attach a star.
    await Promise.resolve();
    expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeUndefined();
    expect(fav.getFavoriteIds).not.toHaveBeenCalled();
  });
});

describe('useBreadcrumbs - table crumb favorite (isLastDataItem gate)', () => {
  beforeEach(() => {
    state.pathname = '/en/app/tables/5';
    state.view = { view: 'data', workflowId: null, dataSourceId: '5', interfaceId: null, publicationId: null };
  });

  it('exposes the favorite on the table crumb and fetches TABLE ids with the string id', async () => {
    fav.getFavoriteIds.mockResolvedValue(['5']);
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(byLabel(result.current.breadcrumbItems as Crumb[], 'My Table')?.favorite?.isFavorite).toBe(true));
    expect(fav.getFavoriteIds).toHaveBeenCalledWith('TABLE');
    act(() => byLabel(result.current.breadcrumbItems as Crumb[], 'My Table')!.favorite!.onToggle());
    expect(fav.removeFavorite).toHaveBeenCalledWith('TABLE', '5');
  });

  it('does NOT put a star on the table crumb when drilled into a JSON subpath', async () => {
    state.pathname = '/en/app/tables/5/some/field';
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(byLabel(result.current.breadcrumbItems as Crumb[], 'My Table')).toBeDefined());
    expect(byLabel(result.current.breadcrumbItems as Crumb[], 'My Table')!.favorite).toBeUndefined();
  });
});

describe('useBreadcrumbs - interface crumb favorite', () => {
  beforeEach(() => {
    state.pathname = '/en/app/interface/if-1';
    state.view = { view: 'interface', workflowId: null, dataSourceId: null, interfaceId: 'if-1', publicationId: null };
  });

  it('exposes the favorite on the interface crumb and fetches INTERFACE ids', async () => {
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(last(result.current.breadcrumbItems as Crumb[]).favorite).toBeDefined());
    expect(fav.getFavoriteIds).toHaveBeenCalledWith('INTERFACE');
    act(() => last(result.current.breadcrumbItems as Crumb[]).favorite!.onToggle());
    expect(fav.addFavorite).toHaveBeenCalledWith('INTERFACE', 'if-1');
  });
});
