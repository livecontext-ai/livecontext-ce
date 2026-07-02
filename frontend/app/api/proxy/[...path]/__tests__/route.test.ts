// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NextRequest } from 'next/server';
import { GET } from '../route';

// Regression: the proxy used to treat ANY `?token=` query as the auth bearer and DELETE
// it before forwarding. That collided with endpoints whose backend reads a RESOURCE token
// from `?token=` (the unauthenticated invitation-accept lookup, email verify, password
// reset, ...): the token was stripped, so the backend saw none and returned valid:false /
// 404. The fix only hijacks `?token=` when it is actually a JWT access token; opaque/UUID
// resource tokens are forwarded untouched.

const GATEWAY = 'http://localhost:8080';

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn(async () =>
    new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'content-type': 'application/json' } }),
  );
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function makeReq(url: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest(url, { headers });
}

function params(path: string[]): { params: Promise<{ path: string[] }> } {
  return { params: Promise.resolve({ path }) };
}

function calledUrl(): string {
  return String(fetchMock.mock.calls[0][0]);
}

function calledAuth(): string | undefined {
  const init = fetchMock.mock.calls[0][1] as { headers?: Record<string, string> } | undefined;
  return init?.headers?.Authorization;
}

describe('proxy route - ?token query handling', () => {
  it('forwards a non-JWT resource ?token to the gateway (invitation-accept lookup) instead of stripping it', async () => {
    const uuid = '8e8caa84-1f01-4c4e-bd88-7efe872aee91';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${uuid}`),
      params(['organizations', 'invitations', 'info']),
    );

    // The resource token survives in the query, and it is NOT promoted to a bearer.
    expect(calledUrl()).toBe(`${GATEWAY}/api/organizations/invitations/info?token=${uuid}`);
    expect(calledAuth()).toBeUndefined();
  });

  it('still hijacks a JWT-shaped ?token as the Authorization bearer and strips it from the query', async () => {
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/files/x?token=${jwt}`),
      params(['files', 'x']),
    );

    expect(calledUrl()).toBe(`${GATEWAY}/api/files/x`); // token removed from the query
    expect(calledAuth()).toBe(`Bearer ${jwt}`);
  });

  it('keeps a resource ?token untouched when an Authorization header is already present', async () => {
    const uuid = 'abc-123-resource';
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.sig';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${uuid}`, {
        authorization: `Bearer ${jwt}`,
      }),
      params(['organizations', 'invitations', 'info']),
    );

    expect(calledUrl()).toContain(`token=${uuid}`);
    expect(calledAuth()).toBe(`Bearer ${jwt}`);
  });

  it('forwards an eyJ-prefixed but non-3-segment ?token (pins the shape boundary, not just the prefix)', async () => {
    // A token that starts with the JWT prefix but is NOT a 3-segment compact JWT must be
    // treated as a resource token and forwarded, so the discriminator can't be loosened to
    // a bare prefix check without this test failing.
    const notAJwt = 'eyJabc.def';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${notAJwt}`),
      params(['organizations', 'invitations', 'info']),
    );

    expect(calledUrl()).toBe(`${GATEWAY}/api/organizations/invitations/info?token=${encodeURIComponent(notAJwt)}`);
    expect(calledAuth()).toBeUndefined();
  });

  it('preserves other query params alongside a forwarded resource ?token', async () => {
    const uuid = 'tok-uuid';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/organizations/invitations/info?token=${uuid}&probe=1`),
      params(['organizations', 'invitations', 'info']),
    );

    expect(calledUrl()).toContain(`token=${uuid}`);
    expect(calledUrl()).toContain('probe=1');
  });
});

describe('proxy route - CORS response headers', () => {
  // Regression: the proxy used to emit `Access-Control-Allow-Origin: *` together with
  // `Access-Control-Allow-Credentials: true`. Browsers reject that pair, and auth is
  // Bearer-token (never a cookie), so credentials mode must not be advertised. The
  // wildcard origin stays; the credentials header must be gone.
  it('sets a wildcard origin without advertising credentials', async () => {
    const res = await GET(
      makeReq('http://localhost:3000/api/proxy/users/status'),
      params(['users', 'status']),
    );

    expect(res.headers.get('Access-Control-Allow-Origin')).toBe('*');
    expect(res.headers.get('Access-Control-Allow-Credentials')).toBeNull();
  });
});
