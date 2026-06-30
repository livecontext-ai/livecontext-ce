// @vitest-environment jsdom
/**
 * AgentView is the /app/agent shell with four tabs (Agents / Skills / Fleet / Metrics).
 * These pin the contract that the URL (?view=) is the SINGLE source of truth for the
 * active tab, derived at render time:
 *   - no ?view=        → Agents list
 *   - ?view=skills     → Skills tab
 *   - ?view=fleet      → full-screen Fleet canvas (the tab bar is hidden here, so the
 *                        breadcrumb is the only way out)
 *   - ?view=metrics    → Metrics dashboard
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
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

let searchParams = new URLSearchParams();
const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParams,
  useRouter: () => ({ replace, push: vi.fn() }),
  usePathname: () => '/app/agent',
}));

import { AgentView } from '../AgentView';

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
});
afterEach(() => cleanup());

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
  // Skills must now be encoded as ?view=skills so the URL stays the source of truth.
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

  it('keeps the Agents tab on a clean URL (no ?view=)', () => {
    searchParams = new URLSearchParams('view=skills');
    render(<AgentView />);
    fireEvent.click(screen.getByText('tabAgents'));
    expect(replace).toHaveBeenCalledWith('/app/agent');
  });
});
