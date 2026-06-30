/**
 * Recent Activity Service
 *
 * Bell's 3rd-tab feed - top-50 most-recently-edited resources across
 * workflows, applications, interfaces, agents, skills, and tables in the
 * caller's active workspace. Read-only; no pagination, no polling
 * (frontend caches via react-query staleTime).
 *
 * Mirrors `RecentActivityResponseDto` + `RecentActivityItemDto` shape in
 * `backend/common-lib/src/main/java/com/apimarketplace/common/recentactivity/`.
 */

import { apiClient } from '../api-client';

/**
 * One of 6 resource kinds - drives the per-row icon + the filter chip.
 * APPLICATION and WORKFLOW share the underlying `workflows` table but
 * render differently in the UI (separate filter chip + different routing
 * target via `/app/applications/{publicationId}` vs `/app/workflow/{id}`).
 */
export type ResourceKind =
  | 'WORKFLOW'
  | 'APPLICATION'
  | 'INTERFACE'
  | 'AGENT'
  | 'SKILL'
  | 'TABLE';

export interface RecentActivityItem {
  kind: ResourceKind;
  resourceId: string;
  name: string;
  /** ISO-8601 instant of the row's `updated_at`. */
  lastEditedAt: string;
  /** {@code tenant_id} of the row owner - resolves to {@link actorDisplayName}
   *  via the aggregator's batch user lookup (cache-aware in AuthClient). */
  actorId?: string;
  /** Resolved display name from auth-service - may be null when the user
   *  was deleted or {@code actorId} doesn't match a UserOnboarding row. */
  actorDisplayName?: string;
  /** Always null in v3.3.1 (UserOnboarding lacks an avatar column -
   *  backfill is a follow-up). */
  actorAvatarUrl?: string;
  /**
   * APPLICATION-only: the backing workflow's {@code source_publication_id}.
   * The application page is keyed by PUBLICATION id, not workflow id, so an
   * APPLICATION row routes to {@code /app/applications/{publicationId}}. Absent
   * for every other kind (and for legacy applications with no publication, where
   * the row falls back to the workflow editor). Mirrors the Triggers tab's
   * {@code ActiveAutomation.publicationId}.
   */
  publicationId?: string;
}

export interface RecentActivityResponse {
  items: RecentActivityItem[];
  /** Count of items the user has in the OTHER scope (capped at 50).
   *  Zero when in personal scope (no cross-org aggregation). */
  peerScopeCount: number;
  /** {@code 'Personal'} when in an org workspace with peer items, else
   *  null. Drives the cross-scope empty-state hint. */
  peerScopeLabel?: string;
}

export class RecentActivityService {
  /**
   * Fetch the top-50 most-recently-edited resources in the active
   * workspace. Server-side scope routing reads X-User-ID +
   * X-Organization-ID (gateway-injected from the JWT + active-org claim);
   * the client passes no params.
   */
  async getRecentActivity(): Promise<RecentActivityResponse> {
    return apiClient.get<RecentActivityResponse>('/activities/recent');
  }
}

export const recentActivityService = new RecentActivityService();

/**
 * Ordered chip strip - matches the visual placement in the Activity tab
 * filter row. Stable ordering avoids chip-shuffling between renders.
 */
export const RESOURCE_KIND_ORDER: ResourceKind[] = [
  'WORKFLOW',
  'APPLICATION',
  'INTERFACE',
  'AGENT',
  'SKILL',
  'TABLE',
];
