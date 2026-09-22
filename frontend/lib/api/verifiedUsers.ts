import { IS_MANAGED_CLOUD } from '@/lib/edition';
import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Batching loader and cache for the verified badge.
 *
 * <p>The badge is rendered next to a name, and names come in lists: a marketplace
 * grid, a review thread, a DM sidebar. Asking per name would mean one request per
 * card. Every call made inside the same frame is therefore collected and answered by
 * ONE request, and each answer is then cached for the life of the page, so scrolling
 * a card out and back costs nothing.
 *
 * <p>Deliberately NOT react-query. The badge decorates cards that render in trees
 * with no {@code QueryClientProvider} (the chat highlights row, the applications
 * grid, the DM header), and {@code useQuery} throws outright without one - it would
 * take those trees down rather than just skip a badge. The same reasoning already
 * keeps {@code UserActionMenu} off a required query client.
 *
 * <p>Failure is silent and closed: a badge lookup must never take a page down, so a
 * failed batch resolves everyone in it as unverified rather than rejecting.
 */

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

type Waiter = (verified: boolean) => void;

/**
 * Answers already known, so a re-render never re-asks. Verified status changes about
 * as often as someone gets verified, and the page is reloaded far more often than
 * that, so a session-lifetime cache is the right staleness.
 */
const resolved = new Map<string, boolean>();

const pending = new Map<string, Waiter[]>();
let timer: ReturnType<typeof setTimeout> | null = null;

async function flush(): Promise<void> {
  timer = null;
  const batch = new Map(pending);
  pending.clear();

  const ids = Array.from(batch.keys());
  const verified = new Set<string>();
  /**
   * Ids whose request failed. They resolve `false` like everyone else - a badge lookup
   * must never take a page down - but they are NOT written to the cache: a cached
   * `false` is permanent for the life of the page, so one transient 5xx would erase
   * every badge on the platform until a full reload, silently.
   */
  const unanswered = new Set<string>();

  const chunks: string[][] = [];
  for (let i = 0; i < ids.length; i += MAX_IDS_PER_REQUEST) {
    chunks.push(ids.slice(i, i + MAX_IDS_PER_REQUEST));
  }

  await Promise.all(
    chunks.map(async (chunk) => {
      try {
        const found = await unifiedApiService.getVerifiedUserIds(chunk);
        found.forEach((id) => verified.add(String(id)));
      } catch {
        // Fail closed for this render, and retry on the next one.
        chunk.forEach((id) => unanswered.add(id));
      }
    }),
  );

  batch.forEach((waiters, id) => {
    const flag = verified.has(id);
    if (!unanswered.has(id)) {
      resolved.set(id, flag);
    }
    waiters.forEach((resolve) => resolve(flag));
  });
}

/**
 * The answer already held for this user, or undefined when it has not been asked yet.
 * Lets a component paint a known badge on its FIRST render instead of flashing it in
 * a tick later.
 */
export function cachedVerifiedFlag(userId: string): boolean | undefined {
  if (!IS_MANAGED_CLOUD || !userId) return false;
  return resolved.get(userId);
}

/**
 * Whether this user carries the verified badge, resolved through the shared batch.
 * Always false on a self-hosted deployment, without issuing a request.
 */
export function loadVerifiedFlag(userId: string): Promise<boolean> {
  if (!IS_MANAGED_CLOUD || !userId) {
    return Promise.resolve(false);
  }
  const known = resolved.get(userId);
  if (known !== undefined) {
    return Promise.resolve(known);
  }
  return new Promise<boolean>((resolve) => {
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

/** Test seam: drop the cache so each case starts from a cold page. */
export function __resetVerifiedUserCache(): void {
  resolved.clear();
  pending.clear();
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
}
