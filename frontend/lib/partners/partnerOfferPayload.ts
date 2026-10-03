/**
 * The offer payload a client may read, and its parsing. Shared by the server-side read of the
 * offer page and by the signed-in read the browser makes (a client already attributed to the
 * partner still reads an offer whose code is used up): no server-only import here.
 */
import { proxiedAvatarUrl } from './avatarUrl';
import { CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { isTierKey, type PartnerTierKey, type PlanKey } from '@/lib/partners/tiers';
import { OFFER_TOKEN_RE as TOKEN } from '@/lib/partners/offerToken';
import type { NodeIconData } from '@/lib/api/orchestrator/types';

/** The partner, as their public profile shows them. */
export interface OfferPartner {
  name: string;
  handle: string | null;
  avatarUrl: string | null;
  tier: PartnerTierKey | null;
  /** The verified badge their marketplace cards carry. */
  verified: boolean;
}

/**
 * One of the partner's apps the offer includes, installed in the client's workspace on payment.
 * The marketplace's own list item for it (same field names), so the offer page draws it with the
 * marketplace card: its screen, the publisher's avatar and badge, the integrations it uses.
 */
export interface OfferApp {
  id: string;
  title: string;
  description: string | null;
  publisherId: string;
  publisherName: string | null;
  nodeIcons: NodeIconData[];
  /** The run and interface its screen is drawn from (the marketplace showcase), when it has one. */
  showcaseRunId: string | null;
  showcaseInterfaceId: string | null;
  /** PUBLIC or UNLISTED (an offer gives no other kind). */
  visibility: 'PUBLIC' | 'UNLISTED';
}

export interface PublicPartnerOffer {
  token: string;
  code: string;
  /** Credits a new account receives with the code. */
  credits: number;
  plan: PlanKey;
  creditTier: number;
  cycle: 'monthly' | 'yearly';
  /** Null when the partner's profile is private: the page then does not name them. */
  partner: OfferPartner | null;
  /** The partner's apps included with the offer (none on an offer without any). */
  apps: OfferApp[];
  /** The smallest plan those apps install on (a checkout through the offer on a smaller one is refused); null: any. */
  appsPlan: PlanKey | null;
}

const text = (value: unknown): string | undefined => (typeof value === 'string' && value ? value : undefined);

/** An integration icon of the list item, its string fields only. */
function mapNodeIcon(raw: unknown): NodeIconData | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const i = raw as Record<string, unknown>;
  const icon: NodeIconData = {
    nodeId: text(i.nodeId), nodeKind: text(i.nodeKind), iconSlug: text(i.iconSlug), avatarUrl: text(i.avatarUrl),
    isMcp: i.isMcp === true,
  };
  return icon.iconSlug || icon.nodeId || icon.nodeKind ? icon : null;
}

/** The apps of the payload; an entry missing what the card needs is left out, never drawn half. */
function mapApps(raw: unknown): OfferApp[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry) => {
    if (typeof entry !== 'object' || entry === null) return [];
    const a = entry as Record<string, unknown>;
    const id = text(a.id);
    const title = text(a.title);
    const publisherId = a.publisherId === undefined || a.publisherId === null ? undefined : String(a.publisherId);
    if (!id || !title || !publisherId) return [];
    return [{
      id,
      title,
      description: text(a.description) ?? null,
      publisherId,
      publisherName: text(a.publisherName) ?? null,
      nodeIcons: Array.isArray(a.nodeIcons) ? a.nodeIcons.map(mapNodeIcon).filter((i): i is NodeIconData => i !== null) : [],
      showcaseRunId: text(a.showcaseRunId) ?? null,
      showcaseInterfaceId: text(a.showcaseInterfaceId) ?? null,
      visibility: a.visibility === 'UNLISTED' ? 'UNLISTED' as const : 'PUBLIC' as const,
    }];
  });
}

const PLANS: Record<string, PlanKey> = { STARTER: 'starter', PRO: 'pro', TEAM: 'team' };

/** Parses the payload, or null when anything a client would read is missing or off the price list. */
export function mapPartnerOffer(raw: unknown): PublicPartnerOffer | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;
  const plan = typeof row.plan_code === 'string' ? PLANS[row.plan_code] : undefined;
  const tier = row.credit_tier_index;
  const credits = row.credits;
  if (typeof row.token !== 'string' || !TOKEN.test(row.token)) return null;
  if (typeof row.code !== 'string' || !row.code) return null;
  if (!plan || typeof tier !== 'number' || !Number.isInteger(tier) || tier < 0 || tier >= CREDIT_TIERS.length) return null;
  if (row.billing_cycle !== 'monthly' && row.billing_cycle !== 'yearly') return null;
  if (typeof credits !== 'number' || !Number.isFinite(credits) || credits < 0) return null;
  let partner: OfferPartner | null = null;
  if (typeof row.partner === 'object' && row.partner !== null) {
    const p = row.partner as Record<string, unknown>;
    if (typeof p.name === 'string' && p.name) {
      partner = {
        name: p.name,
        handle: typeof p.handle === 'string' && p.handle ? p.handle : null,
        avatarUrl: proxiedAvatarUrl(p.avatar_url),
        tier: isTierKey(p.tier) ? p.tier : null,
        verified: p.verified === true,
      };
    }
  }
  const appsPlan = typeof row.apps_plan === 'string' ? PLANS[row.apps_plan] ?? null : null;
  return { token: row.token, code: row.code, credits, plan, creditTier: tier, cycle: row.billing_cycle, partner, apps: mapApps(row.apps), appsPlan };
}
