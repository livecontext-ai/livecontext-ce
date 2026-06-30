'use client';

import { useEffect, useState } from 'react';
import { organizationApi } from '@/lib/api/organization-api';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { useAuth } from '@/lib/providers/smart-providers';
import type { TaskPerson } from '@/lib/api/orchestrator/task.types';

/**
 * Teammates assignable to a task (Jira-style human assignee / reviewer).
 *
 * Source of truth is the active workspace's member list (`organizationApi
 * .getOrganization(currentOrgId).members`), which always includes the current
 * user - flagged `isSelf` (matched by email) and sorted first so the picker can
 * surface "(You)" for self-assignment. Returns `[]` until an active workspace is
 * resolved (`currentOrgId` null pre-hydration); the picker then shows agents only.
 */
export function useTaskPeople(): TaskPerson[] {
  const { currentOrgId } = useCurrentOrg();
  const { user } = useAuth();
  const myEmail = (user?.email || '').toLowerCase();
  const [people, setPeople] = useState<TaskPerson[]>([]);

  useEffect(() => {
    if (!currentOrgId) { setPeople([]); return; }
    let cancelled = false;

    organizationApi.getOrganization(currentOrgId)
      .then((org) => {
        if (cancelled) return;
        const members = Array.isArray(org?.members) ? org.members : [];
        const mapped: TaskPerson[] = members.map((m) => ({
          userId: String(m.userId),
          displayName: m.displayName || m.email || `User ${m.userId}`,
          avatarUrl: m.avatarUrl ?? null,
          email: m.email ?? null,
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
