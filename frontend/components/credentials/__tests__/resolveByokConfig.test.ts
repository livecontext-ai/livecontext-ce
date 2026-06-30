import { describe, it, expect } from 'vitest';

import { resolveByokConfig } from '../CredentialWizard';
import type { CredentialTemplate } from '@/lib/api/orchestrator/types';

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

describe('resolveByokConfig', () => {
  it('returns the safe default (surface="hidden", empty content) when the template is null - wizard render must not crash before the template is fetched', () => {
    const out = resolveByokConfig(null);
    expect(out.surface).toBe('hidden');
    expect(out.consoleUrl).toBeNull();
    expect(out.steps).toEqual([]);
  });

  it('returns surface="hidden" when the template carries no metadata block - covers the ~99% of OAuth2 APIs in the catalog that have not opted into BYOK exposure', () => {
    const tmpl = buildTemplate({});
    expect(resolveByokConfig(tmpl).surface).toBe('hidden');
  });

  it('returns surface="hidden" when metadata exists but has no oauth2Config.byok - partial metadata must not flip BYOK on by accident', () => {
    const tmpl = buildTemplate({
      metadata: { oauth2Config: { authorizationUrl: 'https://x', tokenUrl: 'https://y', scopes: [] } },
    });
    expect(resolveByokConfig(tmpl).surface).toBe('hidden');
  });

  it('reads metadata.oauth2Config.byok as the canonical source - matches what catalog.credentials.metadata stores after ApiMigrationImporter pass-through', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          byok: {
            surface: 'inline',
            consoleUrl: 'https://console.cloud.google.com/apis/credentials',
            redirectUriHint: 'Paste under Authorized redirect URIs',
            scopeNotes: 'CASA disclaimer.',
            steps: [
              { title: 'Open Console', body: 'Go to APIs & Services.' },
              { title: 'Create client', body: "Pick 'Web application'." },
            ],
          },
        },
      },
    });
    const out = resolveByokConfig(tmpl);
    expect(out.surface).toBe('inline');
    expect(out.consoleUrl).toBe('https://console.cloud.google.com/apis/credentials');
    expect(out.redirectUriHint).toBe('Paste under Authorized redirect URIs');
    expect(out.scopeNotes).toBe('CASA disclaimer.');
    expect(out.steps).toHaveLength(2);
    expect(out.steps[0]).toEqual({ title: 'Open Console', body: 'Go to APIs & Services.' });
  });

  it('regression - production wire envelope: unwraps the Spring jsonb {type:"jsonb", value:"<json string>"} envelope on `metadata`, mirroring what the catalog API returns to the wizard. If a future change drops the unwrap, every imported API would silently surface=hidden because the resolver would see an opaque envelope object', () => {
    const tmpl = buildTemplate({
      metadata: {
        type: 'jsonb',
        value: JSON.stringify({
          oauth2Config: {
            byok: { surface: 'disclosure', consoleUrl: 'https://api.slack.com/apps' },
          },
        }),
      } as any,
    });
    const out = resolveByokConfig(tmpl);
    expect(out.surface).toBe('disclosure');
    expect(out.consoleUrl).toBe('https://api.slack.com/apps');
  });

  it('clamps unknown surface values down to "hidden" - defensive parsing means a malformed JSON cannot accidentally activate an inline toggle. Test names the exact safety contract: typo in JSON ("inlines" instead of "inline") fails closed, not open', () => {
    const tmpl = buildTemplate({
      metadata: { oauth2Config: { byok: { surface: 'inlines' } } },
    });
    expect(resolveByokConfig(tmpl).surface).toBe('hidden');
  });

  it('drops malformed steps (missing title or body, wrong types) without throwing - a half-edited JSON in the catalog must still let the wizard render', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          byok: {
            surface: 'inline',
            steps: [
              { title: 'OK', body: 'Both present.' },
              { title: 'Missing body' },
              { body: 'Missing title' },
              null,
              'string-not-object',
              { title: 123, body: 456 },
              { title: '', body: 'empty title' },
            ],
          },
        },
      },
    });
    const out = resolveByokConfig(tmpl);
    expect(out.steps).toHaveLength(1);
    expect(out.steps[0].title).toBe('OK');
  });

  it('drops empty-string and non-string optional fields - empty consoleUrl or scopeNotes must not render as a broken link or zero-content paragraph', () => {
    const tmpl = buildTemplate({
      metadata: {
        oauth2Config: {
          byok: {
            surface: 'disclosure',
            consoleUrl: '',
            redirectUriHint: '',
            scopeNotes: '',
          },
        },
      },
    });
    const out = resolveByokConfig(tmpl);
    expect(out.consoleUrl).toBeNull();
    expect(out.redirectUriHint).toBeNull();
    expect(out.scopeNotes).toBeNull();
  });

  it('returns "hidden" if byok itself is the wrong type - guards against a mistaken byok: ["a","b"] in JSON', () => {
    const tmpl = buildTemplate({
      metadata: { oauth2Config: { byok: ['surface', 'inline'] } as any },
    });
    expect(resolveByokConfig(tmpl).surface).toBe('hidden');
  });
});
