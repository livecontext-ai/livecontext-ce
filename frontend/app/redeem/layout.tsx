import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

/**
 * The shared referral link ({origin}/redeem?code=...) is a public, top-level
 * route OUTSIDE the [locale] segment (like /billing, /about, /shared) so a pasted
 * link without a locale prefix resolves instead of 404ing. Locale comes from the
 * NEXT_LOCALE cookie via resolveRequestLocale; messages feed the client provider
 * so RewardRedeemCard's useTranslations works here.
 */
export default async function RedeemLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      {children}
    </NextIntlClientProvider>
  );
}
