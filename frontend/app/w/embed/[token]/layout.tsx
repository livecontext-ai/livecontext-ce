import type { Metadata } from 'next';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, setRequestLocale } from 'next-intl/server';
import { resolveRequestLocale } from '@/i18n/resolveRequestLocale';

export const metadata: Metadata = {
  title: 'Chat Widget',
  description: 'Embedded chat widget',
  robots: 'noindex, nofollow',
};

export default async function WidgetEmbedLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const locale = await resolveRequestLocale();
  setRequestLocale(locale);
  const messages = await getMessages();

  return (
    <NextIntlClientProvider messages={messages} locale={locale}>
      <div className="min-h-full m-0 p-0 overflow-hidden">
        {children}
      </div>
    </NextIntlClientProvider>
  );
}
