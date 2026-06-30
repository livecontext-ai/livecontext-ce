import { getRequestConfig } from 'next-intl/server';
import { routing } from './routing';

/**
 * Deep-merge a locale's messages over the English reference so that any key
 * missing in a non-default locale degrades to English instead of rendering its
 * raw key path. The active locale always wins where it defines a key.
 */
function deepMergeMessages(
  base: Record<string, unknown>,
  override: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...base };
  for (const [key, value] of Object.entries(override)) {
    const prev = out[key];
    if (
      value && typeof value === 'object' && !Array.isArray(value) &&
      prev && typeof prev === 'object' && !Array.isArray(prev)
    ) {
      out[key] = deepMergeMessages(
        prev as Record<string, unknown>,
        value as Record<string, unknown>,
      );
    } else {
      out[key] = value;
    }
  }
  return out;
}

export default getRequestConfig(async ({ requestLocale }) => {
  // This typically corresponds to the `[locale]` segment
  let locale = await requestLocale;

  // Ensure that a valid locale is used
  if (!locale || !routing.locales.includes(locale as any)) {
    locale = routing.defaultLocale;
  }

  const active = (await import(`../messages/${locale}.json`)).default;

  // `en` is the reference set; other locales fall back to it for missing keys.
  if (locale === routing.defaultLocale) {
    return { locale, messages: active };
  }

  const fallback = (await import('../messages/en.json')).default;
  return { locale, messages: deepMergeMessages(fallback, active) };
});
