// @vitest-environment jsdom
/**
 * A partner's offer page (/offer/<token>): the partner's choice in front, the client free to
 * change it and to get it back in one click, and a checkout that only opens once the account is
 * known, is not already paying, and the partner's code has been applied.
 */
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import { calcPrice, CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX } from '@/lib/billing/pricing-constants';
import { PENDING_REWARD_CODE_KEY } from '@/lib/lifecycle/pendingRewardCode';
import { markOfferResume, OFFER_RESUME_KEY } from '@/lib/partners/offerResume';
import type { PublicPartnerOffer } from '@/lib/partners/publicPartnerOffer';
import { ApiError } from '@/lib/api/api-client';

const state = vi.hoisted(() => ({
  auth: { isAuthenticated: false, isReady: false, numericUserId: null as number | null, isLoading: false, user: null as { sub: string } | null },
  subscription: null as unknown,
  subscriptionLoading: false,
  subscriptionError: null as string | null,
}));
const forceLoadSubscription = vi.fn();
const loginWithRedirect = vi.fn();
const createSubscription = vi.fn();
const assignLocation = vi.fn();
const redeem = vi.fn();
const track = vi.fn();

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ ...state.auth, loginWithRedirect }),
  useOptionalAuth: () => state.auth,
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({
    subscription: state.subscription, isLoading: state.subscriptionLoading, error: state.subscriptionError,
    createSubscription, forceLoadSubscription,
  }),
}));
vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (url: string) => assignLocation(url) }));
vi.mock('@/lib/api/services/reward-api.service', () => ({ rewardApi: { redeem: (code: string) => redeem(code) } }));
// The marketplace card needs the app router; what it draws is OfferAppsSection's own test.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({ publication }: { publication: { title: string } }) => <div>{publication.title}</div>,
}));
vi.mock('next/link', () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a>,
}));

import { choiceFromSearch, offerReturnPath, PartnerOfferView } from '../PartnerOfferView';

const TEAM_3 = CREDIT_TIERS[3].toLocaleString('en');
const usd = (n: number) => `$${n.toLocaleString('en')}`;

function offer(overrides: Partial<PublicPartnerOffer> = {}): PublicPartnerOffer {
  return {
    token: 'Abc23XyZ9k', code: 'NORTHWIND', credits: 8000, plan: 'team', creditTier: 3, cycle: 'yearly',
    partner: { name: 'Northwind Studio', handle: 'northwind', avatarUrl: null, tier: 'gold', verified: false }, apps: [], appsPlan: null,
    ...overrides,
  };
}

let client: QueryClient;
function renderOffer(o: PublicPartnerOffer = offer()) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerOfferView offer={o} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}
const text = (id: string) => screen.getByTestId(id).textContent ?? '';
const signedIn = (planCode = 'FREE', providerSubscriptionId: string | null = planCode === 'FREE' ? null : 'sub_1') => {
  state.auth = { isAuthenticated: true, isReady: true, numericUserId: 42, isLoading: false, user: { sub: 'user-42' } };
  state.subscription = { subscription: { planCode, providerSubscriptionId } };
};

beforeAll(() => {
  class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }));
});

beforeEach(() => {
  HTMLElement.prototype.scrollTo = vi.fn();
  // jsdom lays nothing out: a rail showing two 316px cards of three, so there is a slide to make.
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.dataset.testid === 'plan-grid' ? 700 : 0;
  });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.hasAttribute('data-plan-cell') ? 316 : 0;
  });
  window.history.replaceState(null, '', '/offer/Abc23XyZ9k');
  window.localStorage.clear();
  state.auth = { isAuthenticated: false, isReady: false, numericUserId: null, isLoading: false, user: null };
  state.subscription = null;
  state.subscriptionLoading = false;
  state.subscriptionError = null;
  forceLoadSubscription.mockReset();
  loginWithRedirect.mockReset();
  createSubscription.mockReset().mockResolvedValue({ url: 'https://checkout.stripe.com/c/pay/cs_1' });
  assignLocation.mockReset();
  redeem.mockReset().mockResolvedValue({ status: 'APPLIED' });
  track.mockReset();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('PartnerOfferView: the partner choice in front', () => {
  it('names the partner, puts their plan first with its price, and states the credits their code gives', () => {
    renderOffer();

    expect(text('partner-offer-partner')).toContain('Northwind Studio');
    expect(text('partner-offer-partner')).toContain('@northwind');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Northwind Studio recommends Team for you');
    expect(text('partner-offer-gift')).toBe('Plus 8,000 free credits with the code NORTHWIND');
    expect(screen.getByTestId('offer-plan-team').getAttribute('data-recommended')).toBe('true');
    expect(within(screen.getByTestId('offer-plan-team')).getByTestId('offer-recommended-badge').textContent)
      .toBe('Recommended by Northwind Studio');
    expect(screen.getByTestId('offer-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
    expect((screen.getByTestId('offer-credits') as HTMLSelectElement).value).toBe('3');
    expect(text('offer-summary')).toContain(`Team with ${TEAM_3} credits a month`);
    expect(text('offer-price')).toBe(`${usd(calcPrice('team', 'yearly', 3))} a month, billed yearly`);
    // The partner's choice is the current one: nothing to reset.
    expect(screen.queryByTestId('offer-reset')).toBeNull();
    // The rail opens on Team (third card, two steps of 316px) at once.
    expect(screen.getByTestId('plan-grid').scrollTo).toHaveBeenCalledWith({ left: 632, behavior: 'auto' });
  });

  it('a partner whose profile is private is not named, and the page still makes the offer', () => {
    renderOffer(offer({ partner: null }));

    expect(screen.queryByTestId('partner-offer-partner')).toBeNull();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Your partner recommends Team for you');
    expect(screen.getByTestId('offer-recommended-badge').textContent).toBe('Recommended by your partner');
  });

  it('when another code is already waiting (first code wins), this code\'s credits are not promised', () => {
    window.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'TECHDOX', savedAt: Date.now() }));

    renderOffer();

    expect(screen.queryByTestId('partner-offer-gift')).toBeNull();
  });

  it('regression: a signed-in visitor has the code applied on arrival (stored before the redeemer reads the store)', async () => {
    signedIn();
    renderOffer();

    // The redeemer on the page reads the store on its first render: stored in an effect, the code
    // came after it, and nothing was applied until the next page load.
    await waitFor(() => expect(redeem).toHaveBeenCalledWith('NORTHWIND'));
    expect(createSubscription).not.toHaveBeenCalled();
  });
});

describe('PartnerOfferView: changing the choice and getting the partner\'s back', () => {
  it('the client changes cycle and credits, sees the new price, and one click brings the partner choice back', () => {
    renderOffer();
    const rail = screen.getByTestId('plan-grid');

    fireEvent.click(screen.getByTestId('offer-cycle-monthly'));
    fireEvent.change(screen.getByTestId('offer-credits'), { target: { value: '5' } });

    expect(text('offer-price')).toBe(`${usd(calcPrice('team', 'monthly', 5))} a month`);
    expect(text('offer-reset')).toBe("Back to Northwind Studio's choice");

    fireEvent.click(screen.getByTestId('offer-reset'));

    expect(screen.getByTestId('offer-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
    expect((screen.getByTestId('offer-credits') as HTMLSelectElement).value).toBe('3');
    expect(text('offer-price')).toBe(`${usd(calcPrice('team', 'yearly', 3))} a month, billed yearly`);
    expect(screen.queryByTestId('offer-reset')).toBeNull();
    // ...and the rail slides back to the partner's plan.
    expect(rail.scrollTo).toHaveBeenLastCalledWith({ left: 632, behavior: 'smooth' });
  });

  it('above its credit cap Starter is shown but cannot be chosen, and says why', () => {
    renderOffer();
    // 250,000 credits: above Starter's 100,000.
    fireEvent.change(screen.getByTestId('offer-credits'), { target: { value: '5' } });

    const starter = screen.getByTestId('offer-plan-starter');
    expect(within(starter).queryByTestId('offer-choose-starter')).toBeNull();
    expect(starter.textContent).toContain('Starter goes up to 100,000 credits a month');
    // Shown at the most it sells, never at a price for credits it does not offer.
    expect(starter.textContent).toContain(usd(calcPrice('starter', 'yearly', 4)));
    expect(starter.textContent).not.toContain(usd(calcPrice('starter', 'yearly', 5)));
    expect(starter.textContent).toContain('100,000 credits a month');
    expect(within(screen.getByTestId('offer-plan-pro')).getByTestId('offer-choose-pro')).toBeTruthy();
  });
});

describe('PartnerOfferView: paying', () => {
  it('a visitor is sent to sign in, and comes back here with the choice they made and "carry on"', async () => {
    renderOffer();
    fireEvent.click(screen.getByTestId('offer-cycle-monthly'));

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(loginWithRedirect).toHaveBeenCalledWith({
      appState: { returnTo: '/offer/Abc23XyZ9k?plan=team&tier=3&cycle=monthly&continue=1' },
    }));
    expect(createSubscription).not.toHaveBeenCalled();
  });

  it('a signed-in account on the free plan applies the partner code, then opens the checkout for its choice', async () => {
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(redeem).toHaveBeenCalledWith('NORTHWIND');
    expect(redeem.mock.invocationCallOrder[0]).toBeLessThan(createSubscription.mock.invocationCallOrder[0]);
    expect(createSubscription).toHaveBeenCalledWith({ planCode: 'TEAM', billingCycle: 'yearly', creditTierIndex: '3', partnerOfferToken: 'Abc23XyZ9k' });
    // Applied once, even though the notice runs the same redeem.
    expect(redeem).toHaveBeenCalledTimes(1);
  });

  it('regression: a redeem still in flight is waited for, never raced, before the checkout opens', async () => {
    let finish: (v: unknown) => void = () => {};
    redeem.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));
    await act(async () => {});
    expect(createSubscription).not.toHaveBeenCalled();
    expect(text('offer-continue')).toBe('Opening the payment...');

    await act(async () => { finish({ status: 'APPLIED' }); });
    await waitFor(() => expect(createSubscription).toHaveBeenCalled());
  });

  it('a code the server refuses for good (already attributed, own code) does not block paying', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    redeem.mockRejectedValue(new ApiError('used', 409, 'ALREADY_ATTRIBUTED'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalled());
  });

  it('a redeem that fails for a passing reason stops before paying, says so, and the next click tries again', async () => {
    redeem.mockRejectedValueOnce(new Error('network'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));
    expect((await screen.findByRole('alert')).textContent).toBe('The payment page could not be opened. Try again.');
    expect(createSubscription).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('offer-continue'));
    await waitFor(() => expect(assignLocation).toHaveBeenCalled());
    expect(redeem).toHaveBeenCalledTimes(2);
  });

  it('a checkout that cannot be created is said, not swallowed', async () => {
    createSubscription.mockResolvedValue({});
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    expect((await screen.findByRole('alert')).textContent).toBe('The payment page could not be opened. Try again.');
    expect(assignLocation).not.toHaveBeenCalled();
  });

  it('choosing another plan card pays for that plan, with the credits and cycle shown', async () => {
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-choose-pro'));

    await waitFor(() => expect(createSubscription).toHaveBeenCalledWith({ planCode: 'PRO', billingCycle: 'yearly', creditTierIndex: '3', partnerOfferToken: 'Abc23XyZ9k' }));
  });

  it('an account that already pays opens no checkout here: it is sent to its pricing page with the offer preset', () => {
    signedIn('PRO');
    renderOffer();

    expect(screen.queryByTestId('offer-continue')).toBeNull();
    // The plan cards do not open a checkout either.
    expect((screen.getByTestId('offer-choose-pro') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByTestId('offer-choose-team') as HTMLButtonElement).disabled).toBe(true);
    const link = within(screen.getByTestId('offer-already-paying')).getByRole('link');
    const url = new URL(link.getAttribute('href') ?? '', 'https://x.ai');
    expect(url.pathname).toBe('/app/settings/pricing');
    expect(url.searchParams.get('lc_ref')).toBe('NORTHWIND');
    expect(url.searchParams.get('lc_rec')).toBe('team.3.yearly');
  });

  it('nothing is decided while the account is being read: the button waits', () => {
    state.auth = { isAuthenticated: true, isReady: true, numericUserId: 42, isLoading: false, user: { sub: 'user-42' } };
    state.subscriptionLoading = true;
    renderOffer();

    expect((screen.getByTestId('offer-continue') as HTMLButtonElement).disabled).toBe(true);
    // Waiting for the account is not opening a payment: the label says what the button will do.
    expect(text('offer-continue')).toBe('Continue with Team');
    expect(screen.queryByTestId('offer-already-paying')).toBeNull();
    expect(screen.queryByTestId('offer-account-error')).toBeNull();
  });
});

describe('PartnerOfferView: back from sign-in', () => {
  it('regression: carries on to the checkout once, with the choice the visitor made before signing in, not the partner choice', async () => {
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=pro&tier=2&cycle=monthly&continue=1');
    markOfferResume(window, 'Abc23XyZ9k');
    signedIn();
    const { rerender } = renderOffer();

    await waitFor(() => expect(createSubscription).toHaveBeenCalledWith({ planCode: 'PRO', billingCycle: 'monthly', creditTierIndex: '2', partnerOfferToken: 'Abc23XyZ9k' }));
    // "Carry on" is used once: Back from the checkout lands on the offer with the choice, not on Stripe again.
    expect(window.location.search).toBe('?plan=pro&tier=2&cycle=monthly');
    rerender(
      <QueryClientProvider client={client}>
        <NextIntlClientProvider locale="en" messages={enMessages}>
          <PartnerOfferView offer={offer()} />
        </NextIntlClientProvider>
      </QueryClientProvider>,
    );
    await act(async () => {});
    expect(createSubscription).toHaveBeenCalledTimes(1);
  });

  it('without "carry on", the choice is restored and nothing is paid', async () => {
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=pro&tier=2&cycle=monthly');
    signedIn();
    renderOffer();
    await act(async () => {});

    expect(text('offer-summary')).toContain(`Pro with ${CREDIT_TIERS[2].toLocaleString('en')} credits a month`);
    expect(screen.getByTestId('offer-reset')).toBeTruthy();
    expect(createSubscription).not.toHaveBeenCalled();
  });

  it('waits for the account to be fully known (its numeric id) before carrying on', async () => {
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=team&tier=3&cycle=yearly&continue=1');
    markOfferResume(window, 'Abc23XyZ9k');
    state.auth = { isAuthenticated: true, isReady: false, numericUserId: null, isLoading: true, user: { sub: 'user-42' } };
    state.subscription = { subscription: { planCode: 'FREE' } };
    renderOffer();
    await act(async () => {});

    expect(createSubscription).not.toHaveBeenCalled();
    expect(redeem).not.toHaveBeenCalled();
  });

  it('an account that already pays is not carried to a checkout', async () => {
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=team&tier=3&cycle=yearly&continue=1');
    markOfferResume(window, 'Abc23XyZ9k');
    signedIn('TEAM');
    renderOffer();
    await act(async () => {});

    expect(createSubscription).not.toHaveBeenCalled();
  });
});

describe('PartnerOfferView: Starter cap, pricing link, unreadable subscription, unverified email', () => {
  it('regression: a Starter choice given more credits than Starter sells moves to Pro, never a price Starter does not have', () => {
    renderOffer(offer({ plan: 'starter', creditTier: 2, cycle: 'monthly' }));

    fireEvent.change(screen.getByTestId('offer-credits'), { target: { value: '5' } });

    expect(text('offer-summary')).toContain('Pro with 250,000 credits a month');
    expect(text('offer-price')).toBe(`${usd(calcPrice('pro', 'monthly', 5))} a month`);
    expect((screen.getByTestId('offer-continue') as HTMLButtonElement).disabled).toBe(false);
    // Back to the partner's choice is one click, as ever.
    fireEvent.click(screen.getByTestId('offer-reset'));
    expect(text('offer-summary')).toContain('Starter with 25,000 credits a month');
  });

  it('regression: the link to the pricing page opens on the visitor selection and still names the partner choice', () => {
    renderOffer();
    fireEvent.click(screen.getByTestId('offer-cycle-monthly'));

    const href = screen.getByRole('link', { name: 'See every plan' }).getAttribute('href') ?? '';
    const url = new URL(href, 'https://x.ai');
    expect(url.searchParams.get('planCode')).toBe('TEAM');
    expect(url.searchParams.get('billingCycle')).toBe('monthly');
    // lc_rec is what the pricing page vouches for as the partner's recommendation.
    expect(url.searchParams.get('lc_rec')).toBe('team.3.yearly');
  });

  it('regression: an account that cannot be read says so and offers to retry, instead of "opening" for ever', () => {
    state.auth = { isAuthenticated: true, isReady: true, numericUserId: 42, isLoading: false, user: { sub: 'user-42' } };
    state.subscription = null;
    state.subscriptionError = 'HTTP_503';
    renderOffer();

    const button = screen.getByTestId('offer-continue') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Continue with Team');
    expect(text('offer-account-error')).toContain('Your account could not be read');

    fireEvent.click(within(screen.getByTestId('offer-account-error')).getByRole('button', { name: 'Try again' }));
    expect(forceLoadSubscription).toHaveBeenCalledTimes(1);
  });

  it('regression: a code kept back for an unverified email sends the client to onboarding, never to an unattributed payment', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    redeem.mockRejectedValue(new ApiError('verify', 403, 'EMAIL_NOT_VERIFIED'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('/en/onboarding'));
    expect(createSubscription).not.toHaveBeenCalled();
    // ...and onboarding brings them back here, carrying on with the same choice.
    const saved = JSON.parse(window.localStorage.getItem('lc_post_onboarding_return_v1') ?? '{}');
    expect(saved.path).toBe('/offer/Abc23XyZ9k?plan=team&tier=3&cycle=yearly&continue=1');
    expect(saved.owner).toBe('user-42');
  });

  it('regression: the server refusing the checkout for an unverified email also sends the client through onboarding, back here', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    createSubscription.mockRejectedValue(new ApiError('verify', 409, 'email_not_verified'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('/en/onboarding'));
    expect(assignLocation).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(OFFER_RESUME_KEY)).toContain('Abc23XyZ9k');
    const saved = JSON.parse(window.localStorage.getItem('lc_post_onboarding_return_v1') ?? '{}');
    expect(saved.path).toBe('/offer/Abc23XyZ9k?plan=team&tier=3&cycle=yearly&continue=1');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('regression: going to pay drops the onboarding app suggestions, so they never stack on the offer\'s welcome', async () => {
    window.sessionStorage.setItem('lc_show_app_suggestions', '1');
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(window.sessionStorage.getItem('lc_show_app_suggestions')).toBeNull();
  });

  it('an offer the partner took down since the page loaded opens no payment: the page reloads and says so', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    createSubscription.mockRejectedValue(new ApiError('gone', 409, 'offer_unavailable'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('/offer/Abc23XyZ9k'));
    expect(assignLocation).toHaveBeenCalledTimes(1);
  });

  it('any other checkout failure is said, and the client can try again', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    createSubscription.mockRejectedValue(new ApiError('boom', 500, 'HTTP_500'));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    expect((await screen.findByRole('alert')).textContent).toBe('The payment page could not be opened. Try again.');
    expect(assignLocation).not.toHaveBeenCalled();
  });
});

describe('PartnerOfferView: blocked storage, account loading, funnel tracking, credit list', () => {
  it('regression: with storage blocked, the code the page remembered is still applied before paying', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    signedIn();
    renderOffer();
    // The gift is promised: the page knows the code applies...
    expect(screen.getByTestId('partner-offer-gift')).toBeTruthy();

    fireEvent.click(screen.getByTestId('offer-continue'));

    // ...so it must be the one applied, before the checkout opens.
    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(redeem).toHaveBeenCalledWith('NORTHWIND');
    expect(redeem.mock.invocationCallOrder[0]).toBeLessThan(createSubscription.mock.invocationCallOrder[0]);
  });

  it('regression: an account whose id never came says so and reloads on retry, instead of "opening" for ever', () => {
    state.auth = { isAuthenticated: true, isReady: true, numericUserId: null, isLoading: false, user: { sub: 'user-42' } };
    state.subscription = { subscription: { planCode: 'FREE' } };
    renderOffer();

    const button = screen.getByTestId('offer-continue') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toBe('Continue with Team');
    expect(text('offer-account-error')).toContain('Your account could not be read');

    fireEvent.click(within(screen.getByTestId('offer-account-error')).getByRole('button', { name: 'Try again' }));
    expect(assignLocation).toHaveBeenCalledWith(window.location.href);
    expect(forceLoadSubscription).not.toHaveBeenCalled();
  });

  it('tracks the offer funnel on the pricing events, marked as coming from a partner offer', async () => {
    signedIn();
    renderOffer();
    fireEvent.click(screen.getByTestId('offer-cycle-monthly'));

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalled());
    expect(track).toHaveBeenCalledWith('pricing_plan_clicked', expect.objectContaining({
      plan_id: 'team', billing_cycle: 'monthly', outcome: 'checkout', source: 'partner_offer', is_partner_choice: false,
    }));
    expect(track).toHaveBeenCalledWith('checkout_started', expect.objectContaining({
      plan_code: 'TEAM', billing_cycle: 'monthly', credit_tier_index: 3, outcome: 'redirect', source: 'partner_offer',
    }));
  });

  it('a visitor click is tracked before the sign-in redirect unloads the page', async () => {
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(loginWithRedirect).toHaveBeenCalled());
    expect(track).toHaveBeenCalledWith('pricing_plan_clicked', expect.objectContaining({ outcome: 'sign_in', is_partner_choice: true }));
    expect(track.mock.invocationCallOrder[0]).toBeLessThan(loginWithRedirect.mock.invocationCallOrder[0]);
  });

  it('the largest credit tiers stay out of the list, as on the pricing page, unless the partner chose one', () => {
    renderOffer();
    expect((screen.getByTestId('offer-credits') as HTMLSelectElement).options).toHaveLength(DEFAULT_MAX_TIER_INDEX + 1);
    cleanup();

    renderOffer(offer({ plan: 'pro', creditTier: CREDIT_TIERS.length - 2 }));
    expect((screen.getByTestId('offer-credits') as HTMLSelectElement).options).toHaveLength(CREDIT_TIERS.length);
  });
});

describe('PartnerOfferView: a code already waiting, and the page restored from the cache', () => {
  it('regression: with an older code waiting (first code wins), that code is the one applied before paying, never this one', async () => {
    window.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'TECHDOX', savedAt: Date.now() }));
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(redeem).toHaveBeenCalledWith('TECHDOX');
    expect(redeem).not.toHaveBeenCalledWith('NORTHWIND');
    expect(redeem.mock.invocationCallOrder[0]).toBeLessThan(createSubscription.mock.invocationCallOrder[0]);
  });

  it('regression: a page restored from the browser cache after leaving for the checkout gets its buttons back', async () => {
    signedIn();
    renderOffer();
    fireEvent.click(screen.getByTestId('offer-continue'));
    await waitFor(() => expect(assignLocation).toHaveBeenCalled());
    expect(text('offer-continue')).toBe('Opening the payment...');

    act(() => {
      const restored = new Event('pageshow') as PageTransitionEvent;
      Object.defineProperty(restored, 'persisted', { value: true });
      window.dispatchEvent(restored);
    });

    expect(text('offer-continue')).toBe('Continue with Team');
    expect((screen.getByTestId('offer-continue') as HTMLButtonElement).disabled).toBe(false);
  });

  it('a first page load (not from the cache) changes nothing', async () => {
    signedIn();
    renderOffer();
    fireEvent.click(screen.getByTestId('offer-continue'));
    await waitFor(() => expect(assignLocation).toHaveBeenCalled());

    act(() => { window.dispatchEvent(new Event('pageshow')); });

    expect(text('offer-continue')).toBe('Opening the payment...');
  });
});

describe('PartnerOfferView: resume guard, an older code refused, the credits promise', () => {
  it('regression: a sign-in page that cannot be reached says so, and leaves no resume marker behind', async () => {
    loginWithRedirect.mockRejectedValue(new Error('discovery failed'));
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    expect((await screen.findByRole('alert')).textContent).toBe('The payment page could not be opened. Try again.');
    expect(window.localStorage.getItem(OFFER_RESUME_KEY)).toBeNull();
  });

  it('regression: a "continue=1" the page did not start opens no checkout, it only restores the choice', async () => {
    // A link written by anyone: no round trip of this page behind it.
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=team&tier=9&cycle=yearly&continue=1');
    signedIn();
    renderOffer();
    await act(async () => {});

    expect(createSubscription).not.toHaveBeenCalled();
    expect(track).not.toHaveBeenCalled();
    expect(text('offer-summary')).toContain('Team with 10,000,000 credits a month');
    expect(window.location.search).toBe('?plan=team&tier=9&cycle=yearly');
  });

  it('the sign-in round trip leaves the marker that lets the page carry on when it comes back', async () => {
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(loginWithRedirect).toHaveBeenCalled());
    expect(JSON.parse(window.localStorage.getItem(OFFER_RESUME_KEY) ?? '{}').token).toBe('Abc23XyZ9k');
  });

  it('an automatic resume is not counted as a click', async () => {
    window.history.replaceState(null, '', '/offer/Abc23XyZ9k?plan=pro&tier=2&cycle=monthly&continue=1');
    markOfferResume(window, 'Abc23XyZ9k');
    signedIn();
    renderOffer();

    await waitFor(() => expect(createSubscription).toHaveBeenCalled());
    expect(track).not.toHaveBeenCalledWith('pricing_plan_clicked', expect.anything());
    expect(track).toHaveBeenCalledWith('checkout_started', expect.objectContaining({ outcome: 'redirect' }));
  });

  it('regression: an older code refused for good gives this offer code its turn, before paying', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    window.localStorage.setItem(PENDING_REWARD_CODE_KEY, JSON.stringify({ code: 'TECHDOX', savedAt: Date.now() }));
    redeem.mockImplementation(async (code: string) => {
      if (code === 'TECHDOX') throw new ApiError('unknown', 404, 'INVALID_CODE');
      return { status: 'APPLIED' };
    });
    signedIn();
    renderOffer();

    fireEvent.click(screen.getByTestId('offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(redeem).toHaveBeenCalledWith('TECHDOX');
    expect(redeem).toHaveBeenCalledWith('NORTHWIND');
    const northwind = redeem.mock.calls.findIndex(([code]) => code === 'NORTHWIND');
    expect(redeem.mock.invocationCallOrder[northwind]).toBeLessThan(createSubscription.mock.invocationCallOrder[0]);
  });

  it('regression: the free credits are not promised to an account the code refuses for good', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    redeem.mockRejectedValue(new ApiError('not new', 409, 'NOT_NEW_ACCOUNT'));
    signedIn();
    renderOffer();

    await waitFor(() => expect(redeem).toHaveBeenCalledWith('NORTHWIND'));
    await waitFor(() => expect(screen.queryByTestId('partner-offer-gift')).toBeNull());
  });

  it('an email still to verify keeps the promise: the code applies once it is verified', async () => {
    const { ApiError } = await import('@/lib/api/api-client');
    redeem.mockRejectedValue(new ApiError('verify', 403, 'EMAIL_NOT_VERIFIED'));
    signedIn();
    renderOffer();

    await waitFor(() => expect(redeem).toHaveBeenCalledWith('NORTHWIND'));
    await act(async () => {});
    expect(screen.getByTestId('partner-offer-gift')).toBeTruthy();
  });

  it('an account that already pays is promised no sign-up credits', () => {
    signedIn('PRO');
    renderOffer();

    expect(screen.queryByTestId('partner-offer-gift')).toBeNull();
  });
});

describe('PartnerOfferView: apps that need a plan, and an account that already pays', () => {
  const app = {
    id: '6f1c0d2e-0000-4000-8000-00000000000a', title: 'Invoice chaser', description: null, publisherId: '42',
    publisherName: 'Northwind Studio', nodeIcons: [], showcaseRunId: null, showcaseInterfaceId: null, visibility: 'PUBLIC' as const,
  };

  it('regression: a plan the offered apps cannot be installed on cannot be paid through the offer, and the page says which plan they need', () => {
    signedIn();
    renderOffer(offer({ plan: 'pro', creditTier: 2, cycle: 'monthly', apps: [app], appsPlan: 'pro' }));

    const starter = screen.getByTestId('offer-plan-starter');
    expect(within(starter).queryByTestId('offer-choose-starter')).toBeNull();
    expect(within(starter).getByText('The apps in this offer need Pro or above.')).toBeTruthy();
    expect(screen.getByTestId('offer-choose-team')).toBeTruthy();
    expect(text('offer-apps-note')).toBe('The apps in this offer need Pro or above.');
  });

  it('a partner choice below what the apps need (the requirement rose since) cannot be paid, and says why', () => {
    signedIn();
    renderOffer(offer({ plan: 'starter', creditTier: 2, cycle: 'monthly', apps: [app], appsPlan: 'pro' }));

    expect((screen.getByTestId('offer-continue') as HTMLButtonElement).disabled).toBe(true);
    expect(text('offer-apps-need-plan')).toBe('The apps in this offer need Pro or above.');
    fireEvent.click(screen.getByTestId('offer-continue'));
    expect(createSubscription).not.toHaveBeenCalled();
  });

  it('the server refusing the plan for the apps says so; an account that now pays is told so and read again', async () => {
    signedIn();
    createSubscription.mockRejectedValueOnce(new ApiError('x', 409, 'offer_plan_too_low'));
    renderOffer(offer({ apps: [app] }));

    fireEvent.click(screen.getByTestId('offer-continue'));
    expect((await screen.findByRole('alert')).textContent).toBe('The apps in this offer need a higher plan. Choose another plan to continue.');
    expect(assignLocation).not.toHaveBeenCalled();

    createSubscription.mockRejectedValueOnce(new ApiError('x', 409, 'offer_already_subscribed'));
    fireEvent.click(screen.getByTestId('offer-continue'));
    // Said, and the account read again (no silent reload into the same button).
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(enMessages.partnerOffer.alreadySubscribed));
    expect(forceLoadSubscription).toHaveBeenCalled();
    expect(assignLocation).not.toHaveBeenCalled();
  });

  it('regression: an account that already pays is not promised the apps: they come with a first subscription through the link', () => {
    signedIn('PRO');
    renderOffer(offer({ apps: [app] }));

    expect(screen.queryByTestId('offer-apps-included')).toBeNull();
    expect(text('offer-apps-note')).toBe(enMessages.partnerOffer.appsNotForSubscribers);
  });

  it('a free account sees the apps included, with no note', () => {
    signedIn();
    renderOffer(offer({ apps: [app] }));

    expect(text('offer-apps-included')).toBe('+ 1 app included');
    expect(screen.queryByTestId('offer-apps-note')).toBeNull();
  });

  it('regression: with apps, no link leads to the price list, which would take the payment without them', () => {
    signedIn();
    renderOffer(offer({ apps: [app] }));
    expect(screen.queryByTestId('offer-all-plans')).toBeNull();
    cleanup();

    signedIn();
    renderOffer(offer());
    expect(screen.getByTestId('offer-all-plans')).toBeTruthy();
  });

  it('"already pays" follows the server: a plan without a Stripe subscription (comped) may still pay through the offer', () => {
    signedIn('PRO', null);
    renderOffer(offer({ apps: [app] }));

    expect(screen.getByTestId('offer-continue')).toBeTruthy();
    expect(screen.queryByTestId('offer-already-paying')).toBeNull();
  });
});

describe('offerReturnPath / choiceFromSearch', () => {
  const fallback = { plan: 'team' as const, creditTier: 3, cycle: 'yearly' as const };

  it('round-trips a choice through the sign-in return', () => {
    const path = offerReturnPath('Abc23XyZ9k', { plan: 'pro', creditTier: 7, cycle: 'monthly' });
    const search = new URLSearchParams(path.split('?')[1]);

    expect(choiceFromSearch(search, fallback)).toEqual({ plan: 'pro', creditTier: 7, cycle: 'monthly' });
    expect(search.get('continue')).toBe('1');
  });

  it('a choice the price list does not sell falls back to the offer', () => {
    for (const q of [
      'plan=enterprise&tier=3&cycle=monthly', 'plan=pro&tier=99&cycle=monthly', 'plan=pro&tier=-1&cycle=monthly',
      'plan=pro&tier=2.5&cycle=monthly', 'plan=pro&tier=3&cycle=weekly', 'plan=starter&tier=6&cycle=monthly', '',
      // A missing tier is not the smallest one (Number('') is 0).
      'plan=pro&cycle=monthly', 'plan=pro&tier=&cycle=monthly', 'plan=pro&tier=+3&cycle=monthly',
    ]) {
      expect(choiceFromSearch(new URLSearchParams(q), fallback), q).toEqual(fallback);
    }
  });
});
