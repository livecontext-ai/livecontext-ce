import 'server-only';
import {
  PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
  gatewayBaseUrl,
  mapPublications,
  type PublicPublicationSummary,
} from './publicPublications';

/**
 * Server-side read of a public author profile, for the /u/{handle} page.
 *
 * Handle lookup only. The sibling by-id endpoint stays authenticated at the
 * gateway because it takes a sequential numeric user id, so a public by-id
 * would let anyone walk 1..N and harvest every profile on the platform.
 *
 * The backend applies the privacy gate: a PRIVATE profile answers 404 exactly
 * like a missing one, so a visitor cannot tell "no such user" from "exists but
 * private". This module preserves that by returning null for both.
 */

export interface PublicProfile {
  userId: number;
  displayName: string | null;
  handle: string;
  avatarUrl: string | null;
  bio: string | null;
  joinedAt: string | null;
  /**
   * Whether the owner opted into search indexing (the PUBLIC visibility state).
   * Defaults to false for anything the backend does not explicitly mark true,
   * so a missing or malformed field can never turn indexing ON by accident.
   */
  searchIndexable: boolean;
  /**
   * Whether this account carries the verified badge (the blue check next to the
   * name). Same strict-true reading as {@link searchIndexable}: only an explicit
   * true grants it, so a malformed payload can never decorate a profile.
   */
  verified: boolean;
}

/**
 * A handle is `^[a-z0-9._-]{2,32}$` server-side. Rejecting anything else before
 * the fetch keeps a crafted path from ever reaching the gateway, and turns the
 * obvious junk URLs into a 404 without a round-trip.
 */
const HANDLE_PATTERN = /^[a-z0-9._-]{2,32}$/;

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

export function mapProfile(raw: unknown): PublicProfile | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;

  const handle = asString(row.handle);
  const userId = typeof row.userId === 'number' ? row.userId : null;
  // Without a handle there is no canonical URL, and without a user id the
  // listings grid cannot be fetched: neither makes a usable page.
  if (!handle || userId === null) return null;

  return {
    userId,
    displayName: asString(row.displayName),
    handle,
    avatarUrl: asString(row.avatarUrl),
    bio: asString(row.bio),
    joinedAt: asString(row.joinedAt),
    // Strict equality with true: an absent field, a string "false", or any
    // unexpected shape must read as "not indexable". Defaulting the other way
    // would index a profile whose owner never opted in.
    searchIndexable: row.searchIndexable === true,
    verified: row.verified === true,
  };
}

async function getJson(path: string, revalidateSeconds: number): Promise<unknown | null> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: revalidateSeconds },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

/** The profile behind a handle, or null when unknown, private, or malformed. */
export async function fetchPublicProfile(
  handle: string,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicProfile | null> {
  if (!HANDLE_PATTERN.test(handle)) return null;
  return mapProfile(await getJson(`/api/users/public/by-handle/${handle}`, revalidateSeconds));
}

/**
 * The author's published listings (ACTIVE + PUBLIC only, enforced backend-side).
 * Returns an empty list on any failure so the profile page still renders.
 */
export async function fetchPublicationsByPublisher(
  userId: number,
  revalidateSeconds = PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
): Promise<PublicPublicationSummary[]> {
  const payload = await getJson(`/api/publications/by-publisher/${userId}`, revalidateSeconds);
  return mapPublications(payload);
}
