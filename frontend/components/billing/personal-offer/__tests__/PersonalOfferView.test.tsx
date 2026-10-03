// @vitest-environment jsdom
/**
 * The personal offer's full-screen page: the price first, the bonus the first subscription brings,
 * the time left, and a checkout that only opens with the offer attached. It opens on the best
 * value the server's own matrix gives; every state that is not an offer to take is said.
 */
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import { calcPrice, CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { ApiError } from '@/lib/api/api-client';
import type { PersonalOfferCurrent, PersonalOfferPreview } from '@/lib/api/services/reward-api.service';

const state = vi.hoisted(() => ({
  auth: { isAuthenticated: true, isReady: true, numericUserId: 42 as number | null, isLoading: false },
}));
const api = vi.hoisted(() => ({ current: vi.fn(), preview: vi.fn() }));
const createSubscription = vi.fn();
const assignLocation = vi.fn();
const loginWithRedirect = vi.fn();
const track = vi.fn();

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ ...state.auth, loginWithRedirect }),
  useOptionalAuth: () => state.auth,
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({ useSubscription: () => ({ createSubscription }) }));
vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (url: string) => assignLocation(url) }));
const edition = vi.hoisted(() => ({ ce: false }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return edition.ce; } }));
vi.mock('@/lib/api/services/reward-api.service', () => ({
  rewardApi: {
    getCurrentPersonalOffer: () => api.current(),
    previewPersonalOffer: (input: unknown) => api.preview(input),
  },
}));
vi.mock('next/link', () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => <a href={href} {...rest}>{children}</a>,
}));

import { bestValueStep, choiceFromUrl, PersonalOfferView, reservedChoice, statusMessage } from '../PersonalOfferView';
import { remainingParts } from '../OfferCountdown';

const TIER_50K = CREDIT_TIERS.indexOf(50_000);
const TIER_250K = CREDIT_TIERS.indexOf(250_000);
// Two days from whenever the suite runs: a fixed date would turn these tests red once it passes.
const EXPIRES = new Date(Date.now() + 2 * 86_400_000).toISOString();

/** The production matrix (V552): no bonus under 50K, 8K from 50K, 40K from 250K, 80K from 500K; Starter stops at 100K. */
function bonus(plan: string, credits: number): number {
  if (plan === 'STARTER' && credits > 100_000) return -1;
  if (credits >= 500_000) return 80_000;
  if (credits >= 250_000) return 40_000;
  if (credits >= 50_000) return 8_000;
  return 0;
}
function preview(tier: number, cycle: 'monthly' | 'yearly'): PersonalOfferPreview {
  const credits = CREDIT_TIERS[tier];
  const next = CREDIT_TIERS.find((c) => c > credits && bonus('PRO', c) > 0) ?? null;
  return {
    status: 'AVAILABLE', offerId: 19, offerVersion: 1, expiresAt: EXPIRES, monthlyCredits: credits, billingCycle: cycle,
    plans: ['STARTER', 'PRO', 'TEAM'].map((planCode) => {
      const b = bonus(planCode, credits);
      return { planCode, bonusCredits: Math.max(b, 0), paygFaceValueUsd: Math.max(b, 0) / 800, status: b < 0 ? 'UNAVAILABLE' : b > 0 ? 'ELIGIBLE' : 'NO_BONUS' };
    }) as PersonalOfferPreview['plans'],
    nextEligibleMonthlyCredits: bonus('PRO', credits) > 0 ? null : next,
    steps: STEPS,
  };
}
/** The tiers the server reads from that matrix. */
const STEPS = [
  { monthlyCredits: 50_000, bonusCredits: 8_000 },
  { monthlyCredits: 250_000, bonusCredits: 40_000 },
  { monthlyCredits: 500_000, bonusCredits: 80_000 },
];
const available: PersonalOfferCurrent = { status: 'AVAILABLE', offerId: 19, offerVersion: 1, expiresAt: EXPIRES, code: 'JANE-BONUS-72H' };

function renderPage(locale = 'en', messages: object = enMessages) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale={locale} messages={messages}>
        <PersonalOfferView />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}
const text = (id: string) => screen.getByTestId(id).textContent ?? '';
const usd = (n: number) => `$${n.toLocaleString('en')}`;

beforeAll(() => {
  class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }));
});

beforeEach(() => {
  HTMLElement.prototype.scrollTo = vi.fn();
  window.history.replaceState(null, '', '/offer/personal');
  window.sessionStorage.clear();
  edition.ce = false;
  state.auth = { isAuthenticated: true, isReady: true, numericUserId: 42, isLoading: false };
  api.current.mockReset().mockResolvedValue(available);
  api.preview.mockReset().mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) =>
    preview(input.creditTierIndex, input.billingCycle));
  createSubscription.mockReset().mockResolvedValue({ url: 'https://checkout.stripe.com/c/pay/cs_1', offerStatus: 'ATTACHED' });
  assignLocation.mockReset();
  loginWithRedirect.mockReset().mockResolvedValue(undefined);
  track.mockReset();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('PersonalOfferView: the offer, price first', () => {
  it('regression: opens on the best value (Starter, 50,000 credits), never on a pack with no bonus', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());
    expect(text('personal-offer-summary')).toContain('Starter with 50,000 credits a month');
    expect(text('personal-offer-price')).toBe(`${usd(calcPrice('starter', 'monthly', TIER_50K))} a month`);
    expect(text('personal-offer-bonus')).toBe('+8,000 bonus credits (worth $10)');
    // The headline says the most the offer gives; the summary, what this choice gives.
    expect(text('personal-offer-title')).toBe('Up to 80,000 credits on top of your first subscription');
    expect((screen.getByTestId('personal-offer-credits') as HTMLSelectElement).value).toBe(String(TIER_50K));
    expect(screen.getByTestId('personal-offer-plan-starter').dataset.selected).toBe('true');
    // Every tier is drawn, the one this choice reaches pressed.
    expect(screen.getByTestId('personal-offer-tier-50000').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('personal-offer-tier-250000').getAttribute('aria-pressed')).toBe('false');
    expect(text('personal-offer-tier-500000')).toBe('From 500,000 credits a month +80,000 credits');
    // One named group, read as such by a screen reader.
    expect(within(screen.getByRole('group', { name: 'Your bonus grows with the credits you choose' })).getAllByRole('button')).toHaveLength(3);
  });

  it('regression: a tier is one click away, up to the top one (Team at 500,000 credits and above gets 80,000)', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-tier-500000'));

    // Starter does not sell that pack: the choice moves to Pro, the smallest plan that does.
    await waitFor(() => expect(text('personal-offer-bonus')).toBe('+80,000 bonus credits (worth $100)'));
    expect(text('personal-offer-summary')).toContain('Pro with 500,000 credits a month');
    expect(screen.getByTestId('personal-offer-tier-500000').getAttribute('aria-pressed')).toBe('true');
    expect(within(screen.getByTestId('personal-offer-plan-team')).getByTestId('personal-offer-card-bonus-team').textContent).toBe('+80,000 credits offered');
    // A bigger pack stays on the top tier.
    fireEvent.change(screen.getByTestId('personal-offer-credits'), { target: { value: String(CREDIT_TIERS.indexOf(1_000_000)) } });
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Pro with 1,000,000 credits a month'));
    expect(screen.getByTestId('personal-offer-tier-500000').getAttribute('aria-pressed')).toBe('true');
    // No "back to the best value": the tiers are the way between the choices.
    expect(screen.queryByTestId('personal-offer-reset')).toBeNull();
  });

  it('shows the time left as a ticking countdown, and the date it ends', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByTestId('offer-countdown')).toBeTruthy());
    expect(text('personal-offer-until')).toContain('Offer valid until');
    expect(screen.getByTestId('offer-countdown-days').textContent).toMatch(/^\d{2}$/);
  });

  it('a bigger pack moves Starter (capped) to Pro with its own bonus; a tier brings a smaller pack back', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.change(screen.getByTestId('personal-offer-credits'), { target: { value: String(TIER_250K) } });

    await waitFor(() => expect(text('personal-offer-bonus')).toBe('+40,000 bonus credits (worth $50)'));
    expect(text('personal-offer-summary')).toContain('Pro with 250,000 credits a month');
    expect(within(screen.getByTestId('personal-offer-plan-starter')).getByText('Starter goes up to 100,000 credits a month')).toBeTruthy();
    expect(within(screen.getByTestId('personal-offer-plan-team')).getByTestId('personal-offer-card-bonus-team').textContent).toBe('+40,000 credits offered');

    fireEvent.click(screen.getByTestId('personal-offer-tier-50000'));
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Pro with 50,000 credits a month'));
    expect(text('personal-offer-bonus')).toBe('+8,000 bonus credits (worth $10)');
  });

  it('a pack with no bonus says from which pack one comes', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.change(screen.getByTestId('personal-offer-credits'), { target: { value: '0' } });

    await waitFor(() => expect(text('personal-offer-no-bonus')).toBe('Bonus starts at 50,000 credits per month.'));
    // Below the first tier: no tier is pressed, the headline still says what the offer can give.
    expect(text('personal-offer-title')).toBe('Up to 80,000 credits on top of your first subscription');
    expect(screen.getByTestId('personal-offer-tier-50000').getAttribute('aria-pressed')).toBe('false');
    // Paying here spends the one first purchase the offer is for: said before the click, here and on each card.
    expect(text('personal-offer-first-purchase')).toBe(enMessages.reward.personalOffer.firstPurchaseUsed);
    // Two sentences, each its own line: never run together.
    const card = screen.getByTestId('personal-offer-card-no-bonus-pro');
    expect(Array.from(card.querySelectorAll('p')).map((p) => p.textContent))
      .toEqual([enMessages.personalOfferPage.cardNoBonus, enMessages.reward.personalOffer.firstPurchaseUsed]);
  });

  it('regression: back from a cancelled payment (or the pricing page sign-in), the client\'s choice is shown, and every tier is still a click away', async () => {
    window.history.replaceState(null, '', `/offer/personal?planCode=TEAM&creditTierIndex=${TIER_250K}&billingCycle=yearly&checkout=cancelled`);
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.getByTestId('personal-offer-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
    expect(text('personal-offer-annual')).toBe(`Billed annually: ${usd(calcPrice('team', 'yearly', TIER_250K) * 12)}, before tax.`);

    expect(screen.getByTestId('personal-offer-tier-250000').getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByTestId('personal-offer-tier-50000'));
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 50,000 credits a month'));
    // The plan and the cycle the client chose are kept.
    expect(screen.getByTestId('personal-offer-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
  });

  it('the chosen plan the offer does not price says so, not where a bonus would start; the selected card is said to a screen reader', async () => {
    window.history.replaceState(null, '', `/offer/personal?planCode=TEAM&creditTierIndex=${TIER_50K}&billingCycle=monthly`);
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      const p = preview(input.creditTierIndex, input.billingCycle);
      return { ...p, plans: p.plans.map((plan) => (plan.planCode === 'TEAM' ? { ...plan, status: 'UNAVAILABLE', bonusCredits: 0 } : plan)) } as PersonalOfferPreview;
    });
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 50,000 credits a month'));
    await waitFor(() => expect(text('personal-offer-no-bonus')).toBe(enMessages.reward.personalOffer.planUnavailable));
    expect(screen.getByTestId('personal-offer-plan-team').getAttribute('aria-current')).toBe('true');
    expect(screen.getByTestId('personal-offer-plan-pro').getAttribute('aria-current')).toBeNull();
  });

  it('a tier the price list does not sell is never drawn, and the headline is the top of the ones it does', async () => {
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => ({
      ...preview(input.creditTierIndex, input.billingCycle),
      steps: [...STEPS.slice(0, 2), { monthlyCredits: 750_000, bonusCredits: 120_000 }],
    }));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    expect(screen.queryByTestId('personal-offer-tier-750000')).toBeNull();
    expect(text('personal-offer-title')).toBe('Up to 40,000 credits on top of your first subscription');
  });

  it('when no pack gets a bonus at all, the page stays on the smallest one and says so', async () => {
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => ({
      ...preview(input.creditTierIndex, input.billingCycle),
      plans: ['STARTER', 'PRO', 'TEAM'].map((planCode) => ({ planCode, bonusCredits: 0, paygFaceValueUsd: 0, status: 'NO_BONUS' })),
      nextEligibleMonthlyCredits: null,
      steps: [],
    }));
    renderPage();

    await waitFor(() => expect(text('personal-offer-no-bonus')).toBe('No bonus with this pack'));
    expect(text('personal-offer-summary')).toContain(`Starter with ${CREDIT_TIERS[0].toLocaleString('en')} credits a month`);
    expect(text('personal-offer-title')).toBe('Your personal offer');
    expect(api.preview).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('personal-offer-tiers')).toBeNull();
  });

  it('the cycle and each plan card can be changed and chosen; yearly shows the yearly total', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-cycle-yearly'));
    await waitFor(() => expect(api.preview).toHaveBeenCalledWith(expect.objectContaining({ creditTierIndex: TIER_50K, billingCycle: 'yearly' })));
    await waitFor(() => expect(text('personal-offer-price')).toBe(`${usd(calcPrice('starter', 'yearly', TIER_50K))} a month, billed yearly`));
    expect(text('personal-offer-annual')).toBe(`Billed annually: ${usd(calcPrice('starter', 'yearly', TIER_50K) * 12)}, before tax.`);

    fireEvent.click(screen.getByTestId('personal-offer-choose-pro'));
    await waitFor(() => expect(createSubscription).toHaveBeenCalledWith({
      planCode: 'PRO', billingCycle: 'yearly', creditTierIndex: String(TIER_50K), personalOfferId: 19, offerVersion: 1,
    }));
  });

  it('while the new pack is being priced, nothing can be paid on the previous figures', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());
    let release: (value: PersonalOfferPreview) => void = () => {};
    api.preview.mockImplementation((input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) =>
      new Promise<PersonalOfferPreview>((resolve) => { release = () => resolve(preview(input.creditTierIndex, input.billingCycle)); }));

    fireEvent.change(screen.getByTestId('personal-offer-credits'), { target: { value: String(TIER_250K) } });

    await waitFor(() => expect((screen.getByTestId('personal-offer-continue') as HTMLButtonElement).disabled).toBe(true));
    expect(screen.queryByTestId('personal-offer-bonus')).toBeNull();
    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    expect(createSubscription).not.toHaveBeenCalled();

    await act(async () => { release(preview(TIER_250K, 'monthly')); });
    await waitFor(() => expect((screen.getByTestId('personal-offer-continue') as HTMLButtonElement).disabled).toBe(false));
  });

  it('regression: the deadline coming while the page is open is read from the server once reached, and the page says the offer expired', async () => {
    const deadline = Date.now() + 5_000;
    api.current.mockImplementation(async () => ({
      ...available, expiresAt: new Date(deadline).toISOString(), status: Date.now() >= deadline ? 'EXPIRED' : 'AVAILABLE',
    }));
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      if (Date.now() >= deadline) throw new ApiError('x', 409, 'OFFER_EXPIRED');
      return preview(input.creditTierIndex, input.billingCycle);
    });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    expect(await screen.findByTestId('personal-offer-status', {}, { timeout: 12_000 })).toHaveProperty('textContent', enMessages.reward.personalOffer.errors.expired);
  }, 25_000);

  it('regression: the deadline passing while the page already shows the reservation still locks it (no refusal from the server needed)', async () => {
    const deadline = Date.now() + 5_000;
    api.current.mockResolvedValue({
      status: 'CHECKOUT_OPEN', offerId: 19, offerVersion: 1, expiresAt: new Date(deadline).toISOString(),
      sessionExpiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
      reservedPlanCode: 'TEAM', reservedCreditTierIndex: TIER_250K, reservedBillingCycle: 'yearly',
    });
    renderPage();
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.getByTestId('personal-offer-credits')).toBeTruthy();

    await waitFor(() => expect(screen.queryByTestId('personal-offer-credits')).toBeNull(), { timeout: 12_000 });
    expect(text('personal-offer-until')).toContain('Your offer is reserved for this checkout until');
    // A way out of the reservation-only view.
    expect(screen.getByTestId('personal-offer-back-locked').getAttribute('href')).toBe('/en/app/chat');
  }, 25_000);

  it('a reservation wins over a choice carried in the address', async () => {
    window.history.replaceState(null, '', `/offer/personal?planCode=PRO&creditTierIndex=${TIER_50K}&billingCycle=monthly`);
    api.current.mockResolvedValue({
      status: 'CHECKOUT_OPEN', offerId: 19, offerVersion: 1, expiresAt: EXPIRES,
      sessionExpiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
      reservedPlanCode: 'TEAM', reservedCreditTierIndex: TIER_250K, reservedBillingCycle: 'yearly',
    });
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
  });

  it('regression: a clock ahead of the server is caught up: the offer is read again after the deadline until the server says it ended', async () => {
    const deadline = Date.now() + 4_000;
    // The server still says the offer is live for two seconds after this clock's deadline.
    api.current.mockImplementation(async () => ({
      ...available, expiresAt: new Date(deadline).toISOString(), status: Date.now() >= deadline + 2_000 ? 'EXPIRED' : 'AVAILABLE',
    }));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    expect(await screen.findByTestId('personal-offer-status', {}, { timeout: 14_000 })).toHaveProperty('textContent', enMessages.reward.personalOffer.errors.expired);
  }, 25_000);

  it('in French, the numbers, the date and the way out follow the app language', async () => {
    renderPage('fr', frMessages);
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());
    expect(text('personal-offer-summary')).toContain((50_000).toLocaleString('fr'));
    expect(screen.getByTestId('personal-offer-all-plans').getAttribute('href')).toBe('/fr/app/settings/pricing');
    expect(text('personal-offer-until')).toMatch(new RegExp(`${new Date(EXPIRES).getUTCFullYear()}|${new Date(EXPIRES).getFullYear()}`));
    expect(text('personal-offer-until')).not.toMatch(/January|February|March|April|May|June|July|August|September|October|November|December/);
  });

  it('a cancelled payment is counted once, and the marker leaves the address', async () => {
    window.history.replaceState(null, '', `/offer/personal?planCode=PRO&creditTierIndex=${TIER_50K}&billingCycle=monthly&checkout=cancelled`);
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    expect(track).toHaveBeenCalledWith('checkout_returned', expect.objectContaining({ status: 'cancelled', source: 'personal_offer' }));
    expect(track.mock.calls.filter(([name]) => name === 'checkout_returned')).toHaveLength(1);
    expect(new URLSearchParams(window.location.search).get('checkout')).toBeNull();
    expect(new URLSearchParams(window.location.search).get('planCode')).toBe('PRO');
  });

  it('self-hosted: no offer is read, and the page says there is none, signed in or not', async () => {
    edition.ce = true;
    const first = renderPage();
    expect((await screen.findByTestId('personal-offer-status')).textContent).toBe('There is no personal offer on this account right now.');
    first.unmount();

    state.auth = { isAuthenticated: false, isReady: false, numericUserId: null, isLoading: false };
    renderPage();
    expect((await screen.findByTestId('personal-offer-status')).textContent).toBe('There is no personal offer on this account right now.');
    expect(screen.queryByTestId('personal-offer-sign-in')).toBeNull();
    expect(api.current).not.toHaveBeenCalled();
  });
});

describe('PersonalOfferView: paying', () => {
  it('regression: one payment page at a time: a second click while it opens sends nothing', async () => {
    createSubscription.mockReturnValue(new Promise(() => {}));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    fireEvent.click(screen.getByTestId('personal-offer-choose-team'));

    await waitFor(() => expect(text('personal-offer-continue')).toBe('Opening the payment...'));
    expect(createSubscription).toHaveBeenCalledTimes(1);
    // Nor can a tier change the choice being paid.
    expect((screen.getByTestId('personal-offer-tier-500000') as HTMLButtonElement).disabled).toBe(true);
  });

  it('back from the checkout through the page cache, the buttons come back', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());
    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    await waitFor(() => expect(assignLocation).toHaveBeenCalled());
    expect((screen.getByTestId('personal-offer-continue') as HTMLButtonElement).disabled).toBe(true);

    const restored = new Event('pageshow') as PageTransitionEvent;
    Object.defineProperty(restored, 'persisted', { value: true });
    act(() => { window.dispatchEvent(restored); });

    await waitFor(() => expect((screen.getByTestId('personal-offer-continue') as HTMLButtonElement).disabled).toBe(false));
    expect(text('personal-offer-continue')).toBe('Continue with Starter');
  });

  it('a plan the preview does not price is refused in words, never opened', async () => {
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      const p = preview(input.creditTierIndex, input.billingCycle);
      return { ...p, plans: p.plans.filter((plan) => plan.planCode !== 'TEAM') };
    });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-choose-team'));

    expect((await screen.findByRole('alert')).textContent).toBe(enMessages.reward.personalOffer.planUnavailable);
    expect(createSubscription).not.toHaveBeenCalled();
  });

  it('opens the checkout with the offer attached, for the choice in front', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-continue'));

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_1'));
    expect(createSubscription).toHaveBeenCalledWith({
      planCode: 'STARTER', billingCycle: 'monthly', creditTierIndex: String(TIER_50K), personalOfferId: 19, offerVersion: 1,
    });
    expect(track).toHaveBeenCalledWith('checkout_started', expect.objectContaining({ source: 'personal_offer', outcome: 'redirect' }));
  });

  it('regression: a session that does not carry the offer is never paid from here', async () => {
    createSubscription.mockResolvedValue({ url: 'https://checkout.stripe.com/c/pay/cs_plain' });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(enMessages.reward.personalOffer.attachFailed);
    expect(assignLocation).not.toHaveBeenCalled();
  });

  it('regression: another benefit in the way at checkout is said, not "try again" for ever', async () => {
    createSubscription.mockRejectedValueOnce(new ApiError('x', 409, 'OFFER_CONFLICT'));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-continue'));

    expect((await screen.findByRole('alert')).textContent).toBe(enMessages.reward.personalOffer.errors.conflict);
  });

  it('regression: the email link opened while a checkout is open opens on the reservation, even when the account\'s offer arrives after the first preview', async () => {
    window.history.replaceState(null, '', '/offer/personal?lc_offer=JANE-BONUS-72H');
    let releaseCurrent: (v: PersonalOfferCurrent) => void = () => {};
    api.current.mockImplementation(() => new Promise<PersonalOfferCurrent>((resolve) => { releaseCurrent = resolve; }));
    renderPage();
    await waitFor(() => expect(api.preview).toHaveBeenCalled());

    await act(async () => {
      releaseCurrent({
        status: 'CHECKOUT_OPEN', offerId: 19, offerVersion: 1, expiresAt: EXPIRES,
        sessionExpiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
        reservedPlanCode: 'TEAM', reservedCreditTierIndex: TIER_250K, reservedBillingCycle: 'yearly',
      });
    });

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
  });

  it('a refusal the server names is said in words; a stale offer is read again', async () => {
    createSubscription.mockRejectedValueOnce(new ApiError('x', 409, 'OFFER_FIRST_PAYMENT_REQUIRED'));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('personal-offer-summary')).toBeTruthy());

    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    expect((await screen.findByRole('alert')).textContent).toBe(enMessages.reward.personalOffer.errors.firstPaymentRequired);

    const reads = api.current.mock.calls.length;
    createSubscription.mockRejectedValueOnce(new ApiError('x', 409, 'OFFER_PREVIEW_STALE'));
    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    await waitFor(() => expect(api.current.mock.calls.length).toBeGreaterThan(reads));
    expect(screen.getByRole('alert').textContent).toBe('The payment page could not be opened. Try again.');
  });

  it('a signed-out visitor signs in first and comes back to this offer, code included', async () => {
    state.auth = { isAuthenticated: false, isReady: false, numericUserId: null, isLoading: false };
    window.history.replaceState(null, '', '/offer/personal?lc_offer=JANE-BONUS-72H&billingCycle=monthly');
    renderPage();

    fireEvent.click(await screen.findByTestId('personal-offer-sign-in'));

    await waitFor(() => expect(loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/offer/personal?lc_offer=JANE-BONUS-72H' } }));
    expect(api.current).not.toHaveBeenCalled();
  });

  it('a sign-in page that cannot be opened is said, and the button stays', async () => {
    state.auth = { isAuthenticated: false, isReady: false, numericUserId: null, isLoading: false };
    loginWithRedirect.mockRejectedValue(new Error('popup blocked'));
    renderPage();

    fireEvent.click(await screen.findByTestId('personal-offer-sign-in'));

    expect((await screen.findByRole('alert')).textContent).toBe('The sign-in page could not be opened. Try again.');
    expect(screen.getByTestId('personal-offer-sign-in')).toBeTruthy();
  });
});

describe('PersonalOfferView: a checkout already open', () => {
  const PAST = '2026-01-01T00:00:00Z';
  const reservedTeam = (expiresAt: string, sessionExpiresAt: string): PersonalOfferCurrent => ({
    status: 'CHECKOUT_OPEN', offerId: 19, offerVersion: 1, expiresAt, sessionExpiresAt,
    reservedPlanCode: 'TEAM', reservedCreditTierIndex: TIER_250K, reservedBillingCycle: 'yearly',
  });

  it('before the deadline, the page opens on the reserved choice; everything can still be changed', async () => {
    api.current.mockResolvedValue(reservedTeam(EXPIRES, new Date(Date.now() + 20 * 60_000).toISOString()));
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.getByTestId('personal-offer-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
    // The countdown runs to the offer's deadline, and the line under it says the same date.
    expect(text('personal-offer-until')).toContain('Offer valid until');
    expect(screen.getByTestId('personal-offer-credits')).toBeTruthy();
    fireEvent.click(screen.getByTestId('personal-offer-tier-50000'));
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 50,000 credits a month'));
  });

  it('regression: past the deadline, the reservation still payable is shown alone instead of "expired", and is the one paid', async () => {
    const session = new Date(Date.now() + 20 * 60_000).toISOString();
    api.current.mockResolvedValue(reservedTeam(PAST, session));
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      if (input.creditTierIndex !== TIER_250K || input.billingCycle !== 'yearly') throw new ApiError('x', 409, 'OFFER_EXPIRED');
      return {
        ...preview(TIER_250K, 'yearly'), status: 'CHECKOUT_OPEN', expiresAt: PAST,
        plans: [
          { planCode: 'STARTER', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'PRO', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'TEAM', bonusCredits: 40_000, paygFaceValueUsd: 50, status: 'ELIGIBLE' },
        ],
      } as PersonalOfferPreview;
    });
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.queryByTestId('personal-offer-status')).toBeNull();
    expect(text('personal-offer-bonus')).toBe('+40,000 bonus credits (worth $50)');
    // Nothing else to choose: the pack, the cycle, the best value and the price list are gone.
    expect(screen.queryByTestId('personal-offer-credits')).toBeNull();
    expect(screen.queryByTestId('personal-offer-cycle-monthly')).toBeNull();
    expect(screen.queryByTestId('personal-offer-all-plans')).toBeNull();
    // regression: locked, the page leads with what this reservation gives (40,000), not with the
    // 80,000 of a bigger tier it can no longer change to, and draws no tier to aim for.
    expect(text('personal-offer-title')).toBe('Your first subscription, with 40,000 credits on top');
    expect(screen.queryByTestId('personal-offer-tiers')).toBeNull();
    // The time left is the reservation's.
    expect(text('personal-offer-until')).toContain('Your offer is reserved for this checkout until');
    expect(screen.getByTestId('offer-countdown')).toBeTruthy();

    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    await waitFor(() => expect(createSubscription).toHaveBeenCalledWith({
      planCode: 'TEAM', billingCycle: 'yearly', creditTierIndex: String(TIER_250K), personalOfferId: 19, offerVersion: 1,
    }));
  });

  it('regression: the deadline coming while the page is open moves it to the reservation, even from another choice, with no error', async () => {
    const deadline = Date.now() + 5_000;
    const session = new Date(Date.now() + 20 * 60_000).toISOString();
    api.current.mockResolvedValue(reservedTeam(new Date(deadline).toISOString(), session));
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      const onReservation = input.creditTierIndex === TIER_250K && input.billingCycle === 'yearly';
      if (Date.now() >= deadline && !onReservation) throw new ApiError('x', 409, 'OFFER_EXPIRED');
      return preview(input.creditTierIndex, input.billingCycle);
    });
    renderPage();
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    // Before the deadline the client looks at another choice.
    fireEvent.click(screen.getByTestId('personal-offer-tier-50000'));
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 50,000 credits a month'));

    await waitFor(() => expect(screen.queryByTestId('personal-offer-credits')).toBeNull(), { timeout: 12_000 });
    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.queryByTestId('personal-offer-status')).toBeNull();
    expect(text('personal-offer-until')).toContain('Your offer is reserved for this checkout until');
    await waitFor(() => expect((screen.getByTestId('personal-offer-continue') as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByTestId('personal-offer-continue'));
    await waitFor(() => expect(createSubscription).toHaveBeenCalledWith(expect.objectContaining({
      planCode: 'TEAM', billingCycle: 'yearly', creditTierIndex: String(TIER_250K),
    })));
  }, 25_000);

  it('regression: the server refusing packs as expired while this clock still runs settles it: the page moves to the reservation, never a spinner', async () => {
    api.current.mockResolvedValue(reservedTeam(EXPIRES, new Date(Date.now() + 20 * 60_000).toISOString()));
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      if (input.creditTierIndex !== TIER_250K || input.billingCycle !== 'yearly') throw new ApiError('x', 409, 'OFFER_EXPIRED');
      return {
        ...preview(TIER_250K, 'yearly'), status: 'CHECKOUT_OPEN',
        plans: [
          { planCode: 'STARTER', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'PRO', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'TEAM', bonusCredits: 40_000, paygFaceValueUsd: 50, status: 'ELIGIBLE' },
        ],
      } as PersonalOfferPreview;
    });
    renderPage();

    await waitFor(() => expect(text('personal-offer-summary')).toContain('Team with 250,000 credits a month'));
    expect(screen.queryByTestId('personal-offer-loading')).toBeNull();
    expect(screen.queryByTestId('personal-offer-status')).toBeNull();
    expect(screen.queryByTestId('personal-offer-credits')).toBeNull();
  });

  it('a locked reservation without a bonus does not point at a bigger pack it can no longer change to', async () => {
    api.current.mockResolvedValue(reservedTeam(EXPIRES, new Date(Date.now() + 20 * 60_000).toISOString()));
    api.preview.mockImplementation(async (input: { creditTierIndex: number; billingCycle: 'monthly' | 'yearly' }) => {
      if (input.creditTierIndex !== TIER_250K || input.billingCycle !== 'yearly') throw new ApiError('x', 409, 'OFFER_EXPIRED');
      return {
        ...preview(TIER_250K, 'yearly'), status: 'CHECKOUT_OPEN', nextEligibleMonthlyCredits: 500_000,
        plans: [
          { planCode: 'STARTER', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'PRO', bonusCredits: 0, paygFaceValueUsd: 0, status: 'UNAVAILABLE' },
          { planCode: 'TEAM', bonusCredits: 0, paygFaceValueUsd: 0, status: 'NO_BONUS' },
        ],
      } as PersonalOfferPreview;
    });
    renderPage();

    await waitFor(() => expect(text('personal-offer-no-bonus')).toBe('No bonus with this pack'));
    // Neither the headline nor a tier names a bonus this reservation cannot get.
    expect(text('personal-offer-title')).toBe('Your personal offer');
    expect(screen.queryByTestId('personal-offer-tiers')).toBeNull();
  });

  it('a pack refused as expired, with no reservation, says the offer expired', async () => {
    api.preview.mockRejectedValue(new ApiError('x', 409, 'OFFER_EXPIRED'));
    renderPage();

    expect((await screen.findByTestId('personal-offer-status')).textContent).toBe(enMessages.reward.personalOffer.errors.expired);
  });
});

describe('PersonalOfferView: when there is no offer to take', () => {
  const statusOf = async () => (await screen.findByTestId('personal-offer-status')).textContent ?? '';

  it('an expired offer says so, with the way to the plans', async () => {
    api.current.mockResolvedValue({ ...available, status: 'EXPIRED' });
    renderPage();

    expect(await statusOf()).toBe(enMessages.reward.personalOffer.errors.expired);
    // In the app's language, as the email's link set it.
    expect(screen.getByTestId('personal-offer-pricing').getAttribute('href')).toBe('/en/app/settings/pricing');
    expect(screen.getByTestId('personal-offer-back').getAttribute('href')).toBe('/en/app/chat');
  });

  it('regression: a bonus already granted (or a payment settling) is said even when the link is reopened after the deadline', async () => {
    window.history.replaceState(null, '', '/offer/personal?lc_offer=JANE-BONUS-72H');
    api.preview.mockRejectedValue(new ApiError('x', 409, 'OFFER_EXPIRED'));
    api.current.mockResolvedValue({ ...available, status: 'GRANTED', grantedCredits: 8000 });
    const first = renderPage();
    await waitFor(async () => expect(await statusOf()).toBe('8,000 bonus PAYG credits added.'));
    first.unmount();

    api.current.mockResolvedValue({ ...available, status: 'PENDING_PAYMENT' });
    renderPage();
    await waitFor(async () => expect(await statusOf()).toBe(enMessages.reward.personalOffer.paymentPending));
  });

  it('signed in but the account id never came: said, and the page is loaded again on retry', async () => {
    state.auth = { isAuthenticated: true, isReady: true, numericUserId: null, isLoading: false };
    renderPage();

    expect(await statusOf()).toBe(enMessages.reward.personalOffer.errors.generic);
    fireEvent.click(screen.getByTestId('personal-offer-retry'));
    expect(assignLocation).toHaveBeenCalledWith(window.location.href);
    expect(api.current).not.toHaveBeenCalled();
  });

  it('a bonus already granted says how much', async () => {
    api.current.mockResolvedValue({ ...available, status: 'GRANTED', grantedCredits: 8000 });
    renderPage();

    expect(await statusOf()).toBe('8,000 bonus PAYG credits added.');
  });

  it('no offer on this account, and a code that is not this account\'s, are told apart', async () => {
    api.current.mockResolvedValue({ status: 'NONE' });
    const first = renderPage();
    expect(await statusOf()).toBe('There is no personal offer on this account right now.');
    first.unmount();

    window.history.replaceState(null, '', '/offer/personal?lc_offer=SOMEONE-BONUS-72H');
    api.preview.mockRejectedValue(new ApiError('x', 409, 'OFFER_UNAVAILABLE'));
    renderPage();
    expect(await statusOf()).toBe(enMessages.reward.personalOffer.errors.notEligible);
  });

  it('an offer that cannot be read is said, and read again on retry', async () => {
    api.current.mockRejectedValue(new ApiError('x', 503, 'HTTP_503'));
    renderPage();

    expect(await statusOf()).toBe(enMessages.reward.personalOffer.errors.generic);
    const reads = api.current.mock.calls.length;
    fireEvent.click(screen.getByTestId('personal-offer-retry'));
    await waitFor(() => expect(api.current.mock.calls.length).toBeGreaterThan(reads));
  });
});

describe('the pure parts', () => {
  it('regression remainingParts: days, hours, minutes, seconds rounded up, so the last second shows until the deadline itself; null once reached', () => {
    const now = Date.parse('2026-10-02T10:00:00Z');
    expect(remainingParts(now + ((2 * 24 + 3) * 3600 + 4 * 60 + 5) * 1000, now)).toEqual({ days: 2, hours: 3, minutes: 4, seconds: 5 });
    expect(remainingParts(now + 999, now)).toEqual({ days: 0, hours: 0, minutes: 0, seconds: 1 });
    expect(remainingParts(now, now)).toBeNull();
    expect(remainingParts(now - 1, now)).toBeNull();
    expect(remainingParts(Number.NaN, now)).toBeNull();
  });

  it('choiceFromUrl: a sellable selection only; Starter above its cap moves to Pro', () => {
    expect(choiceFromUrl(new URLSearchParams('planCode=TEAM&creditTierIndex=5&billingCycle=yearly'))).toEqual({ plan: 'team', creditTier: 5, cycle: 'yearly' });
    expect(choiceFromUrl(new URLSearchParams('planCode=STARTER&creditTierIndex=5&billingCycle=monthly'))).toEqual({ plan: 'pro', creditTier: 5, cycle: 'monthly' });
    expect(choiceFromUrl(new URLSearchParams('creditTierIndex=3&billingCycle=monthly'))).toEqual({ plan: 'starter', creditTier: 3, cycle: 'monthly' });
    expect(choiceFromUrl(new URLSearchParams('billingCycle=monthly'))).toBeNull();
    expect(choiceFromUrl(new URLSearchParams('creditTierIndex=99&billingCycle=monthly'))).toBeNull();
    expect(choiceFromUrl(new URLSearchParams('creditTierIndex=3&billingCycle=weekly'))).toBeNull();
  });

  it('bestValueStep: the smallest plan with a bonus, else the next pack with one, else done', () => {
    expect(bestValueStep(preview(TIER_50K, 'monthly'), { plan: 'team', creditTier: TIER_50K, cycle: 'monthly' })).toEqual({ kind: 'plan', plan: 'starter' });
    expect(bestValueStep(preview(TIER_250K, 'monthly'), { plan: 'starter', creditTier: TIER_250K, cycle: 'monthly' })).toEqual({ kind: 'plan', plan: 'pro' });
    expect(bestValueStep(preview(0, 'monthly'), { plan: 'starter', creditTier: 0, cycle: 'monthly' })).toEqual({ kind: 'tier', creditTier: TIER_50K });
    const none = { ...preview(0, 'monthly'), nextEligibleMonthlyCredits: null };
    expect(bestValueStep(none, { plan: 'starter', creditTier: 0, cycle: 'monthly' })).toEqual({ kind: 'done' });
  });

  it('statusMessage: every state that is not an offer to take has its words; an offer being read has none', () => {
    const offer = { current: undefined, candidateCode: null, isLoading: false, isError: false };
    expect(statusMessage('EXPIRED', null, offer)).toEqual({ ns: 'offer', key: 'errors.expired' });
    expect(statusMessage(null, 'OFFER_ALREADY_USED', offer)).toEqual({ ns: 'offer', key: 'errors.alreadyUsed' });
    expect(statusMessage('PENDING_PAYMENT', null, offer)).toEqual({ ns: 'offer', key: 'paymentPending' });
    expect(statusMessage('PROCESSING', null, offer)).toEqual({ ns: 'offer', key: 'processing' });
    expect(statusMessage('REVIEW_REQUIRED', null, offer)).toEqual({ ns: 'offer', key: 'reviewRequired' });
    expect(statusMessage('CONFLICT', null, offer)).toEqual({ ns: 'offer', key: 'errors.conflict' });
    expect(statusMessage('CHECKOUT_CREATING', null, offer)).toEqual({ ns: 'offer', key: 'errors.checkoutActive' });
    expect(statusMessage('AVAILABLE', 'OFFER_CONFLICT', offer)).toEqual({ ns: 'offer', key: 'errors.conflict' });
    expect(statusMessage('DISABLED', null, offer)).toEqual({ ns: 'offer', key: 'errors.unavailable' });
    expect(statusMessage('NO_BONUS', null, offer)).toEqual({ ns: 'offer', key: 'usedWithoutBonus' });
    expect(statusMessage('CLAWED_BACK', null, offer)).toEqual({ ns: 'offer', key: 'reversed' });
    expect(statusMessage('NONE', null, offer)).toEqual({ ns: 'page', key: 'noOffer' });
    expect(statusMessage('NONE', null, { ...offer, candidateCode: 'JANE-BONUS-72H', isLoading: true })).toBeNull();
    expect(statusMessage('AVAILABLE', null, offer)).toBeNull();
    expect(statusMessage(null, null, { ...offer, isError: true })).toEqual({ ns: 'offer', key: 'errors.generic', retry: true });
    // A pack refused as expired: said once the account's offer is known, never over a reservation.
    expect(statusMessage(null, 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'errors.expired' });
    expect(statusMessage(null, 'OFFER_EXPIRED', { ...offer, isLoading: true })).toBeNull();
    expect(statusMessage('CHECKOUT_OPEN', 'OFFER_EXPIRED', { ...offer, current: { status: 'CHECKOUT_OPEN' } }, true)).toBeNull();
  });

  it('regression statusMessage: what the offer has become wins over a pack refused as expired (the link reopened after the deadline)', () => {
    const offer = { current: { status: 'X' }, candidateCode: 'JANE-BONUS-72H', isLoading: false, isError: true };
    expect(statusMessage('GRANTED', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'granted' });
    expect(statusMessage('PENDING_PAYMENT', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'paymentPending' });
    expect(statusMessage('PROCESSING', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'processing' });
    expect(statusMessage('NO_BONUS', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'usedWithoutBonus' });
    expect(statusMessage('CLAWED_BACK', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'reversed' });
    expect(statusMessage('DISABLED', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'errors.unavailable' });
    expect(statusMessage('ALREADY_USED', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'errors.alreadyUsed' });
    expect(statusMessage('AVAILABLE', 'OFFER_EXPIRED', offer)).toEqual({ ns: 'offer', key: 'errors.expired' });
  });

  it('reservedChoice: an open checkout\'s sellable choice only', () => {
    const open = { status: 'CHECKOUT_OPEN', reservedPlanCode: 'PRO', reservedCreditTierIndex: 3, reservedBillingCycle: 'yearly' };
    expect(reservedChoice(open)).toEqual({ plan: 'pro', creditTier: 3, cycle: 'yearly' });
    expect(reservedChoice({ ...open, status: 'AVAILABLE' })).toBeNull();
    expect(reservedChoice({ ...open, reservedPlanCode: 'GOLD' })).toBeNull();
    expect(reservedChoice({ ...open, reservedCreditTierIndex: 99 })).toBeNull();
    expect(reservedChoice({ ...open, reservedBillingCycle: 'weekly' })).toBeNull();
    expect(reservedChoice({ status: 'CHECKOUT_OPEN' })).toBeNull();
    expect(reservedChoice(undefined)).toBeNull();
  });
});

describe('OfferCountdown', () => {
  it('ticks down, and once it reaches zero says the offer ended and asks the page to read it again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: false });
    vi.setSystemTime(new Date('2026-10-04T23:30:58Z'));
    const { OfferCountdown } = await import('../OfferCountdown');
    const onElapsed = vi.fn();
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <OfferCountdown expiresAt="2026-10-04T23:31:00Z" onElapsed={onElapsed} />
      </NextIntlClientProvider>,
    );
    await act(async () => { vi.advanceTimersByTime(0); });
    expect(screen.getByTestId('offer-countdown-seconds').textContent).toBe('02');

    // One second before the deadline it still runs: the server would still say the offer is live.
    await act(async () => { vi.advanceTimersByTime(1_000); });
    expect(screen.getByTestId('offer-countdown-seconds').textContent).toBe('01');
    expect(onElapsed).not.toHaveBeenCalled();

    await act(async () => { vi.advanceTimersByTime(2_000); });
    expect(screen.getByTestId('offer-countdown-ended').textContent).toBe('This offer has ended.');
    expect(onElapsed).toHaveBeenCalledTimes(1);
  });
});
