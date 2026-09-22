/**
 * Membership mutations drop the cached workspace roster.
 *
 * <p>The roster puts names and faces on resource attribution and fills the task assignee
 * picker. Without these calls, someone this very session just removed keeps being named as an
 * owner, and someone who just accepted an invite cannot be assigned a task - for as long as the
 * cache lives. Nothing errors either way, which is exactly why each call site needs pinning:
 * deleting one is otherwise silent.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
const put = vi.fn();
const del = vi.fn();
const get = vi.fn();

vi.mock('../api-client', () => ({
  apiClient: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    put: (...args: unknown[]) => put(...args),
    delete: (...args: unknown[]) => del(...args),
  },
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
}));

const invalidateWorkspaceMembers = vi.fn();
// The LEAF cache module is what organization-api imports (breaking the cycle), so it is what
// has to be mocked here.
vi.mock('@/lib/api/workspaceMembersCache', () => ({
  invalidateWorkspaceMembers: (...args: unknown[]) => invalidateWorkspaceMembers(...args),
}));

import { organizationApi } from '../organization-api';

beforeEach(() => {
  invalidateWorkspaceMembers.mockReset();
  [post, put, del, get].forEach((fn) => fn.mockReset());
});

afterEach(() => vi.clearAllMocks());

describe('organizationApi membership mutations', () => {
  it('drops the roster when a member is removed', async () => {
    del.mockResolvedValue(undefined);

    await organizationApi.removeMember('org-1', 42);

    expect(invalidateWorkspaceMembers).toHaveBeenCalledWith('org-1');
  });

  it('drops the roster when a member role changes', async () => {
    put.mockResolvedValue({ userId: 42, role: 'ADMIN' });

    await organizationApi.changeMemberRole('org-1', 42, 'ADMIN');

    expect(invalidateWorkspaceMembers).toHaveBeenCalledWith('org-1');
  });

  it('drops the roster when the caller leaves', async () => {
    post.mockResolvedValue(undefined);

    await organizationApi.leaveOrganization('org-1');

    expect(invalidateWorkspaceMembers).toHaveBeenCalledWith('org-1');
  });

  it('drops the roster of the workspace just JOINED, by id and by token', async () => {
    post.mockResolvedValue({ id: 'org-joined' });

    await organizationApi.acceptInvitationById('inv-1');
    expect(invalidateWorkspaceMembers).toHaveBeenCalledWith('org-joined');

    invalidateWorkspaceMembers.mockReset();
    await organizationApi.acceptInvitation('token-1');
    expect(invalidateWorkspaceMembers).toHaveBeenCalledWith('org-joined');
  });

  it('does NOT drop the roster merely because an invite was sent', async () => {
    post.mockResolvedValue({ id: 'inv-1', email: 'new@example.com' });

    await organizationApi.inviteMember('org-1', 'new@example.com', 'member');

    // Nobody has joined yet. Invalidating here would throw away a good roster on an action
    // that changed no membership, and would make the doc's claim about what is wired false.
    expect(invalidateWorkspaceMembers).not.toHaveBeenCalled();
  });
});
