'use client';

import { Lock, Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { isPublicVisibility } from '@/lib/utils/visibility';

type Visibility = 'PUBLIC' | 'PRIVATE' | 'UNLISTED' | string | null | undefined;

// Re-export so existing call sites can keep importing the helper from here.
export { isPublicVisibility };

/**
 * Tiny public / private indicator for an OWN publication's card footer: a Globe when the app is
 * listed on the marketplace (`PUBLIC`), a Lock when it is private (`PRIVATE` / `UNLISTED`). Render
 * it only for the viewer's OWN publications - acquired apps carry the publisher's visibility, not
 * the viewer's, so a lock there would be misleading.
 */
export function VisibilityBadge({ visibility, className }: { visibility: Visibility; className?: string }) {
  const t = useTranslations('common');
  const isPublic = isPublicVisibility(visibility);
  const Icon = isPublic ? Globe : Lock;
  const label = isPublic ? t('visibilityPublic') : t('visibilityPrivate');
  return (
    <span className={`shrink-0 text-theme-muted ${className ?? ''}`} title={label} aria-label={label}>
      <Icon className="h-3 w-3" />
    </span>
  );
}
