import type { Metadata } from 'next';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { ThemeProvider } from '@/components/ThemeProvider';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

export const metadata: Metadata = {
  title: 'Form Submission',
  description: 'Submit a form',
  robots: 'noindex, nofollow',
};

/**
 * Minimal layout for public form pages.
 * No auth, no sidebar, no navigation.
 * Nested layouts must NOT declare <html>/<body> - the root layout owns those.
 */
export default async function PublicFormLayout({
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
        <div className="min-h-full m-0 p-0 bg-slate-50 dark:bg-slate-950">
          {children}
        </div>
      </ThemeProvider>
    </NextIntlClientProvider>
  );
}
