'use client';

import { useEffect, useState } from 'react';
import { loadWorkspaceMembers } from '@/lib/api/workspaceMembers';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { useAuth } from '@/lib/providers/smart-providers';
import type { TaskPerson } from '@/lib/api/orchestrator/task.types';

/**
 * Teammates assignable to a task (Jira-style human assignee / reviewer).
 *
 * Source of truth is the active workspace's member roster, which always includes the
 * current user - flagged `isSelf` (matched by email) and sorted first so the picker can
 * surface "(You)" for self-assignment. Returns `[]` until an active workspace is
 * resolved (`currentOrgId` null pre-hydration); the picker then shows agents only.
 *
 * The roster comes from the shared `loadWorkspaceMembers` cache rather than a private
 * `getOrganization` call, so a page showing both this picker and resource attribution
 * fetches the members ONCE. What is local to this hook is the shape, not the fetch:
 * `isSelf` and the self-first ordering are a picker concern and stay here.
 */
export function useTaskPeople(): TaskPerson[] {
  const { currentOrgId } = useCurrentOrg();
  const { user } = useAuth();
  const myEmail = (user?.email || '').toLowerCase();
  const [people, setPeople] = useState<TaskPerson[]>([]);

  useEffect(() => {
    if (!currentOrgId) { setPeople([]); return; }
    let cancelled = false;

    loadWorkspaceMembers(currentOrgId)
      .then((roster) => {
        if (cancelled) return;
        if (!roster) { setPeople([]); return; }
        const mapped: TaskPerson[] = Array.from(roster.values()).map((m) => ({
          userId: m.userId,
          // The shared roster falls back to the bare id when a member has neither name nor
          // email; a picker row reading "42" is a worse label than "User 42", so the picker
          // keeps its own wording for that case.
          displayName: m.displayName === m.userId ? `User ${m.userId}` : m.displayName,
          avatarUrl: m.avatarUrl,
          email: m.email,
          isSelf: !!myEmail && (m.email || '').toLowerCase() === myEmail,
        }));
        // Self first, then alphabetical by display name.
        mapped.sort((a, b) =>
          a.isSelf === b.isSelf ? a.displayName.localeCompare(b.displayName) : a.isSelf ? -1 : 1);
        setPeople(mapped);
      })
      .catch(() => { if (!cancelled) setPeople([]); });

    return () => { cancelled = true; };
  }, [currentOrgId, myEmail]);

  return people;
}
