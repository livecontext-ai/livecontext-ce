import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Batching loader and cache for the public badges shown next to a name: the blue
 * verified check and the gold official-partner seal. One lookup answers both, so a
 * name that carries either costs the same single request.
 *
 * <p>Badges are rendered next to names, and names come in lists: a marketplace grid,
 * a review thread, a DM sidebar. Asking per name would mean one request per card.
 * Every call made inside the same frame is therefore collected and answered by ONE
 * request, and each answer is then cached for the life of the page, so scrolling a
 * card out and back costs nothing.
 *
 * <p>Deliberately NOT react-query. The badge decorates cards that render in trees
 * with no {@code QueryClientProvider} (the chat highlights row, the applications
 * grid, the DM header), and {@code useQuery} throws outright without one - it would
 * take those trees down rather than just skip a badge. The same reasoning already
 * keeps {@code UserActionMenu} off a required query client.
 *
 * <p>Failure is silent and closed: a badge lookup must never take a page down, so a
 * failed batch resolves everyone in it as carrying no badge rather than rejecting.
 */

/** What one user carries. */
export interface BadgeFlags {
  verified: boolean;
  partner: boolean;
}

const NO_BADGE: BadgeFlags = Object.freeze({ verified: false, partner: false });

/**
 * How long to keep collecting ids before firing. One frame: long enough that a whole
 * list mounting in a single React commit lands in one request, short enough to stay
 * invisible.
 */
const BATCH_WINDOW_MS = 16;

/**
 * Mirrors `VerifiedAccountService.MAX_BATCH_SIZE` on the backend. Exported so
 * `verifiedBatchCap.test.ts` can pin it against the Java constant: if the backend cap
 * were lowered without this following, every over-sized request would 400, the catch
 * below would swallow it, and every badge on the platform would quietly disappear.
 */
export const MAX_IDS_PER_REQUEST = 100;

type Waiter = (flags: BadgeFlags) => void;

/**
 * Answers already known, so a re-render never re-asks. Badge status changes about as
 * often as someone gets verified or becomes a partner, and the page is reloaded far
 * more often than that, so a session-lifetime cache is the right staleness.
 */
const resolved = new Map<string, BadgeFlags>();

const pending = new Map<string, Waiter[]>();
let timer: ReturnType<typeof setTimeout> | null = null;

async function flush(): Promise<void> {
  timer = null;
  const batch = new Map(pending);
  pending.clear();

  const ids = Array.from(batch.keys());
  const verified = new Set<string>();
  const partners = new Set<string>();
  /**
   * Ids whose request failed. They resolve to no badge like everyone else - a badge
   * lookup must never take a page down - but they are NOT written to the cache: a
   * cached answer is permanent for the life of the page, so one transient 5xx would
   * erase every badge on the platform until a full reload, silently.
   */
  const unanswered = new Set<string>();

  const chunks: string[][] = [];
  for (let i = 0; i < ids.length; i += MAX_IDS_PER_REQUEST) {
    chunks.push(ids.slice(i, i + MAX_IDS_PER_REQUEST));
  }

  await Promise.all(
    chunks.map(async (chunk) => {
      try {
        const found = await unifiedApiService.getUserBadges(chunk);
        (found?.verified ?? []).forEach((id) => verified.add(String(id)));
        (found?.partners ?? []).forEach((id) => partners.add(String(id)));
      } catch {
        // Fail closed for this render, and retry on the next one.
        chunk.forEach((id) => unanswered.add(id));
      }
    }),
  );

  batch.forEach((waiters, id) => {
    const flags: BadgeFlags = { verified: verified.has(id), partner: partners.has(id) };
    if (!unanswered.has(id)) {
      resolved.set(id, flags);
    }
    waiters.forEach((resolve) => resolve(flags));
  });
}

/**
 * The badges already known for this user, or undefined when not asked yet. Lets a
 * component paint a known badge on its FIRST render instead of flashing it in a tick
 * later.
 */
export function cachedBadges(userId: string): BadgeFlags | undefined {
  if (!IS_MANAGED_CLOUD || !userId) return NO_BADGE;
  return resolved.get(userId);
}

/**
 * The badges this user carries, resolved through the shared batch. Never a badge on a
 * self-hosted deployment, and no request issued there.
 */
export function loadBadges(userId: string): Promise<BadgeFlags> {
  if (!IS_MANAGED_CLOUD || !userId) {
    return Promise.resolve(NO_BADGE);
  }
  const known = resolved.get(userId);
  if (known !== undefined) {
    return Promise.resolve(known);
  }
  return new Promise<BadgeFlags>((resolve) => {
    const waiters = pending.get(userId);
    if (waiters) {
      waiters.push(resolve);
    } else {
      pending.set(userId, [resolve]);
    }
    if (timer === null) {
      timer = setTimeout(() => { void flush(); }, BATCH_WINDOW_MS);
    }
  });
}

/** The verified half of {@link cachedBadges}. */
export function cachedVerifiedFlag(userId: string): boolean | undefined {
  return cachedBadges(userId)?.verified;
}

/** The verified half of {@link loadBadges}. */
export function loadVerifiedFlag(userId: string): Promise<boolean> {
  return loadBadges(userId).then((flags) => flags.verified);
}

/** Test seam: drop the cache so each case starts from a cold page. */
export function __resetVerifiedUserCache(): void {
  resolved.clear();
  pending.clear();
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
}
