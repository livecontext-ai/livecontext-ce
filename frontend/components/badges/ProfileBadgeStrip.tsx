'use client';

import React, { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { badgesService, type Badge } from '@/lib/api/orchestrator/badges.service';
import { BadgeDetailDialog } from './BadgeDetailDialog';
import { BadgeMedal } from './BadgeMedal';
import { trackTrophyViewed } from './badgeAnalytics';

/** Width of one medal tile (`w-[68px]`) and of the gap between tiles (`gap-x-4`). */
const TILE_WIDTH = 68;
const TILE_GAP = 16;
/** Tiles per row before the row has been measured (and where there is no layout). */
const FALLBACK_PER_ROW = 6;

/** How many tiles fit on ONE row of the given pixel width (always at least one). */
export function tilesPerRow(width: number): number {
  if (!Number.isFinite(width) || width <= 0) return FALLBACK_PER_ROW;
  return Math.max(1, Math.floor((width + TILE_GAP) / (TILE_WIDTH + TILE_GAP)));
}

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
 * <p>Collapsed, the shelf is exactly ONE row, measured on the live width. When
 * the trophies overflow it, the last slot becomes a filled "+N" tile that opens
 * the rest, so the row never wraps and the hidden count is always visible.
 *
 * <p>Renders nothing at all while loading, on error, or when the person has no
 * trophies. An empty box on a stranger's page is noise, and the read is
 * best-effort by design (a private profile answers 404).
 */
export function ProfileBadgeStrip({ userId }: ProfileBadgeStripProps) {
  const t = useTranslations('badges');
  const [expanded, setExpanded] = useState(false);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  // Callback ref held in state: the row only mounts once the trophies have
  // loaded, so the observer must attach when the element appears.
  const [rowEl, setRowEl] = useState<HTMLDivElement | null>(null);
  const [perRow, setPerRow] = useState(FALLBACK_PER_ROW);

  const { data } = useQuery({
    queryKey: ['badges', 'public', String(userId)],
    queryFn: () => badgesService.getPublicBadges(userId),
    enabled: userId != null && userId !== '',
    retry: false,
    staleTime: 5 * 60_000,
  });

  useEffect(() => {
    if (!rowEl) return;
    const measure = () => setPerRow(tilesPerRow(rowEl.clientWidth));
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(rowEl);
    return () => observer.disconnect();
  }, [rowEl]);

  const badges: Badge[] = data ?? [];
  if (badges.length === 0) return null;

  const overflows = badges.length > perRow;
  const shown = expanded || !overflows ? badges : badges.slice(0, perRow - 1);
  const hiddenCount = badges.length - shown.length;
  const selected = badges.find((badge) => badge.code === selectedCode) ?? null;

  return (
    <section>
      <div className="mb-3 flex items-baseline gap-2">
        <h2 className="text-sm font-medium text-theme-primary">{t('publicHeading')}</h2>
        <span className="text-xs tabular-nums text-theme-muted">{badges.length}</span>
      </div>

      <div
        ref={setRowEl}
        data-testid="profile-badge-row"
        // No overflow clipping: the tile count is measured to fit, and clipping cut
        // the medals' focus rings at the top and bottom of the single row.
        className={`flex gap-x-4 gap-y-3 ${expanded ? 'flex-wrap' : 'flex-nowrap'}`}
      >
        {shown.map((badge) => (
          <button
            key={badge.code}
            type="button"
            onClick={() => {
              trackTrophyViewed(badge, 'profile');
              setSelectedCode(badge.code);
            }}
            title={t(`item.${badge.code}.name`)}
            className="flex w-[68px] flex-shrink-0 flex-col items-center gap-1 rounded-xl p-1 text-center
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

        {hiddenCount > 0 && (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            aria-label={t('showMore', { count: hiddenCount })}
            title={t('showMore', { count: hiddenCount })}
            className="group flex w-[68px] flex-shrink-0 flex-col items-center gap-1 rounded-xl p-1 text-center
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
          >
            {/* Filled accent disc, same footprint as a medal: a faint text link
                under the row was easy to miss, this reads as part of the shelf. */}
            <span
              className="flex h-[52px] w-[52px] items-center justify-center rounded-full
                         bg-[var(--accent-primary)] text-sm font-semibold tabular-nums
                         text-[var(--accent-foreground)] shadow-md transition-transform
                         group-hover:scale-105"
            >
              +{hiddenCount}
            </span>
            <span className="text-xs font-medium leading-tight text-theme-primary">
              {t('showAll')}
            </span>
          </button>
        )}
      </div>

      {expanded && overflows && (
        <button
          type="button"
          onClick={() => setExpanded(false)}
          className="mt-2 text-xs text-theme-secondary underline-offset-2 hover:underline"
        >
          {t('showLess')}
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
