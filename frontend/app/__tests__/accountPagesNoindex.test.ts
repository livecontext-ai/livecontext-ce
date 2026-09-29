import { describe, expect, it, vi } from 'vitest';

// The redeem layout imports the intl server helpers; only its static metadata is read here.
vi.mock('next-intl/server', () => ({ getMessages: vi.fn(), setRequestLocale: vi.fn() }));
vi.mock('next-intl', () => ({ NextIntlClientProvider: () => null }));
vi.mock('@/i18n/resolveRequestLocale', () => ({ resolveRequestLocale: vi.fn() }));

/**
 * Account pages are not search results.
 *
 * With no robots rule of their own they inherited the landing's title,
 * description and `index, follow`, so search engines saw a duplicate of the
 * home page at each of these URLs.
 */
const layouts: Record<string, () => Promise<{ metadata?: { robots?: unknown } }>> = {
  '/forgot-password': () => import('@/app/[locale]/forgot-password/layout'),
  '/reset-password': () => import('@/app/[locale]/reset-password/layout'),
  '/invitations/accept': () => import('@/app/[locale]/invitations/layout'),
  '/login': () => import('@/app/[locale]/login/layout'),
  '/register': () => import('@/app/[locale]/register/layout'),
  '/redeem': () => import('@/app/redeem/layout'),
};

describe('account pages are noindex', () => {
  it.each(Object.keys(layouts))('%s tells crawlers not to index it', async (route) => {
    const { metadata } = await layouts[route]();
    expect(metadata?.robots).toEqual({ index: false, follow: false });
  });
});
