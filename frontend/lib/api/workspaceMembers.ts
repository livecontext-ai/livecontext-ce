import { organizationApi } from '@/lib/api/organization-api';
import {
  clearInFlight,
  currentGeneration,
  readCachedRoster,
  readInFlight,
  setInFlight,
  writeRoster,
  type MemberSummary,
} from '@/lib/api/workspaceMembersCache';

export type { MemberSummary } from '@/lib/api/workspaceMembersCache';
/** Re-exported so callers have one import for "the roster", whichever half they need. */
export { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembersCache';

/**
 * Per-workspace member roster, fetched once and shared by everything that needs to put a name
 * to a user id: resource attribution, the task assignee picker.
 *
 * <p>Every resource row already carries its owner's user id (the {@code tenant_id} the backend
 * scopes on), so attributing a card costs a NAME, not a lookup per card. The whole roster of
 * the active workspace answers every card on every list at once, which is why this resolves a
 * workspace rather than a batch of ids: one request, then nothing.
 *
 * <p>Deliberately NOT react-query. This feeds a control rendered inside cards that mount in
 * trees with no {@code QueryClientProvider} (the applications grid, a project tab, a
 * publication preview), and {@code useQuery} does not degrade there - it throws, taking the
 * whole subtree down rather than dropping one button. Same reasoning, and same shape, as
 * {@code lib/api/verifiedUsers}.
 *
 * <p>Failure resolves to NULL, not to an empty roster, and is not cached. The distinction is
 * load-bearing: an empty roster would mean "this id belongs to nobody here", so a transient
 * 5xx would relabel every owner on the page as being outside the workspace. Null means "nobody
 * could be named", which the caller renders as no attribution at all rather than as a claim
 * about a person, and the next opener retries.
 *
 * <p>What is cached, for how long, and when it is dropped lives in
 * {@code workspaceMembersCache} - a LEAF module, so that {@code organizationApi} can invalidate
 * the roster on a membership change without the two API modules importing each other.
 */
export function loadWorkspaceMembers(orgId?: string | null): Promise<Map<string, MemberSummary> | null> {
  if (!orgId) return Promise.resolve(null);

  const cached = readCachedRoster(orgId);
  if (cached) return Promise.resolve(cached);

  const running = readInFlight(orgId);
  if (running) return running;

  const startedUnder = currentGeneration();
  const request = organizationApi
    .getOrganization(orgId)
    .then((org) => {
      // Keyed by user id as a STRING: resource rows carry the owner id as a string and members
      // arrive as numbers, and comparing those two directly is the bug this prevents.
      const roster = new Map<string, MemberSummary>();
      for (const member of org?.members ?? []) {
        const id = String(member.userId);
        roster.set(id, {
          userId: id,
          displayName: member.displayName || member.email || id,
          avatarUrl: member.avatarUrl ?? null,
          email: member.email ?? null,
        });
      }
      writeRoster(orgId, roster, startedUnder);
      return roster;
    })
    .catch(() => null)
    .finally(() => {
      clearInFlight(orgId);
    });

  setInFlight(orgId, request);
  return request;
}
