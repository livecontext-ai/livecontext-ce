'use client';

import React, { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Trophy } from 'lucide-react';
import { useBadges } from '@/hooks/useBadges';
import { getClientLocale } from '@/lib/utils/locale';
import { badgeProgress, type Badge, type BadgeFamily } from '@/lib/api/orchestrator/badges.service';
import { FAMILY_ORDER } from './badgeVisuals';
import { BadgeCard } from './BadgeCard';
import { BadgeDetailDialog } from './BadgeDetailDialog';
import { BadgeMedal } from './BadgeMedal';
import { trackTrophyViewed, type TrophyEntryPoint } from './badgeAnalytics';

/**
 * The trophy wall. Tighter columns than a card grid would allow, because a bare
 * medal needs only its own width, and a generous ROW gap so the names never read
 * as belonging to the medal below them.
 */
const GRID =
  'grid grid-cols-3 gap-x-3 gap-y-7 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6';

/** How many "closest to unlocking" medals the header highlights. */
const NEXT_UP_COUNT = 4;
/** How many "recently unlocked" medals the header highlights. */
const RECENT_COUNT = 6;

/**
 * The signed-in user's full trophy wall: a summary header, two shortcut strips
 * (just earned / nearly there), then every badge grouped by family.
 *
 * <p>Locked badges are shown, not hidden. The grid doubles as the only place
 * that says what the platform rewards, so hiding what has not been earned
 * would leave a new user with an empty page and no idea what to aim at.
 */
export function BadgeCollection() {
  const t = useTranslations('badges');
  const locale = getClientLocale();
  const { badges, unlockedCount, totalCount, isLoading, error } = useBadges();
  // The code, not the badge object: a refetch replaces every object, and holding
  // one would pin the dialog to a stale snapshot of its own progress.
  const [selectedCode, setSelectedCode] = useState<string | null>(null);

  const byFamily = useMemo(() => {
    const map = new Map<BadgeFamily, Badge[]>();
    for (const badge of badges) {
      const list = map.get(badge.family) ?? [];
      list.push(badge);
      map.set(badge.family, list);
    }
    return map;
  }, [badges]);

  // Opening a trophy from the page itself is what is reported; stepping along the ladder inside
  // the dialog is not, since that is browsing one trophy's family rather than opening a trophy.
  const openFrom = (entryPoint: TrophyEntryPoint) => (badge: Badge) => {
    trackTrophyViewed(badge, entryPoint);
    setSelectedCode(badge.code);
  };

  const selected = useMemo(
    () => badges.find((badge) => badge.code === selectedCode) ?? null,
    [badges, selectedCode],
  );

  const recent = useMemo(
    () =>
      badges
        .filter((badge) => badge.unlocked && badge.unlockedAt)
        .sort((a, b) => (b.unlockedAt ?? '').localeCompare(a.unlockedAt ?? ''))
        .slice(0, RECENT_COUNT),
    [badges],
  );

  const nextUp = useMemo(
    () =>
      badges
        .filter((badge) => !badge.unlocked && badgeProgress(badge) > 0)
        .sort((a, b) => badgeProgress(b) - badgeProgress(a))
        .slice(0, NEXT_UP_COUNT),
    [badges],
  );

  if (isLoading) {
    return (
      <div className={GRID}>
        {Array.from({ length: 12 }).map((_, index) => (
          // Medal-shaped, not card-shaped: a grid of rounded rectangles would
          // promise a wall of cards that never arrives.
          <div key={index} className="flex flex-col items-center gap-2">
            <div className="h-20 w-20 animate-pulse rounded-full bg-[var(--bg-tertiary)]" />
            <div className="h-3 w-16 animate-pulse rounded bg-[var(--bg-tertiary)]" />
            <div className="h-2.5 w-10 animate-pulse rounded bg-[var(--bg-tertiary)]" />
          </div>
        ))}
      </div>
    );
  }

  if (error) {
    return <p className="text-sm text-theme-secondary">{t('loadError')}</p>;
  }

  const completion = totalCount > 0 ? Math.round((unlockedCount / totalCount) * 100) : 0;

  return (
    <div className="space-y-8">
      <header className="space-y-4">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-theme-secondary">
            <Trophy className="h-4 w-4 text-[var(--text-primary)]" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="text-base font-semibold text-[var(--text-primary)]">{t('title')}</h2>
            <p className="mt-0.5 text-sm text-theme-secondary">{t('subtitle')}</p>
          </div>
        </div>

        <div>
          <div className="mb-1.5 flex items-baseline justify-between gap-3">
            <span className="text-sm text-theme-secondary">
              {t('summary', {
                unlocked: unlockedCount.toLocaleString(locale),
                total: totalCount.toLocaleString(locale),
              })}
            </span>
            <span className="text-sm font-medium tabular-nums text-[var(--text-primary)]">{completion}%</span>
          </div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-[var(--bg-tertiary)]">
            <div
              className="h-full rounded-full bg-[var(--accent-primary)] transition-[width] duration-700"
              style={{ width: `${completion}%` }}
            />
          </div>
        </div>
      </header>

      {recent.length > 0 && (
        <MedalStrip title={t('recent')} badges={recent} idPrefix="recent" onSelect={openFrom('recent')} />
      )}
      {nextUp.length > 0 && (
        <MedalStrip title={t('nextUp')} badges={nextUp} idPrefix="next" onSelect={openFrom('next')} />
      )}

      {FAMILY_ORDER.map((family) => {
        const familyBadges = byFamily.get(family);
        if (!familyBadges || familyBadges.length === 0) return null;
        const familyUnlocked = familyBadges.filter((badge) => badge.unlocked).length;
        return (
          <section key={family} className="space-y-3">
            <div className="flex items-baseline justify-between gap-3">
              <h3 className="text-sm font-semibold text-[var(--text-primary)]">
                {t(`family.${family}`)}
              </h3>
              <span className="text-xs tabular-nums text-theme-muted">
                {familyUnlocked} / {familyBadges.length}
              </span>
            </div>
            <div className={GRID}>
              {familyBadges.map((badge) => (
                <BadgeCard
                  key={badge.code}
                  badge={badge}
                  onSelect={openFrom('grid')}
                />
              ))}
            </div>
          </section>
        );
      })}

      <BadgeDetailDialog
        badge={selected}
        siblings={selected ? byFamily.get(selected.family) : undefined}
        onOpenChange={(open) => !open && setSelectedCode(null)}
        onSelect={(picked) => setSelectedCode(picked.code)}
      />
    </div>
  );
}

/**
 * A compact row of medals with their names. Used for the two header strips,
 * where the point is recognition at a glance rather than the full rule text.
 */
function MedalStrip({
  title,
  badges,
  idPrefix,
  onSelect,
}: {
  title: string;
  badges: Badge[];
  idPrefix: string;
  onSelect: (badge: Badge) => void;
}) {
  const t = useTranslations('badges');
  return (
    <section className="space-y-2">
      <h3 className="text-sm font-semibold text-[var(--text-primary)]">{title}</h3>
      {/* Horizontal scroll rather than wrap: the strip is a highlight, and a
          second row of it would compete with the grid below. */}
      <div className="flex gap-4 overflow-x-auto pb-1">
        {badges.map((badge) => (
          <button
            key={badge.code}
            type="button"
            onClick={() => onSelect(badge)}
            className="flex w-20 shrink-0 flex-col items-center gap-1.5 rounded-xl p-1 text-center
                       transition-colors hover:bg-theme-secondary focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
          >
            <BadgeMedal
              family={badge.family}
              tier={badge.tier}
              unlocked={badge.unlocked}
              progress={badgeProgress(badge)}
              size={56}
              idPrefix={idPrefix}
            />
            <span className="line-clamp-2 text-xs leading-tight text-theme-secondary">
              {t(`item.${badge.code}.name`)}
            </span>
          </button>
        ))}
      </div>
    </section>
  );
}
