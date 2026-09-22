'use client';

import { useEffect, useState } from 'react';
import { getActiveOrgIdForRequest } from '@/lib/stores/current-org-store';
import { loadWorkspaceMembers, type MemberSummary } from '@/lib/api/workspaceMembers';

/**
 * The roster, plus whether the question has been PUT at all.
 *
 * <p>`members === null` alone cannot say why: not fetched yet, fetch failed, or there is no
 * workspace to ask. The first is worth waiting on; the other two never resolve on their own,
 * and a caller that cannot tell them apart leaves a loading state on screen forever.
 */
export interface WorkspaceRoster {
  /** Members by user id (string), or null when unanswered. NEVER an empty map on failure. */
  members: Map<string, MemberSummary> | null;
  /**
   * True once this attempt has a final answer, INCLUDING "there was no workspace to ask" and
   * "the request failed". False means an answer is still coming.
   */
  answered: boolean;
}

/**
 * The active workspace's members, keyed by user id, for attributing a resource to a person.
 *
 * <p>`enabled` exists so a card grid pays nothing: the roster is only fetched once something
 * actually needs a name (a popover opening), not once per card mounting. Callers that always
 * need it can omit the argument. Toggling it off and on again (closing then reopening a
 * popover) retries a failed fetch.
 *
 * <p><b>The workspace id is read inside the effect, not during render, and that is
 * deliberate.</b> This hook feeds a control that lives inside shared cards, and those cards
 * mount in test trees that partially mock the org store; a render-time `useCurrentOrg()` makes
 * every one of those files throw on mount, taking the whole tree down rather than dropping one
 * button. Reading it at fetch time is also the more correct moment: it is the workspace the
 * request will actually be scoped to. The same reasoning keeps react-query out of here, see
 * {@code lib/api/workspaceMembers}.
 */
export function useWorkspaceMembers(enabled = true): WorkspaceRoster {
  const [roster, setRoster] = useState<WorkspaceRoster>({ members: null, answered: false });

  useEffect(() => {
    if (!enabled) return;

    let alive = true;
    // Back to "asking" for the duration of this attempt. Without it a re-arm after a failure
    // keeps reporting `answered: true` with no members - the terminal "nobody could be named"
    // - while a fresh request is in flight, so the caller shows its no-attribution fallback
    // and then flips back to a name.
    setRoster((current) => (current.answered ? { members: current.members, answered: false } : current));
    const orgId = getActiveOrgIdForRequest();

    // ONE path, cached or not: `loadWorkspaceMembers` answers a cached roster with an
    // already-resolved promise (and no workspace with a resolved null), so a hit costs a
    // microtask instead of a request. Doing it this way also keeps the effect free of a
    // synchronous setState, which React's own lint flags as a cascading render.
    void loadWorkspaceMembers(orgId).then((members) => {
      if (!alive) return;
      // A failed fetch resolves null and leaves `members` null - callers must read that as
      // "nobody could be named", never as "this id is nobody". `answered` is what separates a
      // failure or a missing workspace, where nothing more is coming, from a fetch in flight.
      setRoster({ members, answered: true });
    });
    return () => {
      alive = false;
    };
  }, [enabled]);

  return roster;
}
