/**
 * Favorites routing for the Applications page.
 *
 * An app card's star maps to ONE of two backend favorite stores depending on
 * provenance:
 *  - PUBLISHED apps -> publication favorites, keyed by the publication id
 *    (`/api/publications/favorites`).
 *  - ACQUIRED (installed) apps -> NATIVE workflow favorites, keyed by the local
 *    CLONED workflow id (`/api/favorites/workflow/{id}`). A cloud-acquired app's
 *    publication id is a REMOTE id absent from the local publications table, whose
 *    favorites table has a NOT NULL FK to it - so favoriting the publication id
 *    400s/FK-fails (the original "Could not update favorites" bug). The acquirer
 *    owns the local clone, so its workflow is the correct, FK-safe favorite key.
 *
 * Pure functions so the routing + the unified "is favorited" derivation are unit
 * testable without rendering the page.
 */
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import type { AppSource } from '@/components/applications/ApplicationCard';

/** The minimal app shape the favorites logic needs. */
export interface AppFavItem {
  pub: WorkflowPublication;
  source: AppSource;
  /** The acquirer's local cloned workflow id (present for acquired apps). */
  workflowId?: string;
}

/** Where an app's star toggles. `null` = not favoritable (acquired app missing its clone id). */
export type FavoriteTarget =
  | { kind: 'publication'; id: string }
  | { kind: 'workflow'; id: string }
  | null;

/** The favorite store + key an app's star toggles, per provenance. */
export function favoriteTargetFor(item: AppFavItem): FavoriteTarget {
  if (item.source === 'acquired') {
    return item.workflowId ? { kind: 'workflow', id: item.workflowId } : null;
  }
  return { kind: 'publication', id: item.pub.id };
}

/**
 * Publication ids considered favorited, unifying the two stores: a PUBLISHED app
 * is favorited when its publication id is starred; an ACQUIRED app is favorited
 * when its local cloned workflow is starred. The result is keyed by publication id
 * so it drives both the card star and the favorites-first sort (which partition on
 * publication id).
 */
export function deriveFavoritePubIds(
  items: AppFavItem[],
  pubFavoriteIds: ReadonlySet<string>,
  wfFavoriteIds: ReadonlySet<string>,
): Set<string> {
  const out = new Set<string>();
  for (const item of items) {
    const favorited = item.source === 'published'
      ? pubFavoriteIds.has(item.pub.id)
      : !!item.workflowId && wfFavoriteIds.has(item.workflowId);
    if (favorited) out.add(item.pub.id);
  }
  return out;
}
