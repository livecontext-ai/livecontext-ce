import { track } from '@/lib/analytics/analytics';
import type { Badge } from '@/lib/api/orchestrator/badges.service';

/**
 * Where a trophy was opened from: the two header strips, the family grid, or a profile.
 * Sent as `entry_point`, never `surface`: `surface` is a reserved common prop set by `track()`.
 */
export type TrophyEntryPoint = 'recent' | 'next' | 'grid' | 'profile';

/** One trophy opened in the detail dialog. Catalog ids and enums only. */
export function trackTrophyViewed(badge: Badge, entryPoint: TrophyEntryPoint): void {
  track('trophy_viewed', {
    badge_code: badge.code,
    badge_family: badge.family.toLowerCase(),
    badge_tier: badge.tier.toLowerCase(),
    unlocked: badge.unlocked,
    entry_point: entryPoint,
  });
}
