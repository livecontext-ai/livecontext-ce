/** Money per currency code, in minor units (cents), as the partner endpoints return it. */
export type AmountsByCurrency = Record<string, number>;

/**
 * "12.00 USD, 3.50 EUR" in the given app locale; empty string when there is nothing
 * above zero. Shared by the admin partner report and the partner's own dashboard, so both
 * read one amount the same way.
 */
export function formatAmounts(amounts: AmountsByCurrency | undefined | null, locale: string): string {
  if (!amounts) return '';
  return Object.entries(amounts)
    .filter(([, minor]) => minor > 0)
    .map(([currency, minor]) => formatMinor(minor, currency, locale))
    .join(', ');
}

/**
 * A commission rate in the app locale's number format, without the percent sign (the
 * translated sentence places it: "12.5%" in English, "12,5 %" in French). An ICU `{percent}`
 * placeholder given a raw number prints it with a dot in every language.
 */
export function formatPercent(percent: number, locale: string): string {
  return percent.toLocaleString(locale, { maximumFractionDigits: 2 });
}

/** One amount in minor units, as currency in the app locale. */
export function formatMinor(minor: number, currency: string, locale: string): string {
  try {
    return (minor / 100).toLocaleString(locale, { style: 'currency', currency: currency.toUpperCase() });
  } catch {
    return `${(minor / 100).toLocaleString(locale)} ${currency.toUpperCase()}`;
  }
}
