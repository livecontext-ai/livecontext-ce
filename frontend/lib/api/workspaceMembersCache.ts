/**
 * The storage behind the workspace-member roster: what is held, for how long, and when it is
 * thrown away. Deliberately a LEAF module - it imports nothing from the API layer.
 *
 * <p>That is the whole reason it exists apart from {@code workspaceMembers}. The roster is
 * fetched through {@code organizationApi}, and {@code organizationApi} has to drop it whenever
 * membership changes - so if the fetching and the dropping lived in one module, the two hot API
 * modules would import each other. Cycles like that are not theoretical here: this codebase has
 * already paid for one that only showed up in a production Turbopack build.
 */

/** One member of a workspace, in the shape the people-facing surfaces need. */
export interface MemberSummary {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  /** Used to recognise the current user (the task picker matches on it). Null when unknown. */
  email: string | null;
}

/**
 * How long a roster is trusted. Short enough that a teammate who joins mid-session appears
 * without a reload, long enough that a list page's popovers all share one request. The cache
 * used to be page-lifetime, which was invisible until someone joined and never showed up in
 * the task assignee picker.
 */
const TTL_MS = 60_000;

interface CachedRoster {
  members: Map<string, MemberSummary>;
  fetchedAt: number;
}

const rosters = new Map<string, CachedRoster>();
const inFlight = new Map<string, Promise<Map<string, MemberSummary> | null>>();

/**
 * Bumped by EVERY invalidation, scoped or global. A fetch captures it at the start and refuses
 * to cache its answer if it has moved: without that, a fetch already in flight when an admin
 * removes someone lands afterwards and re-caches the PRE-removal roster with a fresh
 * timestamp, so the removed member keeps being named for another full TTL - exactly what the
 * invalidation exists to prevent.
 *
 * <p>ONE counter for all workspaces rather than one per workspace. A per-workspace map only
 * knows about a workspace it has already seen invalidated, so the global form had nothing to
 * bump on a first call and the guard silently did not apply. Bumping too often costs a
 * re-fetch; bumping too rarely costs a stale name, which is the failure this exists to stop.
 */
let generation = 0;

/** The roster held for this workspace, if it has not expired. */
export function readCachedRoster(orgId: string): Map<string, MemberSummary> | undefined {
  const cached = rosters.get(orgId);
  if (cached && Date.now() - cached.fetchedAt < TTL_MS) return cached.members;
  return undefined;
}

/** The generation a fetch is starting under, to be handed back to {@link writeRoster}. */
export function currentGeneration(): number {
  return generation;
}

/**
 * Cache a fetched roster, UNLESS an invalidation happened while it was in flight - in which
 * case the answer was already stale when it arrived and caching it would restore what was just
 * thrown away, with a fresh timestamp.
 */
export function writeRoster(
  orgId: string,
  members: Map<string, MemberSummary>,
  startedUnder: number,
): void {
  if (generation !== startedUnder) return;
  rosters.set(orgId, { members, fetchedAt: Date.now() });
}

export function readInFlight(orgId: string): Promise<Map<string, MemberSummary> | null> | undefined {
  return inFlight.get(orgId);
}

export function setInFlight(orgId: string, request: Promise<Map<string, MemberSummary> | null>): void {
  inFlight.set(orgId, request);
}

export function clearInFlight(orgId: string): void {
  inFlight.delete(orgId);
}

/**
 * Drop what is cached for a workspace (or all of them), so the next reader asks again.
 * Called from the member mutations in {@code organizationApi} - the four that actually change
 * who is a member: remove, role change, leave, and accepting an invite. SENDING an invite is
 * deliberately not one of them, because nobody has joined yet.
 */
export function invalidateWorkspaceMembers(orgId?: string | null): void {
  generation += 1;
  if (orgId) {
    rosters.delete(orgId);
    inFlight.delete(orgId);
    return;
  }
  rosters.clear();
  inFlight.clear();
}
