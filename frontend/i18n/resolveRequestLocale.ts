import 'server-only';

import { cookies } from 'next/headers';
import { routing, type Locale } from './routing';

/**
 * Resolve the locale for route segments that live outside the `[locale]` tree
 * (e.g. `/workflows`, `/billing`, `/local-mcp`, the share/embed token routes).
 *
 * Resolution: the `NEXT_LOCALE` cookie if it is a known locale, else the app
 * default ('en'). This INTENTIONALLY mirrors the client-side `getClientLocale()`
 * (URL `[locale]` prefix -> `NEXT_LOCALE` cookie -> 'en') and the `[locale]`
 * tree's own default (the middleware sends a prefixless `/app` to `/en`). All
 * three now agree, so the provider locale that drives `useLocale()`/`t()` can no
 * longer diverge from the locale that drives the date/number formatters.
 *
 * We deliberately do NOT fall back to the `Accept-Language` header: the browser
 * language must never drive the app (per the i18n rules a French-browser user on
 * the English app sees English everywhere). Reading it here was the root of the
 * "French UI text next to English dates" divergence, because the client-side
 * formatters cannot read that header and so never matched it.
 */
export async function resolveRequestLocale(): Promise<Locale> {
  const cookieStore = await cookies();
  const cookieLocale = cookieStore.get('NEXT_LOCALE')?.value;
  if (cookieLocale && routing.locales.includes(cookieLocale as Locale)) {
    return cookieLocale as Locale;
  }

  return routing.defaultLocale;
}
