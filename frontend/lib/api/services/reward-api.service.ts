import { apiClient } from '../api-client';
import { IS_CE } from '@/lib/edition/edition';

/**
 * The caller's personal referral code plus their invite progress.
 * {@code available} is false when a CE install is not cloud-linked (the reward
 * state lives on the cloud account, reachable only through cloud-connect).
 */
export interface RewardInvite {
  available: boolean;
  code: string | null;
  redeemedCount: number;
  pendingCount: number;
  inHoldCount: number;
  rewardedCount: number;
  creditsEarned: number;
  /** Per-side reward (PAYG credits) granted to BOTH parties on conversion, from prod config. */
  rewardCredits: number;
  softCapLimit: number | null;
}

/** Result of a redeem: code is REDEEMED (immediate), PENDING_CONVERSION, or TRACK_ONLY. */
export interface RewardRedeemResult {
  success: boolean;
  code: string;
  status?: string;
}

/**
 * Reward-code API (referral + promo). One edition-aware path: Cloud talks to the
 * Stripe-gated BillingController directly; a CE install routes through the
 * cloud-connect proxy so the reward lands on its bound cloud account.
 *
 * On a typed failure apiClient throws an ApiError whose {@code .code} is one of
 * INVALID_CODE | NOT_REDEEMABLE | ALREADY_REDEEMED | EXHAUSTED | SELF_REFERRAL |
 * ALREADY_PAID | CLOUD_LINK_REQUIRED, so the caller maps it to a localized message.
 */
export class RewardApiService {
  async getMyInvite(): Promise<RewardInvite> {
    if (IS_CE) {
      const raw = await apiClient.get<Record<string, unknown>>('/cloud-link/reward-stats');
      if (!raw || raw.available === false) return emptyInvite(false);
      return normalize(raw);
    }
    const raw = await apiClient.get<Record<string, unknown>>('/billing/me/invite');
    return normalize(raw);
  }

  async redeem(code: string): Promise<RewardRedeemResult> {
    const path = IS_CE ? '/cloud-link/redeem' : '/billing/redeem';
    return apiClient.post<RewardRedeemResult>(path, { code });
  }
}

function asNumber(v: unknown): number {
  return typeof v === 'number' ? v : 0;
}

function normalize(raw: Record<string, unknown>): RewardInvite {
  return {
    available: raw.code != null,
    code: (raw.code as string) ?? null,
    redeemedCount: asNumber(raw.redeemedCount),
    pendingCount: asNumber(raw.pendingCount),
    inHoldCount: asNumber(raw.inHoldCount),
    rewardedCount: asNumber(raw.rewardedCount),
    creditsEarned: asNumber(raw.creditsEarned),
    rewardCredits: asNumber(raw.rewardCredits),
    softCapLimit: typeof raw.softCapLimit === 'number' ? raw.softCapLimit : null,
  };
}

function emptyInvite(available: boolean): RewardInvite {
  return {
    available,
    code: null,
    redeemedCount: 0,
    pendingCount: 0,
    inHoldCount: 0,
    rewardedCount: 0,
    creditsEarned: 0,
    rewardCredits: 0,
    softCapLimit: null,
  };
}

export const rewardApi = new RewardApiService();
