import { defineRouting } from 'next-intl/routing';

export const locales = ['en', 'fr', 'es', 'de', 'pt', 'zh'] as const;
export type Locale = (typeof locales)[number];

// The NEXT_LOCALE cookie next-intl's CLIENT navigation writes on a locale switch carries
// `Secure` whenever the page is HTTPS (a browser drops a Secure cookie set over plain HTTP, so a
// plain-HTTP CE install keeps a working cookie). The server-set cookie is handled per request in
// proxy.ts (lib/security/localeCookie.ts); this module is evaluated there without a window.
const SECURE_PAGE = typeof window !== 'undefined' && window.location.protocol === 'https:';

export const routing = defineRouting({
  locales,
  defaultLocale: 'en',
  localePrefix: 'as-needed',
  localeDetection: true, // Detect locale from NEXT_LOCALE cookie and Accept-Language header
  localeCookie: { sameSite: 'lax', ...(SECURE_PAGE ? { secure: true } : {}) },
});
