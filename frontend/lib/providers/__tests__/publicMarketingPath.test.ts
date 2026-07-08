import { describe, expect, it } from 'vitest';
import { isPublicMarketingPath } from '../publicMarketingPath';

// Regression pin for the landing-SEO fix: paths returning true here are NEVER
// replaced by the blocking auth spinner / SessionGate in smart-providers, so
// their real content reaches the server-rendered HTML (crawlers that do not
// execute JavaScript see the page). Paths returning false keep the blocking
// auth UI exactly as before.
describe('isPublicMarketingPath', () => {
  it('covers the landing page, bare and under every locale prefix', () => {
    expect(isPublicMarketingPath('/')).toBe(true);
    for (const locale of ['en', 'fr', 'es', 'de', 'pt', 'zh']) {
      expect(isPublicMarketingPath(`/${locale}`)).toBe(true);
    }
  });

  it('covers the public marketing and docs surfaces', () => {
    for (const path of [
      '/compare',
      '/compare/n8n-alternative',
      '/about',
      '/contact',
      '/legal/privacy',
      '/changelog',
      '/docs',
      '/docs/agents',
      '/blog',
      '/blog/the-niche-data-advantage',
    ]) {
      expect(isPublicMarketingPath(path), path).toBe(true);
    }
  });

  it('keeps the blocking auth UI on app, auth and share surfaces', () => {
    for (const path of [
      '/app/chat',
      '/en/app/chat',
      '/fr/app/settings/pricing',
      '/en/onboarding',
      '/en/ce-setup',
      '/en/login',
      '/workflows/builder',
      '/billing/success',
      '/f/token123',
      '/s/token123',
      '/w/embed/token123',
      null,
    ]) {
      expect(isPublicMarketingPath(path), String(path)).toBe(false);
    }
  });

  it('does not treat lookalike prefixes as public', () => {
    expect(isPublicMarketingPath('/aboutus')).toBe(false);
    expect(isPublicMarketingPath('/comparetool')).toBe(false);
    expect(isPublicMarketingPath('/blogger')).toBe(false);
  });
});
