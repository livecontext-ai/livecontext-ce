import { describe, it, expect } from 'vitest';

import { resolveOAuth2Defaults } from '../CredentialWizard';
import type { CredentialTemplate } from '@/lib/api/orchestrator/types';

/**
 * Build a minimal CredentialTemplate around the bits the resolver inspects.
 * Anything not relevant to the resolver gets a benign default - we are pinning
 * a pure function, not the whole template surface.
 */
function buildTemplate(overrides: Partial<CredentialTemplate>): CredentialTemplate {
  return {
    id: 'tmpl-1',
    credential_name: 'gmail',
    display_name: 'Gmail',
    auth_type: 'oauth2',
    icon_slug: 'gmail',
    properties: [],
    ...overrides,
  } as CredentialTemplate;
}

describe('resolveOAuth2Defaults', () => {
  it('reads metadata.oauth2Config as the canonical source - matches what auth-service OAuth2Engine consumes from the catalog', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
          tokenUrl: 'https://oauth2.googleapis.com/token',
          scopes: [
            'https://www.googleapis.com/auth/gmail.readonly',
            'https://www.googleapis.com/auth/gmail.send',
          ],
        },
      },
    });

    const out = resolveOAuth2Defaults(tmpl);

    expect(out.authUrl).toBe('https://accounts.google.com/o/oauth2/v2/auth');
    expect(out.tokenUrl).toBe('https://oauth2.googleapis.com/token');
    expect(out.scopes).toBe(
      'https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send'
    );
  });

  it('joins metadata.oauth2Config.scopes (string[]) with a space - matches the wizard scopes input control format', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://provider/auth',
          tokenUrl: 'https://provider/token',
          scopes: ['read', 'write'],
        },
      },
    });

    expect(resolveOAuth2Defaults(tmpl).scopes).toBe('read write');
  });

  it('accepts metadata.oauth2Config.scopes as a pre-joined string when the catalog already normalized it', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://provider/auth',
          tokenUrl: 'https://provider/token',
          scopes: 'read write',
        },
      },
    });

    expect(resolveOAuth2Defaults(tmpl).scopes).toBe('read write');
  });

  it('returns metadata-resolved URLs even when scopes is missing - some providers leave scope selection to runtime, the URLs are still pinned', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://provider/auth',
          tokenUrl: 'https://provider/token',
        },
      },
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://provider/auth');
    expect(out.tokenUrl).toBe('https://provider/token');
    expect(out.scopes).toBeNull();
  });

  it('falls back to properties[].default when metadata.oauth2Config is absent - retro-compat for templates that have not been re-imported with the metadata block', () => {
    const tmpl = buildTemplate({
      properties: [
        { name: 'authUrl', displayName: 'Authorization URL', type: 'string', default: 'https://legacy/auth' },
        { name: 'accessTokenUrl', displayName: 'Token URL', type: 'string', default: 'https://legacy/token' },
        { name: 'scope', displayName: 'Scopes', type: 'string', default: 'read write' },
      ],
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://legacy/auth');
    expect(out.tokenUrl).toBe('https://legacy/token');
    expect(out.scopes).toBe('read write');
  });

  it('legacy fallback also accepts tokenUrl property name (vs accessTokenUrl) and scopes (vs scope)', () => {
    const tmpl = buildTemplate({
      properties: [
        { name: 'authUrl', displayName: 'Authorization URL', type: 'string', default: 'https://legacy/auth' },
        { name: 'tokenUrl', displayName: 'Token URL', type: 'string', default: 'https://legacy/token' },
        { name: 'scopes', displayName: 'Scopes', type: 'string', default: 'a b' },
      ],
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.tokenUrl).toBe('https://legacy/token');
    expect(out.scopes).toBe('a b');
  });

  it('parses properties when delivered as a JSON string (some endpoints stringify it) - defensive against backend response shape variance', () => {
    const tmpl = buildTemplate({
      properties: JSON.stringify([
        { name: 'authUrl', default: 'https://stringified/auth' },
        { name: 'tokenUrl', default: 'https://stringified/token' },
      ]),
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://stringified/auth');
    expect(out.tokenUrl).toBe('https://stringified/token');
  });

  it('parses properties when wrapped as { value: [...] } envelope', () => {
    const tmpl = buildTemplate({
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      properties: { value: [
        { name: 'authUrl', default: 'https://wrapped/auth' },
        { name: 'tokenUrl', default: 'https://wrapped/token' },
      ]} as any,
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://wrapped/auth');
    expect(out.tokenUrl).toBe('https://wrapped/token');
  });

  it('regression: reads metadata through the production wire envelope { type:"jsonb", value:"<json>" } - catalog-service\'s unwrapVariants does NOT unwrap metadata, so the resolver must do it inline', () => {
    // Reproduces the exact wire shape the catalog endpoint returns today
    // (CredentialTemplateController only unwraps `variants`; `metadata` and
    // `properties` arrive as PGobject-style envelopes). Without the inline
    // unwrap, the metadata branch silently no-ops and the resolver falls
    // through to the legacy properties path - defeating the "metadata is
    // canonical" intent. Pinning this here forces the next refactor to keep
    // the unwrap working until the backend serializes metadata as plain JSON.
    const envelope = {
      type: 'jsonb',
      value: JSON.stringify({
        oauth2Config: {
          authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
          tokenUrl: 'https://oauth2.googleapis.com/token',
          scopes: ['https://www.googleapis.com/auth/gmail.send'],
        },
      }),
    };
    const tmpl = buildTemplate({ metadata: envelope });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://accounts.google.com/o/oauth2/v2/auth');
    expect(out.tokenUrl).toBe('https://oauth2.googleapis.com/token');
    expect(out.scopes).toBe('https://www.googleapis.com/auth/gmail.send');
  });

  it('regression: reads properties through the production wire envelope { type:"jsonb", value:"<json>" }', () => {
    // Same envelope shape as metadata - `properties` reaches the wire as a
    // PGobject envelope too (the controller only unwraps `variants`). The
    // legacy fallback path must keep working through the envelope so retro-
    // compat is preserved for templates that lack a metadata block.
    const envelope = {
      type: 'jsonb',
      value: JSON.stringify([
        { name: 'authUrl', default: 'https://envelope/auth' },
        { name: 'tokenUrl', default: 'https://envelope/token' },
        { name: 'scope', default: 'read write' },
      ]),
    };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tmpl = buildTemplate({ properties: envelope as any });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://envelope/auth');
    expect(out.tokenUrl).toBe('https://envelope/token');
    expect(out.scopes).toBe('read write');
  });

  it('falls through to legacy properties when metadata.oauth2Config is an empty object - defensive against catalog templates whose oauth2Config block was added but never populated', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tmpl = buildTemplate({
      metadata: { oauth2Config: {} },
      properties: [
        { name: 'authUrl', displayName: 'Authorization URL', type: 'string', default: 'https://legacy/auth' },
        { name: 'tokenUrl', displayName: 'Token URL', type: 'string', default: 'https://legacy/token' },
      ],
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://legacy/auth');
    expect(out.tokenUrl).toBe('https://legacy/token');
  });

  it('returns null scopes when metadata.oauth2Config.scopes is an empty array - distinguishes "no scopes declared" from "scopes declared but empty"', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://provider/auth',
          tokenUrl: 'https://provider/token',
          scopes: [],
        },
      },
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.scopes).toBeNull();
  });

  it('returns nulls for every field when neither metadata nor properties have defaults - custom APIs the user is registering for the first time', () => {
    const tmpl = buildTemplate({ properties: [] });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBeNull();
    expect(out.tokenUrl).toBeNull();
    expect(out.scopes).toBeNull();
  });

  it('returns nulls when template is null/undefined - defensive against early renders before fetch resolves', () => {
    expect(resolveOAuth2Defaults(null)).toEqual({ authUrl: null, tokenUrl: null, scopes: null });
    expect(resolveOAuth2Defaults(undefined)).toEqual({ authUrl: null, tokenUrl: null, scopes: null });
  });

  it('ignores non-string values in metadata.oauth2Config (defensive: malformed catalog data must not crash the wizard)', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 42,
          tokenUrl: { wrong: 'shape' },
          scopes: [42, 'good', null],
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any,
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBeNull();
    expect(out.tokenUrl).toBeNull();
    // Only the well-typed string scope survives; a single 'good' ⇒ "good"
    expect(out.scopes).toBe('good');
  });

  it('regression: catalog template with metadata block AND legacy properties prefers metadata - properties should not silently shadow the canonical source', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          authorizationUrl: 'https://canonical/auth',
          tokenUrl: 'https://canonical/token',
          scopes: ['canonical'],
        },
      },
      properties: [
        { name: 'authUrl', displayName: 'X', type: 'string', default: 'https://legacy/auth' },
        { name: 'tokenUrl', displayName: 'X', type: 'string', default: 'https://legacy/token' },
      ],
    });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://canonical/auth');
    expect(out.tokenUrl).toBe('https://canonical/token');
  });

  it('regression: deleted-BYOK reconnect resolves through the production wire envelope - the user-reported bug end-to-end', () => {
    // Reproduces the user-reported sequence:
    //   1. User had a tenant-owned BYOK platform_credential for Gmail.
    //   2. User deleted it. Cascade flipped dependent credentials to needs_reauth
    //      (auth.platform_credentials no longer has any row for "gmail").
    //   3. User clicks "Use my own OAuth app" on the needs_reauth row.
    //   4. Wizard fetches the gmail catalog template via the catalog endpoint,
    //      which returns metadata as the PGobject envelope (controller does NOT
    //      unwrap it).
    //   5. Wizard's hide-gate is `activeMode==='advanced' && authUrl && tokenUrl`.
    //      For the URL fields to be hidden + pre-filled, the resolver MUST
    //      answer through the envelope.
    // The previous gate also required `hasPlatformCredentials===true`, which
    // was false because the BYOK was just deleted. This test pins the FIXED
    // contract: the wire-shape input the user actually hits resolves to real
    // URLs, so the wizard hide-gate fires and the user lands on
    // [Client ID, Client Secret] instead of empty placeholder URL fields.
    const wireShape = {
      type: 'jsonb',
      value: JSON.stringify({
        oauth2Config: {
          authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
          tokenUrl: 'https://oauth2.googleapis.com/token',
          scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
        },
      }),
    };
    const tmpl = buildTemplate({ metadata: wireShape });

    const out = resolveOAuth2Defaults(tmpl);
    expect(out.authUrl).toBe('https://accounts.google.com/o/oauth2/v2/auth');
    expect(out.tokenUrl).toBe('https://oauth2.googleapis.com/token');
    expect(out.scopes).toBe('https://www.googleapis.com/auth/gmail.readonly');
  });
});
