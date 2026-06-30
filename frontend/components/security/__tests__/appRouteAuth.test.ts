import { describe, expect, it } from 'vitest';
import {
  buildLoginRedirectPath,
  isAppRoute,
  isProtectedAppRoute,
  isPublicAppRoute,
  stripAppLocale,
} from '../appRouteAuth';

describe('app route auth helpers', () => {
  it('normalizes every configured locale prefix before route checks', () => {
    expect(stripAppLocale('/fr/app/chat')).toBe('/app/chat');
    expect(stripAppLocale('/de/app/settings/pricing')).toBe('/app/settings/pricing');
    expect(stripAppLocale('/pt/app/settings/information')).toBe('/app/settings/information');
    expect(stripAppLocale('/zh/app/agent')).toBe('/app/agent');
  });

  it('marks /app pages protected except pricing, information, recovery, and public gateway paths', () => {
    expect(isAppRoute('/en/app/chat')).toBe(true);
    expect(isProtectedAppRoute('/en/app/chat')).toBe(true);
    expect(isProtectedAppRoute('/app/settings/overview')).toBe(true);

    expect(isPublicAppRoute('/fr/app/settings/pricing')).toBe(true);
    expect(isProtectedAppRoute('/fr/app/settings/pricing')).toBe(false);
    expect(isPublicAppRoute('/app/settings/information')).toBe(true);
    expect(isProtectedAppRoute('/app/settings/information')).toBe(false);
    expect(isPublicAppRoute('/en/app/settings/cloud-account/recover/public-token')).toBe(true);
    expect(isProtectedAppRoute('/en/app/settings/cloud-account/recover/public-token')).toBe(false);
    expect(isPublicAppRoute('/fr/app/settings/cloud-link/recover/public-token')).toBe(true);
    expect(isProtectedAppRoute('/fr/app/settings/cloud-link/recover/public-token')).toBe(false);
    expect(isProtectedAppRoute('/app/public/render/abc')).toBe(false);
  });

  it('builds a localized login redirect that preserves the requested app URL', () => {
    expect(buildLoginRedirectPath('/fr/app/chat', 'draft=1')).toBe(
      '/fr/login?returnTo=%2Ffr%2Fapp%2Fchat%3Fdraft%3D1',
    );
    expect(buildLoginRedirectPath('/app/workflow/new')).toBe(
      '/en/login?returnTo=%2Fapp%2Fworkflow%2Fnew',
    );
  });
});
