/**
 * Cloud edition only: a self-hosted (CE) install asking to be linked to this cloud account.
 *
 * <p>The CE "Connect to Cloud" button opens {@code /<locale>/onboarding?ce_link=1&client_id=..&
 * redirect_uri=..&state=..&code_challenge=..&code_challenge_method=S256} on the cloud instead of
 * the bare Keycloak authorization, so a new account first completes the cloud onboarding (email
 * verification, profile) and a paid plan. This module is the ONE place that:
 * <ol>
 *   <li>validates those parameters (strictly: anything unexpected rejects the whole link),</li>
 *   <li>keeps them in {@code sessionStorage} so they survive the sign-in redirect, the
 *       FirstLoginGuard redirect, the email-verification step, a reload and a Stripe checkout
 *       (all same-tab navigations),</li>
 *   <li>rebuilds the Keycloak authorization URL from THIS app's own Keycloak configuration, and</li>
 *   <li>decides, from {@code GET /api/ce-link/eligibility}, whether to continue to Keycloak or
 *       to the pricing page.</li>
 * </ol>
 *
 * <p><b>No open redirect.</b> Nothing from the query is used as a navigation target: the
 * authorize URL's origin, realm and path come from {@code NEXT_PUBLIC_KEYCLOAK_*}; the query
 * only contributes values that pass a whitelist shape, and {@code redirect_uri} must be a CE
 * loopback callback ({@code http://localhost:<port>/api/cloud-link/callback} or the 127.0.0.1
 * form). Keycloak still enforces its own exact redirect allowlist on top.
 *
 * <p>CE builds never act on any of this (every entry point is a no-op when {@code IS_CE}).
 */

import { IS_CE } from '@/lib/edition';
import { ceLinkService, type CeLinkEligibility } from '@/lib/api/ce-link.service';
import { assignLocation } from '@/lib/navigation/assignLocation';

/** sessionStorage key holding the pending link (JSON). */
export const PENDING_CE_LINK_KEY = 'lc.pendingCeLink';

/**
 * How long a pending link stays usable. Mirrors the CE backend's default
 * {@code cloud-link.pending-auth-ttl} (2 h, sized for onboarding plus a checkout): past it the
 * CE has forgotten the PKCE verifier, so continuing would only land on its "expired" message.
 */
export const PENDING_CE_LINK_TTL_MS = 2 * 60 * 60 * 1000;

/** The only callback path a CE install exposes for the cloud-link OAuth flow. */
const CE_CALLBACK_PATH = '/api/cloud-link/callback';
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1']);
const STATE_RE = /^[A-Za-z0-9_-]{8,128}$/;
/** RFC 7636 S256 challenge: base64url(SHA-256) without padding = exactly 43 characters. */
const CODE_CHALLENGE_RE = /^[A-Za-z0-9_-]{43}$/;
const MAX_REDIRECT_URI_LENGTH = 2048;

export interface PendingCeLink {
  clientId: string;
  redirectUri: string;
  state: string;
  codeChallenge: string;
  codeChallengeMethod: 'S256';
  /** Epoch millis when the link was first captured (drives {@link PENDING_CE_LINK_TTL_MS}). */
  savedAt: number;
}

export interface KeycloakLinkConfig {
  /** Keycloak base URL without the realm (NEXT_PUBLIC_KEYCLOAK_URL). */
  url: string | undefined;
  realm: string | undefined;
  clientId: string | undefined;
}

/** This app's own Keycloak configuration, the same values the OIDC provider is built from. */
export function keycloakConfigFromEnv(): KeycloakLinkConfig {
  return {
    url: process.env.NEXT_PUBLIC_KEYCLOAK_URL,
    realm: process.env.NEXT_PUBLIC_KEYCLOAK_REALM,
    clientId: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID,
  };
}

/**
 * A CE loopback callback, byte for byte: {@code http://localhost[:port]/api/cloud-link/callback}
 * or the 127.0.0.1 form. The raw string must equal its own parsed form, so encodings, backslashes,
 * credentials, a query or a fragment can never smuggle another target through.
 */
export function isAllowedCeRedirectUri(raw: unknown): raw is string {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_REDIRECT_URI_LENGTH) return false;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  if (url.href !== raw) return false;
  if (url.protocol !== 'http:') return false;
  if (!LOOPBACK_HOSTS.has(url.hostname)) return false;
  if (url.username || url.password) return false;
  if (url.pathname !== CE_CALLBACK_PATH) return false;
  if (url.search || url.hash) return false;
  return true;
}

function validateFields(
  fields: {
    clientId: unknown;
    redirectUri: unknown;
    state: unknown;
    codeChallenge: unknown;
    codeChallengeMethod: unknown;
  },
  expectedClientId: string | undefined,
): Omit<PendingCeLink, 'savedAt'> | null {
  const { clientId, redirectUri, state, codeChallenge, codeChallengeMethod } = fields;
  if (!expectedClientId || clientId !== expectedClientId) return null;
  if (!isAllowedCeRedirectUri(redirectUri)) return null;
  if (typeof state !== 'string' || !STATE_RE.test(state)) return null;
  if (typeof codeChallenge !== 'string' || !CODE_CHALLENGE_RE.test(codeChallenge)) return null;
  if (codeChallengeMethod !== 'S256') return null;
  return { clientId, redirectUri, state, codeChallenge, codeChallengeMethod: 'S256' };
}

/**
 * Parse the onboarding query. Returns null when it is not a CE-link request
 * ({@code ce_link} is not {@code 1}) or when any parameter fails validation.
 */
export function parseCeLinkParams(
  search: string | URLSearchParams,
  expectedClientId: string | undefined = keycloakConfigFromEnv().clientId,
  now: number = Date.now(),
): PendingCeLink | null {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  if (params.get('ce_link') !== '1') return null;
  const valid = validateFields(
    {
      clientId: params.get('client_id'),
      redirectUri: params.get('redirect_uri'),
      state: params.get('state'),
      codeChallenge: params.get('code_challenge'),
      codeChallengeMethod: params.get('code_challenge_method'),
    },
    expectedClientId,
  );
  return valid ? { ...valid, savedAt: now } : null;
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

export function savePendingCeLink(link: PendingCeLink): void {
  try {
    storage()?.setItem(PENDING_CE_LINK_KEY, JSON.stringify(link));
  } catch {
    // Storage blocked: the link cannot survive a navigation in this tab. Nothing to fall back on.
  }
}

export function clearPendingCeLink(): void {
  try {
    storage()?.removeItem(PENDING_CE_LINK_KEY);
  } catch {
    // ignore
  }
}

/**
 * The pending link of this tab, re-validated (storage is never trusted more than the query) and
 * within its TTL. Anything invalid or expired is removed and reads as null. Always null in CE.
 */
export function loadPendingCeLink(
  expectedClientId: string | undefined = keycloakConfigFromEnv().clientId,
  now: number = Date.now(),
): PendingCeLink | null {
  if (IS_CE) return null;
  let raw: string | null = null;
  try {
    raw = storage()?.getItem(PENDING_CE_LINK_KEY) ?? null;
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<PendingCeLink>;
    const valid = validateFields(
      {
        clientId: parsed.clientId,
        redirectUri: parsed.redirectUri,
        state: parsed.state,
        codeChallenge: parsed.codeChallenge,
        codeChallengeMethod: parsed.codeChallengeMethod,
      },
      expectedClientId,
    );
    const savedAt = typeof parsed.savedAt === 'number' ? parsed.savedAt : NaN;
    if (!valid || !Number.isFinite(savedAt) || savedAt > now || now - savedAt > PENDING_CE_LINK_TTL_MS) {
      clearPendingCeLink();
      return null;
    }
    return { ...valid, savedAt };
  } catch {
    clearPendingCeLink();
    return null;
  }
}

export function hasPendingCeLink(): boolean {
  return loadPendingCeLink() !== null;
}

export type CeLinkCapture = 'absent' | 'saved' | 'invalid';

/**
 * On arrival at the onboarding page: store a valid CE-link request of the current URL.
 * A {@code ce_link=1} request that fails validation also drops any older pending link, so a
 * rejected link can never silently continue a previous one. No-op in CE.
 */
export function captureCeLinkFromSearch(
  search: string,
  expectedClientId: string | undefined = keycloakConfigFromEnv().clientId,
  now: number = Date.now(),
): CeLinkCapture {
  if (IS_CE) return 'absent';
  const params = new URLSearchParams(search);
  if (params.get('ce_link') !== '1') return 'absent';
  const link = parseCeLinkParams(params, expectedClientId, now);
  if (!link) {
    clearPendingCeLink();
    return 'invalid';
  }
  savePendingCeLink(link);
  return 'saved';
}

/**
 * The Keycloak authorization request the CE started, rebuilt from this app's Keycloak
 * configuration. Null when that configuration is missing or malformed (never guessed).
 */
export function buildCeLinkAuthorizeUrl(
  link: PendingCeLink,
  keycloak: KeycloakLinkConfig = keycloakConfigFromEnv(),
): string | null {
  if (!keycloak.url || !keycloak.realm) return null;
  let base: URL;
  try {
    base = new URL(keycloak.url);
  } catch {
    return null;
  }
  if (base.protocol !== 'https:' && base.protocol !== 'http:') return null;
  const root = `${base.origin}${base.pathname.replace(/\/+$/, '')}`;
  const query = new URLSearchParams({
    client_id: link.clientId,
    redirect_uri: link.redirectUri,
    response_type: 'code',
    scope: 'openid',
    code_challenge: link.codeChallenge,
    code_challenge_method: link.codeChallengeMethod,
    state: link.state,
  });
  return `${root}/realms/${encodeURIComponent(keycloak.realm)}/protocol/openid-connect/auth?${query.toString()}`;
}

/** Where a signed-in user without a paid plan is sent while a CE link is pending. */
export function ceLinkPricingPath(locale: string): string {
  return `/${locale}/app/settings/pricing?ce_link=1`;
}

/**
 * - {@code none}: no pending link in this tab (or CE build): the caller does what it did before.
 * - {@code redirected}: eligible; the pending link was cleared and the browser sent to Keycloak.
 * - {@code plan_required}: not eligible; the link stays pending until a paid plan exists.
 * - {@code error}: the eligibility check or the Keycloak configuration failed; the link stays pending.
 */
export type CeLinkContinuation = 'none' | 'redirected' | 'plan_required' | 'error';

export interface ContinueCeLinkOptions {
  fetchEligibility?: () => Promise<CeLinkEligibility>;
  navigate?: (url: string) => void;
  keycloak?: KeycloakLinkConfig;
}

/**
 * Continue a pending CE link: ask the cloud whether the user may link, then either hand the
 * browser to Keycloak (the SSO session is live, so no password or second factor again) or
 * report {@code plan_required}. Keycloak redirects to the CE callback, which completes the link.
 */
export async function continuePendingCeLink(
  options: ContinueCeLinkOptions = {},
): Promise<CeLinkContinuation> {
  const keycloak = options.keycloak ?? keycloakConfigFromEnv();
  const link = loadPendingCeLink(keycloak.clientId);
  if (!link) return 'none';
  const fetchEligibility = options.fetchEligibility ?? (() => ceLinkService.eligibility());
  const navigate = options.navigate ?? assignLocation;

  let eligibility: CeLinkEligibility;
  try {
    eligibility = await fetchEligibility();
  } catch {
    return 'error';
  }
  if (!eligibility?.eligible) return 'plan_required';

  const authorizeUrl = buildCeLinkAuthorizeUrl(link, keycloak);
  if (!authorizeUrl) return 'error';
  clearPendingCeLink();
  navigate(authorizeUrl);
  return 'redirected';
}
