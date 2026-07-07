import type { Metadata } from 'next';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, getTranslations, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';

export async function generateMetadata(): Promise<Metadata> {
  const locale = await resolveRequestLocale();
  const t = await getTranslations({ locale, namespace: 'contact.metadata' });

  return {
    title: t('title'),
    description: t('description'),
    alternates: { canonical: '/contact' },
    // Self-hosted deployments must never index marketing pages.
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
}

export default async function ContactLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      <LandingShell>
        <div className="max-w-3xl mx-auto px-6 py-10">{children}</div>
      </LandingShell>
    </NextIntlClientProvider>
  );
}
