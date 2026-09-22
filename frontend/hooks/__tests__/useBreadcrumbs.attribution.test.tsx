// @vitest-environment jsdom
/**
 * The attribution the breadcrumb hands to its info control.
 *
 * <p>Three things this pins, all of which fail silently: the attribution APPEARS once the name
 * read resolves, it is DROPPED when the crumb moves to another workflow, and it names the
 * resource it describes (`resourceKey`) so the reused control lets go of the previous
 * workflow's editors.
 *
 * <p>The middle one is narrower than it looks, and the test says so: normally the crumb hides
 * its info while a name resolves, so a stale attribution has nowhere to show. The reset earns
 * its place only for a workflow whose name is already PRIMED - the one you just created and
 * were redirected into - because that crumb paints straight away. Removing the reset turns
 * exactly that case red and nothing else, which is the honest size of the guard.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor, cleanup } from '@testing-library/react';

const WF_A = '3f7a9c2b-5d6e-7f8a-9b0c-1d2e3f4a5b6c';
const WF_B = '9c8b7a6d-5e4f-4a3b-8c7d-6e5f4a3b2c1d';

let mockView: {
  view: string;
  workflowId: string | null;
  dataSourceId: string | null;
  interfaceId: string | null;
  publicationId: string | null;
} = { view: 'workflow', workflowId: WF_A, dataSourceId: null, interfaceId: null, publicationId: null };

vi.mock('next/navigation', () => ({
  usePathname: () => `/en/app/workflow/${mockView.workflowId}`,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({ useCurrentView: () => mockView }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isLoading: false }),
}));
vi.mock('@/contexts/NavigationGuardContext', () => ({ useSafeNavigate: () => vi.fn() }));

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

import { useBreadcrumbs, type BreadcrumbItem } from '../useBreadcrumbs';
import { forgetWorkflowName, rememberWorkflowName } from '@/lib/workflows/recentWorkflowNames';

/** The breadcrumb is [Home, Workflows, <resource>]; the info lives on the last crumb. */
const tail = (items: BreadcrumbItem[]): BreadcrumbItem => items[items.length - 1];

beforeEach(() => {
  mockView = { view: 'workflow', workflowId: WF_A, dataSourceId: null, interfaceId: null, publicationId: null };
  getWorkflow.mockReset();
  forgetWorkflowName(WF_A);
  forgetWorkflowName(WF_B);
});
afterEach(() => cleanup());

describe('useBreadcrumbs - resource attribution', () => {
  it('carries owner and timestamps once the workflow read resolves', async () => {
    getWorkflow.mockResolvedValue({
      name: 'Quarterly report',
      description: '',
      tenantId: '42',
      createdAt: '2026-03-12T14:32:00Z',
      updatedAt: '2026-09-07T09:12:00Z',
    });

    const { result } = renderHook(() => useBreadcrumbs());

    // Note what this does NOT pin: removing the attribution from the memo's dependency list
    // keeps this green, because the title changes in the same commit and recomputes the memo
    // anyway. The dependencies are still right to have; they are simply not what this asserts.
    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeTruthy());
    expect(tail(result.current.breadcrumbItems).info).toMatchObject({
      ownerId: '42',
      createdAt: '2026-03-12T14:32:00Z',
      updatedAt: '2026-09-07T09:12:00Z',
    });
  });

  it('names the resource it describes, so the reused control drops the previous editors', async () => {
    getWorkflow.mockResolvedValue({ name: 'Quarterly report', description: '', tenantId: '42' });

    const { result } = renderHook(() => useBreadcrumbs());

    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeTruthy());
    expect(tail(result.current.breadcrumbItems).info).toMatchObject({ resourceKey: `workflow:${WF_A}` });
  });

  it('offers a workflow an editor list, because a workflow is the one resource that keeps one', async () => {
    getWorkflow.mockResolvedValue({ name: 'Quarterly report', description: '', tenantId: '42' });

    const { result } = renderHook(() => useBreadcrumbs());

    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeTruthy());
    expect(typeof tail(result.current.breadcrumbItems).info?.loadEditors).toBe('function');
  });

  it('drops the previous attribution when the crumb moves to a workflow whose NAME is already known', async () => {
    // The window this guards is narrow and real: normally the crumb hides its info while the
    // name resolves, so a stale attribution has nowhere to show. But a workflow the user just
    // CREATED arrives with its name primed, so the crumb paints immediately - and without the
    // reset it would paint the previous workflow's owner and dates under it.
    getWorkflow.mockImplementation((id: string) =>
      id === WF_A
        ? Promise.resolve({
            name: 'A',
            description: '',
            tenantId: '42',
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-02T00:00:00Z',
          })
        : new Promise(() => {}));

    const { result, rerender } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeTruthy());

    rememberWorkflowName(WF_B, 'B, just created');
    mockView = { ...mockView, workflowId: WF_B };
    rerender();

    await waitFor(() => expect(tail(result.current.breadcrumbItems).label).toBe('B, just created'));
    expect(tail(result.current.breadcrumbItems).info, "A's attribution must not sit under B's name")
      .toBeUndefined();
  });

  it('hides the attribution while a workflow with no primed name is still resolving', async () => {
    getWorkflow.mockImplementation((id: string) =>
      id === WF_A
        ? Promise.resolve({ name: 'A', description: '', tenantId: '42', createdAt: '2026-01-01T00:00:00Z' })
        : new Promise(() => {}));

    const { result, rerender } = renderHook(() => useBreadcrumbs());
    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeTruthy());

    mockView = { ...mockView, workflowId: WF_B };
    rerender();

    await waitFor(() => expect(tail(result.current.breadcrumbItems).info).toBeUndefined());
  });

  it('omits the attribution entirely when the workflow read fails', async () => {
    getWorkflow.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => useBreadcrumbs());

    // The crumb still shows its fallback title; what it must not do is state an owner or a
    // date it never received.
    await waitFor(() => expect(tail(result.current.breadcrumbItems).label).toBe(`Workflow ${WF_A}`));
    expect(tail(result.current.breadcrumbItems).info).toBeUndefined();
  });
});
