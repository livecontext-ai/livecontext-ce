import { defineRouting } from 'next-intl/routing';

export const locales = ['en', 'fr', 'es', 'de', 'pt', 'zh'] as const;
export type Locale = (typeof locales)[number];

export const routing = defineRouting({
  locales,
  defaultLocale: 'en',
  localePrefix: 'as-needed',
  localeDetection: true // Detect locale from NEXT_LOCALE cookie and Accept-Language header
});
