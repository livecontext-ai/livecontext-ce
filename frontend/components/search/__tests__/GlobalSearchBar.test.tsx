/**
 * @vitest-environment jsdom
 *
 * GlobalSearchBar: header search rendered on every page. The `inline` variant
 * is the centered pill with an anchored dropdown; the `compact` variant is the
 * mobile icon trigger opening a full-width bar. It fans a debounced query out
 * to conversations, workflows and agents server-side search, matches the
 * settings sections client-side, and navigates to the picked result.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const mockPathname = vi.fn(() => '/en/app');
vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname(),
}));

// Stable translator identity by default: the component's search effect depends
// on `t`, so a per-render closure here would defeat React's dependency
// memoization. The render-loop regression test swaps in an unstable factory.
const stableT = (key: string, params?: Record<string, string>) =>
  params?.query ? `${key}:${params.query}` : key;
let translatorFactory: () => typeof stableT = () => stableT;
vi.mock('next-intl', () => ({
  useTranslations: () => translatorFactory(),
}));

const safeNavigate = vi.fn();
vi.mock('@/contexts/NavigationGuardContext', () => ({
  useSafeNavigate: () => safeNavigate,
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  stripLocale: (pathname: string | null) =>
    (pathname ?? '').replace(/^\/(en|fr|de|es|pt|zh)(?=\/|$)/, '') || '/',
}));

const hasRole = vi.fn(() => false);
const authState = { isAuthenticated: true };
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isAuthenticated: authState.isAuthenticated, hasRole }),
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

const searchConversations = vi.fn();
vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: { searchConversations: (...args: unknown[]) => searchConversations(...args) },
}));

const getWorkflowsPage = vi.fn();
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflowsPage: (...args: unknown[]) => getWorkflowsPage(...args) },
}));

const getAgentsPage = vi.fn();
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgentsPage: (...args: unknown[]) => getAgentsPage(...args) },
}));

import { GlobalSearchBar } from '../GlobalSearchBar';

function primeEmptyRemotes() {
  searchConversations.mockResolvedValue({ content: [] });
  getWorkflowsPage.mockResolvedValue({ workflows: [] });
  getAgentsPage.mockResolvedValue({ items: [] });
}

async function settle() {
  // Debounce (250ms) then let the promises resolve.
  await act(async () => {
    vi.advanceTimersByTime(300);
  });
  await act(async () => {
    await Promise.resolve();
  });
}

async function typeAndSettle(value: string, testId = 'global-search-input') {
  const input = screen.getByTestId(testId);
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value } });
  await settle();
}

beforeEach(() => {
  vi.useFakeTimers();
  mockPathname.mockReturnValue('/en/app');
  hasRole.mockReturnValue(false);
  authState.isAuthenticated = true;
  translatorFactory = () => stableT;
  primeEmptyRemotes();
});

afterEach(() => {
  vi.runOnlyPendingTimers();
  vi.useRealTimers();
  vi.clearAllMocks();
  cleanup();
});

describe('GlobalSearchBar variants', () => {
  it('inline variant renders the pill on any route', () => {
    mockPathname.mockReturnValue('/en/app/workflow/abc');
    render(<GlobalSearchBar />);
    expect(screen.getByTestId('global-search-input')).toBeTruthy();
  });

  it('compact variant opens the mobile bar from the trigger', () => {
    render(<GlobalSearchBar variant="compact" />);
    expect(screen.queryByTestId('global-search-input-mobile')).toBeNull();
    const trigger = screen.getByTestId('global-search-trigger');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(trigger);
    expect(screen.getByTestId('global-search-input-mobile')).toBeTruthy();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
  });
});

describe('GlobalSearchBar render stability', () => {
  // Regression test for the render loop originally shipped in useGlobalSearch:
  // the empty-query branch called setRemoteResults([]) with a NEW array on every
  // effect run, so any unstable effect dependency (here: the next-intl `t`
  // identity) re-rendered forever until the process ran out of memory. The fix
  // is the functional bail-out (prev.length ? [] : prev). With the bail-out
  // removed, this render never stabilizes and the test times out.
  it('does not re-render forever when the translator identity is unstable', { timeout: 4000 }, () => {
    translatorFactory = () => (key: string, params?: Record<string, string>) =>
      params?.query ? `${key}:${params.query}` : key; // new closure per render
    render(<GlobalSearchBar />);
    // hasRole is called once per render - a bounded count proves stability.
    expect(hasRole.mock.calls.length).toBeLessThan(30);
  });
});

describe('GlobalSearchBar searching', () => {
  it('debounces then queries conversations, workflows and agents with the term', async () => {
    render(<GlobalSearchBar />);
    await typeAndSettle('invoices');
    expect(searchConversations).toHaveBeenCalledWith('invoices', 'title');
    expect(getWorkflowsPage).toHaveBeenCalledWith({ q: 'invoices', size: 5 });
    expect(getAgentsPage).toHaveBeenCalledWith({ q: 'invoices', size: 5 });
  });

  it('coalesces rapid keystrokes into a single request with the final term', async () => {
    render(<GlobalSearchBar />);
    const input = screen.getByTestId('global-search-input');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'in' } });
    fireEvent.change(input, { target: { value: 'inv' } });
    fireEvent.change(input, { target: { value: 'invo' } });
    expect(searchConversations).not.toHaveBeenCalled(); // nothing before the debounce
    await settle();
    expect(searchConversations).toHaveBeenCalledTimes(1);
    expect(searchConversations).toHaveBeenCalledWith('invo', 'title');
  });

  it('renders grouped results and navigates on click', async () => {
    searchConversations.mockResolvedValue({ content: [{ id: 'c1', title: 'Invoice chat' }] });
    getWorkflowsPage.mockResolvedValue({ workflows: [{ id: 'w1', name: 'Invoice workflow' }] });
    getAgentsPage.mockResolvedValue({ items: [{ id: 'a1', name: 'Invoice agent' }] });

    render(<GlobalSearchBar />);
    await typeAndSettle('invoice');

    expect(screen.getByText('Invoice chat')).toBeTruthy();
    expect(screen.getByText('Invoice workflow')).toBeTruthy();
    expect(screen.getByText('Invoice agent')).toBeTruthy();

    fireEvent.click(screen.getByText('Invoice workflow'));
    expect(safeNavigate).toHaveBeenCalledWith('/app/workflow/w1');
  });

  it('Enter picks the active (first) result', async () => {
    searchConversations.mockResolvedValue({ content: [{ id: 'c1', title: 'Invoice chat' }] });

    render(<GlobalSearchBar />);
    await typeAndSettle('invoice');
    fireEvent.keyDown(screen.getByTestId('global-search-input'), { key: 'Enter' });
    expect(safeNavigate).toHaveBeenCalledWith('/app/c/c1');
  });

  it('ArrowDown/ArrowUp move and wrap the active option, Enter picks it', async () => {
    searchConversations.mockResolvedValue({
      content: [
        { id: 'c1', title: 'First chat' },
        { id: 'c2', title: 'Second chat' },
      ],
    });

    render(<GlobalSearchBar />);
    await typeAndSettle('chat');
    const input = screen.getByTestId('global-search-input');

    const selectedLabels = () =>
      screen.getAllByRole('option')
        .filter(o => o.getAttribute('aria-selected') === 'true')
        .map(o => o.textContent);

    expect(selectedLabels()).toEqual(['First chat']);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(selectedLabels()).toEqual(['Second chat']);
    fireEvent.keyDown(input, { key: 'ArrowDown' }); // wraps to the first
    expect(selectedLabels()).toEqual(['First chat']);
    fireEvent.keyDown(input, { key: 'ArrowUp' }); // wraps back to the last
    expect(selectedLabels()).toEqual(['Second chat']);

    fireEvent.keyDown(input, { key: 'Enter' });
    expect(safeNavigate).toHaveBeenCalledWith('/app/c/c2');
  });

  it('ignores a stale response that resolves after a newer query', async () => {
    let resolveStale: (value: unknown) => void = () => {};
    searchConversations.mockImplementationOnce(
      () => new Promise(resolve => { resolveStale = resolve; })
    );

    render(<GlobalSearchBar />);
    const input = screen.getByTestId('global-search-input');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: 'aa' } });
    await settle(); // request 1 in flight (conversations pending)

    searchConversations.mockResolvedValue({ content: [{ id: 'c-new', title: 'Fresh result' }] });
    fireEvent.change(input, { target: { value: 'aab' } });
    await settle(); // request 2 resolves
    expect(screen.getByText('Fresh result')).toBeTruthy();

    await act(async () => {
      resolveStale({ content: [{ id: 'c-old', title: 'Stale result' }] });
      await Promise.resolve();
    });
    expect(screen.queryByText('Stale result')).toBeNull();
    expect(screen.getByText('Fresh result')).toBeTruthy();
  });

  it('skips all remote searches when unauthenticated but still matches settings', async () => {
    authState.isAuthenticated = false;
    render(<GlobalSearchBar />);
    await typeAndSettle('pricing');
    expect(searchConversations).not.toHaveBeenCalled();
    expect(getWorkflowsPage).not.toHaveBeenCalled();
    expect(getAgentsPage).not.toHaveBeenCalled();
    expect(screen.getByText('Pricing')).toBeTruthy();
  });

  it('shows the empty state when nothing matches', async () => {
    render(<GlobalSearchBar />);
    await typeAndSettle('zzz-nothing');
    expect(screen.getByText('noResults:zzz-nothing')).toBeTruthy();
  });

  it('Escape closes the dropdown', async () => {
    searchConversations.mockResolvedValue({ content: [{ id: 'c1', title: 'Invoice chat' }] });
    render(<GlobalSearchBar />);
    await typeAndSettle('invoice');
    expect(screen.getByTestId('global-search-results')).toBeTruthy();
    fireEvent.keyDown(screen.getByTestId('global-search-input'), { key: 'Escape' });
    expect(screen.queryByTestId('global-search-results')).toBeNull();
  });

  it('searches from the compact variant too', async () => {
    searchConversations.mockResolvedValue({ content: [{ id: 'c9', title: 'Mobile chat' }] });
    render(<GlobalSearchBar variant="compact" />);
    fireEvent.click(screen.getByTestId('global-search-trigger'));
    await typeAndSettle('mobile', 'global-search-input-mobile');
    fireEvent.click(screen.getByText('Mobile chat'));
    expect(safeNavigate).toHaveBeenCalledWith('/app/c/c9');
  });
});

describe('GlobalSearchBar settings sections', () => {
  it('matches settings sections client-side and navigates to them', async () => {
    mockPathname.mockReturnValue('/en/app/settings/overview');
    render(<GlobalSearchBar />);
    await typeAndSettle('pricing');
    fireEvent.click(screen.getByText('Pricing'));
    expect(safeNavigate).toHaveBeenCalledWith('/app/settings/pricing');
  });

  it('ranks settings sections before remote results on settings routes', async () => {
    mockPathname.mockReturnValue('/en/app/settings/overview');
    searchConversations.mockResolvedValue({ content: [{ id: 'c1', title: 'pricing chat' }] });
    render(<GlobalSearchBar />);
    await typeAndSettle('pricing');
    const options = screen.getAllByRole('option').map(o => o.textContent);
    expect(options.indexOf('Pricing')).toBeLessThan(options.indexOf('pricing chat'));
  });

  it('ranks remote results before settings sections outside settings', async () => {
    mockPathname.mockReturnValue('/en/app');
    searchConversations.mockResolvedValue({ content: [{ id: 'c1', title: 'pricing chat' }] });
    render(<GlobalSearchBar />);
    await typeAndSettle('pricing');
    const options = screen.getAllByRole('option').map(o => o.textContent);
    expect(options.indexOf('pricing chat')).toBeLessThan(options.indexOf('Pricing'));
  });

  it('hides admin-only sections from non-admins', async () => {
    mockPathname.mockReturnValue('/en/app/settings/overview');
    render(<GlobalSearchBar />);
    await typeAndSettle('node types');
    expect(screen.queryByText('Node Types')).toBeNull();
  });

  it('shows admin-only sections to platform admins', async () => {
    hasRole.mockReturnValue(true);
    mockPathname.mockReturnValue('/en/app/settings/overview');
    render(<GlobalSearchBar />);
    await typeAndSettle('node types');
    expect(screen.getByText('Node Types')).toBeTruthy();
  });
});
