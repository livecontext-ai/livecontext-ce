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

  it('LC-044: never promotes a JWT-shaped ?token to the Authorization bearer', async () => {
    const jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig';
    await GET(
      makeReq(`http://localhost:3000/api/proxy/files/x?token=${jwt}`),
      params(['files', 'x']),
    );

    // A session credential in a URL is not an authentication channel any more, and it is
    // stripped rather than forwarded in the gateway URL.
    expect(calledAuth()).toBeUndefined();
    expect(calledUrl()).toBe(`${GATEWAY}/api/files/x`);
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

// Scope, stated plainly: this route handler is REACHED in production only for
// `/api/proxy/external-proxy` (the live proxy for every other path is `proxy.ts`, a middleware
// rewrite that never lands here). Its log line runs on that path, so the redaction below is a
// real control there; these tests drive it through GET only because the harness calls the
// handler directly. The live proxy is covered by `__tests__/proxy.no-url-logging.test.ts`,
// which pins that `proxy.ts` logs no URL at all.
describe('proxy route - log redaction (LC-067)', () => {
  function loggedLine(): string {
    const spy = console.info as unknown as ReturnType<typeof vi.fn>;
    return spy.mock.calls.map((c) => String(c[0])).join('\n');
  }

  beforeEach(() => {
    vi.spyOn(console, 'info').mockImplementation(() => undefined);
  });

  it.each(['sig', 'signature', 'key', 'exp', 'token', 'api_key', 'share_token', 'password'])(
    'redacts the %s query parameter value',
    async (name) => {
      await GET(
        makeReq(`http://localhost:3000/api/proxy/some/path?${name}=CAPABILITY-VALUE&page=2`),
        params(['some', 'path']),
      );

      const line = loggedLine();
      expect(line).toContain('some/path');
      expect(line).not.toContain('CAPABILITY-VALUE');
      expect(line).toContain('page=2');
    },
  );

  it('drops the whole query of a signed-URL capability path', async () => {
    await GET(
      makeReq('http://localhost:3000/api/proxy/files/proxy-signed?fileId=abc&exp=1999999999&sig=deadbeef'),
      params(['files', 'proxy-signed']),
    );

    const line = loggedLine();
    expect(line).toContain('files/proxy-signed');
    expect(line).not.toContain('deadbeef');
    expect(line).not.toContain('1999999999');
    expect(line).not.toContain('fileId=abc');
  });
});

describe('proxy route - CORS response headers', () => {
  // LC-027 audit round 2: the handler emitted `Access-Control-Allow-Origin: *`, so any site
  // could read what this route returns (it serves /api/proxy/external-proxy, an authenticated
  // URL fetcher). Every real caller is same-origin, so no CORS header is emitted at all.
  it('emits no CORS headers (same-origin only)', async () => {
    const res = await GET(
      makeReq('http://localhost:3000/api/proxy/users/status', { origin: 'https://evil.example' }),
      params(['users', 'status']),
    );

    expect(res.headers.get('Access-Control-Allow-Origin')).toBeNull();
    expect(res.headers.get('Access-Control-Allow-Credentials')).toBeNull();
    expect(res.headers.get('Access-Control-Allow-Methods')).toBeNull();
    expect(res.headers.get('X-Request-Id')).toBeTruthy();
  });
});

describe('proxy route - Set-Cookie relay', () => {
  // Regression: every backend response header was copied with Headers.set, so a response
  // carrying two Set-Cookie headers reached the browser with only the last one. The OAuth
  // connect relies on its per-flow binding cookie surviving this hop (LC-005).
  it('relays every Set-Cookie header, not only the last one', async () => {
    const upstream = new Headers({ 'content-type': 'application/json' });
    upstream.append('set-cookie', 'lc_oauth_aaa=1; Path=/; HttpOnly; SameSite=Lax');
    upstream.append('set-cookie', 'other=2; Path=/');
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 200, headers: upstream }));

    const res = await GET(
      makeReq('http://localhost:3000/api/proxy/credentials/oauth2/initiate'),
      params(['credentials', 'oauth2', 'initiate']),
    );

    const cookies = res.headers.getSetCookie();
    expect(cookies).toHaveLength(2);
    expect(cookies[0]).toContain('lc_oauth_aaa=1');
    expect(cookies[1]).toContain('other=2');
  });
});
