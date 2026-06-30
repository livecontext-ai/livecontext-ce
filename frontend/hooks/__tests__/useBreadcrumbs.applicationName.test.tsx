// @vitest-environment jsdom
/**
 * Regression: the breadcrumb leaf on an INSTALLED application page must show the app's
 * NAME, not the generic "Application".
 *
 * An acquired CLOUD app's publication id is absent from the local catalog on a cloud-linked
 * CE, so a plain publicationService.getPublicationById 404s. The breadcrumb previously caught
 * that and fell back to the literal "Application". The fix routes the application page through
 * resolveApplicationPublication (the same resolver the page uses), which on CE retries the
 * remote by-id proxy - so the real title shows.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, cleanup } from '@testing-library/react';

const APP_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

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
vi.mock('@/contexts/NavigationGuardContext', () => ({ useSafeNavigate: () => vi.fn() }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: vi.fn().mockResolvedValue({}),
    getInterface: vi.fn().mockResolvedValue({}),
  },
}));
// Cloud-linked CE so resolveApplicationPublication takes the remote-fallback branch.
vi.mock('@/lib/edition', () => ({ IS_CE: true }));

const svc = vi.hoisted(() => ({
  getPublicationById: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
  getFavoriteIds: vi.fn(),
  addFavorite: vi.fn(),
  removeFavorite: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));
vi.mock('@/lib/api/orchestrator/project.service', () => ({ projectService: { getProject: vi.fn().mockResolvedValue({}) } }));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getApiById: vi.fn().mockResolvedValue({}), getToolById: vi.fn().mockResolvedValue({}) },
}));

import { useBreadcrumbs } from '../useBreadcrumbs';

const leafLabel = (items: Array<{ label: string }>) => items[items.length - 1]?.label;

beforeEach(() => {
  svc.getFavoriteIds.mockResolvedValue([]);
  svc.addFavorite.mockResolvedValue(undefined);
  svc.removeFavorite.mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('useBreadcrumbs - installed application name', () => {
  it('acquired cloud app (local by-id 404) → leaf shows the name via the remote fallback, not "Application"', async () => {
    svc.getPublicationById.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    svc.getPublicationByIdPublic.mockResolvedValue({ title: 'Universal File Downloader', publicationType: 'WORKFLOW' });

    const { result } = renderHook(() => useBreadcrumbs());

    await waitFor(() => expect(leafLabel(result.current.breadcrumbItems)).toBe('Universal File Downloader'));
    // Remote proxy was consulted with remote=true after the local miss.
    expect(svc.getPublicationByIdPublic).toHaveBeenCalledWith(APP_ID, true);
    expect(leafLabel(result.current.breadcrumbItems)).not.toBe('Application');
  });

  it('locally-resolvable app → leaf shows the name from the local by-id (no remote call)', async () => {
    svc.getPublicationById.mockResolvedValue({ title: 'My Local App', publicationType: 'WORKFLOW' });

    const { result } = renderHook(() => useBreadcrumbs());

    await waitFor(() => expect(leafLabel(result.current.breadcrumbItems)).toBe('My Local App'));
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled();
  });
});
