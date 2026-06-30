// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// getInvitationInfo no longer uses the authenticated apiClient (mocked here so the
// module loads without apiClient's side effects). Regression: the accept page is hit by a
// LOGGED-OUT invitee where apiClient has no token provider, so the old apiClient.get
// no-op'd ("No token provider configured") and the register form never showed. The fix is
// a raw public fetch.
vi.mock('../api-client', () => ({ apiClient: {} }));

import { organizationApi } from '../organization-api';

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('organizationApi.getInvitationInfo (public, raw fetch)', () => {
  it('fetches the public proxy endpoint with the token in the query and NO Authorization header', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ valid: true, email: 'a@b.co', hasAccount: false }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );

    const info = await organizationApi.getInvitationInfo('invite-token-ce-test');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/proxy/organizations/invitations/info?token=invite-token-ce-test');
    expect((init?.headers as Record<string, string> | undefined)?.Authorization).toBeUndefined();
    expect(info).toEqual({ valid: true, email: 'a@b.co', hasAccount: false });
  });

  it('url-encodes a token with special characters', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ valid: false }), { status: 200 }));
    await organizationApi.getInvitationInfo('tok with/slash+plus');
    expect(String(fetchMock.mock.calls[0][0])).toBe(
      '/api/proxy/organizations/invitations/info?token=tok%20with%2Fslash%2Bplus',
    );
  });

  it('returns {valid:false} on a non-ok response', async () => {
    fetchMock.mockResolvedValue(new Response('err', { status: 500 }));
    expect(await organizationApi.getInvitationInfo('x')).toEqual({ valid: false });
  });

  it('returns {valid:false} on a network error (never throws to the caller)', async () => {
    fetchMock.mockRejectedValue(new Error('network down'));
    expect(await organizationApi.getInvitationInfo('x')).toEqual({ valid: false });
  });
});
