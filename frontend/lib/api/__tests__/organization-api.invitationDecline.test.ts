// @vitest-environment node
import { describe, it, expect, vi, afterEach } from 'vitest';

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('../api-client', () => ({ apiClient: { post } }));

import { organizationApi, isInvitationEmailNotVerifiedError } from '../organization-api';

afterEach(() => {
  vi.clearAllMocks();
});

describe('organizationApi.declineInvitation (accept page Decline button)', () => {
  it('posts the token as a query param to the authenticated decline endpoint', async () => {
    post.mockResolvedValue({ id: 'inv-1', status: 'CANCELLED' });

    const result = await organizationApi.declineInvitation('tok-1');

    expect(post).toHaveBeenCalledWith('/organizations/invitations/decline', null, {
      params: { token: 'tok-1' },
    });
    expect(result).toEqual({ id: 'inv-1', status: 'CANCELLED' });
  });
});

describe('isInvitationEmailNotVerifiedError', () => {
  it('recognises the backend EMAIL_NOT_VERIFIED code carried by ApiError', () => {
    expect(isInvitationEmailNotVerifiedError({ status: 403, code: 'EMAIL_NOT_VERIFIED' })).toBe(true);
  });

  it('does not treat other refusals (email mismatch, generic 403) as unverified', () => {
    expect(isInvitationEmailNotVerifiedError({ status: 403, code: 'HTTP_403' })).toBe(false);
    expect(isInvitationEmailNotVerifiedError(new Error('Invitation email does not match user email'))).toBe(false);
    expect(isInvitationEmailNotVerifiedError(null)).toBe(false);
    expect(isInvitationEmailNotVerifiedError(undefined)).toBe(false);
  });
});
