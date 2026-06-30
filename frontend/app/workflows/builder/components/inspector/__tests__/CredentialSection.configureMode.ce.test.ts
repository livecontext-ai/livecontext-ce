import { describe, expect, it, vi } from 'vitest';
import type { CredentialTemplate } from '@/lib/api/orchestrator';

// CE/Cloud credential-scope parity: CE has no platform-shared OAuth app, the
// backend requests the full catalog scope set (scopes ∪ byokOnlyScopes) on
// every Standard connect, so a byok-only required scope must NOT route the
// wizard to the advanced (BYOK) form. The cloud behavior is pinned by the
// sibling CredentialSection.configureMode.test.ts (default edition = cloud).
vi.mock('@/lib/edition', () => ({
  EDITION: 'ce',
  IS_CE: true,
  IS_CLOUD: false,
}));

import { resolveConfigureModeForRequiredScopes } from '../CredentialSection';

const GMAIL_LABELS = 'https://www.googleapis.com/auth/gmail.labels';
const GMAIL_SEND = 'https://www.googleapis.com/auth/gmail.send';
const GMAIL_READONLY = 'https://www.googleapis.com/auth/gmail.readonly';
const GMAIL_SETTINGS = 'https://www.googleapis.com/auth/gmail.settings.basic';

function oauthTemplate({
  scopes,
  byokOnlyScopes = [],
  surface = 'hidden',
}: {
  scopes: string[];
  byokOnlyScopes?: string[];
  surface?: 'hidden' | 'inline' | 'disclosure';
}): CredentialTemplate {
  return {
    id: 'gmail-oauth2',
    credential_name: 'gmail',
    display_name: 'Gmail',
    auth_type: 'oauth2',
    properties: [],
    metadata: {
      type: 'jsonb',
      value: JSON.stringify({
        oauth2Config: {
          scopes,
          byokOnlyScopes,
          byok: { surface },
        },
      }),
    },
  } as CredentialTemplate;
}

describe('resolveConfigureModeForRequiredScopes - CE (IS_CE=true)', () => {
  it('regression - a byok-only required scope keeps standard in CE (it used to force the advanced BYOK form, a pointless detour since the CE Standard connect requests byok-only scopes)', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_READONLY], template)).toBe('standard');
  });

  it('still opens advanced in CE for a scope declared in NEITHER list - the connect cannot request it, only the custom-OAuth form (manual scope input) can', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_SETTINGS], template)).toBe('advanced');
  });

  it('keeps standard in CE when every required scope is in the base platform list (unchanged path)', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_SEND], template)).toBe('standard');
  });
});
