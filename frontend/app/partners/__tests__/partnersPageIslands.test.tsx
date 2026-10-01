// @vitest-environment jsdom
/**
 * The /partners page with its two interactive parts REAL (the main suite stubs them): the page
 * lives outside the [locale] tree and hands them only a slice of the messages, so this proves
 * that slice is enough for the calculator and the form to render, in English and in French.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createTranslator, type AbstractIntlMessages } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';

const messages = { en, fr } as Record<string, AbstractIntlMessages>;
const state = vi.hoisted(() => ({ locale: 'en' }));

vi.mock('server-only', () => ({}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('next-intl/server', () => ({
  getTranslations: async ({ locale, namespace }: { locale: string; namespace: string }) => createTranslator({
    locale, messages: messages[locale], namespace, onError: (error) => { throw error; },
  }),
  getMessages: async ({ locale }: { locale: string }) => messages[locale],
  setRequestLocale: vi.fn(),
}));
vi.mock('@/i18n/resolveRequestLocale', () => ({ resolveRequestLocale: async () => state.locale }));
vi.mock('next/navigation', () => ({ notFound: () => { throw new Error('NOT_FOUND'); } }));
// Hermetic: the page reads the live marketplace, which must never be called from a test.
vi.mock('@/lib/marketplace/publicPublications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/marketplace/publicPublications')>()),
  fetchAllPublicPublications: async () => ({ publications: [], truncated: false }),
}));
vi.mock('@/lib/partners/publicPartnerTerms', () => ({
  fetchPartnerTerms: async () => ({
    commission_percent: 30, commission_months: 12, hold_days: 14, audience_credits: 8000,
    tiers: [
      { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
      { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
      { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
    ],
    tier_currency: 'usd', tier_settle_days: 60, founder_until: '2100-01-01T00:00:00Z', founder_open: true,
  }),
}));
vi.mock('@/components/landing/LandingShell', () => ({
  LandingHeader: () => <header />, LandingFooter: () => <footer />, landingChromeStyles: '',
}));
vi.mock('@/components/landing/LandingThemeProvider', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/app/[locale]/_landing/FaqItem', () => ({
  default: ({ children }: { children: React.ReactNode }) => <details>{children}</details>,
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isAuthenticated: false, isLoading: false, loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn(), setLandingIntent: vi.fn() }));

import PartnersPage from '../page';

async function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>{await PartnersPage()}</QueryClientProvider>);
}

describe('/partners with its real interactive parts', () => {
  afterEach(cleanup);

  it('in English, the calculator and the form render from the messages the page hands them', async () => {
    state.locale = 'en';
    await renderPage();

    expect(screen.getByTestId('partner-calculator')).toBeTruthy();
    expect(screen.getByLabelText('Paying clients you bring')).toBeTruthy();
    expect(screen.getByLabelText('Company or brand')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in and send' })).toBeTruthy();
  });

  it('in French too', async () => {
    state.locale = 'fr';
    await renderPage();

    expect(screen.getByLabelText('Clients payants que vous amenez')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Se connecter et envoyer' })).toBeTruthy();
  });
});
