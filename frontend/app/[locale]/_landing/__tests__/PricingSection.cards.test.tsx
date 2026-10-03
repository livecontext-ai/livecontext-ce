// @vitest-environment jsdom
/**
 * The landing's plan cards, drawn through the shared PlanCardFrame: each card keeps its own wiring
 * (the recommended badge, the price suffix of a priced plan, the enterprise contact link, the
 * sign-in or app route of the self-serve plans, and the click tracked before leaving).
 */
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';

const state = vi.hoisted(() => ({ isAuthenticated: false }));
const push = vi.fn();
const loginWithRedirect = vi.fn();
const track = vi.fn();

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isAuthenticated: state.isAuthenticated, isLoading: false, loginWithRedirect }),
}));
vi.mock('@/lib/analytics/analytics', () => ({
  track: (...args: unknown[]) => track(...args),
  setLandingIntent: vi.fn(),
}));
vi.mock('@/hooks/usePricingEvent', () => ({ usePricingEvent: () => ({ event: null }) }));
vi.mock('@/components/pricing/ComparePlansLink', () => ({ default: () => null }));
vi.mock('@/components/pricing/PlanComparisonDialog', () => ({ default: () => null }));

import PricingSection from '../PricingSection';

const cards = en.pricing.planCards;

function cell(name: string): HTMLElement {
  return screen.getByRole('heading', { level: 3, name }).closest('[data-plan-cell]') as HTMLElement;
}

beforeAll(() => {
  class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }));
});

beforeEach(() => {
  state.isAuthenticated = false;
  push.mockReset();
  loginWithRedirect.mockReset().mockResolvedValue(undefined);
  track.mockReset();
});
afterEach(cleanup);

function renderSection() {
  render(
    <NextIntlClientProvider locale="en" messages={en}>
      <PricingSection />
    </NextIntlClientProvider>,
  );
}

describe('landing pricing cards (through PlanCardFrame)', () => {
  it('draws the five plans, the recommended badge on Starter only, and the period after priced plans only', () => {
    renderSection();

    for (const name of [cards.free.name, cards.starter.name, cards.pro.name, cards.team.name, cards.enterprise.name]) {
      expect(cell(name)).toBeTruthy();
    }
    expect(cell(cards.starter.name).textContent).toContain(cards.badges.recommended);
    expect(cell(cards.pro.name).textContent).not.toContain(cards.badges.recommended);
    expect(cell(cards.pro.name).textContent).toContain(cards.period);
    expect(cell(cards.free.name).textContent).not.toContain(cards.period);
    expect(cell(cards.enterprise.name).textContent).toContain(cards.price.contactSales);
    expect(cell(cards.enterprise.name).textContent).not.toContain(cards.period);
  });

  it('enterprise links to the contact form with its subject filled in, and the click is tracked', () => {
    renderSection();

    const link = within(cell(cards.enterprise.name)).getByRole('link', { name: cards.enterprise.cta });
    expect(link.getAttribute('href')).toBe(`/contact?category=other&message=${encodeURIComponent(cards.enterprise.contactMessage)}`);
    fireEvent.click(link);
    expect(track).toHaveBeenCalledWith('landing_plan_clicked', expect.objectContaining({ plan_id: 'enterprise' }));
    expect(loginWithRedirect).not.toHaveBeenCalled();
  });

  it('a visitor choosing a self-serve plan signs in and comes back to pricing, tracked before leaving', async () => {
    renderSection();

    const link = within(cell(cards.pro.name)).getByRole('link', { name: cards.pro.cta });
    expect(link.getAttribute('href')).toBe('/app/settings/pricing');
    fireEvent.click(link);

    await waitFor(() => expect(loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/app/settings/pricing' } }));
    expect(track).toHaveBeenCalledWith('landing_plan_clicked', expect.objectContaining({ plan_id: 'pro', is_authenticated: false }));
    expect(track.mock.invocationCallOrder[0]).toBeLessThan(loginWithRedirect.mock.invocationCallOrder[0]);
  });

  it('a signed-in person goes straight to the pricing page', async () => {
    state.isAuthenticated = true;
    renderSection();

    fireEvent.click(within(cell(cards.starter.name)).getByRole('link', { name: cards.starter.cta }));

    await waitFor(() => expect(push).toHaveBeenCalledWith('/app/settings/pricing'));
    expect(loginWithRedirect).not.toHaveBeenCalled();
  });
});
