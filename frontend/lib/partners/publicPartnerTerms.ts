/**
 * Server-side read of the partner program terms, for the public /partners page.
 *
 * <p>Same shape as the other public reads (`app/videos/_lib/publicVideos.ts`): it calls the
 * gateway DIRECTLY rather than looping through `/api/proxy`, never uses the browser-bound
 * `api-client`, and reads `GATEWAY_SERVICE_URL` at runtime. `/api/public/partner-program/terms`
 * is anonymous at the gateway and carries no user data.
 *
 * <p>The page advertises figures a partner will be paid on, so they come from the backend
 * defaults every new partner code starts from, never from copy. When the read fails the page
 * renders without figures rather than with a guess.
 */
import 'server-only';

import { IS_CE } from '@/lib/edition';
import { gatewayBaseUrl } from '@/lib/marketplace/publicPublications';
import type { PartnerProgramTerms } from '@/lib/api/services/partner-program-api.service';
import { isTierKey, TIER_ORDER, type PartnerTierTerm } from '@/lib/partners/tiers';

/** An hour: the terms change when an operator edits a setting, not per request. */
export const PARTNER_TERMS_REVALIDATE_SECONDS = 3600;

/** A ceiling on a gateway that accepts the connection and never answers. */
const GATEWAY_READ_TIMEOUT_MS = 8000;

function positiveNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

/**
 * The tiers, in Silver, Gold, Platinum order, or an empty list when the payload does not carry
 * all three well-formed: a page that cannot state every tier states none rather than a partial
 * ladder.
 */
function mapTiers(raw: unknown): PartnerTierTerm[] {
  if (!Array.isArray(raw)) return [];
  const tiers: PartnerTierTerm[] = [];
  for (const item of raw) {
    if (typeof item !== 'object' || item === null) return [];
    const row = item as Record<string, unknown>;
    const percent = positiveNumber(row.commission_percent);
    const threshold = positiveNumber(row.threshold_minor);
    if (!isTierKey(row.tier) || percent === null || threshold === null) return [];
    tiers.push({ tier: row.tier, commission_percent: percent, threshold_minor: threshold });
  }
  const ordered = TIER_ORDER.map((key) => tiers.find((t) => t.tier === key));
  return ordered.every(Boolean) ? (ordered as PartnerTierTerm[]) : [];
}

/** Parses the terms payload, or null when any base figure is missing or malformed. */
export function mapPartnerTerms(raw: unknown): PartnerProgramTerms | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;
  const percent = positiveNumber(row.commission_percent);
  const months = positiveNumber(row.commission_months);
  const hold = positiveNumber(row.hold_days);
  const credits = positiveNumber(row.audience_credits);
  if (percent === null || months === null || hold === null || credits === null) return null;
  const founderUntil = typeof row.founder_until === 'string' && !Number.isNaN(Date.parse(row.founder_until))
    ? row.founder_until : null;
  const settleDays = positiveNumber(row.tier_settle_days);
  return {
    commission_percent: percent,
    commission_months: months,
    hold_days: hold,
    audience_credits: credits,
    // The tiers are stated with the rule that moves a partner between them, or not at all.
    tiers: settleDays === null ? [] : mapTiers(row.tiers),
    tier_settle_days: settleDays,
    tier_currency: typeof row.tier_currency === 'string' && row.tier_currency ? row.tier_currency : 'usd',
    founder_until: founderUntil,
    // Only an explicit true with a real deadline opens the founder offer on the page.
    founder_open: row.founder_open === true && founderUntil !== null,
  };
}

/** The live program terms, or null on a self-hosted build or any failure. */
export async function fetchPartnerTerms(
  revalidateSeconds = PARTNER_TERMS_REVALIDATE_SECONDS,
): Promise<PartnerProgramTerms | null> {
  if (IS_CE) return null;
  try {
    const res = await fetch(`${gatewayBaseUrl()}/api/public/partner-program/terms`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
      signal: AbortSignal.timeout(GATEWAY_READ_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    return mapPartnerTerms(await res.json());
  } catch {
    return null;
  }
}
