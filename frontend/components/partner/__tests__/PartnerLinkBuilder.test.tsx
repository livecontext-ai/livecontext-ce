// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';
import { CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { SITE_URL } from '@/lib/seo/siteUrl';
import { ApiError } from '@/lib/api/api-client';
import type { PartnerOffer } from '@/lib/api/services/partner-program-api.service';

const api = vi.hoisted(() => ({ offers: vi.fn(), createOffer: vi.fn(), deactivateOffer: vi.fn() }));
vi.mock('@/lib/api/services/partner-program-api.service', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  partnerProgramApi: api,
}));
const myApps = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => ({ numericUserId: 42 }) }));
vi.mock('@/lib/api/orchestrator/publication.service', async (importOriginal) => {
  // The real service (other modules bind its methods at import), reading the partner's apps from the test.
  const actual = await importOriginal<typeof import('@/lib/api/orchestrator/publication.service')>();
  actual.publicationService.getMyPublications = (() => myApps.get()) as never;
  return actual;
});

import { PartnerLinkBuilder } from '../PartnerLinkBuilder';

const PRO_250K = CREDIT_TIERS.indexOf(250_000);

function offer(overrides: Partial<PartnerOffer> = {}): PartnerOffer {
  return {
    token: 'Abc23XyZ9k', plan_code: 'PRO', credit_tier_index: PRO_250K, billing_cycle: 'monthly',
    label: null, created_at: '2026-10-01T10:00:00Z', ...overrides,
  };
}

function renderBuilder({ ratePercent = 40 as number | null, onCopy = vi.fn() } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerLinkBuilder code="NORTHWIND" audienceCredits={8000} ratePercent={ratePercent} commissionMonths={12} onCopy={onCopy} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
  return { onCopy };
}
const text = (id: string) => screen.getByTestId(id).textContent;

describe('PartnerLinkBuilder', () => {
  beforeEach(() => {
    api.offers.mockReset().mockResolvedValue({ offers: [] });
    api.createOffer.mockReset().mockImplementation(async (body) => ({
      success: true,
      offer: offer({ plan_code: body.plan_code, credit_tier_index: body.credit_tier_index, billing_cycle: body.billing_cycle, label: body.label ?? null }),
    }));
    api.deactivateOffer.mockReset().mockResolvedValue({ success: true });
    myApps.get.mockReset().mockResolvedValue({ count: 0, publications: [] });
  });
  afterEach(cleanup);

  it('starts on Pro with 250,000 credits a month: the list price, the code\'s offer and the partner\'s cut', () => {
    renderBuilder();

    expect(text('builder-client-sees')).toContain('Pro with 250,000 credits a month');
    // $24 for Pro + $185 for 250K credits, the real price list.
    expect(text('builder-price')).toBe('$209 a month');
    expect(text('builder-offer')).toBe('Plus 8,000 free credits with your code NORTHWIND');
    // 40% of $209.
    expect(text('builder-commission')).toContain('About $84 a month in commission on this client');
    expect(text('builder-commission')).toContain('40% of each paid invoice, for 12 months');
    // No link before the partner creates one: a link is an offer the server knows.
    expect(screen.queryByTestId('builder-link')).toBeNull();
  });

  it('creates the offer for the choice shown and gives its short link, which copy copies', async () => {
    const { onCopy } = renderBuilder();

    fireEvent.change(screen.getByTestId('builder-label'), { target: { value: '  Acme  ' } });
    fireEvent.click(screen.getByTestId('builder-create'));

    await waitFor(() => expect(screen.getByTestId('builder-link')).toBeTruthy());
    expect(api.createOffer).toHaveBeenCalledWith({
      plan_code: 'PRO', credit_tier_index: PRO_250K, billing_cycle: 'monthly', label: 'Acme',
    });
    expect(text('builder-link')).toBe(`${SITE_URL}/offer/Abc23XyZ9k`);
    // The name field empties for the next client.
    expect((screen.getByTestId('builder-label') as HTMLInputElement).value).toBe('');
    fireEvent.click(screen.getByTestId('builder-copy'));
    expect(onCopy).toHaveBeenCalledWith(`${SITE_URL}/offer/Abc23XyZ9k`);
    // The list is read again, so the new link shows up in it.
    await waitFor(() => expect(api.offers).toHaveBeenCalledTimes(2));
  });

  it('Enter in the client field creates the link, as the button does', async () => {
    renderBuilder();

    fireEvent.change(screen.getByTestId('builder-label'), { target: { value: 'Acme' } });
    fireEvent.submit(screen.getByTestId('builder-label').closest('form') as HTMLFormElement);

    await waitFor(() => expect(api.createOffer).toHaveBeenCalledTimes(1));
    expect(api.createOffer.mock.calls[0][0].label).toBe('Acme');
    // The field is labelled on its own, not together with the button.
    expect(screen.getByLabelText('Client (optional)')).toBe(screen.getByTestId('builder-label'));
  });

  it('a blank client name is sent as no name, not as spaces', async () => {
    renderBuilder();

    fireEvent.change(screen.getByTestId('builder-label'), { target: { value: '   ' } });
    fireEvent.click(screen.getByTestId('builder-create'));

    await waitFor(() => expect(api.createOffer).toHaveBeenCalled());
    expect(api.createOffer.mock.calls[0][0].label).toBeUndefined();
  });

  it('regression: changing the choice after creating hides the link, so it never sits under a choice it does not carry', async () => {
    renderBuilder();
    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(screen.getByTestId('builder-link')).toBeTruthy());

    fireEvent.click(screen.getByTestId('builder-cycle-yearly'));

    expect(screen.queryByTestId('builder-link')).toBeNull();
  });

  it('yearly billing: the monthly price with the yearly base discount, and a yearly offer', async () => {
    renderBuilder();

    fireEvent.click(screen.getByTestId('builder-cycle-yearly'));

    // round($24 x 0.8) + $185.
    expect(text('builder-price')).toBe('$204 a month, billed yearly');
    // One invoice a year: 40% of 12 x $204, on that invoice, not "a month".
    expect(text('builder-commission')).toContain('About $979 in commission on a yearly invoice');
    expect(screen.getByTestId('builder-cycle-yearly').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('builder-cycle-monthly').getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(api.createOffer).toHaveBeenCalled());
    expect(api.createOffer.mock.calls[0][0].billing_cycle).toBe('yearly');
  });

  it('switching to Starter clamps the credits to its cap and offers no tier above it', async () => {
    renderBuilder();

    fireEvent.click(screen.getByTestId('builder-plan-starter'));

    const select = screen.getByTestId('builder-credits') as HTMLSelectElement;
    expect(select.options).toHaveLength(CREDIT_TIERS.indexOf(100_000) + 1);
    expect(select.value).toBe(String(CREDIT_TIERS.indexOf(100_000)));
    expect(screen.getByTestId('builder-plan-starter').getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByTestId('builder-plan-pro').getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(api.createOffer).toHaveBeenCalled());
    expect(api.createOffer.mock.calls[0][0]).toMatchObject({ plan_code: 'STARTER', credit_tier_index: CREDIT_TIERS.indexOf(100_000) });
  });

  it('a credit tier above the default range is offered on Pro', async () => {
    renderBuilder();

    fireEvent.change(screen.getByTestId('builder-credits'), { target: { value: String(CREDIT_TIERS.indexOf(5_000_000)) } });

    expect(text('builder-client-sees')).toContain('5,000,000 credits a month');
    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(api.createOffer).toHaveBeenCalled());
    expect(api.createOffer.mock.calls[0][0].credit_tier_index).toBe(CREDIT_TIERS.indexOf(5_000_000));
  });

  it('a refusal the server names is said in words, and an unknown one falls back to a generic message', async () => {
    api.createOffer.mockRejectedValueOnce(new ApiError('conflict', 409, 'code_inactive'));
    renderBuilder();

    fireEvent.click(screen.getByTestId('builder-create'));
    expect((await screen.findByRole('alert')).textContent).toBe('Your partner code is inactive: a link would lead nowhere.');
    expect(screen.queryByTestId('builder-link')).toBeNull();

    api.createOffer.mockRejectedValueOnce(new ApiError('bad', 400, 'something_new'));
    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('The link could not be created. Try again.'));
  });

  it('lists the live links with their choice, copies one again and deactivates one', async () => {
    api.offers.mockResolvedValue({
      offers: [
        offer({ token: 'Tok2Team77', plan_code: 'TEAM', credit_tier_index: 3, billing_cycle: 'yearly', label: 'Acme' }),
        offer({ token: 'Tok1Pro888' }),
      ],
    });
    const { onCopy } = renderBuilder();

    const rows = await screen.findAllByTestId('builder-link-row');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Acme');
    expect(rows[0].textContent).toContain(`Team, ${CREDIT_TIERS[3].toLocaleString('en')} credits a month, yearly`);
    expect(rows[1].textContent).toContain('Unnamed client');
    expect(rows[1].textContent).toContain('Pro, 250,000 credits a month, monthly');

    fireEvent.click(within(rows[0]).getByRole('button', { name: /copy/i }));
    expect(onCopy).toHaveBeenCalledWith(`${SITE_URL}/offer/Tok2Team77`);

    // Deactivating breaks a link the client may have: asked once more first.
    fireEvent.click(within(rows[1]).getByTestId('builder-deactivate'));
    expect(api.deactivateOffer).not.toHaveBeenCalled();
    expect(rows[1].textContent).toContain('Deactivate this link? Your client will no longer be able to open it.');
    fireEvent.click(within(rows[1]).getByTestId('builder-deactivate-confirm'));
    await waitFor(() => expect(api.deactivateOffer).toHaveBeenCalledWith('Tok1Pro888'));
    await waitFor(() => expect(api.offers).toHaveBeenCalledTimes(2));
  });

  it('cancelling the confirmation deactivates nothing and brings the row back', async () => {
    api.offers.mockResolvedValue({ offers: [offer({ token: 'Tok1Pro888' })] });
    renderBuilder();
    const row = await screen.findByTestId('builder-link-row');

    fireEvent.click(within(row).getByTestId('builder-deactivate'));
    fireEvent.click(within(row).getByRole('button', { name: 'Cancel' }));

    expect(api.deactivateOffer).not.toHaveBeenCalled();
    expect(row.textContent).toContain('Pro, 250,000 credits a month, monthly');
  });

  it('a deactivation that fails is said, not swallowed', async () => {
    api.offers.mockResolvedValue({ offers: [offer({ token: 'Tok1Pro888' })] });
    api.deactivateOffer.mockRejectedValue(new ApiError('down', 503, 'HTTP_503'));
    renderBuilder();
    const row = await screen.findByTestId('builder-link-row');

    fireEvent.click(within(row).getByTestId('builder-deactivate'));
    fireEvent.click(within(row).getByTestId('builder-deactivate-confirm'));

    expect((await screen.findByRole('alert')).textContent).toBe('The link could not be deactivated. Try again.');
  });

  it('regression: while the list loads, or when it cannot be read, it does not claim the partner has no link', async () => {
    let fail: (e: unknown) => void = () => {};
    api.offers.mockReturnValue(new Promise((_resolve, reject) => { fail = reject; }));
    renderBuilder();

    expect(text('builder-links')).toContain('Loading your links...');
    expect(text('builder-links')).not.toContain('No link yet');

    await act(async () => { fail(new ApiError('down', 503, 'HTTP_503')); });
    await waitFor(() => expect(screen.getByTestId('builder-links-error').textContent)
      .toBe('Your links could not be loaded. Reload the page to try again.'));
    expect(text('builder-links')).not.toContain('No link yet');
  });

  it('with no live link, the list says so', async () => {
    renderBuilder();

    await waitFor(() => expect(text('builder-links')).toContain('No link yet: create one above.'));
  });

  it('without a known rate, it states no commission rather than guess one', () => {
    renderBuilder({ ratePercent: null });

    expect(screen.queryByTestId('builder-commission')).toBeNull();
    expect(text('builder-offer')).toContain('8,000');
  });

  const app = (id: string, title: string) => ({
    id, title, publisherId: '42', displayMode: 'APPLICATION', status: 'ACTIVE', visibility: 'PUBLIC', nodeIcons: [],
  });

  it('the apps picked go with the offer, in the order picked; the client sees them counted, and the next link starts with none', async () => {
    myApps.get.mockResolvedValue({ count: 2, publications: [app('app-a', 'Invoice chaser'), app('app-b', 'Lead finder')] });
    renderBuilder();

    fireEvent.click(within(await screen.findByTestId('builder-app-app-b')).getByRole('checkbox'));
    fireEvent.click(within(screen.getByTestId('builder-app-app-a')).getByRole('checkbox'));
    expect(text('builder-apps-line')).toBe('2 apps installed in their workspace after payment');

    fireEvent.click(screen.getByTestId('builder-create'));

    await waitFor(() => expect(api.createOffer).toHaveBeenCalledTimes(1));
    expect(api.createOffer.mock.calls[0][0].app_ids).toEqual(['app-b', 'app-a']);
    await waitFor(() => expect(screen.queryByTestId('builder-apps-line')).toBeNull());
    expect((within(screen.getByTestId('builder-app-app-a')).getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
  });

  it('a link without apps sends none, and a live link with apps says how many', async () => {
    api.offers.mockResolvedValue({ offers: [offer({ token: 'TokApps001', app_ids: ['a', 'b', 'c'] }), offer({ token: 'TokNone002' })] });
    renderBuilder();

    const rows = await screen.findAllByTestId('builder-link-row');
    expect(within(rows[0]).getByTestId('builder-link-apps').textContent).toBe(' · 3 apps');
    expect(within(rows[1]).queryByTestId('builder-link-apps')).toBeNull();

    fireEvent.click(screen.getByTestId('builder-create'));
    await waitFor(() => expect(api.createOffer).toHaveBeenCalled());
    expect(api.createOffer.mock.calls[0][0].app_ids).toBeUndefined();
  });

  it('an app refused by the server is said in words', async () => {
    api.createOffer.mockRejectedValue(new ApiError('bad', 400, 'invalid_apps'));
    renderBuilder();

    fireEvent.click(screen.getByTestId('builder-create'));

    expect((await screen.findByRole('alert')).textContent).toContain('One of the chosen apps can no longer be offered');
  });
});
