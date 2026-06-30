import { describe, expect, it } from 'vitest';
import type { CredentialTemplate } from '@/lib/api/orchestrator';

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

describe('resolveConfigureModeForRequiredScopes', () => {
  it('opens advanced for an unconfigured tool when a required scope is not platform-grantable', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_READONLY], template)).toBe('advanced');
  });

  it('keeps standard when every required scope is platform-grantable', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_SEND], template)).toBe('standard');
  });

  it('keeps standard when the catalog does not expose a BYOK path', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      surface: 'hidden',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_READONLY], template)).toBe('standard');
  });

  it('opens advanced for undeclared scopes that the platform OAuth app cannot grant', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      surface: 'disclosure',
    });

    expect(resolveConfigureModeForRequiredScopes([GMAIL_SETTINGS], template)).toBe('advanced');
  });
});
