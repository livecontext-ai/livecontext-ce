import { apiClient } from '../api-client';

/**
 * Thematic group a badge belongs to. Mirrors the backend `BadgeFamily` enum.
 * The union is exhaustive on purpose: adding a family server-side should make
 * the artwork map below fail to type-check until the frontend gives it a look.
 */
export type BadgeFamily =
  | 'FOUNDER'
  | 'TENURE'
  | 'BUILDER'
  | 'APP_MAKER'
  | 'OPERATOR'
  | 'RELIABILITY'
  | 'CONSISTENCY'
  | 'NIGHT_OWL'
  | 'SHIPPER'
  | 'PUBLISHER'
  | 'SHARER'
  | 'POPULARITY'
  | 'REACHABLE'
  | 'MULTICHANNEL'
  | 'REMOTE_CONTROL';

/** Visual rank. Mirrors the backend `BadgeTier` enum, weakest first. */
export type BadgeTier = 'BRONZE' | 'SILVER' | 'GOLD' | 'PLATINUM' | 'DIAMOND';

/** Measured quantity. Mirrors the backend `BadgeMetric` enum. */
export type BadgeMetric =
  | 'DAYS_UNTIL_JOIN_CUTOFF'
  | 'MEMBER_DAYS'
  | 'WORKFLOWS_CREATED'
  | 'APPLICATIONS_CREATED'
  | 'RUNS_LAUNCHED'
  | 'RUNS_COMPLETED'
  | 'ACTIVE_DAYS'
  | 'NIGHT_LAUNCHES'
  | 'WORKFLOWS_PINNED'
  | 'PUBLICATIONS_PUBLISHED'
  | 'PUBLICATIONS_PUBLIC'
  | 'PUBLICATION_USES'
  | 'CHANNELS_CONNECTED'
  | 'CHANNEL_SERVICES'
  | 'REMOTE_DECISIONS';

/** One badge plus the viewer's standing against it. Mirrors backend `BadgeView`. */
export interface Badge {
  /** Stable catalog code - also the i18n key and the artwork key. */
  code: string;
  family: BadgeFamily;
  tier: BadgeTier;
  metric: BadgeMetric;
  threshold: number;
  /**
   * Current metric value while locked; the value FROZEN at unlock time once
   * unlocked, so a trophy never displays a number below the one that earned it.
   */
  value: number;
  unlocked: boolean;
  /** ISO instant, null while locked. */
  unlockedAt: string | null;
}

/**
 * Fraction of the way to the threshold, clamped to [0, 1]. Lives here rather
 * than in the components so the grid, the detail dialog and the summary ring
 * cannot drift on rounding.
 */
export function badgeProgress(badge: Badge): number {
  if (badge.unlocked) return 1;
  if (badge.threshold <= 0) return 0;
  return Math.max(0, Math.min(1, badge.value / badge.threshold));
}

class BadgesService {
  /** The signed-in user's full grid (unlocked + locked with live progress). */
  async getMyBadges(): Promise<Badge[]> {
    return apiClient.get<Badge[]>('/badges/me');
  }

  /**
   * Someone else's UNLOCKED trophies, for their profile page. Locked badges and
   * raw metric values never cross: the backend reports `value` as the threshold,
   * so a visitor learns what the trophy says and nothing about the owner's
   * activity. 404 for a profile its owner set to PRIVATE.
   */
  async getPublicBadges(userId: string | number): Promise<Badge[]> {
    return apiClient.get<Badge[]>(`/badges/public/${userId}`);
  }
}

export const badgesService = new BadgesService();
