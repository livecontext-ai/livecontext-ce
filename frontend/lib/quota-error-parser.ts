/**
 * PR11c - parser for the backend `CreditConsumeResult.quotaCapExceeded`
 * error string. The backend emits:
 *
 *   QUOTA_CAP_EXCEEDED:<dimension>:<consumed>/<cap>
 *
 * e.g. `QUOTA_CAP_EXCEEDED:credits:99.0000/100.0000`. Both numeric
 * values can carry trailing zeros (BigDecimal.toString preserves
 * scale on the wire). The parser tolerates this and returns native
 * `number` values for downstream formatters.
 *
 * <p>When the input string doesn't match this shape (e.g. the caller
 * passed a generic "Insufficient credits" message), returns null -
 * letting the caller fall through to the generic 402 surface.
 *
 * <p>Intentionally pure (no React, no i18n) so unit-testable in isolation.
 */

export interface QuotaCapExceededInfo {
  dimension: 'credits' | 'storage' | 'tokens' | string;
  consumed: number;
  cap: number;
}

const QUOTA_CAP_PREFIX = 'QUOTA_CAP_EXCEEDED:';

export function parseQuotaCapExceeded(error: string | null | undefined): QuotaCapExceededInfo | null {
  if (!error || typeof error !== 'string') return null;
  if (!error.startsWith(QUOTA_CAP_PREFIX)) return null;
  const tail = error.slice(QUOTA_CAP_PREFIX.length);
  // Expected: `<dim>:<consumed>/<cap>` - split on the FIRST colon so a
  // dimension containing a colon (none today, but forward-compat) doesn't
  // break the parser. The remaining split-on-`/` is unambiguous since
  // BigDecimal.toString never emits a slash.
  const firstColon = tail.indexOf(':');
  if (firstColon < 0) return null;
  const dimension = tail.slice(0, firstColon);
  const valuePart = tail.slice(firstColon + 1);
  const slash = valuePart.indexOf('/');
  if (slash < 0) return null;
  const consumedStr = valuePart.slice(0, slash);
  const capStr = valuePart.slice(slash + 1);
  const consumed = parseFloat(consumedStr);
  const cap = parseFloat(capStr);
  if (!isFinite(consumed) || !isFinite(cap)) return null;
  return { dimension, consumed, cap };
}

/**
 * Helper for the FE: build a user-facing message from the parsed cap
 * info + a next-intl translator. Lives next to the parser so the i18n
 * key conventions stay collocated with the wire shape.
 *
 * <p>Usage:
 *   const t = useTranslations('quota');
 *   const info = parseQuotaCapExceeded(err);
 *   if (info) return formatQuotaMessage(info, t);
 */
export function formatQuotaMessage(
  info: QuotaCapExceededInfo,
  t: (key: string, values?: Record<string, unknown>) => string,
): string {
  const dimKey =
    info.dimension === 'credits' ? 'capExceededCredits'
    : info.dimension === 'storage' ? 'capExceededStorage'
    : info.dimension === 'tokens' ? 'capExceededTokens'
    : 'capExceededGeneric';
  return t(dimKey, {
    consumed: info.consumed,
    cap: info.cap,
    dimension: info.dimension,
  });
}
