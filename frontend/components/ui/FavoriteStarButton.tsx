'use client';

import { Star } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';

interface FavoriteStarButtonProps {
  isFavorite: boolean;
  onToggle: () => void;
  /** Extra classes (e.g. to override the default bottom-left placement). */
  className?: string;
}

/**
 * The favorites star overlaid on a resource card's thumbnail. Always visible once
 * favorited (so the user sees their picks at a glance); hover/focus-revealed
 * otherwise. Identical look to the Applications card star. Requires a positioned
 * ancestor (`relative`) and the card root to carry `group` for the hover reveal.
 *
 * Stops propagation so it never triggers the card's own navigation. The
 * accessible label is localized here (`common.addToFavorites` /
 * `common.removeFromFavorites`).
 */
export function FavoriteStarButton({ isFavorite, onToggle, className }: FavoriteStarButtonProps) {
  const t = useTranslations('common');
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); e.preventDefault(); onToggle(); }}
      aria-pressed={isFavorite}
      aria-label={isFavorite ? t('removeFromFavorites') : t('addToFavorites')}
      title={isFavorite ? t('removeFromFavorites') : t('addToFavorites')}
      className={cn(
        'absolute bottom-3 left-3 z-20 inline-flex items-center justify-center h-7 w-7 rounded-full backdrop-blur bg-white/80 dark:bg-black/50 border border-white/40 dark:border-white/10 shadow-sm transition-opacity',
        isFavorite
          ? 'opacity-100 text-amber-500'
          : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100 text-theme-secondary hover:text-theme-primary',
        className,
      )}
    >
      <Star className={cn('h-3.5 w-3.5', isFavorite && 'fill-current')} />
    </button>
  );
}
