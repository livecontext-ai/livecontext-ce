import { describe, it, expect } from 'vitest';
import {
  matchUserCredentialsForTool,
  findBestUserCredential,
  hasUserCredentialForIntegration,
  hasExactIntegrationMatch,
} from '../credentialMatching';
import type { Credential } from '@/lib/api/orchestrator';

function makeCred(overrides: Partial<Credential>): Credential {
  return {
    id: 1,
    tenant_id: 't',
    name: 'cred',
    integration: 'gmail',
    type: 'OAuth2',
    environment: 'Production',
    status: 'active',
    credential_data: {},
    scopes: [],
    tags: [],
    is_default: false,
    created_at: '',
    updated_at: '',
    ...overrides,
  } as Credential;
}

describe('credentialMatching', () => {
  describe('matchUserCredentialsForTool', () => {
    it('matches on exact integration', () => {
      const creds = [makeCred({ id: 1, integration: 'gmail' })];
      const out = matchUserCredentialsForTool(creds, { credentialName: 'x' }, 'gmail');
      expect(out).toHaveLength(1);
    });

    it('does not match when integration merely contains tool credentialName', () => {
      const creds = [makeCred({ id: 2, integration: 'google_gmail' })];
      const out = matchUserCredentialsForTool(creds, { credentialName: 'gmail' }, undefined);
      expect(out).toHaveLength(0);
    });

    it('matches on exact user credential name', () => {
      const creds = [makeCred({ id: 3, integration: 'custom', name: 'gmail' })];
      const out = matchUserCredentialsForTool(creds, { credentialName: 'gmail' }, undefined);
      expect(out).toHaveLength(1);
    });

    it('does not match when user credential name merely contains tool credentialName', () => {
      const creds = [makeCred({ id: 6, integration: 'custom', name: 'my gmail prod' })];
      const out = matchUserCredentialsForTool(creds, { credentialName: 'gmail' }, undefined);
      expect(out).toHaveLength(0);
    });

    it('matches on display name', () => {
      const creds = [makeCred({ id: 4, integration: 'Gmail API', name: 'cred' })];
      const out = matchUserCredentialsForTool(creds, { displayName: 'Gmail API' }, undefined);
      expect(out).toHaveLength(1);
    });

    it('returns empty when nothing matches', () => {
      const creds = [makeCred({ id: 5, integration: 'slack', name: 'slack-prod' })];
      const out = matchUserCredentialsForTool(creds, { credentialName: 'gmail' }, 'gmail');
      expect(out).toHaveLength(0);
    });

    it('returns empty for empty inputs', () => {
      expect(matchUserCredentialsForTool(undefined, undefined, undefined)).toEqual([]);
      expect(matchUserCredentialsForTool([], { credentialName: 'x' }, 'y')).toEqual([]);
      expect(matchUserCredentialsForTool([makeCred({})], undefined, undefined)).toEqual([]);
    });
  });

  describe('findBestUserCredential', () => {
    it('prefers the default credential when multiple match', () => {
      const creds = [
        makeCred({ id: 10, integration: 'gmail', is_default: false, name: 'a' }),
        makeCred({ id: 11, integration: 'gmail', is_default: true, name: 'b' }),
        makeCred({ id: 12, integration: 'gmail', is_default: false, name: 'c' }),
      ];
      expect(findBestUserCredential(creds, 'gmail')?.id).toBe(11);
    });

    it('falls back to the first match when no default', () => {
      const creds = [
        makeCred({ id: 20, integration: 'gmail' }),
        makeCred({ id: 21, integration: 'gmail' }),
      ];
      expect(findBestUserCredential(creds, 'gmail')?.id).toBe(20);
    });

    it('returns null when nothing matches', () => {
      expect(findBestUserCredential([makeCred({ integration: 'x' })], 'gmail')).toBeNull();
    });
  });

  describe('hasUserCredentialForIntegration', () => {
    it('true when a match exists', () => {
      expect(
        hasUserCredentialForIntegration([makeCred({ integration: 'gmail' })], 'gmail')
      ).toBe(true);
    });

    it('false when no match', () => {
      expect(
        hasUserCredentialForIntegration([makeCred({ integration: 'slack' })], 'gmail')
      ).toBe(false);
    });

    it('false for empty inputs', () => {
      expect(hasUserCredentialForIntegration(undefined, 'gmail')).toBe(false);
      expect(hasUserCredentialForIntegration([], 'gmail')).toBe(false);
      expect(hasUserCredentialForIntegration([makeCred({})], undefined)).toBe(false);
    });
  });

  describe('hasExactIntegrationMatch (strict)', () => {
    it('true on exact integration', () => {
      expect(
        hasExactIntegrationMatch([makeCred({ integration: 'gmail' })], 'gmail')
      ).toBe(true);
    });

    it('is case-insensitive', () => {
      expect(
        hasExactIntegrationMatch([makeCred({ integration: 'Gmail' })], 'gmail')
      ).toBe(true);
    });

    it('does NOT substring-match (googlecloud vs google)', () => {
      expect(
        hasExactIntegrationMatch([makeCred({ integration: 'google' })], 'googlecloud')
      ).toBe(false);
      expect(
        hasExactIntegrationMatch([makeCred({ integration: 'googlecloud' })], 'google')
      ).toBe(false);
    });

    it('false for empty inputs', () => {
      expect(hasExactIntegrationMatch(undefined, 'gmail')).toBe(false);
      expect(hasExactIntegrationMatch([], 'gmail')).toBe(false);
      expect(hasExactIntegrationMatch([makeCred({})], undefined)).toBe(false);
    });
  });

  describe('matchUserCredentialsForTool - regression cases', () => {
    it('empty toolCred object with integration still filters', () => {
      const creds = [makeCred({ integration: 'slack' })];
      expect(matchUserCredentialsForTool(creds, {}, 'gmail')).toHaveLength(0);
    });

    it('unrelated integrations do not cross-match', () => {
      const creds = [makeCred({ integration: 'slack', name: 'slack-prod' })];
      expect(matchUserCredentialsForTool(creds, { credentialName: 'gmail' }, 'gmail'))
        .toHaveLength(0);
    });
  });
});
