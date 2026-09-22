// @vitest-environment jsdom
/**
 * The task assignee picker's people list, after it was moved onto the shared workspace roster.
 *
 * The move was a DRY fix, and DRY fixes are where behaviour quietly changes: the picker used to
 * fetch on every mount and to word an unnamed member its own way. Both are pinned here, because
 * neither would fail anything if it regressed - the picker would just show a slightly different,
 * slightly staler set of people.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getOrganization = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganization: (...args: unknown[]) => getOrganization(...args) },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId: 'org-1', currentOrgRole: 'MEMBER' }),
  getActiveOrgIdForRequest: () => 'org-1',
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ user: { email: 'ada@example.com' } }),
}));

import { useTaskPeople } from '../useTaskPeople';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

const ADA = {
  userId: 42,
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  avatarUrl: null,
  role: 'OWNER',
  joinedAt: '2026-01-01T00:00:00Z',
  isOwner: true,
};
const GRACE = { ...ADA, userId: 7, email: 'grace@example.com', displayName: 'Grace Hopper', isOwner: false };
/** A member with neither a display name nor an email - the fallback case. */
const NAMELESS = { ...ADA, userId: 99, email: '', displayName: '', isOwner: false };

function Probe() {
  const people = useTaskPeople();
  return (
    <ol data-testid="people">
      {people.map((person) => (
        <li key={person.userId} data-self={String(person.isSelf)}>{person.displayName}</li>
      ))}
    </ol>
  );
}

beforeEach(() => {
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
  getOrganization.mockResolvedValue({ id: 'org-1', members: [GRACE, ADA] });
});

afterEach(() => cleanup());

describe('useTaskPeople', () => {
  it('lists the workspace, current user first and flagged', async () => {
    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId('people').children).toHaveLength(2));
    const rows = Array.from(screen.getByTestId('people').children);
    expect(rows[0]).toHaveTextContent('Ada Lovelace');
    expect(rows[0]).toHaveAttribute('data-self', 'true');
    expect(rows[1]).toHaveTextContent('Grace Hopper');
  });

  it('words an unnamed member as a person, not as a raw id', async () => {
    // The shared roster falls back to the bare id, which reads as a database row in a picker
    // of colleagues. The picker keeps its own wording for that case.
    getOrganization.mockResolvedValue({ id: 'org-1', members: [NAMELESS] });

    render(<Probe />);

    await waitFor(() => expect(screen.getByTestId('people')).toHaveTextContent('User 99'));
  });

  it('shares ONE roster request with everything else on the page', async () => {
    render(<><Probe /><Probe /></>);

    await waitFor(() => expect(screen.getAllByTestId('people')[1].children).toHaveLength(2));
    // The picker used to issue its own getOrganization. On a page that also attributes
    // resources, that was two requests for the same answer.
    expect(getOrganization).toHaveBeenCalledTimes(1);
  });

  it('shows nobody rather than a broken list when the roster cannot be fetched', async () => {
    getOrganization.mockRejectedValue(new Error('503'));

    render(<Probe />);

    await waitFor(() => expect(getOrganization).toHaveBeenCalled());
    expect(screen.getByTestId('people').children).toHaveLength(0);
  });
});
