import type { Metadata } from 'next';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

/**
 * A partner's offer link (/offer/<token>) is a public, top-level route OUTSIDE the [locale]
 * segment and outside the app shell (no sidebar, no panels: a page made to choose and pay),
 * like /redeem. Locale from the NEXT_LOCALE cookie; messages feed the client provider.
 *
 * Never indexed: every offer is one client's link, and none is meant to be found.
 */
export const metadata: Metadata = {
  robots: { index: false, follow: false },
};

export default async function OfferLayout({ children }: { children: React.ReactNode }) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      {children}
    </NextIntlClientProvider>
  );
}
