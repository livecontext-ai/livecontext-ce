// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Persona questionnaire of the onboarding page, CE edition.
 *
 * Same harness as page.persona.test.tsx with IS_CE flipped to true. Pins that
 * step 2 offers the CE use-cases (not the cloud primary goals), that a goal
 * saved under the cloud edition is dropped on restore, and that the save
 * payload carries the CE goal value alongside the (edition-agnostic) tools.
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  track: vi.fn(),
  leaveForChat: vi.fn(),
}));

// The one navigation to the chat, observed rather than inferred from a spinner that the loading
// and saving states also show.
vi.mock('@/lib/navigation/leaveForChat', () => ({ leaveForChat: mocks.leaveForChat }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: true,
    loginWithRedirect: vi.fn(),
    // The page destructures logout for its sign-out hatches. Absent, the first
    // future case here that reaches one dies on "logout is not a function".
    logout: vi.fn(),
  }),
}));

vi.mock('@/lib/api', () => ({
  apiClient: { get: mocks.apiGet, post: mocks.apiPost },
}));

// The credential-redirect handler reads the address through next/navigation, which returns null
// outside a router context. A real URLSearchParams over window.location keeps the test driving
// the same code the browser does, including the history-API cleanup.
vi.mock('next/navigation', () => ({
  usePathname: () => window.location.pathname,
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

vi.mock('@/lib/edition', () => ({ IS_CE: true }));

vi.mock('@/lib/analytics/analytics', () => ({ track: mocks.track }));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="spinner" />,
}));

import OnboardingPage from '../page';

type StatusOverrides = Record<string, unknown>;

/** CE-valid profile answers so the restore keeps the profile fields intact. */
function mockStatus(overrides: StatusOverrides) {
  mocks.apiGet.mockImplementation(async (path: string) => {
    if (path === '/auth/email/status') return { verified: true };
    if (path === '/auth-service/api/onboarding/status') {
      return {
        needsOnboarding: true,
        completed: false,
        skipped: false,
        currentStep: 2,
        displayName: 'Jane',
        profession: 'developer',
        companySize: 'team',
        ...overrides,
      };
    }
    if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
      return { available: true, message: '' };
    }
    throw new Error(`unexpected GET ${path}`);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OnboardingPage />
    </QueryClientProvider>,
  );
}

const pressed = (name: string | RegExp) =>
  screen.getByRole('button', { name }).getAttribute('aria-pressed');

const CE_GOAL_LABELS = [
  'ce.useCases.internalAutomation',
  'ce.useCases.privateAssistants',
  'ce.useCases.dataPipelines',
  'ce.useCases.toolOrchestration',
  'ce.useCases.teamWorkspaces',
  'ce.useCases.marketplacePublishing',
  'ce.useCases.evaluationSandbox',
  'ce.useCases.other',
];

const CLOUD_GOAL_LABELS = [
  'primaryGoals.emailFollowUps',
  'primaryGoals.contentPublishing',
  'primaryGoals.leadGeneration',
  'primaryGoals.customerSupport',
  'primaryGoals.reporting',
  'primaryGoals.dataSync',
  'primaryGoals.monitoringAlerts',
  'primaryGoals.aiAssistant',
  'primaryGoals.other',
];

describe('Onboarding persona questionnaire (CE edition)', () => {
  beforeEach(() => {
    // restoreAllMocks does not clear a vi.fn call history: without this, one completing test
    // makes every later leaveForChat assertion pass on its own.
    mocks.leaveForChat.mockReset();
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.track.mockReset();
    mocks.apiPost.mockResolvedValue({});
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  it('step 2 offers the CE use-cases as goal chips, none of the cloud primary goals', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('ce.step2.title')).toBeInTheDocument();
    expect(screen.queryByText('step2.title')).not.toBeInTheDocument();

    for (const label of CE_GOAL_LABELS) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
    }
    for (const label of CLOUD_GOAL_LABELS) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument();
    }

    // Tools are edition-agnostic: still offered in CE.
    expect(screen.getByRole('button', { name: 'tools.gmail' })).toBeInTheDocument();

    // The goal is single-choice here too.
    fireEvent.click(screen.getByRole('button', { name: 'ce.useCases.internalAutomation' }));
    fireEvent.click(screen.getByRole('button', { name: 'ce.useCases.privateAssistants' }));
    expect(pressed('ce.useCases.internalAutomation')).toBe('false');
    expect(pressed('ce.useCases.privateAssistants')).toBe('true');
  });

  it('drops a stored cloud goal on restore: no chip selected and Next disabled until a CE goal is picked', async () => {
    mockStatus({
      currentStep: 2,
      primaryGoal: 'email-follow-ups', // cloud-only goal, not offered in CE
      toolsUsed: ['gmail'],
    });
    renderPage();

    expect(await screen.findByText('ce.step2.title')).toBeInTheDocument();
    // The cloud option is not rendered at all, and no CE chip inherits the selection.
    expect(screen.queryByRole('button', { name: 'primaryGoals.emailFollowUps' })).not.toBeInTheDocument();
    for (const label of CE_GOAL_LABELS) {
      expect(pressed(label)).toBe('false');
    }
    // The tools survive the restore (same list in both editions).
    expect(pressed('tools.gmail')).toBe('true');

    const next = screen.getByRole('button', { name: /^next$/ });
    expect(next).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'ce.useCases.dataPipelines' }));
    expect(pressed('ce.useCases.dataPipelines')).toBe('true');
    expect(next).toBeEnabled();
  });

  it('saves the CE goal value and the selected tools', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('ce.step2.title')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'ce.useCases.toolOrchestration' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.github' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.slack' }));
    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    const [endpoint, payload] = mocks.apiPost.mock.calls[0];
    expect(endpoint).toBe('/auth-service/api/onboarding/save');
    expect(payload).toEqual({
      displayName: 'Jane',
      profession: 'developer',
      companySize: 'team',
      primaryGoal: 'tool-orchestration',
      toolsUsed: ['github', 'slack'],
      previousTool: null,
      referralSource: null,
      currentStep: 2,
    });
    expect(payload).not.toHaveProperty('interests');
    expect(payload).not.toHaveProperty('useCases');
    expect(payload).not.toHaveProperty('experienceLevel');

    expect(await screen.findByText('ce.step3.title')).toBeInTheDocument();
  });

  it('goes straight to the chat after completing, self-hosted too: no apps panel in between', async () => {
    // Connecting apps left onboarding for the setup checklist. CE completes by the same route.
    mockStatus({ currentStep: 3, previousTool: 'n8n', referralSource: 'search' });
    renderPage();

    expect(await screen.findByText('ce.step3.title')).toBeInTheDocument();
    const complete = screen.getByRole('button', { name: /complete/ });
    await waitFor(() => expect(complete).toBeEnabled());
    fireEvent.click(complete);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/complete', expect.objectContaining({ currentStep: 3 })));
    await waitFor(() => expect(mocks.leaveForChat).toHaveBeenCalledWith('en'));
    expect(mocks.leaveForChat).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('ce.step3.title')).not.toBeInTheDocument();
    expect(screen.queryByText('step4.title')).not.toBeInTheDocument();
  });
});
