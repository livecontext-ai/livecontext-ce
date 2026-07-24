// Bounds for the public_link node's ttl_minutes param, mirrored from the backend clamp
// (PublicLinkNode: 5 minutes to 10080 minutes / 7 days, default 240 = 4 hours). The
// inspector commits through these so a typed value can never leave the documented range.

export const PUBLIC_LINK_TTL_MIN = 5;
export const PUBLIC_LINK_TTL_MAX = 10080;
export const PUBLIC_LINK_TTL_DEFAULT = 240;

export const PUBLIC_LINK_DISPOSITIONS = ['inline', 'attachment'] as const;
export type PublicLinkDisposition = (typeof PUBLIC_LINK_DISPOSITIONS)[number];
export const PUBLIC_LINK_DISPOSITION_DEFAULT: PublicLinkDisposition = 'inline';

/**
 * Clamp the ttl_minutes input on commit (blur). Non-numeric / empty input falls back to
 * the default. Mirrors the backend clamp so a typed value can never leave 5-10080:
 * typing 999999 or 1 must land on 10080 / 5, never reach the plan raw.
 */
export function clampPublicLinkTtlMinutes(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseInt(String(raw), 10);
  if (!Number.isFinite(parsed)) return PUBLIC_LINK_TTL_DEFAULT;
  return Math.min(PUBLIC_LINK_TTL_MAX, Math.max(PUBLIC_LINK_TTL_MIN, parsed));
}
