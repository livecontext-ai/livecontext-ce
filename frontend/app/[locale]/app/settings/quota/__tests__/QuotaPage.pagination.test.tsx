// @vitest-environment jsdom
/**
 * The usage history is paged and filtered IN PLACE.
 *
 * <p>Every page or filter change used to put the whole Usage page back into its loading
 * skeleton: the page was unmounted, the scroll jumped to the top and the analytics panel
 * reloaded, so a reader paging the history had to scroll back down after every click. Only the
 * first load and a workspace re-scope (a different dataset altogether) may show the skeleton.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

const quotaApi = vi.hoisted(() => ({ getSummary: vi.fn(), getHistory: vi.fn() }));
// A STABLE translator, like next-intl's: the page keys its fetch on `t`, so a fresh function per
// render would refetch in a loop.
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
const historyPage = (number: number, tag = 'page') => ({
  content: [{
    id: number + 1, createdAt: '2026-09-01T00:00:00Z', sourceType: 'WORKFLOW_NODE', provider: null, model: null,
    promptTokens: null, completionTokens: null, cachedTokens: null, amount: -1, description: `row-${tag}-${number}`,
  }],
  number,
  totalPages: 3,
});

/** Holds the next history request open until `release` or `fail` is called. */
function holdNextHistory() {
  const handle = { release: () => {}, fail: () => {} };
  quotaApi.getHistory.mockImplementationOnce((page: number, _size: number, type?: string) =>
    new Promise((resolve, reject) => {
      handle.release = () => resolve(historyPage(page, type ?? 'page'));
      handle.fail = () => reject(new Error('HTTP 500'));
    }),
  );
  return handle;
}

const nextButton = () => screen.getByRole('button', { name: 'history.nextPage' });
const table = () => screen.getByTestId('usage-history-table');

async function renderLoaded() {
  render(<QuotaPage />);
  expect(await screen.findByText('row-page-0')).toBeInTheDocument();
  return screen.getByTestId('analytics');
}

describe('Quota page - usage history paging', () => {
  beforeEach(() => {
    quotaApi.getSummary.mockReset().mockResolvedValue(summary);
    quotaApi.getHistory.mockReset().mockImplementation((page: number, _size: number, type?: string) =>
      Promise.resolve(historyPage(page, type ?? 'page')));
  });

  it('regression - changing page keeps the page mounted instead of swapping it for the loading skeleton', async () => {
    const analytics = await renderLoaded();
    const held = holdNextHistory();

    fireEvent.click(nextButton());

    // In flight: the previous rows and the very same analytics element stay; the table only
    // says it is busy.
    await waitFor(() => expect(table()).toHaveAttribute('aria-busy', 'true'));
    expect(screen.getByText('row-page-0')).toBeInTheDocument();
    expect(screen.getByTestId('analytics')).toBe(analytics);

    await act(async () => held.release());
    expect(await screen.findByText('row-page-1')).toBeInTheDocument();
    expect(table()).toHaveAttribute('aria-busy', 'false');
    expect(screen.getByTestId('analytics')).toBe(analytics);
    expect(quotaApi.getHistory).toHaveBeenLastCalledWith(1, 15, undefined, 'org-1', false);
  });

  it('changing the filter also refetches in place, from page 0 of that filter', async () => {
    const analytics = await renderLoaded();
    const held = holdNextHistory();

    fireEvent.click(screen.getByText('filter-agent'));

    await waitFor(() => expect(table()).toHaveAttribute('aria-busy', 'true'));
    expect(screen.getByText('row-page-0')).toBeInTheDocument();
    expect(screen.getByTestId('analytics')).toBe(analytics);

    await act(async () => held.release());
    expect(await screen.findByText('row-AGENT_EXECUTION-0')).toBeInTheDocument();
    expect(quotaApi.getHistory).toHaveBeenLastCalledWith(0, 15, 'AGENT_EXECUTION', 'org-1', false);
  });

  it('a workspace re-scope is a different dataset, so it still shows the skeleton', async () => {
    const analytics = await renderLoaded();
    const held = holdNextHistory();

    fireEvent.click(screen.getByText('scope-org-2'));

    await waitFor(() => expect(screen.queryByTestId('usage-history-table')).toBeNull());
    expect(screen.queryByText('row-page-0')).toBeNull();

    await act(async () => held.release());
    expect(await screen.findByText('row-page-0')).toBeInTheDocument();
    expect(quotaApi.getHistory).toHaveBeenLastCalledWith(0, 15, undefined, 'org-2', false);
    // Remounted with the page, which is what the skeleton means here.
    expect(screen.getByTestId('analytics')).not.toBe(analytics);
  });

  it('the pager is disabled while a page is in flight, so a double click cannot skip a page or run past the end', async () => {
    await renderLoaded();
    const held = holdNextHistory();

    fireEvent.click(nextButton());
    await waitFor(() => expect(nextButton()).toBeDisabled());
    expect(screen.getByRole('button', { name: 'history.previousPage' })).toBeDisabled();
    expect(screen.getByText('filter-agent')).toBeDisabled();
    fireEvent.click(nextButton());

    await act(async () => held.release());
    expect(await screen.findByText('row-page-1')).toBeInTheDocument();
    expect(quotaApi.getHistory.mock.calls.map((c) => c[0])).toEqual([0, 1]);
    expect(nextButton()).toBeEnabled();
  });

  it('a failed page change keeps the rows, says so beside the table, and the next click retries the same page', async () => {
    await renderLoaded();
    const held = holdNextHistory();

    fireEvent.click(nextButton());
    await waitFor(() => expect(table()).toHaveAttribute('aria-busy', 'true'));
    await act(async () => held.fail());

    expect(await screen.findByRole('alert')).toHaveTextContent('error.loadFailed');
    // Beside the table only: the top-of-page banner is for a page with no table to page.
    expect(screen.getAllByText('error.loadFailed')).toHaveLength(1);
    expect(screen.getByText('row-page-0')).toBeInTheDocument();
    expect(table()).toHaveAttribute('aria-busy', 'false');
    expect(screen.getByText('history.page:{"current":1,"total":3}')).toBeInTheDocument();

    // The next click asks for page 1 again, not page 2.
    await waitFor(() => expect(nextButton()).toBeEnabled());
    fireEvent.click(nextButton());
    expect(await screen.findByText('row-page-1')).toBeInTheDocument();
    expect(quotaApi.getHistory.mock.calls.at(-1)?.[0]).toBe(1);
    expect(quotaApi.getHistory.mock.calls.some((c) => c[0] === 2)).toBe(false);
  });

  it('the four spend cards share one grid that holds four columns', async () => {
    await renderLoaded();
    const grid = screen.getByTestId('usage-breakdown-grid');
    expect(grid.children).toHaveLength(4);
    expect(grid.className).toContain('lg:grid-cols-4');
  });
});

/**
 * "How long the balance lasts" divides the ACCOUNT wallet by the spend the panel shows. The page
 * hands the panel a balance only when that spend is the whole account's.
 */
describe('Quota page - the balance the analytics may divide', () => {
  const ws = (id: string, role: string) => ({ id, currentUserRole: role, paused: false, pendingDeletion: false });

  beforeEach(() => {
    analyticsProps.last = null;
    quotaApi.getSummary.mockReset().mockResolvedValue(summary);
    quotaApi.getHistory.mockReset().mockImplementation((page: number) => Promise.resolve(historyPage(page)));
  });

  it('an owner whose only workspace this is: the view is the account, the balance is passed', async () => {
    memberships.list = [ws('org-1', 'OWNER')];
    await renderLoaded();
    expect(analyticsProps.last?.balance).toBe(100);
  });

  it('regression - one workspace of several: its pace is not what drains the wallet, no balance', async () => {
    memberships.list = [ws('org-1', 'OWNER'), ws('org-2', 'OWNER')];
    await renderLoaded();
    expect(analyticsProps.last?.balance).toBeNull();
  });

  it('...but the "All workspaces" view of an owner covers the account again', async () => {
    memberships.list = [ws('org-1', 'OWNER'), ws('org-2', 'OWNER')];
    await renderLoaded();
    fireEvent.click(screen.getByText('scope-all'));
    await screen.findByText('row-page-0');
    await waitFor(() => expect(analyticsProps.last?.allWorkspaces).toBe(true));
    expect(analyticsProps.last?.balance).toBe(100);
  });

  it('a guest in someone else\'s workspace never gets the balance', async () => {
    memberships.list = [ws('org-1', 'MEMBER')];
    await renderLoaded();
    expect(analyticsProps.last?.balance).toBeNull();
  });

  it('while the memberships are loading, nothing is claimed', async () => {
    memberships.list = undefined;
    await renderLoaded();
    expect(analyticsProps.last?.balance).toBeNull();
  });
});
