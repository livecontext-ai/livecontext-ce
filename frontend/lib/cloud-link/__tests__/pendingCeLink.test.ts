/**
 * @vitest-environment jsdom
 *
 * The pending CE link: the only code that turns a query string written by a self-hosted install
 * into a navigation to Keycloak. Every rejection below is a way a crafted link could otherwise
 * steer a signed-in cloud user (open redirect, foreign callback, downgraded PKCE).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));
const eligibilityMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/ce-link.service', () => ({
  ceLinkService: { eligibility: () => eligibilityMock() },
}));
const assignMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (u: string) => assignMock(u) }));

import {
  PENDING_CE_LINK_KEY,
  PENDING_CE_LINK_TTL_MS,
  buildCeLinkAuthorizeUrl,
  captureCeLinkFromSearch,
  ceLinkPricingPath,
  clearPendingCeLink,
  continuePendingCeLink,
  hasPendingCeLink,
  isAllowedCeRedirectUri,
  loadPendingCeLink,
  parseCeLinkParams,
  savePendingCeLink,
  type KeycloakLinkConfig,
  type PendingCeLink,
} from '../pendingCeLink';

const CLIENT_ID = 'livecontext-frontend';
const CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'; // 43 chars, base64url
const STATE = '0f8fad5b-d9cb-469f-a165-70867728950e';
const REDIRECT = 'http://localhost:8080/api/cloud-link/callback';
const KC: KeycloakLinkConfig = { url: 'https://auth.livecontext.ai', realm: 'livecontext', clientId: CLIENT_ID };

function query(overrides: Record<string, string | null> = {}): string {
  const base: Record<string, string | null> = {
    ce_link: '1',
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT,
    state: STATE,
    code_challenge: CHALLENGE,
    code_challenge_method: 'S256',
    ...overrides,
  };
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(base)) if (v !== null) p.set(k, v);
  return `?${p.toString()}`;
}

function link(overrides: Partial<PendingCeLink> = {}): PendingCeLink {
  return {
    clientId: CLIENT_ID,
    redirectUri: REDIRECT,
    state: STATE,
    codeChallenge: CHALLENGE,
    codeChallengeMethod: 'S256',
    savedAt: Date.now(),
    ...overrides,
  };
}

beforeEach(() => {
  edition.isCe = false;
  sessionStorage.clear();
  eligibilityMock.mockReset();
  assignMock.mockReset();
});
afterEach(() => sessionStorage.clear());

describe('parseCeLinkParams', () => {
  it('accepts a well-formed CE link request', () => {
    const parsed = parseCeLinkParams(query(), CLIENT_ID, 1000);
    expect(parsed).toEqual({
      clientId: CLIENT_ID,
      redirectUri: REDIRECT,
      state: STATE,
      codeChallenge: CHALLENGE,
      codeChallengeMethod: 'S256',
      savedAt: 1000,
    });
  });

  it('accepts the 127.0.0.1 loopback and any port', () => {
    expect(parseCeLinkParams(query({ redirect_uri: 'http://127.0.0.1:3000/api/cloud-link/callback' }), CLIENT_ID)).not.toBeNull();
    expect(parseCeLinkParams(query({ redirect_uri: 'http://localhost/api/cloud-link/callback' }), CLIENT_ID)).not.toBeNull();
  });

  it('is not a CE link without ce_link=1', () => {
    expect(parseCeLinkParams(query({ ce_link: null }), CLIENT_ID)).toBeNull();
    expect(parseCeLinkParams(query({ ce_link: 'true' }), CLIENT_ID)).toBeNull();
  });

  it('rejects a client id other than the app\'s own Keycloak client, and any when the app has none', () => {
    expect(parseCeLinkParams(query({ client_id: 'admin-cli' }), CLIENT_ID)).toBeNull();
    expect(parseCeLinkParams(query(), undefined)).toBeNull();
    expect(parseCeLinkParams(query(), '')).toBeNull();
  });

  it.each([
    ['a foreign host', 'http://evil.example/api/cloud-link/callback'],
    ['a look-alike host', 'http://localhost.evil.example/api/cloud-link/callback'],
    ['https', 'https://localhost:8080/api/cloud-link/callback'],
    ['a non-callback path', 'http://localhost:8080/api/other'],
    ['a path traversal', 'http://localhost:8080/api/cloud-link/callback/../../x'],
    ['a query', 'http://localhost:8080/api/cloud-link/callback?next=http://evil.example'],
    ['a fragment', 'http://localhost:8080/api/cloud-link/callback#x'],
    ['credentials', 'http://user:pw@localhost:8080/api/cloud-link/callback'],
    ['a non-canonical spelling', 'http://LOCALHOST:8080/api/cloud-link/callback'],
    ['a javascript: URL', 'javascript:alert(1)'],
    ['a relative URL', '/api/cloud-link/callback'],
    ['an empty value', ''],
  ])('rejects a redirect_uri with %s', (_label, uri) => {
    expect(parseCeLinkParams(query({ redirect_uri: uri }), CLIENT_ID)).toBeNull();
  });

  it.each([
    ['missing', null],
    ['too short', 'abc'],
    ['too long', 'a'.repeat(129)],
    ['with a slash', 'abc/def-ghi-jkl'],
    ['with a space', 'abc def ghijkl'],
  ])('rejects a state that is %s', (_label, state) => {
    expect(parseCeLinkParams(query({ state }), CLIENT_ID)).toBeNull();
  });

  it.each([
    ['missing', null],
    ['42 chars', CHALLENGE.slice(0, 42)],
    ['44 chars', `${CHALLENGE}A`],
    ['padded base64', `${CHALLENGE.slice(0, 42)}=`],
    ['standard base64 alphabet', `${CHALLENGE.slice(0, 42)}+`],
  ])('rejects a code_challenge that is %s', (_label, challenge) => {
    expect(parseCeLinkParams(query({ code_challenge: challenge }), CLIENT_ID)).toBeNull();
  });

  it.each([['plain'], ['s256'], [null]])('rejects code_challenge_method %s (only S256)', (method) => {
    expect(parseCeLinkParams(query({ code_challenge_method: method }), CLIENT_ID)).toBeNull();
  });
});

describe('isAllowedCeRedirectUri', () => {
  it('only accepts strings', () => {
    expect(isAllowedCeRedirectUri(undefined)).toBe(false);
    expect(isAllowedCeRedirectUri(42)).toBe(false);
    expect(isAllowedCeRedirectUri(REDIRECT)).toBe(true);
  });
});

describe('storage', () => {
  it('captures a valid request into sessionStorage and loads it back', () => {
    expect(captureCeLinkFromSearch(query(), CLIENT_ID, 5000)).toBe('saved');
    expect(loadPendingCeLink(CLIENT_ID, 6000)).toEqual(link({ savedAt: 5000 }));
  });

  it('reports absent without touching an existing pending link when the URL is not a CE link', () => {
    savePendingCeLink(link());
    expect(captureCeLinkFromSearch('?foo=bar', CLIENT_ID)).toBe('absent');
    expect(loadPendingCeLink(CLIENT_ID)).not.toBeNull();
  });

  it('drops an older pending link when a new ce_link request is invalid', () => {
    savePendingCeLink(link());
    expect(captureCeLinkFromSearch(query({ code_challenge_method: 'plain' }), CLIENT_ID)).toBe('invalid');
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('expires a link older than the TTL', () => {
    savePendingCeLink(link({ savedAt: 0 }));
    expect(loadPendingCeLink(CLIENT_ID, PENDING_CE_LINK_TTL_MS + 1)).toBeNull();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('re-validates what storage holds: a tampered redirect is dropped', () => {
    sessionStorage.setItem(
      PENDING_CE_LINK_KEY,
      JSON.stringify(link({ redirectUri: 'http://evil.example/api/cloud-link/callback' })),
    );
    expect(loadPendingCeLink(CLIENT_ID)).toBeNull();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('drops unparseable storage content', () => {
    sessionStorage.setItem(PENDING_CE_LINK_KEY, '{not json');
    expect(loadPendingCeLink(CLIENT_ID)).toBeNull();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('is inert in a CE build: nothing captured, nothing loaded', () => {
    edition.isCe = true;
    expect(captureCeLinkFromSearch(query(), CLIENT_ID)).toBe('absent');
    sessionStorage.setItem(PENDING_CE_LINK_KEY, JSON.stringify(link()));
    expect(loadPendingCeLink(CLIENT_ID)).toBeNull();
  });

  it('clearPendingCeLink removes it', () => {
    savePendingCeLink(link());
    clearPendingCeLink();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });
});

describe('buildCeLinkAuthorizeUrl', () => {
  it('rebuilds the authorize URL from the app\'s Keycloak config and the validated values only', () => {
    const url = new URL(buildCeLinkAuthorizeUrl(link(), KC)!);
    expect(`${url.origin}${url.pathname}`).toBe(
      'https://auth.livecontext.ai/realms/livecontext/protocol/openid-connect/auth',
    );
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: CLIENT_ID,
      redirect_uri: REDIRECT,
      response_type: 'code',
      scope: 'openid',
      code_challenge: CHALLENGE,
      code_challenge_method: 'S256',
      state: STATE,
    });
  });

  it('tolerates a trailing slash on the Keycloak URL', () => {
    expect(buildCeLinkAuthorizeUrl(link(), { ...KC, url: 'https://auth.livecontext.ai/' })).toMatch(
      /^https:\/\/auth\.livecontext\.ai\/realms\/livecontext\/protocol\/openid-connect\/auth\?/,
    );
  });

  it('returns null rather than guessing when the Keycloak config is missing or not http(s)', () => {
    expect(buildCeLinkAuthorizeUrl(link(), { ...KC, url: undefined })).toBeNull();
    expect(buildCeLinkAuthorizeUrl(link(), { ...KC, realm: '' })).toBeNull();
    expect(buildCeLinkAuthorizeUrl(link(), { ...KC, url: 'javascript:alert(1)' })).toBeNull();
    expect(buildCeLinkAuthorizeUrl(link(), { ...KC, url: 'not a url' })).toBeNull();
  });
});

describe('continuePendingCeLink', () => {
  it('returns none and asks nothing when no link is pending', async () => {
    expect(await continuePendingCeLink({ keycloak: KC })).toBe('none');
    expect(eligibilityMock).not.toHaveBeenCalled();
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('eligible: clears the pending link and navigates to the rebuilt Keycloak URL', async () => {
    savePendingCeLink(link());
    eligibilityMock.mockResolvedValue({ eligible: true, planCode: 'PRO', reason: null });

    expect(await continuePendingCeLink({ keycloak: KC })).toBe('redirected');
    expect(assignMock).toHaveBeenCalledTimes(1);
    expect(assignMock.mock.calls[0][0]).toBe(buildCeLinkAuthorizeUrl(link(), KC));
    expect(hasPendingCeLink()).toBe(false);
  });

  it('not eligible: keeps the link pending and does not navigate', async () => {
    savePendingCeLink(link());
    eligibilityMock.mockResolvedValue({ eligible: false, planCode: 'FREE', reason: 'PLAN_REQUIRED' });

    expect(await continuePendingCeLink({ keycloak: KC })).toBe('plan_required');
    expect(assignMock).not.toHaveBeenCalled();
    expect(loadPendingCeLink(CLIENT_ID)).not.toBeNull();
  });

  it('a malformed eligibility answer is not eligible (fails closed)', async () => {
    savePendingCeLink(link());
    eligibilityMock.mockResolvedValue(undefined);
    expect(await continuePendingCeLink({ keycloak: KC })).toBe('plan_required');
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('eligibility request failure: error, link kept, no navigation', async () => {
    savePendingCeLink(link());
    eligibilityMock.mockRejectedValue(new Error('503'));
    expect(await continuePendingCeLink({ keycloak: KC })).toBe('error');
    expect(assignMock).not.toHaveBeenCalled();
    expect(loadPendingCeLink(CLIENT_ID)).not.toBeNull();
  });

  it('eligible but no Keycloak config: error, link kept, no navigation', async () => {
    savePendingCeLink(link());
    eligibilityMock.mockResolvedValue({ eligible: true, planCode: 'PRO', reason: null });
    expect(await continuePendingCeLink({ keycloak: { ...KC, url: undefined } })).toBe('error');
    expect(assignMock).not.toHaveBeenCalled();
    expect(loadPendingCeLink(CLIENT_ID)).not.toBeNull();
  });

  it('uses injected collaborators when given', async () => {
    savePendingCeLink(link());
    const navigate = vi.fn();
    const fetchEligibility = vi.fn().mockResolvedValue({ eligible: true, planCode: 'TEAM', reason: null });
    expect(await continuePendingCeLink({ keycloak: KC, navigate, fetchEligibility })).toBe('redirected');
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(assignMock).not.toHaveBeenCalled();
  });
});

describe('ceLinkPricingPath', () => {
  it('points at the locale pricing page with the ce_link flag', () => {
    expect(ceLinkPricingPath('fr')).toBe('/fr/app/settings/pricing?ce_link=1');
  });
});
