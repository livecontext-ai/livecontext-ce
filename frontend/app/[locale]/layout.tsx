import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { notFound } from 'next/navigation';
import { routing } from '@/i18n/routing';
import CookieConsentBanner from '@/components/CookieConsentBanner';
import { IS_CE } from '@/lib/edition';

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export default async function LocaleLayout({
  children,
  params
}: {
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;

  // Ensure that the incoming `locale` is valid
  if (!routing.locales.includes(locale as any)) {
    notFound();
  }

  // Enable static rendering
  setRequestLocale(locale);

  // Providing all messages to the client
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages}>
      {children}
      {/* Global cookie-consent banner: shows once on the first locale page a
          visitor opens (landing or deep-linked app), dismissed site-wide.
          CE (self-hosted) has no tracking/cookies to consent to, so it's hidden. */}
      {!IS_CE && <CookieConsentBanner />}
    </NextIntlClientProvider>
  );
}
