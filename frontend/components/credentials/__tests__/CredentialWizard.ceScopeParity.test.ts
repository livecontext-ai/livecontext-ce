import { describe, expect, it, vi } from 'vitest';
import type { CredentialTemplate } from '@/lib/api/orchestrator';

// CE/Cloud credential-scope parity: this file pins the CE (community edition)
// branch. CE has NO platform-shared OAuth app - the install-wide credential is
// the user's own OAuth client and the backend always puts the full catalog
// scope set (scopes ∪ byokOnlyScopes) on the authorize URL - so the frontend
// must treat byok-only scopes as grantable by a Standard (re)connect and never
// flag an integration as "BYOK required". Cloud behavior is pinned by the
// sibling suites that run with the default (cloud) edition.
vi.mock('@/lib/edition', () => ({
  EDITION: 'ce',
  IS_CE: true,
  IS_CLOUD: false,
}));

import {
  isFullyByokTemplate,
  resolveByokOnlyScopeList,
  resolvePlatformScopeList,
} from '../CredentialWizard';

const GMAIL_LABELS = 'https://www.googleapis.com/auth/gmail.labels';
const GMAIL_SEND = 'https://www.googleapis.com/auth/gmail.send';
const GMAIL_READONLY = 'https://www.googleapis.com/auth/gmail.readonly';
const GMAIL_MODIFY = 'https://www.googleapis.com/auth/gmail.modify';

function oauthTemplate({
  scopes,
  byokOnlyScopes = [],
}: {
  scopes: string[];
  byokOnlyScopes?: string[];
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
        },
      }),
    },
  } as CredentialTemplate;
}

describe('CredentialWizard CE scope parity (IS_CE=true)', () => {
  it('regression - CE treats byokOnlyScopes as platform-grantable: resolvePlatformScopeList returns the full union so the MissingScopesBanner never routes a CE user into the BYOK-only dead end', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_LABELS, GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY, GMAIL_MODIFY],
    });

    expect(resolvePlatformScopeList(template)).toEqual([
      GMAIL_LABELS,
      GMAIL_SEND,
      GMAIL_READONLY,
      GMAIL_MODIFY,
    ]);
  });

  it('CE union dedups a scope declared in both scopes and byokOnlyScopes', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_SEND, GMAIL_READONLY],
      byokOnlyScopes: [GMAIL_READONLY],
    });

    expect(resolvePlatformScopeList(template)).toEqual([GMAIL_SEND, GMAIL_READONLY]);
  });

  it('regression - CE never flags a template as fully-BYOK (zero platform scopes + byokOnlyScopes used to force the BYOK form even though a CE Standard connect requests those scopes)', () => {
    const template = oauthTemplate({
      scopes: [],
      byokOnlyScopes: [GMAIL_READONLY],
    });

    expect(isFullyByokTemplate(template)).toBe(false);
  });

  it('CE keeps resolveByokOnlyScopeList intact - the advanced-form scope prefill still sees the catalog byok list', () => {
    const template = oauthTemplate({
      scopes: [GMAIL_SEND],
      byokOnlyScopes: [GMAIL_READONLY, GMAIL_MODIFY],
    });

    expect(resolveByokOnlyScopeList(template)).toEqual([GMAIL_READONLY, GMAIL_MODIFY]);
  });

  it('resolvePlatformScopeList stays empty for a template without oauth2Config (CE branch does not invent scopes)', () => {
    expect(resolvePlatformScopeList(null)).toEqual([]);
    expect(
      resolvePlatformScopeList({
        id: 'x',
        credential_name: 'x',
        display_name: 'X',
        auth_type: 'oauth2',
        properties: [],
        metadata: null,
      } as unknown as CredentialTemplate),
    ).toEqual([]);
  });
});
