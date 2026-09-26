// @vitest-environment jsdom
/**
 * The Agents-page tab crumbs.
 *
 * They are trailing crumbs that carry the ONLY visible name of the screen the user is on:
 * the Agents page renders no title of its own, and Fleet even hides the tab bar, so the
 * crumb is the only exit. Every tab in AgentPageTabBar must therefore have an entry in
 * AGENT_TAB_CRUMBS - the Memory tab shipped without one and fell through to the bare
 * "Agents" crumb, which is exactly the gap these tests close.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, cleanup } from '@testing-library/react';

let mockPathname = '/en/app/agent';
let mockSearchParams = new URLSearchParams();
let mockView = 'agent';

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useSearchParams: () => mockSearchParams,
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({
    view: mockView, workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null,
  }),
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

import { useBreadcrumbs, AGENT_TAB_CRUMBS } from '../useBreadcrumbs';

const pushState = vi.fn();
const realPushState = window.history.pushState;

const labels = () => {
  const { result } = renderHook(() => useBreadcrumbs());
  return result.current.breadcrumbItems.map((i) => i.label);
};

beforeEach(() => {
  mockPathname = '/en/app/agent';
  mockSearchParams = new URLSearchParams();
  mockView = 'agent';
  navigate.mockClear();
  pushState.mockClear();
  window.history.pushState = pushState as unknown as typeof window.history.pushState;
});
afterEach(() => {
  window.history.pushState = realPushState;
  cleanup();
});

describe('useBreadcrumbs - Agents page tabs', () => {
  // One case per entry of AGENT_TAB_CRUMBS: a missing entry silently degrades to the bare
  // "Agents" crumb, with no error, which is how the Memory tab went unnoticed.
  // Driven off the map itself, not a copy of it: a hardcoded list here would go stale the
  // moment a tab is added, which is the exact failure this suite exists to prevent. The
  // map is keyed by the tab union, so TypeScript already refuses a tab with no entry.
  it.each(Object.entries(AGENT_TAB_CRUMBS))('names the %s tab in the trailing crumb', (view, expected) => {
    mockSearchParams = new URLSearchParams(`view=${view}`);
    expect(labels()).toEqual(['', 'Agents', expected]);
  });

  it('leaves the crumb at "Agents" on the default tab (no ?view=)', () => {
    expect(labels()).toEqual(['', 'Agents']);
  });

  it('leaves the crumb at "Agents" for an unknown ?view= instead of echoing it back', () => {
    mockSearchParams = new URLSearchParams('view=nope');
    expect(labels()).toEqual(['', 'Agents']);
  });

  // ?view= is whatever the address bar holds, and the crumb map is a plain object: a bare
  // lookup of 'toString' or '__proto__' walks the prototype chain and returns a truthy
  // non-string, which the crumb would then render as a React child. These are the keys that
  // exist on every object and on no tab.
  it.each(['toString', 'constructor', 'valueOf', 'hasOwnProperty', '__proto__'])(
    'does not turn the inherited %s key into a crumb',
    (view) => {
      mockSearchParams = new URLSearchParams(`view=${view}`);
      expect(labels()).toEqual(['', 'Agents']);
    },
  );

  // Leaving a tab is a change of ADDRESS on the page already on screen, so it goes through
  // the history API: a router push that only REMOVES ?view= is dropped when the page was
  // loaded at that address, and the crumb would then do nothing on a shared link or a reload.
  it('the "Agents" crumb exits a tab by clearing ?view= on the page already on screen', () => {
    mockSearchParams = new URLSearchParams('view=settings');
    const { result } = renderHook(() => useBreadcrumbs());
    const agentsCrumb = result.current.breadcrumbItems.find((i) => i.label === 'Agents');

    agentsCrumb?.onClick?.();
    expect(pushState).toHaveBeenCalledWith(null, '', '/en/app/agent');
    expect(navigate).not.toHaveBeenCalled();
  });
});
