// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WELCOME_GIFT_FLAG } from '@/lib/onboarding/welcomeGiftHandoff';

/**
 * Persona questionnaire (steps 2 and 3) of the onboarding page.
 *
 * Pins the new questions (primary goal + tools, previous tool + referral
 * source), the per-step completion rules, the bounded save payload (no
 * `interests` / `useCases` / `experienceLevel` any more), the restore of
 * saved answers, and the `onboarding_completed` analytics props.
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  track: vi.fn(),
}));

vi.mock('next-intl', () => ({
  // Keys echo, so queries can find controls by their key text.
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: true,
    loginWithRedirect: vi.fn(),
  }),
}));

vi.mock('@/lib/api', () => ({
  apiClient: { get: mocks.apiGet, post: mocks.apiPost },
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

vi.mock('@/lib/analytics/analytics', () => ({ track: mocks.track }));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="spinner" />,
}));

import OnboardingPage from '../page';

type StatusOverrides = Record<string, unknown>;

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
        profession: 'sales',
        companySize: 'solo',
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

describe('Onboarding persona questionnaire', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.track.mockReset();
    mocks.apiPost.mockResolvedValue({});
  });

  afterEach(() => {
    cleanup();
    // Restored here rather than at the end of the test that installs it: a spy
    // on sessionStorage survives a failing assertion, and every later test in
    // this file reads that store.
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('step 2 asks for ONE primary goal and the tools in use, and requires the goal to continue', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    // Old questions are gone.
    expect(screen.queryByText('interests.automation')).not.toBeInTheDocument();
    expect(screen.queryByText('useCasesLabel')).not.toBeInTheDocument();
    expect(screen.queryByText('experienceLevel')).not.toBeInTheDocument();
    // No free-text input on this step: the only inputs would be the display name (step 1).
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    const next = screen.getByRole('button', { name: /^next$/ });
    expect(next).toBeDisabled();

    // Primary goal is single-choice: picking a second one replaces the first.
    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.reporting' }));
    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.dataSync' }));
    expect(pressed('primaryGoals.reporting')).toBe('false');
    expect(pressed('primaryGoals.dataSync')).toBe('true');
    expect(next).toBeEnabled();

    // Tools are multi-choice and toggle.
    fireEvent.click(screen.getByRole('button', { name: 'tools.gmail' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.slack' }));
    fireEvent.click(screen.getByRole('button', { name: 'tools.gmail' }));
    expect(pressed('tools.gmail')).toBe('false');
    expect(pressed('tools.slack')).toBe('true');

    fireEvent.click(next);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    const [endpoint, payload] = mocks.apiPost.mock.calls[0];
    expect(endpoint).toBe('/auth-service/api/onboarding/save');
    expect(payload).toEqual({
      displayName: 'Jane',
      profession: 'sales',
      companySize: 'solo',
      primaryGoal: 'data-sync',
      toolsUsed: ['slack'],
      previousTool: null,
      referralSource: null,
      currentStep: 2,
    });
    expect(payload).not.toHaveProperty('interests');
    expect(payload).not.toHaveProperty('useCases');
    expect(payload).not.toHaveProperty('experienceLevel');

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
  });

  it('step 3 requires both answers, completes with bounded values, and tracks the persona props', async () => {
    mockStatus({ currentStep: 3, primaryGoal: 'reporting', toolsUsed: ['gmail', 'slack'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    expect(screen.getByText('previousToolLabel')).toBeInTheDocument();
    expect(screen.getByText('referralSourceLabel')).toBeInTheDocument();

    const complete = screen.getByRole('button', { name: /complete/ });
    expect(complete).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'previousTools.zapierMake' }));
    expect(complete).toBeDisabled(); // referral source still missing

    fireEvent.click(screen.getByRole('button', { name: 'referralSources.wordOfMouth' }));
    expect(complete).toBeEnabled();
    fireEvent.click(complete);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    const [endpoint, payload] = mocks.apiPost.mock.calls[0];
    expect(endpoint).toBe('/auth-service/api/onboarding/complete');
    expect(payload).toMatchObject({
      primaryGoal: 'reporting',
      toolsUsed: ['gmail', 'slack'],
      previousTool: 'zapier-make',
      referralSource: 'word-of-mouth',
      currentStep: 3,
    });

    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith('onboarding_completed', {
      profession: 'sales',
      primary_goal: 'reporting',
      tools_count: 2,
      previous_tool: 'zapier-make',
      referral_source: 'word-of-mouth',
    }));
    // Both hand-offs, in the order the next screen plays them: what the plan
    // grants (credits and the AI allowance), then the applications to start
    // from. Asserting only the second would let the first be dropped silently,
    // which is exactly how the composer proposal it replaced went unnoticed.
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');
  });

  it('one hand-off the tab refuses to store does not take the other with it', async () => {
    // The two writes are independent niceties, and each is guarded on its own.
    // Sharing one `try` would make them a package: a store that rejects the
    // first key (a private window, a quota, an extension) would skip the second
    // silently, and a reader who completed onboarding correctly would get
    // neither the welcome gift nor the suggestions with nothing to show why.
    //
    // Spied on the PROTOTYPE, not on `window.sessionStorage`. A Storage object
    // is an exotic named-property object: assigning `setItem` on the instance
    // is swallowed as a stored ITEM called "setItem" and the real method keeps
    // running, so an instance spy here silently does nothing. Scoping by key
    // instead of by store keeps the blast radius to the one write under test,
    // which is what matters: blocking every write would also break the
    // analytics client and prove something wider than the guards.
    const realSetItem = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string,
    ) {
      if (key === WELCOME_GIFT_FLAG) throw new Error('denied');
      realSetItem.call(this, key, value);
    });
    mockStatus({ currentStep: 3, primaryGoal: 'reporting', toolsUsed: ['gmail'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    fireEvent.click(screen.getByText('previousTools.n8n'));
    fireEvent.click(screen.getByText('referralSources.search'));
    const complete = screen.getByRole('button', { name: 'complete' });
    await waitFor(() => expect(complete).toBeEnabled());
    fireEvent.click(complete);

    // The one that could be written, was.
    await waitFor(() => expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1'));
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    // And the completion itself still reaches its end state.
    await waitFor(() => expect(screen.getByTestId('spinner')).toBeInTheDocument());
  });

  it('reaches the completed state and hands off, whatever the answers were', async () => {
    // The welcome gift does not depend on the questionnaire: it states what
    // the account gets, which is the same whether the goal was specific or
    // "Something else". The composer proposal this replaced DID depend on the
    // answers and silently produced nothing for the generic ones, which is
    // most of why it went.
    mockStatus({ currentStep: 3, primaryGoal: 'other', toolsUsed: ['gmail'] });
    renderPage();

    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    fireEvent.click(screen.getByText('previousTools.n8n'));
    fireEvent.click(screen.getByText('referralSources.search'));
    const complete = screen.getByRole('button', { name: 'complete' });
    await waitFor(() => expect(complete).toBeEnabled());
    fireEvent.click(complete);

    await waitFor(() => expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1'));
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');
    // ...and the flow really did reach its end state: the questionnaire is
    // gone, replaced by the completed spinner that redirects to chat. The
    // spinner IS the redirect state (a separate effect navigates to chat on
    // `pageState === 'completed'`), so reaching it means the user is not
    // stranded - jsdom cannot follow the navigation itself.
    expect(screen.queryByText('step3.title')).not.toBeInTheDocument();
    expect(screen.getByTestId('spinner')).toBeInTheDocument();
  });

  it('restores only answers that are options in this edition (CE goal and unknown tool are dropped)', async () => {
    mockStatus({
      currentStep: 2,
      primaryGoal: 'internal-automation', // CE-only goal, not offered in cloud
      toolsUsed: ['gmail', 'bogus-tool', 'gmail'],
      previousTool: 'n8n',
      referralSource: 'not-a-source',
    });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'ce.useCases.internalAutomation' })).not.toBeInTheDocument();
    // No goal restored, so the step cannot advance yet.
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
    expect(pressed('tools.gmail')).toBe('true');
    expect(pressed('tools.slack')).toBe('false');

    fireEvent.click(screen.getByRole('button', { name: 'primaryGoals.aiAssistant' }));
    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledTimes(1));
    expect(mocks.apiPost.mock.calls[0][1]).toMatchObject({
      primaryGoal: 'ai-assistant',
      toolsUsed: ['gmail'],
      previousTool: 'n8n',
      referralSource: null,
    });

    // Step 3 shows the restored previous tool and nothing for the unknown source.
    expect(await screen.findByText('step3.title')).toBeInTheDocument();
    expect(pressed('previousTools.n8n')).toBe('true');
    expect(screen.getByRole('button', { name: /complete/ })).toBeDisabled();
  });

  it('skip stays available on the persona steps and tracks the step it was skipped at', async () => {
    mockStatus({ currentStep: 2 });
    renderPage();

    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    // The skip button waits for the display-name availability check (debounced).
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/skip',
      { displayName: 'Jane' },
    ));
    await waitFor(() => expect(mocks.track).toHaveBeenCalledWith('onboarding_skipped', { skipped_at_step: 2 }));
    // Armed on the skip path too: what the plan grants does not depend on how
    // much the user chose to tell us, and someone who skipped the questions is
    // if anything likelier not to know it yet.
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
    expect(sessionStorage.getItem('lc_show_app_suggestions')).toBe('1');
  });
});
