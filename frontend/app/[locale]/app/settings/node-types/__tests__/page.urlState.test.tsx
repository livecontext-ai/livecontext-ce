// @vitest-environment jsdom
/**
 * Settings > Node Types keeps its tab, its search and its category in the address, and the
 * integrations list keeps its search and its page there too.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const t = vi.hoisted(() => (key: string) => key);
const apiGet = vi.hoisted(() => vi.fn());

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => r === 'ADMIN' }),
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: apiGet } }));
vi.mock('@/lib/api/orchestrator/node-type-settings.service', () => ({
  nodeTypeSettingsService: {
    getAll: vi.fn().mockResolvedValue([
      { type: 'core:decision', label: 'Decision', description: 'Branch', category: 'core', enabled: true },
      { type: 'agent:agent', label: 'Agent', description: 'Model', category: 'agent', enabled: true },
    ]),
  },
}));
vi.mock('@/lib/api/services/plan-features.service', () => ({
  planFeaturesService: {
    listForAdmin: vi.fn().mockResolvedValue({ requirements: [], selectablePlans: [] }),
  },
}));
vi.mock('@/components/settings', () => ({ PageHeader: () => null }));
const toast = vi.hoisted(() => ({ toasts: [], addToast: () => {}, removeToast: () => {} }));
vi.mock('@/components/Toast', () => ({ default: () => null, useToast: () => toast }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({ NodeIcon: () => null }));
vi.mock('../components/NodeTypeCard', () => ({
  NodeTypeCard: ({ nodeType }: { nodeType: { label: string } }) => <div data-testid="node-card">{nodeType.label}</div>,
}));
vi.mock('../components/NodeTypeCategorySelect', () => ({
  NodeTypeCategorySelect: ({ selectedCategory, onSelectCategory }: {
    selectedCategory: string | null; onSelectCategory: (c: string | null) => void;
  }) => (
    <div data-testid="category" data-value={selectedCategory ?? ''}>
      <button type="button" onClick={() => onSelectCategory('ai')}>pick-ai</button>
      <button type="button" onClick={() => onSelectCategory(null)}>pick-all</button>
    </div>
  ),
}));
vi.mock('../components/PlanRequirementSelect', () => ({ PlanRequirementSelect: () => null }));
vi.mock('../components/CapabilityPlanList', () => ({
  CapabilityPlanList: () => <div data-testid="capabilities" />,
}));

import NodeTypeSettingsPage from '../page';
import { IntegrationPlanList } from '../components/IntegrationPlanList';

const PAGE = '/en/app/settings/node-types';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const searchBox = () => screen.getByPlaceholderText('searchPlaceholder') as HTMLInputElement;

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue({
    content: [{ slug: 'slack', apiName: 'Slack', iconSlug: 'slack' }],
    last: false,
  });
});
afterEach(cleanup);

describe('NodeTypeSettingsPage - view kept in the address', () => {
  it('opens on the tab the address names', async () => {
    openAt('tab=capabilities');
    render(<NodeTypeSettingsPage />);

    expect(await screen.findByTestId('capabilities')).toBeInTheDocument();
  });

  it('opens the node list with the search and the category the address carries', async () => {
    openAt('q=agent&category=ai');
    render(<NodeTypeSettingsPage />);

    await waitFor(() => expect(screen.getAllByTestId('node-card')).toHaveLength(1));
    expect(screen.getByTestId('node-card')).toHaveTextContent('Agent');
    expect(searchBox().value).toBe('agent');
    expect(screen.getByTestId('category')).toHaveAttribute('data-value', 'ai');
  });

  it('ignores a tab or a category it does not have', async () => {
    openAt('tab=nope&category=nope');
    render(<NodeTypeSettingsPage />);

    await waitFor(() => expect(screen.getAllByTestId('node-card')).toHaveLength(2));
    expect(screen.getByTestId('category')).toHaveAttribute('data-value', '');
  });

  it('writes the search and the category, and a tab change drops them', async () => {
    openAt();
    render(<NodeTypeSettingsPage />);
    await waitFor(() => expect(screen.getAllByTestId('node-card')).toHaveLength(2));

    fireEvent.click(screen.getByText('pick-ai'));
    expect(fakeFolderRouter.search()).toBe('category=ai');

    fireEvent.change(searchBox(), { target: { value: 'ag' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('category=ai&q=ag'));

    fireEvent.click(screen.getByRole('button', { name: 'tabs.capabilities' }));
    expect(fakeFolderRouter.search()).toBe('tab=capabilities');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');
  });
});

describe('IntegrationPlanList - view kept in the address', () => {
  const renderList = () => render(
    <IntegrationPlanList requirements={{}} onChangePlan={() => {}} savingKeys={new Set()} planOptions={[]} />,
  );

  it('asks for the page and the search the address carries', async () => {
    openAt('tab=integrations&q=slack&page=3');
    renderList();

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/workflow-inspector/apis', {
      params: { page: 2, size: 30, name: 'slack' },
    }));
    expect(apiGet).toHaveBeenCalledTimes(1);
  });

  it('writes the page as the pager moves, and a new search goes back to the first page', async () => {
    openAt('tab=integrations');
    renderList();
    await screen.findByText('Slack');

    fireEvent.click(screen.getByRole('button', { name: 'integrations.next' }));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=integrations&page=2'));

    fireEvent.change(screen.getByPlaceholderText('integrations.searchPlaceholder'), { target: { value: 'gm' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=integrations&q=gm'));
    await waitFor(() => expect(apiGet).toHaveBeenLastCalledWith('/workflow-inspector/apis', {
      params: { page: 0, size: 30, name: 'gm' },
    }));
  });

  it('a page restored from the address that is past the end goes back to the first page', async () => {
    // The endpoint gives no total: the only sign that page 40 does not exist is that it is empty.
    apiGet.mockReset().mockImplementation(async (_path: string, opts: { params: { page: number } }) => (
      opts.params.page === 0
        ? { content: [{ slug: 'slack', apiName: 'Slack', iconSlug: 'slack' }], last: true }
        : { content: [], last: true }
    ));
    openAt('tab=integrations&page=40');
    renderList();

    // The user ends on the integrations, not on "no results" for a catalogue that has some.
    expect(await screen.findByText('Slack')).toBeInTheDocument();
    expect(screen.queryByText('noResults')).not.toBeInTheDocument();
    // The first page is the default, spelled by absence; the tab is left alone.
    expect(fakeFolderRouter.search()).toBe('tab=integrations');
    expect(apiGet.mock.calls.map(([, opts]) => opts.params.page)).toEqual([39, 0]);
  });

  it('an empty FIRST page is an empty catalogue, and is not asked for again', async () => {
    apiGet.mockReset().mockResolvedValue({ content: [], last: true });
    openAt('tab=integrations&q=zzz');
    renderList();

    expect(await screen.findByText('noResults')).toBeInTheDocument();
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(fakeFolderRouter.search()).toBe('tab=integrations&q=zzz');
  });
});
