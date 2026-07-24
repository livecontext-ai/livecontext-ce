// @vitest-environment jsdom
/**
 * The agent-marketplace-preview branch of {@link useBreadcrumbs}.
 *
 * Its second crumb is the only "back" affordance on /app/marketplace/agents/{id}
 * (the detail page renders no header of its own). It used to navigate to the
 * bare /app/marketplace, which remounts the page on its default Explore /
 * Applications grid - so going back from an agent left the user staring at
 * applications, with the agent they had just opened nowhere on screen.
 *
 * The marketplace page reads `?type=` (see the marketplace page tests), so the
 * crumb has to carry it for the round trip to close.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, cleanup } from '@testing-library/react';

let mockPathname = '/en/app/marketplace/agents/pub-agent-1';

vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/hooks/useCurrentView', () => ({
  useCurrentView: () => ({
    view: 'marketplace', workflowId: null, dataSourceId: null, interfaceId: null, publicationId: null,
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

import { useBreadcrumbs } from '../useBreadcrumbs';

beforeEach(() => {
  mockPathname = '/en/app/marketplace/agents/pub-agent-1';
  navigate.mockClear();
});
afterEach(() => cleanup());

describe('useBreadcrumbs - agent marketplace preview', () => {
  it('Regression - the Agents crumb returns to the AGENTS grid, not the marketplace root', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    const items = result.current.breadcrumbItems;
    const agentsCrumb = items[items.length - 1];
    expect(agentsCrumb.label).toBe('Agents');

    agentsCrumb.onClick?.();
    expect(navigate).toHaveBeenCalledWith('/en/app/marketplace?type=agents');
  });

  it('the Marketplace root crumb still goes to the root', () => {
    const { result } = renderHook(() => useBreadcrumbs());

    const marketplaceCrumb = result.current.breadcrumbItems.find((i) => i.label === 'Marketplace');
    expect(marketplaceCrumb).toBeDefined();

    marketplaceCrumb!.onClick?.();
    expect(navigate).toHaveBeenCalledWith('/en/app/marketplace');
  });
});
