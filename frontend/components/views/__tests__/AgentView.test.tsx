// @vitest-environment jsdom
/**
 * AgentView is the /app/agent shell with its tabs (Agents / Skills / Memory / Fleet / Metrics /
 * Settings).
 * These pin the contract that the URL (?view=) is the SINGLE source of truth for the
 * active tab, derived at render time:
 *   - no ?view=        → Agents list
 *   - ?view=skills     → Skills tab
 *   - ?view=fleet      → full-screen Fleet canvas (the tab bar is hidden here, so the
 *                        breadcrumb is the only way out)
 *   - ?view=metrics    → Metrics dashboard
 *   - ?view=settings   → Agent & chat defaults panel
 * and that clicking a tab encodes the choice in the URL (default 'agents' = clean URL).
 *
 * Regression: the Fleet canvas used to get stuck when leaving via the breadcrumb (it
 * cleared ?view= but a useEffect-mirrored local state failed to follow). Deriving the
 * tab from the URL makes the view follow the URL - clearing ?view= shows the Agents list.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

vi.mock('@/components/AgentTable', () => ({ AgentTable: () => <div data-testid="agent-table" /> }));
vi.mock('@/components/SkillTab', () => ({ SkillTab: () => <div data-testid="skill-tab" /> }));
vi.mock('@/components/agent-fleet/AgentFleetCanvas', () => ({ AgentFleetCanvas: () => <div data-testid="fleet-canvas" /> }));
vi.mock('@/components/agent-fleet/AgentMetricsDashboard', () => ({ AgentMetricsDashboard: () => <div data-testid="metrics-dashboard" /> }));
// The h2 heading is AgentChatDefaults' own concern now (pinned by its test): the Agents
// tab is its only host, so there is no call-site prop left to check here.
vi.mock('@/components/settings/AgentChatDefaults', () => ({
  AgentChatDefaults: () => <div data-testid="agent-chat-defaults" />,
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

let searchParams = new URLSearchParams();
const routerReplace = vi.fn();
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParams,
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
  usePathname: () => '/app/agent',
}));

/**
 * A tab is a change of ADDRESS on the page already on screen, so it goes through the history
 * API. A router replace of the bare pathname - which is what returning to the default tab
 * asks for - is dropped when the page was loaded at it, which is the "nothing happens" this
 * view's own comment describes.
 */
const replace = vi.fn();
const realReplaceState = window.history.replaceState;

import { AgentView } from '../AgentView';

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
  routerReplace.mockClear();
  window.history.replaceState = ((_data: unknown, _unused: string, url?: string) =>
    replace(url)) as unknown as typeof window.history.replaceState;
});
afterEach(() => {
  window.history.replaceState = realReplaceState;
  cleanup();
});

describe('AgentView - URL is the single source of truth', () => {
  it('renders the Agents list when no ?view= is present', () => {
    render(<AgentView />);
    expect(screen.getByTestId('agent-table')).toBeTruthy();
    expect(screen.queryByTestId('fleet-canvas')).toBeNull();
    expect(screen.queryByTestId('skill-tab')).toBeNull();
  });

  it('renders the full-screen Fleet canvas (and hides the tab bar) on ?view=fleet', () => {
    searchParams = new URLSearchParams('view=fleet');
    render(<AgentView />);
    expect(screen.getByTestId('fleet-canvas')).toBeTruthy();
    // In Fleet the tab bar is not rendered - the breadcrumb is the only exit.
    expect(screen.queryByText('tabFleet')).toBeNull();
    expect(screen.queryByTestId('agent-table')).toBeNull();
  });

  it('renders the Metrics dashboard on ?view=metrics', () => {
    searchParams = new URLSearchParams('view=metrics');
    render(<AgentView />);
    expect(screen.getByTestId('metrics-dashboard')).toBeTruthy();
  });

  // The Settings tab hosts the agent & general-chat defaults - the same editor as the
  // Settings > Agents page. Only a KNOWN view renders its own tab, so this deep-link is
  // what guards that ?view=settings is wired all the way through tabFromView.
  it('renders the agent & chat defaults on ?view=settings (deep-link)', () => {
    searchParams = new URLSearchParams('view=settings');
    render(<AgentView />);
    expect(screen.getByTestId('agent-chat-defaults')).toBeTruthy();
    expect(screen.queryByTestId('agent-table')).toBeNull();
  });

  // Not a regression guard - the ternary chain already defaulted to Agents. It pins that
  // 'settings' was added as its own arm and did not become the catch-all.
  it('falls back to the Agents list on an unknown ?view=', () => {
    searchParams = new URLSearchParams('view=nope');
    render(<AgentView />);
    expect(screen.getByTestId('agent-table')).toBeTruthy();
    expect(screen.queryByTestId('agent-chat-defaults')).toBeNull();
  });

  // Regression: pre-fix, a ?view=skills deep-link fell through to the Agents list
  // (the effect only handled fleet/metrics). Deriving from the URL renders Skills.
  it('renders the Skills tab on ?view=skills (deep-link)', () => {
    searchParams = new URLSearchParams('view=skills');
    render(<AgentView />);
    expect(screen.getByTestId('skill-tab')).toBeTruthy();
    expect(screen.queryByTestId('agent-table')).toBeNull();
  });

  // Contract guard for the reported symptom: leaving Fleet via the breadcrumb clears
  // ?view=, and the view must follow the URL back to the Agents list (canvas unmounts).
  // NOTE: this guards the post-fix "derive from URL" contract; it does not by itself
  // fail on the pre-fix mechanism (in jsdom the old effect happened to re-fire on
  // rerender). The Fleet->Skills test below is the exit-from-Fleet case that genuinely
  // failed pre-fix, since the old effect mishandled any view it didn't special-case.
  it('shows the Agents list once ?view= is cleared (breadcrumb exit from Fleet)', () => {
    searchParams = new URLSearchParams('view=fleet');
    const { rerender } = render(<AgentView />);
    expect(screen.getByTestId('fleet-canvas')).toBeTruthy();

    // Simulate the breadcrumb "Agents" navigation: ?view= is removed.
    searchParams = new URLSearchParams();
    rerender(<AgentView />);

    expect(screen.getByTestId('agent-table')).toBeTruthy();
    expect(screen.queryByTestId('fleet-canvas')).toBeNull();
  });

  // Exit-from-Fleet regression that DOES fail pre-fix: navigating Fleet -> Skills via
  // the URL. The old effect only special-cased fleet/metrics, so on view=skills its
  // `else` branch reset a fleet activeTab to 'agents' (showing the Agents list, not
  // Skills). Deriving from the URL renders Skills - the view follows the URL out of Fleet.
  it('follows the URL out of Fleet to Skills (not back to Agents)', () => {
    searchParams = new URLSearchParams('view=fleet');
    const { rerender } = render(<AgentView />);
    expect(screen.getByTestId('fleet-canvas')).toBeTruthy();

    searchParams = new URLSearchParams('view=skills');
    rerender(<AgentView />);

    expect(screen.getByTestId('skill-tab')).toBeTruthy();
    expect(screen.queryByTestId('fleet-canvas')).toBeNull();
    expect(screen.queryByTestId('agent-table')).toBeNull();
  });
});

describe('AgentView - tab clicks encode the choice in the URL', () => {
  // Regression: pre-fix, clicking Skills cleared the query (router.replace(pathname)).
  // Skills must now be encoded as ?view=skills so the URL stays the source of truth - and it
  // goes through the history API, because the router drops the push that clears the query.
  it('encodes Skills as ?view=skills', () => {
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabSkills'));
    expect(replace).toHaveBeenCalledWith('/app/agent?view=skills');
  });

  it('encodes Fleet as ?view=fleet', () => {
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabFleet'));
    expect(replace).toHaveBeenCalledWith('/app/agent?view=fleet');
  });

  it('encodes Metrics as ?view=metrics', () => {
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabMetrics'));
    expect(replace).toHaveBeenCalledWith('/app/agent?view=metrics');
  });

  it('encodes Settings as ?view=settings', () => {
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabSettings'));
    expect(replace).toHaveBeenCalledWith('/app/agent?view=settings');
  });

  it('keeps the Agents tab on a clean URL (no ?view=)', () => {
    searchParams = new URLSearchParams('view=skills');
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabAgents'));
    expect(replace).toHaveBeenCalledWith('/app/agent');
    // Not the router: from a page loaded on `?view=skills` that call is dropped and the tab
    // does nothing at all.
    expect(routerReplace).not.toHaveBeenCalled();
  });
});
