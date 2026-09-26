'use client';

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { badgesService, type Badge } from '@/lib/api/orchestrator/badges.service';
import { BadgeDetailDialog } from './BadgeDetailDialog';
import { BadgeMedal } from './BadgeMedal';
import { trackTrophyViewed } from './badgeAnalytics';

/** Medals shown before the strip offers to expand. Two rows on a wide profile. */
const COLLAPSED_COUNT = 12;

export interface ProfileBadgeStripProps {
  /** Numeric user id of the profile's owner. */
  userId: number | string;
}

/**
 * The trophy shelf on someone's profile.
 *
 * <p>Deliberately a strip and not the settings grid: a profile is about what a
 * person has DONE, so it shows earned medals only, with no locked slots, no
 * progress bars and no "0 / 50" lines. Those belong to the owner's own trophy
 * page, and putting fifty grey placeholders on a profile would bury the apps
 * the visitor came for.
 *
 * <p>Renders nothing at all while loading, on error, or when the person has no
 * trophies. An empty box on a stranger's page is noise, and the read is
 * best-effort by design (a private profile answers 404).
 */
export function ProfileBadgeStrip({ userId }: ProfileBadgeStripProps) {
  const t = useTranslations('badges');
  const [expanded, setExpanded] = useState(false);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);

  const { data } = useQuery({
    queryKey: ['badges', 'public', String(userId)],
    queryFn: () => badgesService.getPublicBadges(userId),
    enabled: userId != null && userId !== '',
    retry: false,
    staleTime: 5 * 60_000,
  });

  const badges: Badge[] = data ?? [];
  if (badges.length === 0) return null;

  const shown = expanded ? badges : badges.slice(0, COLLAPSED_COUNT);
  const selected = badges.find((badge) => badge.code === selectedCode) ?? null;

  return (
    <section>
      <div className="mb-3 flex items-baseline gap-2">
        <h2 className="text-sm font-medium text-theme-primary">{t('publicHeading')}</h2>
        <span className="text-xs tabular-nums text-theme-muted">{badges.length}</span>
      </div>

      <div className="flex flex-wrap gap-x-4 gap-y-3">
        {shown.map((badge) => (
          <button
            key={badge.code}
            type="button"
            onClick={() => {
              trackTrophyViewed(badge, 'profile');
              setSelectedCode(badge.code);
            }}
            title={t(`item.${badge.code}.name`)}
            className="flex w-[68px] flex-col items-center gap-1 rounded-xl p-1 text-center
                       transition-colors hover:bg-theme-secondary focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
          >
            <BadgeMedal
              family={badge.family}
              tier={badge.tier}
              unlocked
              size={52}
              idPrefix="profile"
            />
            <span className="line-clamp-2 text-xs leading-tight text-theme-secondary">
              {t(`item.${badge.code}.name`)}
            </span>
          </button>
        ))}
      </div>

      {badges.length > COLLAPSED_COUNT && (
        <button
          type="button"
          onClick={() => setExpanded((open) => !open)}
          className="mt-2 text-xs text-theme-secondary underline-offset-2 hover:underline"
        >
          {expanded ? t('showLess') : t('showAll')}
        </button>
      )}

      {/* No siblings: this surface holds only the trophies the owner has earned,
          so a ladder built from it would present the family as complete. */}
      <BadgeDetailDialog
        badge={selected}
        onOpenChange={(open) => !open && setSelectedCode(null)}
      />
    </section>
  );
}
