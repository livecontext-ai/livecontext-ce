import { routing, type Locale } from './routing';

type LanguagePreference = {
  value: string;
  quality: number;
  index: number;
};

function parseQuality(rawPart: string): number {
  const [, rawValue] = rawPart.split('=');
  const quality = Number.parseFloat(rawValue ?? '');
  if (!Number.isFinite(quality)) {
    return 1;
  }
  return Math.min(Math.max(quality, 0), 1);
}

function parseAcceptLanguage(header: string): LanguagePreference[] {
  return header
    .split(',')
    .map((entry, index) => {
      const [rawTag, ...params] = entry.trim().split(';');
      const value = rawTag.trim().toLowerCase();
      const qualityParam = params.find((param) => param.trim().toLowerCase().startsWith('q='));

      return {
        value,
        quality: qualityParam ? parseQuality(qualityParam.trim()) : 1,
        index,
      };
    })
    .filter((preference) => preference.value.length > 0 && preference.quality > 0)
    .sort((left, right) => right.quality - left.quality || left.index - right.index);
}

export function selectLocaleFromAcceptLanguage(header: string | null | undefined): Locale {
  if (!header) {
    return routing.defaultLocale;
  }

  for (const preference of parseAcceptLanguage(header)) {
    if (preference.value === '*') {
      return routing.defaultLocale;
    }

    const exactMatch = routing.locales.find((locale) => locale.toLowerCase() === preference.value);
    if (exactMatch) {
      return exactMatch;
    }

    const baseLanguage = preference.value.split('-')[0];
    const baseMatch = routing.locales.find((locale) => locale.toLowerCase() === baseLanguage);
    if (baseMatch) {
      return baseMatch;
    }
  }

  return routing.defaultLocale;
}
