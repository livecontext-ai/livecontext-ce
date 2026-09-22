// @vitest-environment jsdom
/**
 * The hook behind resource attribution: WHEN it asks, and how it reports not knowing.
 *
 * Its contract has three answers, not two, and the third is the one that bit: `members` null
 * with `answered` true means "nobody can be named and nothing more is coming" (no workspace,
 * or a failed fetch). A caller that reads it as "still loading" leaves a skeleton pulsing for
 * the life of the page.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getOrganization = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganization: (...args: unknown[]) => getOrganization(...args) },
}));

let currentOrgId: string | null = 'org-1';
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgIdForRequest: () => currentOrgId,
}));

import { useWorkspaceMembers } from '../useWorkspaceMembers';
import { invalidateWorkspaceMembers } from '@/lib/api/workspaceMembers';

const ADA = {
  userId: 42,
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2026-01-01T00:00:00Z',
  isOwner: false,
};

/** Renders the hook's answer as attributes a test can read. */
function Probe({ enabled }: { enabled: boolean }) {
  const roster = useWorkspaceMembers(enabled);
  return (
    <div
      data-testid="probe"
      data-answered={String(roster.answered)}
      data-has-members={String(roster.members !== null)}
      data-ada={roster.members?.get('42')?.displayName ?? ''}
    />
  );
}

beforeEach(() => {
  currentOrgId = 'org-1';
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
  getOrganization.mockResolvedValue({ id: 'org-1', members: [ADA] });
});

afterEach(() => cleanup());

describe('useWorkspaceMembers', () => {
  it('asks for nothing while disabled', () => {
    render(<Probe enabled={false} />);

    // This is what makes a 40-card grid free: every card holds one of these, and none of
    // them may fetch until something actually needs a name.
    expect(getOrganization).not.toHaveBeenCalled();
    expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'false');
  });

  it('resolves the roster once enabled', async () => {
    render(<Probe enabled={true} />);

    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-ada', 'Ada Lovelace'));
    expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'true');
  });

  it('answers with NO members, definitively, when there is no active workspace', async () => {
    currentOrgId = null;

    render(<Probe enabled={true} />);

    // `answered` true with no members is the terminal "nobody can be named" state. Reporting
    // it as still-loading is what left a skeleton pulsing forever.
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'true'));
    expect(screen.getByTestId('probe')).toHaveAttribute('data-has-members', 'false');
    expect(getOrganization).not.toHaveBeenCalled();
  });

  it('answers definitively when the fetch fails, and never with an empty roster', async () => {
    getOrganization.mockRejectedValue(new Error('503'));

    render(<Probe enabled={true} />);

    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'true'));
    // Not an empty Map: that would read as "this id belongs to nobody here" and relabel
    // every owner on the page.
    expect(screen.getByTestId('probe')).toHaveAttribute('data-has-members', 'false');
  });

  it('retries a failed fetch when it is enabled again', async () => {
    getOrganization.mockRejectedValueOnce(new Error('503'));
    getOrganization.mockResolvedValueOnce({ id: 'org-1', members: [ADA] });

    const { rerender } = render(<Probe enabled={true} />);
    await waitFor(() => expect(getOrganization).toHaveBeenCalledTimes(1));

    // Closing and reopening the popover is the retry, which is why `enabled` is a dependency.
    rerender(<Probe enabled={false} />);
    rerender(<Probe enabled={true} />);

    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-ada', 'Ada Lovelace'));
    expect(getOrganization).toHaveBeenCalledTimes(2);
  });

  it('goes back to ASKING while a retry is in flight, instead of staying at "nobody"', async () => {
    // After a failure the hook reports `answered: true` with no members - the terminal "nobody
    // could be named". Re-arming must clear that for the duration of the new attempt, or the
    // caller renders its no-attribution fallback and then flips to a name, on a control whose
    // whole job is to not state things it cannot support.
    let release: (value: unknown) => void = () => {};
    getOrganization.mockRejectedValueOnce(new Error('503'));
    getOrganization.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    const { rerender } = render(<Probe enabled={true} />);
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'true'));

    rerender(<Probe enabled={false} />);
    rerender(<Probe enabled={true} />);

    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-answered', 'false'));
    release({ id: 'org-1', members: [ADA] });
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveAttribute('data-ada', 'Ada Lovelace'));
  });

  it('shares one request across every consumer on the page', async () => {
    render(<><Probe enabled={true} /><Probe enabled={true} /><Probe enabled={true} /></>);

    await waitFor(() => expect(screen.getAllByTestId('probe')[2]).toHaveAttribute('data-ada', 'Ada Lovelace'));
    expect(getOrganization).toHaveBeenCalledTimes(1);
  });
});
