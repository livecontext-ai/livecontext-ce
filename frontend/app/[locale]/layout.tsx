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
      {/* The document's own language. The ROOT layout renders `<html lang="en">` for the
          whole app: it is shared with every route outside this tree (marketplace, docs,
          models, legal) and cannot read this param without opting the entire site out of
          static rendering, so a translated page declared itself English to every screen
          reader, and to `PlanLimitToastListener`, which picks its wording from this
          attribute. This stamps the real one before hydration.
          It does NOT reach a crawler that runs no JavaScript: the SERVED attribute is
          still en, and fixing that needs a second root layout for this tree. Search
          engines are told the language by hreflang, the canonical and the JSON-LD
          `inLanguage`, which are correct on every page. */}
      <script dangerouslySetInnerHTML={{ __html: `document.documentElement.lang=${JSON.stringify(locale)}` }} />
      {children}
      {/* Global cookie-consent banner: shows once on the first locale page a
          visitor opens (landing or deep-linked app), dismissed site-wide.
          CE (self-hosted) has no tracking/cookies to consent to, so it's hidden. */}
      {!IS_CE && <CookieConsentBanner />}
    </NextIntlClientProvider>
  );
}
