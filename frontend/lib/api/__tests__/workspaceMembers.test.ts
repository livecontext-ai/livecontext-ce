/**
 * The workspace-roster cache behind resource attribution.
 *
 * What matters here is not that it caches, but WHAT it caches: a failed fetch must not be
 * remembered, and must not be reported as an empty roster. An empty roster is a claim
 * ("this id belongs to nobody in this workspace") that would relabel every owner on the
 * page as a former member after one transient 5xx.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getOrganization = vi.fn();
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganization: (...args: unknown[]) => getOrganization(...args) },
}));

import { invalidateWorkspaceMembers, loadWorkspaceMembers } from '../workspaceMembers';

const ADA = {
  userId: 42,
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  avatarUrl: 'https://example.test/ada.png',
  role: 'ADMIN',
  joinedAt: '2026-01-01T00:00:00Z',
  isOwner: false,
};

beforeEach(() => {
  invalidateWorkspaceMembers();
  getOrganization.mockReset();
});

afterEach(() => invalidateWorkspaceMembers());

describe('loadWorkspaceMembers', () => {
  it('keys members by user id as a STRING', async () => {
    // Resource rows carry the owner id as a string and members arrive as numbers;
    // comparing those directly is the bug this normalisation exists to prevent.
    getOrganization.mockResolvedValue({ id: 'org-1', members: [ADA] });

    const roster = await loadWorkspaceMembers('org-1');

    expect(roster?.get('42')).toEqual({
      userId: '42',
      displayName: 'Ada Lovelace',
      avatarUrl: 'https://example.test/ada.png',
      // Carried for the task picker, which recognises the current user by it.
      email: 'ada@example.com',
    });
  });

  it('falls back to the email, then the id, when a member has no display name', async () => {
    getOrganization.mockResolvedValue({
      id: 'org-1',
      members: [
        { ...ADA, userId: 1, displayName: '' },
        { ...ADA, userId: 2, displayName: '', email: '' },
      ],
    });

    const roster = await loadWorkspaceMembers('org-1');

    expect(roster?.get('1')?.displayName).toBe('ada@example.com');
    expect(roster?.get('2')?.displayName).toBe('2');
  });

  it('fetches once per workspace and serves the rest from cache', async () => {
    getOrganization.mockResolvedValue({ id: 'org-1', members: [ADA] });

    await loadWorkspaceMembers('org-1');
    await loadWorkspaceMembers('org-1');
    await loadWorkspaceMembers('org-1');

    expect(getOrganization).toHaveBeenCalledTimes(1);
  });

  it('collapses concurrent callers into a single request', async () => {
    // A page that opens two popovers in the same frame must not issue two rosters.
    let release: (value: unknown) => void = () => {};
    getOrganization.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    const first = loadWorkspaceMembers('org-1');
    const second = loadWorkspaceMembers('org-1');
    release({ id: 'org-1', members: [ADA] });

    expect(await first).toBe(await second);
    expect(getOrganization).toHaveBeenCalledTimes(1);
  });

  it('keeps workspaces separate', async () => {
    getOrganization.mockImplementation((orgId: string) =>
      Promise.resolve({ id: orgId, members: orgId === 'org-1' ? [ADA] : [] }));

    const first = await loadWorkspaceMembers('org-1');
    const second = await loadWorkspaceMembers('org-2');

    expect(first?.has('42')).toBe(true);
    expect(second?.has('42')).toBe(false);
    expect(getOrganization).toHaveBeenCalledTimes(2);
  });

  it('resolves NULL on failure - never an empty roster', async () => {
    getOrganization.mockRejectedValue(new Error('503'));

    const roster = await loadWorkspaceMembers('org-1');

    // An empty Map would read as "nobody here owns this", which is a false statement
    // about every resource on the page.
    expect(roster).toBeNull();
  });

  it('does not cache a failure, so the next caller retries', async () => {
    getOrganization.mockRejectedValueOnce(new Error('503'));
    getOrganization.mockResolvedValueOnce({ id: 'org-1', members: [ADA] });

    expect(await loadWorkspaceMembers('org-1')).toBeNull();

    const retried = await loadWorkspaceMembers('org-1');
    expect(retried?.get('42')?.displayName).toBe('Ada Lovelace');
    expect(getOrganization).toHaveBeenCalledTimes(2);
  });

  it('asks nothing without a workspace', async () => {
    expect(await loadWorkspaceMembers(null)).toBeNull();
    expect(await loadWorkspaceMembers(undefined)).toBeNull();
    expect(await loadWorkspaceMembers('')).toBeNull();
    expect(getOrganization).not.toHaveBeenCalled();
  });

  it('tolerates a response with no members array', async () => {
    getOrganization.mockResolvedValue({ id: 'org-1' });

    const roster = await loadWorkspaceMembers('org-1');

    expect(roster).not.toBeNull();
    expect(roster?.size).toBe(0);
  });
});

describe('roster freshness', () => {
  it('re-asks once the entry has expired, so a teammate who joins appears without a reload', async () => {
    // The cache used to last the whole page. Nobody saw it until someone joined a workspace
    // and never showed up in the task assignee picker - a bug with no error and no symptom
    // other than a person who is not there.
    vi.useFakeTimers();
    try {
      getOrganization.mockResolvedValue({ id: 'org-1', members: [ADA] });
      await loadWorkspaceMembers('org-1');
      await loadWorkspaceMembers('org-1');
      expect(getOrganization).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(61_000);
      await loadWorkspaceMembers('org-1');

      expect(getOrganization).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('serves the cache for the whole of a list page, not one request per reader', async () => {
    vi.useFakeTimers();
    try {
      getOrganization.mockResolvedValue({ id: 'org-1', members: [ADA] });
      await loadWorkspaceMembers('org-1');

      vi.advanceTimersByTime(30_000);
      await loadWorkspaceMembers('org-1');

      expect(getOrganization).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('invalidateWorkspaceMembers', () => {
  it('beats an in-flight fetch even when EVERY workspace is dropped at once', async () => {
    // The no-arg form is what an accept-invite response with no id degrades to. A per-workspace
    // generation map had nothing to bump on a first call, so the guard silently did not apply
    // and the stale answer was cached anyway - the exact hole the counter exists to close.
    let release: (value: unknown) => void = () => {};
    getOrganization.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
    getOrganization.mockResolvedValue({ id: 'org-1', members: [] });

    const inFlight = loadWorkspaceMembers('org-1');
    invalidateWorkspaceMembers();
    release({ id: 'org-1', members: [ADA] });
    await inFlight;

    await loadWorkspaceMembers('org-1');
    expect(getOrganization).toHaveBeenCalledTimes(2);
  });

  it('beats a fetch that was already in flight when the membership changed', async () => {
    // The race: a popover opens (fetch starts), an admin removes a member 200ms later, the
    // fetch lands afterwards. Caching that answer would restore the pre-removal roster with a
    // FRESH timestamp, so the removed member keeps being named for another whole TTL - the
    // exact outcome the invalidation exists to prevent.
    let release: (value: unknown) => void = () => {};
    getOrganization.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
    getOrganization.mockResolvedValue({ id: 'org-1', members: [] });

    const inFlight = loadWorkspaceMembers('org-1');
    invalidateWorkspaceMembers('org-1');
    release({ id: 'org-1', members: [ADA] });
    await inFlight;

    // The next reader must ask again rather than be served the answer that was already stale
    // when it arrived.
    await loadWorkspaceMembers('org-1');
    expect(getOrganization).toHaveBeenCalledTimes(2);
  });


  it('drops one workspace and leaves the others', async () => {
    getOrganization.mockImplementation((orgId: string) =>
      Promise.resolve({ id: orgId, members: [ADA] }));
    await loadWorkspaceMembers('org-1');
    await loadWorkspaceMembers('org-2');
    expect(getOrganization).toHaveBeenCalledTimes(2);

    invalidateWorkspaceMembers('org-1');

    // Only the dropped workspace is asked again.
    await loadWorkspaceMembers('org-1');
    await loadWorkspaceMembers('org-2');
    expect(getOrganization).toHaveBeenCalledTimes(3);
  });
});
