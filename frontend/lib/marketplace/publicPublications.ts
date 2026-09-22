/**
 * Server-side reads of the public marketplace, for the crawlable pages.
 *
 * <p>This is the first server-component data path in the app that talks to our
 * OWN backend (the only prior server fetch, `lib/changelog/githubReleases.ts`,
 * targets GitHub), so it deliberately mirrors that module's shape: a pure
 * mapper that can be unit-tested without I/O, plus a thin fetch wrapper that
 * degrades to an empty result instead of taking a public page down.
 *
 * Three deliberate choices, each of which has a wrong-looking easy alternative:
 *
 * 1. It calls the gateway DIRECTLY, not `/api/proxy/*`. Going through the proxy
 *    from the server would be an HTTP hop to ourselves, plus the CORS and token
 *    rewriting in `proxy.ts` that a server render has no use for.
 * 2. It does NOT use `lib/api/api-client`. That client is browser-bound: its
 *    base URL is the relative `/api/proxy` (which `fetch` cannot resolve without
 *    an origin), it sends `credentials: 'include'`, it throws when no auth token
 *    is installed, and it is a `globalThis` singleton, so giving it a token on
 *    the server would share that token across concurrent requests.
 * 3. It reads `GATEWAY_SERVICE_URL` first. `NEXT_PUBLIC_*` variables are inlined
 *    at BUILD time; the non-public one is injected into the pod at runtime
 *    (helm `commonEnv`), so it stays correct if the cluster's service DNS
 *    changes without a rebuild.
 *
 * These endpoints are anonymous by design at the gateway, so no credentials are
 * ever attached here. Any response that would require a user context must NOT
 * be fetched through this module.
 */
import 'server-only';

import { IS_MANAGED_CLOUD } from '@/lib/edition';

import type { NodeIconData } from '@/lib/api/orchestrator/types';

/** How long a public marketplace page may serve stale data, in seconds. */
export const PUBLIC_MARKETPLACE_REVALIDATE_SECONDS = 900;

/**
 * Cache window for a showcase render, DELIBERATELY shorter than the listing's.
 *
 * <p>A showcase's media are addressed by HMAC-signed anonymous URLs
 * (`/api/files/proxy-signed?...&sig=...`) that expire after 15 minutes. Caching
 * the render for the listing's own 900s window would mean a page served late in
 * that window hands the visitor URLs with seconds of life left, so the app
 * preview would intermittently render with every image and video broken - and
 * it would look like a broken publication, not an expired link. The in-app card
 * previews never hit this because they fetch the render in the browser at view
 * time; a server-rendered page has to leave itself margin.
 */
export const PUBLIC_SHOWCASE_REVALIDATE_SECONDS = 300;

/** A marketplace listing as the public pages need it. */
export interface PublicPublicationSummary {
  id: string;
  /** URL slug backing /marketplace/{slug}. Null on rows predating the backfill. */
  publicSlug: string | null;
  title: string;
  description: string;
  publisherName: string | null;
  /**
   * Internal user id of the publisher. Read ONLY to address their public
   * avatar (`/api/proxy/users/{id}/avatar`, served anonymously); never
   * rendered as text.
   */
  publisherId: string | null;
  /** Author @handle, or null when the publisher has no public profile. */
  publisherHandle: string | null;
  publisherAvatarUrl: string | null;
  categorySlug: string | null;
  categoryName: string | null;
  averageRating: number;
  reviewCount: number;
  useCount: number;
  publishedAt: string | null;
  updatedAt: string | null;
  publicationType: string;
  /** Accent colour of the category, for the listing's own chrome. */
  categoryColor: string | null;
  /** How the publisher presents it: `APPLICATION` or `WORKFLOW`. */
  displayMode: string | null;
  /** Credits one run costs an acquirer. 0 = free. */
  creditsPerUse: number;
  /** True when the publisher froze a showcase, i.e. a preview can be rendered. */
  hasShowcase: boolean;
  /**
   * Integration + node glyphs the backend already computed for cards. Shaped as
   * `NodeIcon` props, so the public pages render the SAME icons the app does.
   */
  nodeIcons: NodeIconData[];
  /** What the listing is made of. Zeroes when the publisher published a bare workflow. */
  agentCount: number;
  interfaceCount: number;
  workflowCount: number;
  skillCount: number;
  datasourceCount: number;
  /**
   * The published plan, credential-scrubbed and position-stripped by the
   * backend. Left as `unknown`: only `buildPublicGraph` reads it, and it is
   * defensive about every field, so no shape is asserted here.
   */
  planSnapshot: unknown;
}

/**
 * Base URL of the gateway as seen from the Next.js server process.
 * Never falls back to a public origin: a public URL here would send server
 * renders back out through the internet (and through Cloudflare) instead of
 * straight to the in-cluster service.
 */
export function gatewayBaseUrl(): string {
  return (
    process.env.GATEWAY_SERVICE_URL ||
    process.env.NEXT_PUBLIC_SPRING_BASE_URL ||
    'http://localhost:8080'
  );
}

/**
 * Slug shape produced by the backend generator: lowercase alphanumerics joined
 * by single hyphens, capped at the column width.
 *
 * Validated BEFORE any fetch. `/marketplace/{slug}` is a dynamic route, so every
 * distinct URL a scanner invents would otherwise become one gateway request
 * from the SSR pod, all sharing a single anonymous rate-limit bucket. Rejecting
 * junk locally turns a URL scan into cheap local 404s instead of load on the
 * gateway (and, at volume, 429s that would make legitimate pages render empty).
 */
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const SLUG_MAX_LENGTH = 120;

export function isValidSlugFormat(slug: string): boolean {
  if (typeof slug !== 'string') return false;
  if (slug.length === 0 || slug.length > SLUG_MAX_LENGTH) return false;
  return SLUG_PATTERN.test(slug);
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * Keep only the entries that can actually drive a `NodeIcon`. An icon row is
 * decoration: one malformed entry must not cost the page its whole icon strip,
 * so bad entries are dropped individually rather than failing the list.
 */
function asNodeIcons(value: unknown): NodeIconData[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is NodeIconData => typeof entry === 'object' && entry !== null,
  );
}

/**
 * Map one raw publication object from the backend into a view model.
 *
 * Defensive on purpose: the public pages render whatever the marketplace
 * happens to contain, including rows written before any given field existed.
 * A row without an id or a title is unusable for a page and is dropped by
 * {@link mapPublications} rather than rendered half-empty.
 */
export function mapPublication(raw: unknown): PublicPublicationSummary | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;

  const id = asString(row.id);
  const title = asString(row.title);
  if (!id || !title) return null;

  const category = (typeof row.category === 'object' && row.category !== null
    ? (row.category as Record<string, unknown>)
    : {}) as Record<string, unknown>;

  return {
    id,
    publicSlug: asString(row.publicSlug),
    title,
    description: asString(row.description) ?? '',
    publisherName: asString(row.publisherName),
    publisherId: asString(row.publisherId),
    publisherHandle: asString(row.publisherHandle),
    publisherAvatarUrl: asString(row.publisherAvatarUrl),
    categorySlug: asString(category.slug),
    categoryName: asString(category.name),
    averageRating: asNumber(row.averageRating),
    reviewCount: asNumber(row.reviewCount),
    useCount: asNumber(row.useCount),
    publishedAt: asString(row.publishedAt),
    updatedAt: asString(row.updatedAt),
    publicationType: asString(row.publicationType) ?? 'WORKFLOW',
    categoryColor: asString(category.color),
    displayMode: asString(row.displayMode),
    creditsPerUse: asNumber(row.creditsPerUse),
    hasShowcase: row.hasShowcase === true,
    nodeIcons: asNodeIcons(row.nodeIcons),
    agentCount: asNumber(row.agentCount),
    interfaceCount: asNumber(row.interfaceCount),
    workflowCount: asNumber(row.workflowCount),
    skillCount: asNumber(row.skillCount),
    datasourceCount: asNumber(row.datasourceCount),
    planSnapshot: row.planSnapshot ?? null,
  };
}

/**
 * Map a marketplace list payload. A non-array `publications` field (or a
 * non-object payload) yields an empty list so a backend shape change degrades
 * to an empty page rather than a 500.
 */
export function mapPublications(payload: unknown): PublicPublicationSummary[] {
  if (typeof payload !== 'object' || payload === null) return [];
  const list = (payload as Record<string, unknown>).publications;
  if (!Array.isArray(list)) return [];
  return list
    .map(mapPublication)
    .filter((item): item is PublicPublicationSummary => item !== null);
}

/**
 * How long a public page waits for the gateway before rendering without it.
 *
 * Sized as a ceiling, not a target: these reads normally answer in around a
 * tenth of a second. What it bounds is the case the try/catch below cannot
 * see, a gateway that accepts the connection and then never answers. Every
 * caller here is on the critical path of a public page, and the landing's is
 * also on the critical path of the BUILD, so an unbounded read is a render
 * that hangs rather than a page that degrades.
 */
const GATEWAY_READ_TIMEOUT_MS = 8000;

async function getJson(path: string, revalidateSeconds: number): Promise<unknown | null> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
      signal: AbortSignal.timeout(GATEWAY_READ_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    // A public page must not fail because the gateway blipped or stalled:
    // callers render an empty state (or notFound()) instead. An abort from the
    // timeout above lands here too, which is the point.
    return null;
  }
}

/**
 * Mirrors `VerifiedAccountService.MAX_BATCH_SIZE` on the backend: the ceiling on one
 * badge lookup, which is why a page with more distinct authors than this asks in
 * several requests instead of one oversized one. Exported so `verifiedBatchCap.test.ts`
 * can pin it against the Java constant.
 */
export const VERIFIED_HANDLES_PER_REQUEST = 100;

/**
 * Which of these authors carry the verified badge, as a set of LOWERCASED @handles.
 *
 * Keyed by handle rather than by user id on purpose: this runs for anonymous
 * visitors, and the id-keyed lookup is authenticated precisely because sequential
 * ids would let anyone page out the platform's verified accounts. A handle is
 * user-chosen and is already printed on the page, so asking about it reveals nothing
 * the visitor is not looking at.
 *
 * Returns an empty set without issuing a request on a self-hosted deployment, and on
 * any failure: a badge lookup must never take a public page down.
 */
export async function fetchVerifiedPublisherHandles(
  handles: Array<string | null | undefined>,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<Set<string>> {
  const verified = new Set<string>();
  if (!IS_MANAGED_CLOUD) return verified;

  const distinct = Array.from(
    new Set(
      handles
        .filter((h): h is string => typeof h === 'string' && h.trim() !== '')
        .map((h) => h.trim().toLowerCase()),
    ),
  );
  if (distinct.length === 0) return verified;

  const chunks: string[][] = [];
  for (let i = 0; i < distinct.length; i += VERIFIED_HANDLES_PER_REQUEST) {
    chunks.push(distinct.slice(i, i + VERIFIED_HANDLES_PER_REQUEST));
  }

  const answers = await Promise.all(
    chunks.map((chunk) => getJson(
      `/api/users/public/verified-handles?handles=${chunk.map(encodeURIComponent).join(',')}`,
      revalidateSeconds,
    )),
  );
  for (const payload of answers) {
    if (typeof payload !== 'object' || payload === null) continue;
    const list = (payload as Record<string, unknown>).verified;
    if (!Array.isArray(list)) continue;
    for (const handle of list) {
      if (typeof handle === 'string') verified.add(handle.toLowerCase());
    }
  }
  return verified;
}

/**
 * Every listing the sitemap may advertise, walked page by page.
 *
 * Bounded on purpose. `maxPages` caps the work a single sitemap render can do,
 * and reaching that cap is reported by the caller rather than silently
 * truncating: a sitemap that quietly drops half the catalog looks healthy while
 * hiding pages from search engines. The walk also stops as soon as a page comes
 * back short, which is the normal end of the catalog.
 */
export async function fetchAllPublicPublications(
  { pageSize = 100, maxPages = 50, revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS } = {},
): Promise<{ publications: PublicPublicationSummary[]; truncated: boolean }> {
  const all: PublicPublicationSummary[] = [];

  for (let page = 0; page < maxPages; page++) {
    const payload = await getJson(
      `/api/publications/marketplace?page=${page}&size=${pageSize}`,
      revalidateSeconds,
    );
    // A failed page ends the walk: continuing would silently produce a sitemap
    // with a hole in the middle of the catalog.
    if (payload === null) return { publications: all, truncated: true };

    const batch = mapPublications(payload);
    all.push(...batch);

    const rawCount = Array.isArray((payload as Record<string, unknown>).publications)
      ? ((payload as Record<string, unknown>).publications as unknown[]).length
      : 0;
    if (rawCount < pageSize) return { publications: all, truncated: false };
  }

  return { publications: all, truncated: true };
}

/**
 * A single publication addressed by its URL slug, or null when the slug is
 * unknown or the publication is not anonymously readable (the backend answers
 * 404 for both, deliberately indistinguishably). Callers should map null to
 * `notFound()`.
 */
export async function fetchPublicationBySlug(
  slug: string,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicPublicationSummary | null> {
  if (!isValidSlugFormat(slug)) return null;
  const payload = await getJson(
    `/api/publications/by-slug/${encodeURIComponent(slug)}`,
    revalidateSeconds,
  );
  return mapPublication(payload);
}

/**
 * The frozen showcase of a publication, as the public render endpoint returns it.
 * Field names mirror the payload; nothing is renamed, so a template that reads
 * `htmlTemplate` here reads the same key the authenticated card preview does.
 */
export interface PublicShowcaseRender {
  htmlTemplate: string;
  cssTemplate: string | null;
  jsTemplate: string | null;
  /** Declared interface format (`vertical`, `square`, ...) or null for the default. */
  format: string | null;
  /** Newest epoch first. Only the first is previewed. */
  items: Array<{ data?: Record<string, unknown> | null }>;
}

/**
 * Read a publication's frozen showcase for anonymous rendering.
 *
 * <p>This is the read that lets a crawlable listing SHOW the application instead
 * of describing it. It targets the same anonymous endpoint the marketplace cards
 * use, and it is safe to call from a server render for two reasons: the endpoint
 * is public at the gateway (no credentials are attached here, per this module's
 * contract), and it serves only the frozen `showcase_*` clone, never the
 * publisher's live run.
 *
 * <p>Returns null when the publication has no showcase, when the read fails, or
 * when the payload carries no HTML to render. Callers must treat null as "show
 * the listing without a preview", never as an error: a listing page must not go
 * down because one showcase is missing.
 */
export async function fetchShowcaseRender(
  publicationId: string,
  revalidateSeconds = PUBLIC_SHOWCASE_REVALIDATE_SECONDS,
): Promise<PublicShowcaseRender | null> {
  if (!publicationId) return null;

  const payload = await getJson(
    `/api/publications/by-id/${encodeURIComponent(publicationId)}/showcase-render`,
    revalidateSeconds,
  );
  if (typeof payload !== 'object' || payload === null) return null;
  const row = payload as Record<string, unknown>;

  const htmlTemplate = asString(row.htmlTemplate);
  // No markup means nothing to draw. Returning a blank shell would render an
  // empty white box that reads as a broken application.
  if (!htmlTemplate) return null;

  return {
    htmlTemplate,
    cssTemplate: asString(row.cssTemplate),
    jsTemplate: asString(row.jsTemplate),
    format: asString(row.format),
    items: Array.isArray(row.items)
      ? row.items.filter(
          (item): item is { data?: Record<string, unknown> | null } =>
            typeof item === 'object' && item !== null,
        )
      : [],
  };
}

/** One publicly readable review of a listing. */
export interface PublicReview {
  id: string;
  reviewerName: string | null;
  /** 1..5, or null for a comment left without a rating. */
  rating: number | null;
  comment: string | null;
  createdAt: string | null;
  /** Replies the review has drawn, including the publisher's own. */
  replyCount: number;
}

export interface PublicReviewPage {
  reviews: PublicReview[];
  /** Total reviews matching the query, not the number returned. */
  totalElements: number;
}

/**
 * Map one review row from the public endpoint.
 *
 * <p>Deliberately narrow: it takes only the fields the public page renders, so a
 * field added to the authenticated payload later cannot reach a crawlable page
 * just by existing. `reviewerId` is already stripped server-side; not reading it
 * here is the second half of that.
 */
export function mapPublicReview(raw: unknown): PublicReview | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;

  const id = asString(row.id);
  if (!id) return null;

  const rating = typeof row.rating === 'number' && Number.isFinite(row.rating) ? row.rating : null;

  return {
    id,
    reviewerName: asString(row.reviewerName),
    rating,
    comment: asString(row.comment),
    createdAt: asString(row.createdAt),
    replyCount: asNumber(row.replyCount),
  };
}

/**
 * Reviews of a publication, for the crawlable listing page.
 *
 * <p>Reads the anonymous alias under the `/by-id/` prefix, not the bare
 * `/publications/{id}/reviews` route: that one is behind the gateway's JWT
 * filter and answers 401 to a visitor. The alias applies the same visibility
 * gate as the detail read, so a listing this page can render is a listing whose
 * reviews it can render.
 *
 * <p>`onlyWithComment` defaults to true: a bare star with no words is already
 * summarised by the average shown in the header, and listing a page of empty
 * rows adds nothing a reader or a crawler can use.
 *
 * <p>Degrades to an empty page on any failure. Reviews are secondary content;
 * a listing must still render without them.
 */
export async function fetchPublicationReviews(
  publicationId: string,
  { size = 10, onlyWithComment = true, revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS } = {},
): Promise<PublicReviewPage> {
  const empty: PublicReviewPage = { reviews: [], totalElements: 0 };
  if (!publicationId) return empty;

  const payload = await getJson(
    `/api/publications/by-id/${encodeURIComponent(publicationId)}/reviews`
      + `?page=0&size=${size}&onlyWithComment=${onlyWithComment}`,
    revalidateSeconds,
  );
  if (typeof payload !== 'object' || payload === null) return empty;
  const body = payload as Record<string, unknown>;

  const reviews = Array.isArray(body.reviews)
    ? body.reviews.map(mapPublicReview).filter((r): r is PublicReview => r !== null)
    : [];

  return { reviews, totalElements: asNumber(body.totalElements) };
}
