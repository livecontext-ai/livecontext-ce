import { describe, it, expect } from 'vitest';
import { isFederatedAccount, isOrganizationSamlAccount, securityTabSections } from './userUtils';

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

describe('isOrganizationSamlAccount', () => {
  it('is true only for the workspace SAML alias shape', () => {
    expect(isOrganizationSamlAccount({ sub: 'u1', identity_provider: 'org-0123456789abcdef0123456789abcdef-saml' })).toBe(true);
    expect(isOrganizationSamlAccount({ sub: 'u1', identity_provider: 'org-0123456789ABCDEF0123456789ABCDEF-saml' })).toBe(true);
  });

  it('is false for social logins, password accounts and near-miss aliases', () => {
    for (const idp of ['google', 'github', '', undefined, 'org-0123456789abcdef0123456789abcde-saml', 'org-0123456789abcdef0123456789abcdef-saml-x']) {
      expect(isOrganizationSamlAccount({ sub: 'u1', identity_provider: idp })).toBe(false);
    }
    expect(isOrganizationSamlAccount(undefined)).toBe(false);
  });
});

describe('securityTabSections', () => {
  const saml = { sub: 'u1', identity_provider: 'org-0123456789abcdef0123456789abcdef-saml' };

  it('gives a password account on the cloud both the password block and the two-factor card', () => {
    expect(securityTabSections({ sub: 'u1' }, true)).toEqual({ password: true, twoFactor: true, show: true });
  });

  it('keeps the Security tab for a Google/GitHub account, holding only the two-factor card', () => {
    for (const idp of ['google', 'github']) {
      expect(securityTabSections({ sub: 'u1', identity_provider: idp }, true))
        .toEqual({ password: false, twoFactor: true, show: true });
    }
  });

  it('hides the tab for a workspace SAML account: its identity provider carries the second factor', () => {
    expect(securityTabSections(saml, true)).toEqual({ password: false, twoFactor: false, show: false });
  });

  it('offers no two-factor card in CE, and no tab to a federated CE account', () => {
    expect(securityTabSections({ sub: 'u1' }, false)).toEqual({ password: true, twoFactor: false, show: true });
    expect(securityTabSections({ sub: 'u1', identity_provider: 'google' }, false))
      .toEqual({ password: false, twoFactor: false, show: false });
  });
});
