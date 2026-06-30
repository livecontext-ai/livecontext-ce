import type { Metadata } from 'next';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { ThemeProvider } from '@/components/ThemeProvider';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

export const metadata: Metadata = {
  title: 'Shared Resource',
  description: 'Access a shared resource',
  robots: 'noindex, nofollow',
};

/**
 * Minimal layout for public share pages.
 * No auth, no sidebar, no navigation.
 */
export default async function ShareLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      <ThemeProvider>
        <div className="min-h-full m-0 p-0 bg-theme-primary">
          {children}
        </div>
      </ThemeProvider>
    </NextIntlClientProvider>
  );
}
