// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Getting out of onboarding when it cannot be finished.
 *
 * Signed in but not onboarded is a closed room: this page draws no sidebar and
 * no user menu, and FirstLoginGuard sends every app route back here while the
 * email is unverified. Four ways to be held there, answered three ways:
 *   - the email step, where the one code that unlocks it goes to the address
 *     being left, and is sent automatically on mount, so the mistake is usually
 *     found after the mail has gone. There is no change-email endpoint. Gets a
 *     sign-out worded around the address;
 *   - the error card, which offers only Retry. Gets a neutral sign-out;
 *   - steps 1 to 3 with a skip or save that keeps failing: both controls lit,
 *     both useless, and the status call answers fine so no error card ever
 *     appears. Gets the same neutral sign-out, rendered from "has been refused
 *     at least once" rather than from "an error is on screen", since the retry
 *     clears the banner and disables both controls while it runs;
 *   - steps 1 to 3 with a name-check outage, which used to disable Skip and
 *     Next together. Fixed at the cause instead: an unanswered check is no
 *     longer read as a verdict on the name.
 * Clearing cookies was the only exit from any of them.
 *
 * A blocked store (private window, quota, extension) was a fifth way, and the
 * worst, because it hid the very hatch this file adds. Three cases below cover
 * the three storage sites it reaches.
 *
 * Who lands on the email step: accounts registered with an email and a password
 * (the realm has `verifyEmail:false` and open registration), so a mistyped or
 * unreachable address, plus anyone Keycloak no longer reports as verified.
 * Social sign-ins normally skip it, since every configured identity provider is
 * `trustEmail:true`.
 *
 * Run against `git show HEAD:page.tsx` (the code before this change), this file
 * gives 37 red and 8 green. The green ones are the key-placement check, the
 * CHARACTERISATION case (it pins pre-existing cooldown behaviour that makes a
 * leftover stamp harmful), and the SCOPING cases, which fence where a hatch
 * must NOT appear and are not evidence for the change.
 *
 * Mutation-tested: each mutant is applied from a pristine copy and its
 * application asserted by checksum. Known survivors are listed rather than
 * hidden: the `currentStep > 0` half of the form hatch's condition (equivalent,
 * since only a save or skip sets submitFailed and neither exists on step 0),
 * and the `isCurrent()` guard on the spinner reset (cosmetic: a stale check can
 * hide the spinner early, but cannot write a verdict).
 */
const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  logout: vi.fn(),
  track: vi.fn(),
}));

vi.mock('next-intl', () => ({
  // Keys echo, so controls are queryable by their key text.
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: true,
    loginWithRedirect: vi.fn(),
    logout: mocks.logout,
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
import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import deMessages from '@/messages/de.json';
import esMessages from '@/messages/es.json';
import ptMessages from '@/messages/pt.json';
import zhMessages from '@/messages/zh.json';

const ALL_LOCALES = {
  en: enMessages,
  fr: frMessages,
  de: deMessages,
  es: esMessages,
  pt: ptMessages,
  zh: zhMessages,
};

const WRONG_EMAIL = 'emailVerification.wrongEmail';
const SIGN_OUT = 'signOut';
const COOLDOWN_KEY = 'email_verification_last_sent';

function mockStatus({ verified, statusFails = false }: { verified: boolean; statusFails?: boolean }) {
  mocks.apiGet.mockImplementation(async (path: string) => {
    if (path === '/auth/email/status') return { verified };
    if (path === '/auth-service/api/onboarding/status') {
      if (statusFails) throw new Error('status unavailable');
      return {
        needsOnboarding: true,
        completed: false,
        skipped: false,
        currentStep: 1,
        displayName: 'Jane',
        profession: 'sales',
        companySize: 'solo',
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

describe('Onboarding - signing out of an account that cannot finish onboarding', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mocks.apiGet.mockReset();
    mocks.apiPost.mockReset();
    mocks.logout.mockReset();
    mocks.track.mockReset();
    mocks.apiPost.mockResolvedValue({});
    mocks.logout.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  it('the three new keys sit in the namespace this page reads, in every locale', () => {
    // The echo translator above makes every other assertion in this file pass on
    // the key STRING, and the locale-parity guard only compares locales to one
    // another. A key placed in the wrong namespace is therefore perfect parity,
    // green everywhere, and renders "onboarding.signOut" verbatim to users. This
    // is the only assertion here that would catch it, so it reads all six rather
    // than English alone: parity with a wrong namespace is still wrong six times.
    for (const [locale, messages] of Object.entries(ALL_LOCALES)) {
      const onboarding = (messages as Record<string, any>).onboarding;
      for (const value of [
        onboarding?.signOut,
        onboarding?.signOutFailed,
        onboarding?.emailVerification?.wrongEmail,
      ]) {
        expect(typeof value, locale).toBe('string');
        expect(value.trim().length, locale).toBeGreaterThan(0);
      }
    }
  });

  it('REGRESSION: the verification step offers a sign-out, and it really signs out', async () => {
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    // No arguments: the default post-logout target is the app entry, which is
    // unauthenticated after sign-out and bounces to the login form. A returnTo
    // of this page would send the person straight back into what they escaped.
    expect(mocks.logout).toHaveBeenCalledWith();
    // This page instruments its other funnel exits (onboarding_skipped), so the
    // new one is not left indistinguishable from any other sign-out.
    expect(mocks.track).toHaveBeenCalledWith('onboarding_signed_out', {
      signed_out_from: 'email_verification',
    });
  });

  it('REGRESSION: signing out clears the cooldown, so the NEXT account really gets a code', async () => {
    // The defect this closes: the stamp belongs to the address being left
    // behind, and sessionStorage survives the round trip through the identity
    // provider back into the same tab. Kept, the next session's mount effect
    // reads it, shows "Code sent!" and sends nothing, so the right account
    // waits up to a minute for a mail that was never sent.
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    await waitFor(() => expect(sessionStorage.getItem(COOLDOWN_KEY)).not.toBeNull());

    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));
    expect(sessionStorage.getItem(COOLDOWN_KEY)).toBeNull();
  });

  it('CHARACTERISATION: a fresh cooldown really does suppress the send, which is what the clearing avoids', async () => {
    // The other half of the pair, and green before the change by design: it
    // pins the PRE-EXISTING behaviour that makes the leftover stamp harmful.
    // Without it the test above asserts a removal whose consequence is
    // unproven, and a reader cannot tell whether the stamp mattered. Here the
    // stamp is left in place, the code is NOT sent, and the screen still claims
    // it was.
    sessionStorage.setItem(COOLDOWN_KEY, Date.now().toString());
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.codeSent')).toBeInTheDocument();
    expect(mocks.apiPost).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /emailVerification.resendIn/ })).toBeDisabled();
  });

  it('REGRESSION: the way out is still there when the code could not be sent', async () => {
    // The state a stuck person is most likely to be in: rate limited, or the
    // address rejected. A hatch that lived behind a successful send would be
    // missing exactly when it is needed.
    mockStatus({ verified: false });
    mocks.apiPost.mockRejectedValue(new Error('rate limited'));
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith('/auth/email/send-code', {}));
    expect(screen.queryByText('emailVerification.codeSent')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));
    expect(mocks.logout).toHaveBeenCalledTimes(1);
  });

  it('REGRESSION: the hatch is disabled while a code is being checked', async () => {
    // A click during the verify round trip would throw away a session one
    // network hop from being valid, and the button sits directly under the
    // inputs the person was just typing into.
    let releaseVerify: () => void = () => {};
    mocks.apiPost.mockImplementation(async (path: string) => {
      if (path === '/auth/email/verify-code') {
        await new Promise<void>((resolve) => { releaseVerify = resolve; });
      }
      return {};
    });
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.paste(screen.getAllByRole('textbox')[0], {
      clipboardData: { getData: () => '123456' },
    });

    const hatch = await screen.findByRole('button', { name: WRONG_EMAIL });
    await waitFor(() => expect(hatch).toBeDisabled());
    fireEvent.click(hatch);
    expect(mocks.logout).not.toHaveBeenCalled();

    releaseVerify();
    expect(await screen.findByText('emailVerification.verified')).toBeInTheDocument();
  });

  it('REGRESSION: a sign-out that never redirects says so instead of looking like a dead click', async () => {
    mockStatus({ verified: false });
    mocks.logout.mockRejectedValue(new Error('redirect blocked'));
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));

    expect(await screen.findByText('signOutFailed')).toBeInTheDocument();
  });

  it('REGRESSION: the error card offers the same way out, since Retry alone does not free the account', async () => {
    mockStatus({ verified: true, statusFails: true });
    renderPage();

    expect(await screen.findByText('errorTitle')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'retry' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: SIGN_OUT }));
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track).toHaveBeenCalledWith('onboarding_signed_out', { signed_out_from: 'error' });
  });

  it('SCOPING: the hatch is gone as soon as the address is confirmed', async () => {
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.paste(screen.getAllByRole('textbox')[0], {
      clipboardData: { getData: () => '123456' },
    });

    expect(await screen.findByText('emailVerification.verified')).toBeInTheDocument();
    expect(mocks.apiPost).toHaveBeenCalledWith('/auth/email/verify-code', { code: '123456' });
    // The account is now the right one, so the hatch would only be a way to
    // lose a just-verified session by accident.
    expect(screen.queryByRole('button', { name: WRONG_EMAIL })).not.toBeInTheDocument();
    // Waited out rather than left hanging: the success screen schedules the
    // advance on a timer, and unmounting before it fires would set state on a
    // dead tree and leave an unexplained console error for whoever reads this
    // suite next.
    expect(await screen.findByText('step1.title', undefined, { timeout: 5000 }))
      .toBeInTheDocument();
  });

  it('REGRESSION: a name-check outage leaves Skip open, so step 1 is not a closed room either', async () => {
    // The same trap, different cause, and fixed where it is caused rather than
    // by adding a permanent control to the funnel. Skip and Next both required
    // an available display name, and the availability check answered
    // "unavailable" when its own endpoint threw: an outage disabled both and
    // step 1 became the closed room the email step used to be. An unanswered
    // check is not a verdict on the name, so Skip stays open. A name that
    // really is taken is still refused, by the skip call itself.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        throw new Error('name check unavailable');
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('displayNameCheckError')).toBeInTheDocument());

    // BOTH controls, not just Skip. Reviving only the exit would leave a new
    // account able to abandon onboarding and never to finish it: the persona
    // answers are lost and the funnel records a skip where there was none.
    expect(screen.getByRole('button', { name: /^next$/ })).toBeEnabled();

    const skip = screen.getByRole('button', { name: 'skipForNow' });
    expect(skip).toBeEnabled();
    fireEvent.click(skip);
    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/skip',
      { displayName: 'Jane' },
    ));
  });

  it('REGRESSION: an unanswered check unblocks the controls without ever claiming the name is free', async () => {
    // The reason this is a separate flag rather than a relaxed
    // `displayNameAvailable`: the green border and the tick mean "the server
    // said this name is free", and during an outage the server said nothing.
    // Painting them would turn an outage into a promise, and the promise breaks
    // on submit. Collapsing the two booleans leaves every other test green.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        throw new Error('name check unavailable');
      }
      throw new Error(`unexpected GET ${path}`);
    });
    const { container } = renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());

    expect(container.querySelector('.text-emerald-500')).toBeNull();
    expect(screen.getByDisplayValue('Jane').className).not.toContain('border-emerald-500');
  });

  it('REGRESSION: the flag does not survive into a name nobody has answered for', async () => {
    // It means "the last COMPLETED check failed", not "a check failed once".
    // Left set across the debounce and the in-flight request, Skip and Next
    // would be live on an unchecked name, and a duplicate would come back from
    // the server as a bare "HTTP 400: Bad Request" with nothing pointing at the
    // name. Shortening below the minimum length is the same window, and is the
    // one an early return used to skip.
    let failNext = true;
    let release: () => void = () => {};
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        if (failNext) { failNext = false; throw new Error('name check unavailable'); }
        await new Promise<void>((resolve) => { release = resolve; });
        return { available: true, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());

    // Too short to be checked at all: the flag must not carry over.
    fireEvent.change(screen.getByDisplayValue('Jane'), { target: { value: 'Jo' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled());
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();

    // Long enough, so a check goes out and is still in flight: same rule.
    fireEvent.change(screen.getByDisplayValue('Jo'), { target: { value: 'Joanna' } });
    await waitFor(() => expect(mocks.apiGet).toHaveBeenCalledWith(
      expect.stringContaining('check-display-name?displayName=Joanna'),
    ));
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();

    release();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());
  });

  it('REGRESSION: a late "taken" answer after an outage closes Skip and Next again', async () => {
    // The flag is cleared on every answered check, so a failure is not sticky.
    // Left set, a real duplicate would keep Skip and Next lit for the rest of
    // the session (the server still refuses, so it is a UI hole, not a data one).
    let failNext = true;
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        if (failNext) { failNext = false; throw new Error('name check unavailable'); }
        return { available: false, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());

    // Type once more: the check answers properly this time, and says taken.
    const input = screen.getByDisplayValue('Jane');
    fireEvent.change(input, { target: { value: 'Janet' } });

    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
  });

  it('SCOPING: a name that is genuinely taken still blocks Skip', async () => {
    // The other side of the same condition, and the reason it is a separate
    // flag rather than a relaxed check: re-enabling Skip on a real "taken"
    // verdict would let a duplicate display name through the front door.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        return { available: false, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
  });

  it('REGRESSION: a second click does not emit a second departure', async () => {
    // The redirect is not instant and the control stays on screen until it
    // happens, so an impatient double-click would count one person twice in the
    // funnel this event exists to measure.
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    const hatch = screen.getByRole('button', { name: WRONG_EMAIL });
    fireEvent.click(hatch);
    fireEvent.click(hatch);

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track.mock.calls.filter(([name]) => name === 'onboarding_signed_out')).toHaveLength(1);
  });

  it('REGRESSION: the departure is counted while the identity is still attached', async () => {
    // Load-bearing ordering: logout() itself emits auth_logged_out and then
    // resets analytics, so an event fired after it lands on an anonymous id and
    // is useless for the funnel.
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));

    expect(mocks.track.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.logout.mock.invocationCallOrder[0]);
  });

  it('REGRESSION: a tab that refuses storage can still leave', async () => {
    // A private window, a quota, an extension: the clear throws. Unguarded that
    // takes the sign-out down with it, on the one control that frees the account.
    // Spied on the PROTOTYPE, since assigning on the Storage instance is
    // swallowed as a stored item called "removeItem" and the real method runs.
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    try {
      mockStatus({ verified: false });
      renderPage();

      expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));
      expect(mocks.logout).toHaveBeenCalledTimes(1);
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('REGRESSION: a failed sign-out from the error card says so without erasing why onboarding failed', async () => {
    // Its own state, not the page error. Reusing `error` would overwrite the
    // card's own description, so the person would be told the sign-out failed
    // and would lose the reason they were sent to this card in the first place.
    mockStatus({ verified: true, statusFails: true });
    mocks.logout.mockRejectedValue(new Error('redirect blocked'));
    renderPage();

    expect(await screen.findByText('errorTitle')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: SIGN_OUT }));

    expect(await screen.findByText('signOutFailed')).toBeInTheDocument();
    expect(screen.getByText('errorDescription')).toBeInTheDocument();
    // And it is re-armed, since the person is still stuck on this card.
    mocks.logout.mockResolvedValue(undefined);
    fireEvent.click(screen.getByRole('button', { name: SIGN_OUT }));
    expect(mocks.logout).toHaveBeenCalledTimes(2);
    // But the departure is counted ONCE. The first attempt already reset
    // analytics inside logout(), so a second emit lands on an anonymous id and
    // doubles one person in the funnel.
    expect(mocks.track.mock.calls.filter(([name]) => name === 'onboarding_signed_out'))
      .toHaveLength(1);
    // And the stale failure line is gone while the retry is in flight, rather
    // than sitting under a button that is currently working.
    await waitFor(() => expect(screen.queryByText('signOutFailed')).not.toBeInTheDocument());
  });

  it('REGRESSION: a skip that keeps failing gets a way out, next to the failure it caused', async () => {
    // The other way steps 1 to 3 can hold someone. Skip and Next are both lit,
    // both fail, and the status call is answering fine so the error card never
    // appears: a reload comes straight back to the same step. The skip also
    // creates the personal organisation server-side, which can fail on its own.
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(new Error('skip unavailable'));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    // Nothing yet: the hatch belongs to the failure, not to the step.
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();

    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    const hatch = await screen.findByRole('button', { name: SIGN_OUT });
    fireEvent.click(hatch);
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track).toHaveBeenCalledWith('onboarding_signed_out', {
      signed_out_from: 'form_error',
    });
  });

  it('REGRESSION: an empty refusal reads as the step\'s own wording, not "HTTP 400: Bad Request"', async () => {
    // These endpoints answer 400 with an EMPTY body, so the api client has
    // nothing to report and synthesises that string, which is what the user
    // used to read. They use the one status for several reasons (a duplicate
    // name, a name outside 3 to 30 characters, an unverified email, an unknown
    // provider id) and the response does not say which, so naming a field here
    // would replace a bad message with a confident wrong one.
    const refused: any = new Error('HTTP 400: Bad Request');
    refused.status = 400;
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(refused);
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    expect(await screen.findByText('skipError')).toBeInTheDocument();
    expect(screen.queryByText('HTTP 400: Bad Request')).not.toBeInTheDocument();
    expect(screen.queryByText('displayNameTaken')).not.toBeInTheDocument();
  });

  it('REGRESSION: the same wording applies on the Next path, not only on Skip', async () => {
    // Two call sites, two assertions. Next is the path most accounts take, and
    // reverting just its arm left the whole suite green when only Skip was
    // covered.
    const refused: any = new Error('HTTP 400: Bad Request');
    refused.status = 400;
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(refused);
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const next = screen.getByRole('button', { name: /^next$/ });
    await waitFor(() => expect(next).toBeEnabled());
    fireEvent.click(next);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/save',
      expect.anything(),
    ));
    expect(await screen.findByText('saveError')).toBeInTheDocument();
    expect(screen.queryByText('HTTP 400: Bad Request')).not.toBeInTheDocument();
  });

  it('REGRESSION: a timeout or a dead connection does not put raw English on the page', async () => {
    // The api client writes these messages for itself, with no server involved:
    // "Request timeout" (408), "Request failed" (500), and a bare
    // "Failed to fetch" with no status at all. Matching the synthesised
    // "HTTP <status>" shape by prefix missed all three, so a French or Chinese
    // account read English on the likeliest failure of all, a flaky connection.
    const cases = [
      Object.assign(new Error('Request timeout'), { status: 408, code: 'TIMEOUT' }),
      Object.assign(new Error('Request failed'), { status: 500, code: 'UNKNOWN' }),
      new TypeError('Failed to fetch'),
    ];

    for (const failure of cases) {
      mockStatus({ verified: true });
      mocks.apiPost.mockRejectedValue(failure);
      renderPage();

      expect(await screen.findByText('step1.title')).toBeInTheDocument();
      const skip = screen.getByRole('button', { name: 'skipForNow' });
      await waitFor(() => expect(skip).toBeEnabled());
      fireEvent.click(skip);

      expect(await screen.findByText('skipError')).toBeInTheDocument();
      expect(screen.queryByText(failure.message)).not.toBeInTheDocument();
      cleanup();
    }
  });

  it('REGRESSION: an abandoned check cannot overrule the answer that replaced it', async () => {
    // Nothing sequenced these responses. It did not matter while a stale answer
    // could only close the gate; it does now, because a stale FAILURE opens it.
    // A slow failing check landing after a fast "taken" answer would light Skip
    // and Next on a name the server had just refused.
    let failFirst: () => void = () => {};
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.includes('displayName=Slowname')) {
        await new Promise<void>((_, reject) => { failFirst = () => reject(new Error('check unavailable')); });
      }
      if (path.includes('displayName=Takenname')) return { available: false, message: '' };
      return { available: true, message: '' };
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue('Jane'), { target: { value: 'Slowname' } });
    await waitFor(() => expect(mocks.apiGet).toHaveBeenCalledWith(
      expect.stringContaining('displayName=Slowname'),
    ));

    fireEvent.change(screen.getByDisplayValue('Slowname'), { target: { value: 'Takenname' } });
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());

    // The abandoned request now fails. Its verdict belongs to a name that is no
    // longer in the field, so it must write nothing.
    failFirst();
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
  });

  it('REGRESSION: an abandoned check cannot declare a name free after it was refused', async () => {
    // The other half of the sequencing. A slow SUCCESS landing after a fast
    // "taken" answer would light the green tick and both controls on a name the
    // server had just refused, which is worse than the failure case: it looks
    // like a verdict rather than an outage.
    let allowFirst: () => void = () => {};
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.includes('displayName=Slowname')) {
        await new Promise<void>((resolve) => { allowFirst = resolve; });
        return { available: true, message: '' };
      }
      if (path.includes('displayName=Takenname')) return { available: false, message: '' };
      return { available: true, message: '' };
    });
    const { container } = renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue('Jane'), { target: { value: 'Slowname' } });
    await waitFor(() => expect(mocks.apiGet).toHaveBeenCalledWith(
      expect.stringContaining('displayName=Slowname'),
    ));

    fireEvent.change(screen.getByDisplayValue('Slowname'), { target: { value: 'Takenname' } });
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());

    allowFirst();
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());
    expect(container.querySelector('.text-emerald-500')).toBeNull();
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
  });

  it('REGRESSION: the hatch goes once a submit succeeds, so it is not a permanent fixture', async () => {
    // Earned by being refused, and unearned by getting through. Kept, one
    // transient failure at step 1 would leave a sign-out control under steps 2
    // and 3 for the rest of the session, which is the permanent funnel control
    // this was put on a failure state to avoid being.
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValueOnce(new Error('save unavailable'));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const next = screen.getByRole('button', { name: /^next$/ });
    await waitFor(() => expect(next).toBeEnabled());
    fireEvent.click(next);
    expect(await screen.findByRole('button', { name: SIGN_OUT })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));
    expect(await screen.findByText('step2.title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();
  });

  it('REGRESSION: the check-failed message says the person may continue', async () => {
    // The field stays red with an error under it while Skip and Next are live,
    // which reads as "fix this first". Restoring the ability to move on without
    // saying so restores half the fix.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        throw new Error('name check unavailable');
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('displayNameCheckError')).toBeInTheDocument());
    // Shown at the same time as usable controls, which is the state the copy
    // has to account for.
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeEnabled();

    // And the copy itself says it, in every locale. A translator dropping the
    // clause would leave that language reading "fix this before continuing".
    const CONTINUE: Record<string, RegExp> = {
      en: /continue/i,
      fr: /continuer/i,
      de: /fortfahren/i,
      es: /continuar/i,
      pt: /continuar/i,
      zh: /继续/,
    };
    for (const [locale, messages] of Object.entries(ALL_LOCALES)) {
      const copy = (messages as Record<string, any>).onboarding.displayNameCheckError;
      expect(copy, locale).toMatch(CONTINUE[locale]);
    }
  });

  it('REGRESSION: a response body is not shown raw either', async () => {
    // Spring answers a 5xx with {error: "Internal Server Error"} and the Next
    // proxy with {error: "Proxy error", message: "connect ECONNREFUSED ..."}.
    // Neither is translated, and the second is internal detail. The page shows
    // its own translated message whatever the body carries.
    const refused: any = new Error('connect ECONNREFUSED 127.0.0.1:8080');
    refused.status = 502;
    refused.details = { error: 'Proxy error', message: 'connect ECONNREFUSED 127.0.0.1:8080' };
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(refused);
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    expect(await screen.findByText('skipError')).toBeInTheDocument();
    expect(screen.queryByText(/ECONNREFUSED/)).not.toBeInTheDocument();
    expect(screen.queryByText('Proxy error')).not.toBeInTheDocument();
  });

  it('REGRESSION: a save the server refused does not carry the person to the next step', async () => {
    // Advancing regardless left the refusal on screen over a step that has no
    // field for it, and the answers just typed were never stored. The Next
    // path is the one most accounts take, so it gets its own case rather than
    // riding on the skip arm.
    const refused: any = new Error('HTTP 400: Bad Request');
    refused.status = 400;
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(refused);
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const next = screen.getByRole('button', { name: /^next$/ });
    await waitFor(() => expect(next).toBeEnabled());
    fireEvent.click(next);

    await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith(
      '/auth-service/api/onboarding/save',
      expect.anything(),
    ));
    expect(await screen.findByText('saveError')).toBeInTheDocument();
    expect(screen.getByText('step1.title')).toBeInTheDocument();
    expect(screen.queryByText('step2.title')).not.toBeInTheDocument();
  });

  it('REGRESSION: while the redirect is pending the hatch says so, instead of ignoring clicks in silence', async () => {
    // The ref already swallowed the second click. Without a visible pending
    // state that reads as a dead control, which is the very thing this hatch
    // exists not to be, and a slow identity provider is the common case.
    let release: () => void = () => {};
    mocks.logout.mockImplementation(() => new Promise<void>((resolve) => { release = resolve; }));
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    const hatch = screen.getByRole('button', { name: WRONG_EMAIL });
    fireEvent.click(hatch);

    await waitFor(() => expect(hatch).toBeDisabled());
    expect(hatch).toHaveAttribute('aria-busy', 'true');
    release();
  });

  it('REGRESSION: a failed sign-out puts the cooldown back, so a refresh does not send a second code', async () => {
    // The stamp is cleared for the NEXT account. If the redirect fails, the
    // same account is still here, and without its stamp a refresh would send
    // another email at once and could trip the server's rate limit.
    mockStatus({ verified: false });
    mocks.logout.mockRejectedValue(new Error('redirect blocked'));
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    await waitFor(() => expect(sessionStorage.getItem(COOLDOWN_KEY)).not.toBeNull());
    const stamp = sessionStorage.getItem(COOLDOWN_KEY);

    fireEvent.click(screen.getByRole('button', { name: WRONG_EMAIL }));
    expect(await screen.findByText('signOutFailed')).toBeInTheDocument();
    expect(sessionStorage.getItem(COOLDOWN_KEY)).toBe(stamp);
  });

  it('REGRESSION: a re-check of the same name that answers "taken" closes the gate again', async () => {
    // The other side of the re-check. An answer, any answer, replaces the
    // outage: once the server says "taken" for this very name, the failure
    // from before must not keep Skip and Next open.
    let calls = 0;
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        calls += 1;
        if (calls === 1) throw new Error('name check unavailable');
        return { available: false, message: '' };
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(2), { timeout: 4000 });
    await waitFor(() => expect(screen.getByText('displayNameTaken')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
  });

  it('REGRESSION: a re-check of the same name does not close the outage exit', async () => {
    // The debounced check depends on next-intl's `t`, which is not
    // referentially stable, so it re-runs with no keystroke (the echo
    // translator in this file reproduces that). Clearing the flag when a check
    // STARTS would disable Skip and Next for the whole request, up to the
    // client's 30 s timeout on a hanging endpoint, without the person touching
    // anything. The flag only changes when a check answers.
    let calls = 0;
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        calls += 1;
        if (calls === 1) throw new Error('name check unavailable');
        return new Promise(() => {}); // later checks hang, like the outage
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());
    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(2), { timeout: 4000 });

    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeEnabled();
  });

  it('REGRESSION: the form hatch stays off the email step, even when that step is showing an error', async () => {
    // The banner is shared by step 0 and the form steps, so an unscoped hatch
    // inside it renders a SECOND sign-out next to the address-worded one, in
    // the commonest state of step 0 (a wrong or expired code), and reports the
    // departure as coming from the form. That would corrupt the only dimension
    // the event has. The other step-0 cases render no error, so they are blind
    // to this; this one puts an error on screen first.
    mockStatus({ verified: false });
    const rejected: any = new Error('invalid');
    rejected.error = 'invalid_code';
    mocks.apiPost.mockImplementation(async (path: string) => {
      if (path === '/auth/email/verify-code') throw rejected;
      return {};
    });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    fireEvent.paste(screen.getAllByRole('textbox')[0], {
      clipboardData: { getData: () => '000000' },
    });

    expect(await screen.findByText('emailVerification.invalidCode')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: WRONG_EMAIL })).toHaveLength(1);
  });

  it('REGRESSION: a failed Next earns the same way out as a failed Skip', async () => {
    // Two call sites again. Covering only the skip arm left the save arm free
    // to stop marking the person as stranded, with the whole suite green, and
    // Next is the path most accounts take.
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(new Error('save unavailable'));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();

    const next = screen.getByRole('button', { name: /^next$/ });
    await waitFor(() => expect(next).toBeEnabled());
    fireEvent.click(next);

    const hatch = await screen.findByRole('button', { name: SIGN_OUT });
    fireEvent.click(hatch);
    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track).toHaveBeenCalledWith('onboarding_signed_out', {
      signed_out_from: 'form_error',
    });
  });

  it('REGRESSION: the form hatch survives the retry that clears the banner', async () => {
    // The retry is the moment the person most needs the way out, and it is the
    // moment a hatch rendered from `error` disappears: the save clears `error`
    // on entry and `saving` disables Skip and Next, so for the length of the
    // request the page has no control at all. That is the closed room again,
    // on this fix's own retry path.
    let releaseSave: () => void = () => {};
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValueOnce(new Error('skip unavailable'));
    mocks.apiPost.mockImplementationOnce(() => new Promise((_, reject) => {
      releaseSave = () => reject(new Error('still unavailable'));
    }));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);
    expect(await screen.findByRole('button', { name: SIGN_OUT })).toBeInTheDocument();

    // Retry: the banner goes and the button turns into its saving state, so
    // neither the message nor Next is on screen while the request hangs.
    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));
    await waitFor(() => expect(screen.queryByText('skip unavailable')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: /saving/ })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /^next$/ })).not.toBeInTheDocument();
    // The hatch is the only thing left, which is exactly why it must not be
    // rendered from the banner that just vanished.
    expect(screen.getByRole('button', { name: SIGN_OUT })).toBeEnabled();

    releaseSave();
    await waitFor(() => expect(screen.getByRole('button', { name: SIGN_OUT })).toBeEnabled());
  });

  it('REGRESSION: two clicks in one frame produce one sign-out, before any re-render can help', async () => {
    // The disabled state only arrives after React re-renders. Two clicks inside
    // the same batch both see the old state, which is what a real double-click
    // on a slow redirect does, so the in-flight ref is the guard that has to
    // hold. Fired inside one `act` for exactly that reason: `fireEvent` twice
    // flushes in between and would pass on the disabled attribute alone.
    mocks.logout.mockImplementation(() => new Promise<void>(() => {}));
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    const hatch = screen.getByRole('button', { name: WRONG_EMAIL });
    act(() => {
      hatch.click();
      hatch.click();
    });

    expect(mocks.logout).toHaveBeenCalledTimes(1);
    expect(mocks.track.mock.calls.filter(([name]) => name === 'onboarding_signed_out'))
      .toHaveLength(1);
  });

  it('REGRESSION: a name typed after an outage is not treated as already checked', async () => {
    // The flag belongs to the name that failed, not to the field. The check is
    // debounced by half a second, so until it runs a fresh name would inherit
    // the previous one's verdict and both controls would be live on something
    // nothing has ever answered for. Asserted synchronously, with no waitFor,
    // because the whole defect lives inside that window.
    mocks.apiGet.mockImplementation(async (path: string) => {
      if (path === '/auth/email/status') return { verified: true };
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, currentStep: 1, displayName: 'Jane', profession: 'sales' };
      }
      if (path.startsWith('/auth-service/api/onboarding/check-display-name')) {
        throw new Error('name check unavailable');
      }
      throw new Error(`unexpected GET ${path}`);
    });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'skipForNow' })).toBeEnabled());

    fireEvent.change(screen.getByDisplayValue('Jane'), { target: { value: 'Brandnewname' } });
    expect(screen.getByRole('button', { name: 'skipForNow' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^next$/ })).toBeDisabled();
  });

  it('REGRESSION: a failed sign-out from a form step survives the next thing the person does', async () => {
    // Nested inside the error banner, this message was unreachable in its own
    // scenario: a save clears `error` on entry, so clicking Next while the
    // redirect was still pending removed the banner, and the rejection a moment
    // later had nowhere to land. The one control meant not to be a dead click
    // became one.
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValueOnce(new Error('skip unavailable'));
    mocks.logout.mockRejectedValue(new Error('redirect blocked'));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    fireEvent.click(await screen.findByRole('button', { name: SIGN_OUT }));
    expect(await screen.findByText('signOutFailed')).toBeInTheDocument();

    // Whatever clears the banner next must not take this with it.
    fireEvent.click(screen.getByRole('button', { name: /^next$/ }));
    expect(screen.getByText('signOutFailed')).toBeInTheDocument();
  });

  it('REGRESSION: the error-card hatch shows the pending redirect rather than swallowing the click', async () => {
    // One case per hatch. They are three separate elements with three separate
    // `disabled` expressions, and a shared case would not say which broke.
    let release: () => void = () => {};
    mocks.logout.mockImplementation(() => new Promise<void>((resolve) => { release = resolve; }));
    mockStatus({ verified: true, statusFails: true });
    renderPage();

    expect(await screen.findByText('errorTitle')).toBeInTheDocument();
    const cardHatch = screen.getByRole('button', { name: SIGN_OUT });
    fireEvent.click(cardHatch);
    await waitFor(() => expect(cardHatch).toBeDisabled());
    release();
  });

  it('REGRESSION: the form hatch shows the pending redirect rather than swallowing the click', async () => {
    let release: () => void = () => {};
    mocks.logout.mockImplementation(() => new Promise<void>((resolve) => { release = resolve; }));
    mockStatus({ verified: true });
    mocks.apiPost.mockRejectedValue(new Error('skip unavailable'));
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    const skip = screen.getByRole('button', { name: 'skipForNow' });
    await waitFor(() => expect(skip).toBeEnabled());
    fireEvent.click(skip);

    const formHatch = await screen.findByRole('button', { name: SIGN_OUT });
    fireEvent.click(formHatch);
    await waitFor(() => expect(formHatch).toBeDisabled());
    release();
  });

  it('SCOPING: neither hatch reaches the profile steps, which have Skip and Next of their own', async () => {
    mockStatus({ verified: true });
    renderPage();

    expect(await screen.findByText('step1.title')).toBeInTheDocument();
    expect(screen.queryByText('emailVerification.title')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: WRONG_EMAIL })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();
    // And no code was sent, since the email step was never entered.
    expect(mocks.apiPost).not.toHaveBeenCalled();
  });

  it('SCOPING: the neutral wording does not leak onto the email step either', async () => {
    // The two hatches are worded for their own screen. Moving the error card's
    // group into the shared layout would render both here, with nothing failing.
    mockStatus({ verified: false });
    renderPage();

    expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SIGN_OUT })).not.toBeInTheDocument();
  });

  it('REGRESSION: a blocked store does not blank the page before anyone can reach the way out', async () => {
    // The mount effect reads the cooldown stamp. Unguarded, a tab with site
    // data blocked threw inside the effect that draws the step, and the page
    // rendered nothing at all: the hatch this file adds was unreachable in
    // exactly the browser configuration most likely to need it.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    try {
      mockStatus({ verified: false });
      renderPage();

      expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: WRONG_EMAIL })).toBeInTheDocument();
      // And the step still works: with no readable stamp, the code is sent.
      await waitFor(() => expect(mocks.apiPost).toHaveBeenCalledWith('/auth/email/send-code', {}));
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('REGRESSION: a blocked store does not strand a CORRECT code on the success screen', async () => {
    // The nastiest of the three storage sites. The clear runs after the success
    // state is set, so an unguarded throw skipped the timer that advances to
    // step 1. The success branch replaces the whole step body, hatch included,
    // so a person who typed the right code was left on "Email verified!" with
    // no control at all, rescuable only by reloading.
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    try {
      mockStatus({ verified: false });
      renderPage();

      expect(await screen.findByText('emailVerification.title')).toBeInTheDocument();
      fireEvent.paste(screen.getAllByRole('textbox')[0], {
        clipboardData: { getData: () => '123456' },
      });

      expect(await screen.findByText('emailVerification.verified')).toBeInTheDocument();
      // It really advances, rather than sitting on the success screen forever.
      expect(await screen.findByText('step1.title', undefined, { timeout: 5000 }))
        .toBeInTheDocument();
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('REGRESSION: a store that refuses to remember the send does not report the send as failed', async () => {
    // The stamp is a convenience; the code really was sent. Sharing the send's
    // try/catch, a storage throw set an error, and the error suppresses the
    // "Code sent!" line: the screen denied something that had just happened.
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    try {
      mockStatus({ verified: false });
      renderPage();

      expect(await screen.findByText('emailVerification.codeSent')).toBeInTheDocument();
      expect(mocks.apiPost).toHaveBeenCalledWith('/auth/email/send-code', {});
    } finally {
      vi.restoreAllMocks();
    }
  });
});
