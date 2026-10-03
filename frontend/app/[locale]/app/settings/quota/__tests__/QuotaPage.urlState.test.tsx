// @vitest-environment jsdom
/**
 * The usage history keeps its page and its source filter in the address, so a reload reopens
 * the same page of rows instead of the first one.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const quotaApi = vi.hoisted(() => ({ getSummary: vi.fn(), getHistory: vi.fn() }));
// A STABLE translator, like next-intl's: the page keys its fetch on `t`.
const t = vi.hoisted(() => (key: string, values?: Record<string, unknown>) =>
  values ? `${key}:${JSON.stringify(values)}` : key);

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/lib/api', () => ({ quotaApi }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false, creditsToUsd: (c: number) => c / 1000 }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string }) => unknown) => sel({ currentOrgId: 'org-1' }),
}));
vi.mock('@/lib/hooks/useCreditWallet', () => ({
  useCreditWallet: () => ({ balance: 100, subBalance: 0, paygBalance: 0, allowance: null, renewsAt: null, periodEndsAt: null }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({ usePaygTiers: () => ({ configured: false }) }));
vi.mock('@/lib/hooks/useScheduledPlanChange', () => ({ useScheduledPlanChange: () => ({ hasScheduledChange: false }) }));
vi.mock('@/lib/api/cloud-link.service', () => ({ cloudLinkService: {} }));
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: [] }) }));
vi.mock('@/lib/api/organization-api', () => ({ organizationApi: {} }));
vi.mock('@/components/billing', () => ({ BalanceBreakdownCard: () => null, TopUpModal: () => null }));
vi.mock('@/components/settings/WorkspaceScopeSelect', () => ({
  WorkspaceScopeSelect: ({ onChange }: { onChange: (id: string) => void }) => (
    <button type="button" onClick={() => onChange('org-2')}>scope-org-2</button>
  ),
  ALL_WORKSPACES_SCOPE: '__all__',
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) => (
    <div data-testid="history-filter" data-value={value}>
      <button type="button" onClick={() => onValueChange('AGENT_EXECUTION')}>filter-agent</button>
      <button type="button" onClick={() => onValueChange('ALL')}>filter-all</button>
      {children}
    </div>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: () => null,
  SelectItem: () => null,
}));
vi.mock('../components/UsageAnalyticsPanel', () => ({ default: () => null }));
vi.mock('../components/modelLabels', () => ({
  useModelNameIndex: () => ({}),
  ProviderModelCell: ({ model }: { model: string }) => <span>{model}</span>,
}));
vi.mock('../OwnKeyRowNote', () => ({ OwnKeyRowNote: () => null }));

import QuotaPage from '../page';

const PAGE = '/en/app/settings/quota';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const historyPage = (number: number, tag = 'page') => ({
  content: [{
    id: number + 1, createdAt: '2026-09-01T00:00:00Z', sourceType: 'WORKFLOW_NODE', provider: null, model: null,
    promptTokens: null, completionTokens: null, cachedTokens: null, amount: -1, description: `row-${tag}-${number}`,
  }],
  number,
  totalPages: 5,
});

beforeEach(() => {
  quotaApi.getSummary.mockReset().mockResolvedValue({ balance: 100, totalConsumedLast30Days: 5, breakdownByType: {} });
  quotaApi.getHistory.mockReset().mockImplementation((page: number, _size: number, type?: string) =>
    Promise.resolve(historyPage(page, type ?? 'page')));
});
afterEach(cleanup);

describe('Quota page - usage history view kept in the address', () => {
  it('regression - opens on the page and the filter the address carries, never on page 1 first', async () => {
    openAt('page=3&type=AGENT_EXECUTION');
    render(<QuotaPage />);

    expect(await screen.findByText('row-AGENT_EXECUTION-2')).toBeInTheDocument();
    expect(quotaApi.getHistory).toHaveBeenCalledWith(2, 15, 'AGENT_EXECUTION', 'org-1', false);
    // The mount-time "workspace changed, back to page 1" reset must not have run.
    expect(quotaApi.getHistory).not.toHaveBeenCalledWith(0, 15, 'AGENT_EXECUTION', 'org-1', false);
    expect(screen.getByTestId('history-filter')).toHaveAttribute('data-value', 'AGENT_EXECUTION');
    expect(fakeFolderRouter.search()).toBe('page=3&type=AGENT_EXECUTION');
  });

  it('writes the page as the pager moves, and a filter change goes back to the first page', async () => {
    openAt();
    render(<QuotaPage />);
    await screen.findByText('row-page-0');

    fireEvent.click(screen.getByRole('button', { name: 'history.nextPage' }));
    expect(await screen.findByText('row-page-1')).toBeInTheDocument();
    expect(fakeFolderRouter.search()).toBe('page=2');

    fireEvent.click(screen.getByText('filter-agent'));
    expect(await screen.findByText('row-AGENT_EXECUTION-0')).toBeInTheDocument();
    expect(fakeFolderRouter.search()).toBe('type=AGENT_EXECUTION');

    fireEvent.click(screen.getByText('filter-all'));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('a workspace re-scope still goes back to the first page', async () => {
    openAt('page=3');
    render(<QuotaPage />);
    await screen.findByText('row-page-2');

    fireEvent.click(screen.getByText('scope-org-2'));

    await waitFor(() => expect(quotaApi.getHistory).toHaveBeenLastCalledWith(0, 15, undefined, 'org-2', false));
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('ignores a page or a source type that makes no sense', async () => {
    openAt('page=abc&type=NOT_A_TYPE');
    render(<QuotaPage />);

    expect(await screen.findByText('row-page-0')).toBeInTheDocument();
    expect(quotaApi.getHistory).toHaveBeenCalledWith(0, 15, undefined, 'org-1', false);
  });
});
