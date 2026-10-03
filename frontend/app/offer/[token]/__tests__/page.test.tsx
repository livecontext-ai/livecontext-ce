// @vitest-environment jsdom
//
// The offer page reads the offer on the server: a live one is drawn by the view; one that is gone
// (unknown token, deactivated offer, a partner code that no longer brings sign-ups) says it is no
// longer available, with the way to the plans, never a page promising what nobody would honour;
// and a read that failed for a passing reason says that, with a way to try again.
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const fetchPartnerOffer = vi.fn();
vi.mock('@/lib/partners/publicPartnerOffer', () => ({ fetchPartnerOffer: (token: string) => fetchPartnerOffer(token) }));
const getTranslations = vi.fn(async (_opts: unknown) => (key: string, values?: Record<string, unknown>) =>
  (values ? `${key}:${JSON.stringify(values)}` : key));
vi.mock('next-intl/server', () => ({
  getTranslations: (opts: unknown) => getTranslations(opts),
  setRequestLocale: vi.fn(),
}));
vi.mock('@/i18n/resolveRequestLocale', () => ({ resolveRequestLocale: async () => 'fr' }));
vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));
vi.mock('@/components/auth/BrandTopbar', () => ({
  BrandTopbar: ({ children, themeLabels }: { children: React.ReactNode; themeLabels: { toLight: string; toDark: string } }) => (
    <header data-testid="brand-topbar" data-to-dark={themeLabels.toDark}>{children}</header>
  ),
}));
vi.mock('@/components/landing/LandingLanguageSelect', () => ({
  default: ({ label, compact }: { label: string; compact?: boolean }) => <span data-testid="language" data-label={label} data-compact={String(!!compact)} />,
}));
// The signed-in re-read of a gone offer is its own component, tested on its own: here it shows what it wraps.
vi.mock('@/components/partner/offer/PartnerOfferForClient', () => ({
  PartnerOfferForClient: ({ children, token }: { children: React.ReactNode; token: string }) => <div data-testid="offer-for-client" data-token={token}>{children}</div>,
}));
vi.mock('@/components/partner/offer/PartnerOfferView', () => ({
  PartnerOfferView: ({ offer }: { offer: { token: string } }) => <div data-testid="offer-view" data-token={offer.token} />,
}));

import PartnerOfferPage, { generateMetadata } from '../page';

async function renderPage(token: string) {
  render(await PartnerOfferPage({ params: Promise.resolve({ token }) }));
}

afterEach(() => {
  cleanup();
  fetchPartnerOffer.mockReset();
});

describe('/offer/<token>', () => {
  it('a live offer is drawn full screen, under the bar of the sign-in page: logo, language, light/dark, no navigation', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'ok', offer: { token: 'Abc23XyZ9k' } });

    await renderPage('Abc23XyZ9k');

    expect(fetchPartnerOffer).toHaveBeenCalledWith('Abc23XyZ9k');
    const bar = screen.getByTestId('brand-topbar');
    expect(bar.getAttribute('data-to-dark')).toBe('toDarkTheme');
    expect(within(bar).getByTestId('language').getAttribute('data-label')).toBe('language');
    expect(within(bar).getByTestId('language').getAttribute('data-compact')).toBe('true');
    expect(screen.getByTestId('offer-view').getAttribute('data-token')).toBe('Abc23XyZ9k');
    expect(screen.queryByTestId('partner-offer-unavailable')).toBeNull();
  });

  it('no offer says it is no longer available and leads to the plans', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'gone' });

    await renderPage('Gone12345x');

    const block = screen.getByTestId('partner-offer-unavailable');
    expect(block.textContent).toContain('unavailableTitle');
    expect(screen.getByRole('link', { name: 'unavailableCta' }).getAttribute('href')).toBe('/app/settings/pricing');
    expect(screen.queryByTestId('offer-view')).toBeNull();
    // A signed-in client of the partner may still read it: the page asks, for this token.
    const reread = screen.getByTestId('offer-for-client');
    expect(reread.getAttribute('data-token')).toBe('Gone12345x');
    expect(reread.contains(block)).toBe(true);
  });

  it('a shared link reads who recommends which plan, never the client, and is never indexed', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'ok', offer: { token: 'Abc23XyZ9k', plan: 'team', partner: { name: 'Northwind Studio' } } });

    const meta = await generateMetadata({ params: Promise.resolve({ token: 'Abc23XyZ9k' }) });

    expect(meta.title).toBe('metaTitle:{"partner":"Northwind Studio","plan":"team.name"}');
    expect(meta.description).toBe('metaDescription');
    expect(meta.robots).toEqual({ index: false, follow: false });
    expect((meta.openGraph as { url?: string }).url).toMatch(/\/offer\/Abc23XyZ9k$/);
  });

  it('a private partner is not named in the shared link, and a dead offer says it is unavailable', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'ok', offer: { token: 'Abc23XyZ9k', plan: 'pro', partner: null } });
    expect((await generateMetadata({ params: Promise.resolve({ token: 'Abc23XyZ9k' }) })).title)
      .toBe('metaTitleGeneric:{"plan":"pro.name"}');

    fetchPartnerOffer.mockResolvedValue({ status: 'gone' });
    expect((await generateMetadata({ params: Promise.resolve({ token: 'Gone12345x' }) })).title).toBe('unavailableTitle');
  });

  it('regression: a read that failed for a passing reason says so and offers to retry, never "no longer available"', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'error' });

    await renderPage('Abc23XyZ9k');

    expect(screen.getByTestId('partner-offer-error').textContent).toContain('loadErrorTitle');
    expect(screen.queryByTestId('partner-offer-unavailable')).toBeNull();
    expect(screen.getByRole('link', { name: 'retry' }).getAttribute('href')).toBe('/offer/Abc23XyZ9k');
    // ...and the shared-link preview, which apps cache, stays neutral.
    expect((await generateMetadata({ params: Promise.resolve({ token: 'Abc23XyZ9k' }) })).title).toBe('eyebrow');
  });

  it('regression: reads its words in the visitor locale, named explicitly (not left to the layout)', async () => {
    fetchPartnerOffer.mockResolvedValue({ status: 'gone' });

    await renderPage('Gone12345x');

    expect(getTranslations).toHaveBeenCalledWith({ locale: 'fr', namespace: 'partnerOffer' });
  });
});
