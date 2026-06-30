// @vitest-environment jsdom
/**
 * Regression tests for the workflow-title branch of {@link useBreadcrumbs}.
 *
 * Bug: after creating a workflow from the modal, the app redirects into
 * /app/workflow/{id} and the breadcrumb resolves the title via getWorkflow(id).
 * That post-create round-trip can transiently fail, and because the breadcrumb
 * fetches exactly once (its effect deps don't change), the "Workflow {uuid}"
 * fallback then stuck - the user never saw the name they had just typed. The fix
 * primes the name the creator already knows (recentWorkflowNames) so the title is
 * correct immediately, independent of that fetch. Navigation from a card never
 * primes the cache, so that path must stay exactly as before.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, act, cleanup } from '@testing-library/react';

const WF_ID = '3f7a9c2b-5d6e-7f8a-9b0c-1d2e3f4a5b6c';

let mockView: { view: string; workflowId: string | null; dataSourceId: string | null; interfaceId: string | null; publicationId: string | null } = {
  view: 'workflow', workflowId: WF_ID, dataSourceId: null, interfaceId: null, publicationId: null,
};

vi.mock('next/navigation', () => ({
  usePathname: () => `/en/app/workflow/${WF_ID}`,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => mockView,
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => vi.fn(),
}));
const getWorkflow = vi.fn();
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getDataSources: vi.fn().mockResolvedValue([]),
    getWorkflow: (id: string) => getWorkflow(id),
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
import { rememberWorkflowName, recallWorkflowName, forgetWorkflowName } from '@/lib/workflows/recentWorkflowNames';

// The breadcrumb is [Home, Workflows, <title>]; the title is the last item.
const titleOf = (items: { label: string }[]) => items[items.length - 1]?.label;

beforeEach(() => {
  mockView = { view: 'workflow', workflowId: WF_ID, dataSourceId: null, interfaceId: null, publicationId: null };
  getWorkflow.mockReset();
  forgetWorkflowName(WF_ID); // module-scoped cache: start each test with no prime
});
afterEach(() => cleanup());

describe('useBreadcrumbs - workflow title', () => {
  it('navigation from a card (not primed): shows the fetched name', async () => {
    getWorkflow.mockResolvedValue({ name: 'Quarterly report', description: '' });
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('Quarterly report'));
  });

  it('not primed + getWorkflow fails: keeps the bare "Workflow {id}" fallback (unchanged behaviour)', async () => {
    getWorkflow.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe(`Workflow ${WF_ID}`));
  });

  it('primed (just created) + getWorkflow FAILS: shows the primed name, NOT the uuid fallback', async () => {
    // This is the reported bug: the post-create fetch transiently fails.
    rememberWorkflowName(WF_ID, 'My fresh flow');
    getWorkflow.mockRejectedValue(new Error('transient'));
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('My fresh flow'));
    expect(titleOf(result.current.breadcrumbItems)).not.toBe(`Workflow ${WF_ID}`);
  });

  it('primed + getWorkflow returns an empty name: falls back to the primed name (not the uuid)', async () => {
    rememberWorkflowName(WF_ID, 'My fresh flow');
    getWorkflow.mockResolvedValue({ name: '', description: '' });
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('My fresh flow'));
  });

  it('primed + getWorkflow succeeds with the real name: shows the authoritative fetched name', async () => {
    rememberWorkflowName(WF_ID, 'Typed in modal');
    getWorkflow.mockResolvedValue({ name: 'Server name of record', description: '' });
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('Server name of record'));
  });

  it('consumes the prime once: a confirmed server name evicts it (no stale name on re-navigation)', async () => {
    rememberWorkflowName(WF_ID, 'Typed in modal');
    getWorkflow.mockResolvedValue({ name: 'Server name of record', description: '' });
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('Server name of record'));
    // The prime has served its purpose and must not linger to resurface later.
    expect(recallWorkflowName(WF_ID)).toBeUndefined();
  });

  it('a rename (metadataEditSaved) drops the stale prime so it cannot resurface on a failed re-fetch', async () => {
    rememberWorkflowName(WF_ID, 'Old name');
    getWorkflow.mockRejectedValue(new Error('transient')); // prime would otherwise persist
    const { result } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('Old name'));

    act(() => {
      window.dispatchEvent(new CustomEvent('metadataEditSaved', {
        detail: { resourceType: 'workflow', id: WF_ID, name: 'Renamed', description: '' },
      }));
    });

    await waitFor(() => expect(titleOf(result.current.breadcrumbItems)).toBe('Renamed'));
    expect(recallWorkflowName(WF_ID)).toBeUndefined();
  });
});
