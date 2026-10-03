// @vitest-environment jsdom
/**
 * The self-hosted (CE) usage history drops an answer that arrives after a newer request.
 *
 * <p>Its pager does not wait for the page in flight, and the full-screen table now requests a new
 * page size on every resize, so requests overlap. Without a guard, a slow answer to an OLDER
 * request landed last and replaced the newer page on screen.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';

const quotaApi = vi.hoisted(() => ({ getSummary: vi.fn(), getHistory: vi.fn() }));
// A STABLE translator, like next-intl's: the page keys its fetch on `t`, so a fresh function per
// render would refetch in a loop.
const t = vi.hoisted(() => (key: string, values?: Record<string, unknown>) =>
  values ? `${key}:${JSON.stringify(values)}` : key);

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/lib/api', () => ({ quotaApi }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: true, creditsToUsd: (c: number) => c / 1000 }));
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
// Not cloud-linked: the page reads its own ledger.
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getStatus: () => Promise.resolve({ registered: false }) },
}));
// The page reads the membership list (shared cache key with the workspace selector) to decide
// whether the analytics may say how long the balance lasts.
const memberships = vi.hoisted(() => ({ list: [] as unknown[] | undefined }));
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: memberships.list }) }));
vi.mock('@/lib/api/organization-api', () => ({ organizationApi: {} }));
vi.mock('@/components/billing', () => ({
  BalanceBreakdownCard: () => <div data-testid="balance-card" />,
  TopUpModal: () => null,
}));
// The real selects are Radix portals; these stand-ins expose the same onChange contracts.
vi.mock('@/components/settings/WorkspaceScopeSelect', () => ({
  WorkspaceScopeSelect: ({ onChange }: { onChange: (id: string) => void }) => (
    <>
      <button type="button" onClick={() => onChange('org-2')}>scope-org-2</button>
      <button type="button" onClick={() => onChange('__all__')}>scope-all</button>
    </>
  ),
  ALL_WORKSPACES_SCOPE: '__all__',
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ onValueChange, disabled, children }: { onValueChange: (v: string) => void; disabled?: boolean; children: React.ReactNode }) => (
    <div>
      <button type="button" disabled={disabled} onClick={() => onValueChange('AGENT_EXECUTION')}>filter-agent</button>
      {children}
    </div>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: () => null,
  SelectItem: () => null,
}));
const analyticsProps = vi.hoisted(() => ({ last: null as null | Record<string, unknown> }));
vi.mock('../components/UsageAnalyticsPanel', () => ({
  default: (props: Record<string, unknown>) => {
    analyticsProps.last = props;
    return <div data-testid="analytics" />;
  },
}));
vi.mock('../components/modelLabels', () => ({
  useModelNameIndex: () => ({}),
  ProviderModelCell: ({ model }: { model: string }) => <span>{model}</span>,
}));
vi.mock('../OwnKeyRowNote', () => ({ OwnKeyRowNote: () => null }));

import QuotaPage from '../page';

const summary = { balance: 100, totalConsumedLast30Days: 5, breakdownByType: {} };
const historyPage = (number: number) => ({
  content: [{
    id: number + 1, createdAt: '2026-09-01T00:00:00Z', sourceType: 'WORKFLOW_NODE', provider: null, model: null,
    promptTokens: null, completionTokens: null, cachedTokens: null, amount: -1, description: `row-page-${number}`,
  }],
  number,
  size: 15,
  totalPages: 5,
});

describe('CE quota page - out-of-order history answers', () => {
  beforeEach(() => {
    quotaApi.getSummary.mockReset().mockResolvedValue(summary);
    quotaApi.getHistory.mockReset().mockImplementation((page: number) => Promise.resolve(historyPage(page)));
  });

  it('regression - an older answer that lands last does not replace the newer page', async () => {
    render(<QuotaPage />);
    expect(await screen.findByText('row-page-0')).toBeInTheDocument();

    const held: Record<number, () => void> = {};
    quotaApi.getHistory.mockImplementation((page: number) =>
      new Promise((resolve) => { held[page] = () => resolve(historyPage(page)); }));

    const next = () => screen.getByRole('button', { name: 'history.nextPage' });
    fireEvent.click(next());
    await act(async () => {});
    fireEvent.click(next());
    await act(async () => {});
    expect(Object.keys(held)).toEqual(['1', '2']);

    await act(async () => held[2]());
    expect(await screen.findByText('row-page-2')).toBeInTheDocument();
    await act(async () => held[1]());

    expect(screen.getByText('row-page-2')).toBeInTheDocument();
    expect(screen.queryByText('row-page-1')).toBeNull();
  });
});

describe('CE quota page - full screen', () => {
  beforeEach(() => {
    quotaApi.getSummary.mockReset().mockResolvedValue(summary);
    quotaApi.getHistory.mockReset().mockImplementation((page: number) => Promise.resolve(historyPage(page)));
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.dataset.testid === 'usage-history-table' ? 1229 : 0; // 30 rows
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('requests the pages at the size that fills the table, and back to 15 on close', async () => {
    render(<QuotaPage />);
    expect(await screen.findByText('row-page-0')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('usage-history-fullscreen-toggle'));
    await waitFor(() => expect(quotaApi.getHistory).toHaveBeenLastCalledWith(0, 30, undefined, 'org-1', false));

    fireEvent.click(screen.getByRole('button', { name: 'history.exitFullscreen' }));
    await waitFor(() => expect(quotaApi.getHistory).toHaveBeenLastCalledWith(0, 15, undefined, 'org-1', false));
  });
});
