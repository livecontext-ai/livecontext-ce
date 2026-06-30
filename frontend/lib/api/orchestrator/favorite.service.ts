/**
 * Resource Favorite Service
 *
 * Per-user, workspace-scoped favorites for the caller's OWN native resources
 * (workflows / tables / interfaces / agents). The native counterpart to
 * {@link publicationService}'s application favorites (which star marketplace
 * publications). Backed by orchestrator-service `/api/favorites/{type}/...`
 * (one generic store, keyed by resource type + id).
 *
 * Single Responsibility: only the favorite star toggle + the favorited-id set
 * each list page reads to float favorites to the top.
 */

import { apiClient } from '../api-client';

/** The native resource kinds that carry a favorites star. Matches the backend enum. */
export type FavoriteResourceType = 'WORKFLOW' | 'TABLE' | 'INTERFACE' | 'AGENT';

export class FavoriteService {
  /**
   * The ids of the caller's favorited resources of one type, for the active
   * workspace (newest-favorited-first). Used to paint each card's star and to
   * float favorites to the top of the list.
   */
  async getFavoriteIds(type: FavoriteResourceType): Promise<string[]> {
    const res = await apiClient.get<{ ids: string[] }>(`/favorites/${type.toLowerCase()}/ids`);
    return res.ids || [];
  }

  /** Star a resource for the caller's active workspace. Idempotent. */
  async addFavorite(type: FavoriteResourceType, resourceId: string): Promise<void> {
    await apiClient.post(`/favorites/${type.toLowerCase()}/${encodeURIComponent(resourceId)}`);
  }

  /** Unstar a resource for the caller's active workspace. Idempotent. */
  async removeFavorite(type: FavoriteResourceType, resourceId: string): Promise<void> {
    await apiClient.delete(`/favorites/${type.toLowerCase()}/${encodeURIComponent(resourceId)}`);
  }
}

export const favoriteService = new FavoriteService();
