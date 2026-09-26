import { describe, expect, it } from 'vitest';
import { stripOidcCallbackParams } from '../oidcCallbackUrl';

/**
 * The sign-in callback used to wipe the whole query string, which also dropped what the
 * redirect was made to keep: the settings page sent to Keycloak with `?tab=security`
 * (enroll / remove an authenticator app) came back on its default tab.
 */
describe('stripOidcCallbackParams', () => {
  it('keeps the caller query parameters and drops the authorization response', () => {
    expect(
      stripOidcCallbackParams('/app/settings/overview', '?tab=security&state=s1&session_state=x&iss=https%3A%2F%2Fkc&code=c1'),
    ).toBe('/app/settings/overview?tab=security');
  });

  it('drops the application-initiated action status Keycloak appends', () => {
    expect(stripOidcCallbackParams('/app/settings/overview', '?tab=security&kc_action_status=success&code=c1&state=s1'))
      .toBe('/app/settings/overview?tab=security');
  });

  it('drops an error response too', () => {
    expect(stripOidcCallbackParams('/app/', '?error=access_denied&error_description=nope&state=s1')).toBe('/app/');
  });

  it('returns the bare path when only callback parameters were present (the default sign-in)', () => {
    expect(stripOidcCallbackParams('/app/', '?code=c1&state=s1&session_state=x')).toBe('/app/');
  });
});
