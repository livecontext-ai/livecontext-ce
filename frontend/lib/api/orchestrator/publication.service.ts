/**
 * Publication Service
 *
 * Handles workflow publications, categories, and marketplace operations.
 * Single Responsibility: Only publication-related operations.
 */

import { apiClient } from '../api-client';

export type HighlightDisplayMode =
  | 'WORKFLOW'
  | 'INTERFACE'
  | 'APPLICATION'
  | 'AGENT'
  | 'TABLE'
  | 'SKILL'
  // Curated row for the public landing page. NOT a publication type - its bucket
  // holds APPLICATION-type publications, curated separately from the APPLICATION
  // bucket (which drives the in-chat highlights row).
  | 'LANDING';

/**
 * Slim publication shape returned by `/api/publications/highlights/{displayMode}`.
 *
 * NOTE: this is NOT the full {@link import('./types').WorkflowPublication} shape.
 * Heavy and sensitive jsonb columns (`planSnapshot`, `agentSnapshot`,
 * `showcaseSnapshot`) plus several agent-specific fields (`agentAvatarUrl`,
 * `agentModelProvider`, `agentModelName`, `category*`, `useCount`) are
 * intentionally absent - anonymous callers have no business reading those.
 *
 * If you add a future highlights row that needs additional fields, extend
 * `PublicHighlightItem.java` (backend) AND this type. Don't assume the full
 * `WorkflowPublication` shape is available here.
 */
export interface PublicHighlightedPublication {
  id: string;
  title: string;
  description?: string;
  displayMode: HighlightDisplayMode;
  creditsPerUse?: number;
  publisherId?: string;
  publisherName?: string;
  publisherAvatarUrl?: string;
  showcaseInterfaceId?: string;
  showcaseRunId?: string;
  nodeIcons?: Array<Record<string, unknown>>;
  agentCount?: number;
  skillCount?: number;
  workflowCount?: number;
  interfaceCount?: number;
  datasourceCount?: number;
  averageRating?: number;
  reviewCount?: number;
}

export interface HighlightedPublicationItem {
  rank: number;
  /**
   * For the public endpoint this is the slim {@link PublicHighlightedPublication}.
   * For the admin endpoint (`getAdminHighlights`) the backend returns the full
   * `WorkflowPublication` entity. The union covers both - narrow with an
   * `'planSnapshot' in pub` check or branch by which method you called.
   */
  publication: PublicHighlightedPublication | import('./types').WorkflowPublication | null;
}

export interface HighlightsResponse {
  displayMode: HighlightDisplayMode;
  highlights: HighlightedPublicationItem[];
}

/**
 * Response shape for `GET /api/publications/favorites` - the caller's personal
 * favorited applications, hydrated to the same slim card DTO as the highlights
 * row (so {@link import('@/components/chat/HighlightedApps')} can render either
 * with one code path).
 */
export interface FavoritesResponse {
  favorites: PublicHighlightedPublication[];
}

import type {
  WorkflowPublication,
  PublishWorkflowRequest,
  UpdatePublicationRequest,
  PublicationsListResponse,
  MarketplacePublicationsResponse,
  WorkflowCategory,
  CategoriesListResponse,
  AcquirePublicationResponse,
  AcquiredApplication,
  AcquiredApplicationsResponse,
  PurchasesResponse,
  PublicationReview,
  PublicationReviewsResponse,
  PublicationRepliesResponse,
  PublishAgentRequest,
  AcquireAgentResponse,
  AgentPublicationSnapshot,
  ModerationStats,
  PendingPublicationsResponse,
  PublicationComparisonData,
  PublishResourceRequest,
  ResourcePublicationResponse,
  ResourcePublicationStatus,
  ResourceType,
  InterfaceRenderResult,
} from './types';

export class PublicationService {
  // ========================================
  // Workflow Publications
  // ========================================

  /**
   * Publish a workflow (create or update publication).
   */
  async publishWorkflow(request: PublishWorkflowRequest): Promise<WorkflowPublication> {
    return apiClient.post<WorkflowPublication>('/publications', request);
  }

  /**
   * Update publication info (without re-snapshotting the plan).
   */
  async updatePublication(publicationId: string, request: UpdatePublicationRequest): Promise<WorkflowPublication> {
    return apiClient.put<WorkflowPublication>(`/publications/${publicationId}`, request);
  }

  /**
   * Unpublish a workflow (set status to INACTIVE).
   */
  async unpublishWorkflow(workflowId: string): Promise<WorkflowPublication> {
    return apiClient.post<WorkflowPublication>(`/publications/workflow/${workflowId}/unpublish`);
  }

  /**
   * Delete a publication permanently.
   */
  async deletePublication(workflowId: string): Promise<void> {
    return apiClient.delete(`/publications/workflow/${workflowId}`);
  }

  /**
   * Get publication by workflow ID.
   */
  async getPublicationByWorkflowId(workflowId: string): Promise<WorkflowPublication & { published: boolean }> {
    return apiClient.get<WorkflowPublication & { published: boolean }>(`/publications/workflow/${workflowId}`);
  }

  /**
   * Get publication by ID with the current auth context. Required for private
   * owner/acquirer application views and share-token views.
   */
  async getPublicationById(publicationId: string): Promise<WorkflowPublication> {
    return apiClient.get<WorkflowPublication>(`/publications/${publicationId}`);
  }

  /**
   * CE-cloud parity router for the public per-publication reads. A cloud-linked
   * CE browses CLOUD publications, whose ids are absent from the local DB - so
   * hitting the local `/publications/by-id/...` endpoints 404s (broken card
   * thumbnails + a 404 detail page). Pass {@code remote=true} to route the read
   * through the CE backend's cloud proxy (`/publications/remote/by-id/...`,
   * forwarded to `marketplace.cloud-api-url`). The proxy is an authenticated CE
   * route so the caller's token is sent; the local public read stays anonymous.
   */
  private byIdRead(publicationId: string, suffix: string, remote?: boolean): { path: string; skipAuth: boolean } {
    const base = remote ? '/publications/remote/by-id' : '/publications/by-id';
    return { path: `${base}/${publicationId}${suffix}`, skipAuth: !remote };
  }

  /**
   * Get publication by ID as an anonymous/public preview reader. Marketplace
   * previews use this deliberately so owners see the same sanitized preview as
   * signed-out visitors. Pass {@code remote=true} on a cloud-linked CE (see
   * {@link byIdRead}).
   */
  async getPublicationByIdPublic(publicationId: string, remote = false): Promise<WorkflowPublication> {
    const { path, skipAuth } = this.byIdRead(publicationId, '', remote);
    return apiClient.get<WorkflowPublication>(path, { skipAuth });
  }

  /**
   * Get the landing-page interface snapshot embedded in a publication.
   * Returns null landing when the publication has no embedded landing page.
   */
  async getLandingSnapshot(publicationId: string, remote = false): Promise<{
    publicationId: string;
    type: string | null;
    title: string | null;
    description: string | null;
    landing: {
      htmlTemplate?: string | null;
      cssTemplate?: string | null;
      jsTemplate?: string | null;
      interfaceType?: string | null;
      data?: unknown;
    } | null;
  }> {
    // Public endpoint: allowlisted on the gateway so anonymous visitors can
    // render marketplace thumbnails without signing in. remote=true → cloud proxy.
    const { path, skipAuth } = this.byIdRead(publicationId, '/landing-snapshot', remote);
    return apiClient.get(path, { skipAuth });
  }

  /**
   * Public aggregated-step list for a showcase clone, optionally filtered by
   * epoch. Mirrors {@code GET /v2/workflows/dag/instances/{runId}/steps/aggregated}.
   * Used by the RunInfo panel in the marketplace preview to display the
   * epoch-scoped step rows.
   */
  async getShowcaseAggregatedSteps(publicationId: string, epoch?: number, remote = false): Promise<any[]> {
    const params = epoch !== undefined ? { epoch: String(epoch) } : undefined;
    const { path, skipAuth } = this.byIdRead(publicationId, '/aggregated-steps', remote);
    return apiClient.get<any[]>(path, { params, skipAuth });
  }

  /**
   * Public per-epoch status counts for a showcase clone. Shape mirrors the
   * auth'd {@code GET /v2/workflows/dag/runs/{runId}/epochs/{epoch}/state}.
   */
  async getShowcaseEpochState(publicationId: string, epoch: number, remote = false): Promise<any> {
    const { path, skipAuth } = this.byIdRead(publicationId, `/epochs/${epoch}/state`, remote);
    return apiClient.get<any>(path, { skipAuth });
  }

  /**
   * Public active-signals list for a specific epoch of a showcase clone.
   * Shape mirrors {@code GET /v2/workflows/dag/runs/{runId}/epochs/{epoch}/signals}.
   */
  async getShowcaseEpochSignals(publicationId: string, epoch: number, remote = false): Promise<any[]> {
    const { path, skipAuth } = this.byIdRead(publicationId, `/epochs/${epoch}/signals`, remote);
    return apiClient.get<any[]>(path, { skipAuth });
  }

  /**
   * In-flight `/run-state` GETs keyed by publicationId. Symmetric to
   * ExecutionService.inFlightStateGets but in a separate Map: runIds and
   * publicationIds occupy distinct namespaces, so collision is impossible
   * by construction.
   */
  private inFlightShowcaseStates = new Map<string, Promise<any>>();

  /**
   * Fetch the frozen run state of a publication's showcase clone (public).
   *
   * Returns the same shape as the auth'd {@code GET /v2/workflows/dag/runs/{runId}/state}
   * (plan, steps, edges, epochTimestamps, completed/failed/skipped sets,
   * currentEpoch, seq, …) so the WorkflowRunManager can hydrate the
   * marketplace preview - status counts, calendar icon, epoch navigation,
   * etc. - without hitting the auth'd endpoint.
   *
   * Concurrent calls for the same publicationId share one HTTP.
   */
  async getShowcaseRunState(publicationId: string, remote = false): Promise<any> {
    // Key by mode too: a local and a cloud read for the same id are distinct calls.
    const key = `${remote ? 'remote:' : ''}${publicationId}`;
    const existing = this.inFlightShowcaseStates.get(key);
    if (existing) return existing;
    const { path, skipAuth } = this.byIdRead(publicationId, '/run-state', remote);
    const p = apiClient
      .get<any>(path, { skipAuth })
      .finally(() => { this.inFlightShowcaseStates.delete(key); });
    this.inFlightShowcaseStates.set(key, p);
    return p;
  }

  /**
   * Render a publication's frozen showcase clone (public, marketplace-only).
   *
   * Backend resolves {showcaseRunId} from the publication entity - callers
   * never pass raw run IDs, which would otherwise let an attacker probe
   * arbitrary workflow runs. The {@code interfaceId} is optional and, when
   * omitted, defaults to the publication's landing {@code showcaseInterfaceId};
   * pass an explicit id to render any interface that belongs to the same
   * frozen workflow (e.g. InterfacePreviewNode instances rendered in the
   * side-panel workflow canvas). Returns the exact same shape as
   * {@link InterfaceService.renderInterface} so the UI can reuse its existing
   * rendering pipeline.
   */
  async getShowcaseRender(
    publicationId: string,
    options: { interfaceId?: string; page?: number; size?: number; epoch?: number; authenticated?: boolean } = {},
    remote = false
  ): Promise<InterfaceRenderResult> {
    const { interfaceId, page = 0, size = 1, epoch, authenticated } = options;
    const params: Record<string, string | number> = { page, size };
    if (interfaceId) params.interfaceId = interfaceId;
    if (epoch != null) params.epoch = epoch;
    // Acquirer/owner read: hit the AUTH'D endpoint so the receipt bypass admits a
    // publication the caller installed even after the publisher made it non-public.
    // The anonymous /by-id path 403s for non-public pubs (correct for marketplace
    // visitors). Cloud-linked CE (remote) keeps the by-id proxy path.
    if (authenticated && !remote) {
      return apiClient.get<InterfaceRenderResult>(
        `/publications/${publicationId}/showcase-render`,
        { params }
      );
    }
    const { path, skipAuth } = this.byIdRead(publicationId, '/showcase-render', remote);
    return apiClient.get<InterfaceRenderResult>(path, { params, skipAuth });
  }

  /**
   * Get my publications (published by current user).
   * @param applicationOnly when true, only returns WORKFLOW publications with a showcase
   *                        interface (i.e. actual applications). Standalone AGENT / TABLE /
   *                        INTERFACE / SKILL publications are filtered out server-side so
   *                        they never leak into /app/applications.
   */
  async getMyPublications(applicationOnly: boolean = false): Promise<PublicationsListResponse> {
    return apiClient.get<PublicationsListResponse>('/publications/my', {
      params: applicationOnly ? { applicationOnly: 'true' } : undefined,
    });
  }

  /**
   * Paged + DB-searchable variant of {@link getMyPublications}.
   * `q` is matched server-side against title + description (ILIKE).
   * Returns the full envelope: { items, totalCount, page, size }.
   */
  async getMyPublicationsPage(options: {
    applicationOnly?: boolean;
    page?: number;
    size?: number;
    q?: string;
  } = {}): Promise<{ items: WorkflowPublication[]; totalCount: number; page: number; size: number }> {
    const params: Record<string, string> = {};
    if (options.applicationOnly) params.applicationOnly = 'true';
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    const data = await apiClient.get<any>('/publications/my/paged', { params });
    return {
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 25,
    };
  }

  /**
   * Fetch ALL of the caller's publications (every type, every status except INACTIVE), paging
   * through `/publications/my/paged` in 100-row batches. Resource listing pages use this to resolve
   * each card's shared / private / in-review / rejected state in ONE sweep - instead of one
   * `is-resource-published` / `is-agent-published` call per item, which saturates the browser's
   * connection pool on large lists.
   */
  async getAllMyPublications(): Promise<WorkflowPublication[]> {
    const all: WorkflowPublication[] = [];
    let page = 0;
    for (;;) {
      const res = await this.getMyPublicationsPage({ applicationOnly: false, page, size: 100 });
      all.push(...res.items);
      if (all.length >= res.totalCount || res.items.length === 0) break;
      page++;
    }
    return all;
  }

  /**
   * Get marketplace publications (active). The route is public (anonymous browse is supported),
   * but we use optionalAuth so a signed-in caller's JWT is sent: the gateway then injects the
   * active workspace and the server can mark apps owned by it as "Installed" (vs "Acquire").
   * Anonymous callers still work (no token → anonymous request).
   */
  async getMarketplacePublications(page: number = 0, size: number = 20, categorySlug?: string): Promise<MarketplacePublicationsResponse> {
    return apiClient.get<MarketplacePublicationsResponse>('/publications/marketplace', {
      params: { page, size, category: categorySlug },
      optionalAuth: true,
    });
  }

  /**
   * A user's published apps, shown on the in-app profile page (/app/u/{userId}).
   * Public endpoint - allowlisted on the gateway; returns ACTIVE + PUBLIC only.
   * Same response shape as the marketplace listing.
   */
  async getByPublisher(userId: string, page: number = 0, size: number = 20): Promise<MarketplacePublicationsResponse> {
    return apiClient.get<MarketplacePublicationsResponse>(`/publications/by-publisher/${userId}`, {
      params: { page, size },
      skipAuth: true,
    });
  }

  /**
   * Onboarding "suggested applications" - personalized public marketplace
   * applications derived from the caller's onboarding choices. The backend
   * (OnboardingCategoryMapper) maps interests / useCases / profession to
   * category slugs and returns matching applications, with a top-applications
   * fallback when none match. Array params are comma-joined (Spring binds them
   * back to a List<String>).
   */
  async getSuggestedApplications(opts: {
    interests?: string[];
    useCases?: string[];
    profession?: string;
    limit?: number;
  } = {}): Promise<PublicationsListResponse> {
    const params: Record<string, string> = { limit: String(opts.limit ?? 8) };
    if (opts.interests?.length) params.interests = opts.interests.join(',');
    if (opts.useCases?.length) params.useCases = opts.useCases.join(',');
    if (opts.profession) params.profession = opts.profession;
    return apiClient.get<PublicationsListResponse>('/publications/suggestions', { params });
  }

  /**
   * Get admin-curated highlighted publications for a given displayMode.
   * Public endpoint (anonymous-accessible) - backed by Caffeine cache (60s).
   * Filters server-side to ACTIVE+PUBLIC, so deactivated entries don't leak.
   */
  async getHighlights(displayMode: HighlightDisplayMode): Promise<HighlightsResponse> {
    return apiClient.get<HighlightsResponse>(`/publications/highlights/${displayMode}`, {
      skipAuth: true,
    });
  }

  /**
   * Admin-only: list curated highlights (includes stale rows so admins can clean up).
   */
  async getAdminHighlights(displayMode: HighlightDisplayMode): Promise<HighlightsResponse> {
    return apiClient.get<HighlightsResponse>(`/publications/admin/highlights/${displayMode}`);
  }

  /**
   * Admin-only: replace the full ordered list of highlights for a displayMode.
   * Validation is all-or-nothing - any unknown / wrong-mode / non-PUBLIC id rejects
   * the entire reorder.
   */
  async replaceHighlights(displayMode: HighlightDisplayMode, orderedIds: string[]): Promise<void> {
    await apiClient.put(`/publications/admin/highlights/${displayMode}`, { orderedIds });
  }

  // ========================================
  // Per-user favorites (personal "Favorites" view on Home)
  // ========================================

  /**
   * The caller's favorited applications (slim card DTO), newest-favorited-first.
   * Authenticated and per-(user, workspace) - the backend scopes by X-User-ID +
   * the active workspace. Favorites whose app was deleted/deactivated are dropped,
   * so this is safe to render directly.
   */
  async getFavorites(): Promise<FavoritesResponse> {
    return apiClient.get<FavoritesResponse>('/publications/favorites');
  }

  /**
   * Lightweight list of the publication ids the caller has favorited - used to
   * paint the filled/empty star on each application card without hydrating the
   * full favorite objects.
   */
  async getFavoriteIds(): Promise<string[]> {
    const res = await apiClient.get<{ ids: string[] }>('/publications/favorites/ids');
    return res.ids || [];
  }

  /** Star an application for the caller's active workspace. Idempotent. */
  async addFavorite(publicationId: string): Promise<void> {
    await apiClient.post(`/publications/${publicationId}/favorite`);
  }

  /** Unstar an application for the caller's active workspace. Idempotent. */
  async removeFavorite(publicationId: string): Promise<void> {
    await apiClient.delete(`/publications/${publicationId}/favorite`);
  }

  /**
   * Search publications by title. Same reasoning as getMarketplacePublications - optionalAuth so a
   * signed-in caller's active workspace reaches the server for per-workspace ownership, while
   * anonymous search still works.
   */
  async searchPublications(query: string, category?: string): Promise<PublicationsListResponse> {
    const params: Record<string, string> = { q: query };
    if (category) params.category = category;
    return apiClient.get<PublicationsListResponse>('/publications/search', {
      params,
      optionalAuth: true,
    });
  }

  /**
   * Get popular publications.
   */
  async getPopularPublications(limit: number = 10): Promise<PublicationsListResponse> {
    return apiClient.get<PublicationsListResponse>('/publications/popular', {
      params: { limit }
    });
  }

  // ========================================
  // Acquire / Applications
  // ========================================

  /**
   * Acquire a publication (clone as new workflow for the current user).
   */
  async acquirePublication(publicationId: string): Promise<AcquirePublicationResponse> {
    return apiClient.post<AcquirePublicationResponse>(`/publications/${publicationId}/acquire`);
  }

  /**
   * Acquire a publication from the cloud marketplace (CE remote mode).
   * Free publications are fetched directly. Paid publications use the linked
   * cloud account's OAuth token for server-to-server authentication.
   */
  async acquireRemotePublication(publicationId: string): Promise<AcquirePublicationResponse> {
    return apiClient.post<AcquirePublicationResponse>(
      `/publications/remote/${publicationId}/acquire`,
      {}
    );
  }

  /**
   * CE remote mode - cloud-parity read proxies (2026-06-10). A cloud-linked CE
   * renders the SAME marketplace UI as cloud; these routes hit the CE backend
   * (`/api/publications/remote/*`), which forwards to the cloud public API
   * configured via `marketplace.cloud-api-url`. The backend is fail-soft: an
   * unreachable cloud yields an empty payload, never an error.
   */
  async getRemoteMarketplacePublications(page: number = 0, size: number = 50, categorySlug?: string): Promise<MarketplacePublicationsResponse> {
    return apiClient.get<MarketplacePublicationsResponse>('/publications/remote/marketplace', {
      params: { page, size, category: categorySlug },
    });
  }

  /** CE remote mode - cloud-parity search proxy (see getRemoteMarketplacePublications). */
  async searchRemotePublications(query: string, category?: string): Promise<PublicationsListResponse> {
    const params: Record<string, string> = { q: query };
    if (category) params.category = category;
    return apiClient.get<PublicationsListResponse>('/publications/remote/search', { params });
  }

  /** CE remote mode - cloud-parity curated highlights proxy (see getRemoteMarketplacePublications). */
  async getRemoteHighlights(displayMode: HighlightDisplayMode): Promise<HighlightsResponse> {
    return apiClient.get<HighlightsResponse>(`/publications/remote/highlights/${displayMode}`);
  }

  /**
   * Get acquired applications for the current user.
   */
  async getAcquiredApplications(): Promise<AcquiredApplicationsResponse> {
    return apiClient.get<AcquiredApplicationsResponse>('/publications/acquired');
  }

  /**
   * Paged + searchable variant of {@link getAcquiredApplications}.
   * `q` matches workflow name + linked publication title/description.
   */
  async getAcquiredApplicationsPage(options: {
    page?: number;
    size?: number;
    q?: string;
  } = {}): Promise<{ items: AcquiredApplication[]; totalCount: number; page: number; size: number }> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    const data = await apiClient.get<any>('/publications/acquired/paged', { params });
    return {
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 25,
    };
  }

  /**
   * Get the current user's APPLICATION workflow for a publication.
   * Works for both publishers and acquirers.
   */
  async getApplicationWorkflow(publicationId: string): Promise<{ workflowId: string } | null> {
    try {
      return await apiClient.get<{ workflowId: string }>(`/publications/${publicationId}/application-workflow`);
    } catch {
      return null;
    }
  }

  /**
   * Get purchases (receipts) for the current user, with re-install status.
   */
  async getPurchases(): Promise<PurchasesResponse> {
    return apiClient.get<PurchasesResponse>('/publications/purchases');
  }

  // ========================================
  // Reviews
  // ========================================

  /**
   * Get reviews for a publication (paginated, newest first).
   *
   * @param onlyWithComment when true, excludes rating-only entries (votes without
   *   text). Set this from the Comments tab so it only shows entries that carry
   *   a real comment. The Info tab continues to source its average and vote
   *   count from {@code publication.averageRating} / {@code publication.reviewCount}.
   */
  async getReviews(
      publicationId: string,
      page: number = 0,
      size: number = 20,
      options?: { onlyWithComment?: boolean }
  ): Promise<PublicationReviewsResponse> {
    const params: Record<string, string | number> = { page, size };
    if (options?.onlyWithComment) params.onlyWithComment = 'true';
    return apiClient.get<PublicationReviewsResponse>(`/publications/${publicationId}/reviews`, { params });
  }

  /**
   * Count of top-level reviews that carry a non-empty comment. Drives the
   * Comments tab badge on the publication panel - distinct from
   * {@code publication.reviewCount} which counts votes.
   */
  async getCommentCount(publicationId: string): Promise<number> {
    try {
      const res = await apiClient.get<{ count: number }>(`/publications/${publicationId}/reviews/comments-count`);
      return res.count ?? 0;
    } catch {
      return 0;
    }
  }

  /**
   * Get current user's review for a publication.
   */
  async getMyReview(publicationId: string): Promise<PublicationReview | null> {
    try {
      return await apiClient.get<PublicationReview>(`/publications/${publicationId}/reviews/mine`);
    } catch {
      return null;
    }
  }

  /**
   * Submit or update a review (upsert).
   */
  async submitReview(publicationId: string, data: { rating?: number; comment?: string }): Promise<PublicationReview> {
    return apiClient.post<PublicationReview>(`/publications/${publicationId}/reviews`, data);
  }

  /**
   * Delete current user's review (both rating and comment in one shot).
   */
  async deleteReview(publicationId: string): Promise<void> {
    return apiClient.delete(`/publications/${publicationId}/reviews`);
  }

  /**
   * Delete only the current user's comment, keeping their star rating. A review
   * that was comment-only is removed entirely. Ratings and comments are separate
   * concerns: deleting a comment from the Comments tab must never wipe the rating.
   */
  async deleteComment(publicationId: string): Promise<void> {
    return apiClient.delete(`/publications/${publicationId}/reviews/comment`);
  }

  /**
   * Delete only the current user's star rating, keeping their comment. A review
   * that was rating-only is removed entirely. Symmetric to {@link deleteComment}.
   */
  async deleteRating(publicationId: string): Promise<void> {
    return apiClient.delete(`/publications/${publicationId}/reviews/rating`);
  }

  // ========================================
  // Replies
  // ========================================

  /**
   * Get replies for a review.
   */
  async getReplies(publicationId: string, reviewId: string): Promise<PublicationRepliesResponse> {
    return apiClient.get<PublicationRepliesResponse>(`/publications/${publicationId}/reviews/${reviewId}/replies`);
  }

  /**
   * Submit a reply to a review.
   */
  async submitReply(publicationId: string, reviewId: string, comment: string): Promise<PublicationReview> {
    return apiClient.post<PublicationReview>(`/publications/${publicationId}/reviews/${reviewId}/replies`, { comment });
  }

  /**
   * Update own reply.
   */
  async updateReply(publicationId: string, replyId: string, comment: string): Promise<PublicationReview> {
    return apiClient.put<PublicationReview>(`/publications/${publicationId}/reviews/replies/${replyId}`, { comment });
  }

  /**
   * Delete own reply.
   */
  async deleteReply(publicationId: string, replyId: string): Promise<void> {
    return apiClient.delete(`/publications/${publicationId}/reviews/replies/${replyId}`);
  }

  // ========================================
  // Categories
  // ========================================

  /**
   * Get all active categories.
   *
   * Public endpoint - the CategoryFilter renders on the marketplace for
   * anonymous visitors. {@code skipAuth: true} bypasses the apiClient's
   * "no token → 401" guard; the gateway allowlists {@code /api/categories}
   * so the request reaches orchestrator without a JWT.
   */
  async getCategories(activeOnly: boolean = true): Promise<CategoriesListResponse> {
    return apiClient.get<CategoriesListResponse>('/categories', {
      params: { activeOnly },
      skipAuth: true,
    });
  }

  /**
   * Get category by ID
   */
  async getCategoryById(id: string): Promise<WorkflowCategory> {
    return apiClient.get<WorkflowCategory>(`/categories/${id}`);
  }

  /**
   * Get category by slug
   */
  async getCategoryBySlug(slug: string): Promise<WorkflowCategory> {
    return apiClient.get<WorkflowCategory>(`/categories/slug/${slug}`);
  }
  // ========================================
  // Agent Publications
  // ========================================

  async publishAgent(request: PublishAgentRequest): Promise<WorkflowPublication> {
    return apiClient.post<WorkflowPublication>('/publications/publish-agent', request);
  }

  async getAgentMarketplace(page = 0, size = 20): Promise<MarketplacePublicationsResponse> {
    const data = await apiClient.get<{
      content: WorkflowPublication[];
      totalElements: number;
      totalPages: number;
      page: number;
      size: number;
    }>('/publications/marketplace/agents', { params: { page: String(page), size: String(size) } });
    return {
      publications: data.content || [],
      count: data.totalElements || 0,
      totalPages: data.totalPages || 0,
      page: data.page || 0,
      size: data.size || size,
    };
  }

  /**
   * Per-type marketplace list - WORKFLOW / TABLE / INTERFACE / SKILL / AGENT.
   * Backed by GET /publications/marketplace/by-type/{type}.
   */
  async getMarketplaceByType(type: ResourceType | 'WORKFLOW' | 'AGENT', page = 0, size = 20): Promise<MarketplacePublicationsResponse> {
    const data = await apiClient.get<{
      content: WorkflowPublication[];
      totalElements: number;
      totalPages: number;
      page: number;
      size: number;
    }>(`/publications/marketplace/by-type/${type}`, { params: { page: String(page), size: String(size) } });
    return {
      publications: data.content || [],
      count: data.totalElements || 0,
      totalPages: data.totalPages || 0,
      page: data.page || 0,
      size: data.size || size,
    };
  }

  async searchAgentMarketplace(query: string): Promise<PublicationsListResponse> {
    const data = await apiClient.get<{
      content: WorkflowPublication[];
      totalElements: number;
    }>('/publications/marketplace/agents/search', { params: { query } });
    return {
      publications: data.content || [],
      count: data.totalElements || 0,
    };
  }

  async acquireAgentPublication(publicationId: string): Promise<AcquireAgentResponse> {
    return apiClient.post<AcquireAgentResponse>(`/publications/acquire-agent/${publicationId}`);
  }

  async isAgentPublished(agentConfigId: string): Promise<boolean> {
    const data = await apiClient.get<{ published: boolean }>(`/publications/is-agent-published/${agentConfigId}`);
    return data.published;
  }

  async getAgentPublicationStatus(agentConfigId: string): Promise<{ exists: boolean; status?: string; published: boolean; rejectionReason?: string | null }> {
    return apiClient.get<{ exists: boolean; status?: string; published: boolean; rejectionReason?: string | null }>(`/publications/is-agent-published/${agentConfigId}`);
  }

  async unpublishAgent(agentConfigId: string): Promise<void> {
    await apiClient.post(`/publications/unpublish-agent/${agentConfigId}`);
  }

  async getAgentSnapshot(publicationId: string, remote = false): Promise<AgentPublicationSnapshot> {
    // Public endpoint under /by-id prefix - same reasoning as getLandingSnapshot:
    // anonymous visitors must be able to render the agent-fleet canvas on the
    // marketplace preview without signing in. remote=true → cloud proxy.
    const { path, skipAuth } = this.byIdRead(publicationId, '/agent-snapshot', remote);
    return apiClient.get<AgentPublicationSnapshot>(path, { skipAuth });
  }

  // ========================================
  // Standalone Resource Publications (TABLE / INTERFACE / SKILL)
  // ========================================

  async publishResource(request: PublishResourceRequest): Promise<ResourcePublicationResponse> {
    return apiClient.post<ResourcePublicationResponse>('/publications/publish-resource', request);
  }

  async unpublishResource(type: ResourceType, resourceId: string): Promise<void> {
    await apiClient.post(`/publications/unpublish-resource/${type}/${resourceId}`);
  }

  async getResourcePublicationStatus(type: ResourceType, resourceId: string): Promise<ResourcePublicationStatus> {
    return apiClient.get<ResourcePublicationStatus>(`/publications/is-resource-published/${type}/${resourceId}`);
  }

  async acquireResourcePublication(publicationId: string): Promise<{ resourceId: string; type: ResourceType }> {
    return apiClient.post(`/publications/acquire-resource/${publicationId}`);
  }

  // ---- Admin Moderation ----

  async getPendingPublications(page = 0, size = 20): Promise<PendingPublicationsResponse> {
    return apiClient.get<PendingPublicationsResponse>('/publications/admin/pending', {
      params: { page: String(page), size: String(size) },
    });
  }

  async getPublicationForReview(publicationId: string): Promise<WorkflowPublication> {
    return apiClient.get<WorkflowPublication>(`/publications/admin/${publicationId}`);
  }

  async getComparisonData(publicationId: string): Promise<PublicationComparisonData> {
    return apiClient.get<PublicationComparisonData>(`/publications/admin/${publicationId}/comparison`);
  }

  async approvePublication(publicationId: string): Promise<WorkflowPublication> {
    return apiClient.post<WorkflowPublication>(`/publications/admin/${publicationId}/approve`);
  }

  async rejectPublication(publicationId: string, reason?: string): Promise<WorkflowPublication> {
    return apiClient.post<WorkflowPublication>(`/publications/admin/${publicationId}/reject`, { reason });
  }

  async getModerationStats(): Promise<ModerationStats> {
    return apiClient.get<ModerationStats>('/publications/admin/stats');
  }
}

export const publicationService = new PublicationService();
