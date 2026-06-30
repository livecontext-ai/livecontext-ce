import { describe, it, expect } from 'vitest';
import { isFederatedAccount } from './userUtils';

describe('isFederatedAccount', () => {
  it('is false for a local-password account (no identity_provider claim)', () => {
    // Direct Keycloak username/password and CE embedded auth carry no identity_provider.
    expect(isFederatedAccount({ sub: 'u1', email: 'a@b.c' })).toBe(false);
    expect(isFederatedAccount({ sub: 'u1', identity_provider: undefined })).toBe(false);
    expect(isFederatedAccount({ sub: 'u1', identity_provider: '' })).toBe(false);
    expect(isFederatedAccount({ sub: 'u1', identity_provider: '   ' })).toBe(false);
  });

  it('is false for an undefined user', () => {
    expect(isFederatedAccount(undefined)).toBe(false);
  });

  it('is true for social-login accounts', () => {
    for (const idp of ['google', 'github', 'microsoft', 'facebook', 'linkedin']) {
      expect(isFederatedAccount({ sub: 'u1', identity_provider: idp })).toBe(true);
    }
  });

  it('is true for an org SAML/SSO account (org-<id>-saml alias)', () => {
    expect(
      isFederatedAccount({ sub: 'u1', identity_provider: 'org-0123456789abcdef0123456789abcdef-saml' }),
    ).toBe(true);
  });
});
