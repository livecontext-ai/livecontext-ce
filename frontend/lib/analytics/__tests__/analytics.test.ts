// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Replace the CDN loader with an in-memory fake client (factory is hoisted).
vi.mock('../posthogLoader', () => {
  const fake = {
    init: vi.fn(),
    capture: vi.fn(),
    identify: vi.fn(),
    register: vi.fn(),
    reset: vi.fn(),
    opt_in_capturing: vi.fn(),
    opt_out_capturing: vi.fn(),
  };
  return { loadPosthog: () => fake, __fake: fake };
});

// The analytics module captures NEXT_PUBLIC_POSTHOG_KEY at eval time, so the
// key MUST be stubbed before its first import.
vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', 'phc_test');

const CONSENT_KEY = 'lc.cookieConsent';
function grantConsent() {
  localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'accepted', version: 1, ts: 1 }));
}

describe('analytics facade (key configured)', () => {
  let analytics: typeof import('../analytics');
  let fake: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    localStorage.clear();
    vi.resetModules(); // fresh module state per test; mock factory re-runs
    const loaderMod = (await import('../posthogLoader')) as unknown as { __fake: Record<string, ReturnType<typeof vi.fn>> };
    fake = loaderMod.__fake;
    Object.values(fake).forEach((fn) => fn.mockClear());
    analytics = await import('../analytics');
  });

  afterEach(() => localStorage.clear());

  it('reports configured when a key is present', () => {
    expect(analytics.isAnalyticsConfigured()).toBe(true);
  });

  it('does NOT initialize without cookie consent', () => {
    analytics.initAnalytics();
    expect(fake.init).not.toHaveBeenCalled();
  });

  it('initializes once consent is granted, and is idempotent', () => {
    grantConsent();
    analytics.initAnalytics();
    analytics.initAnalytics();
    expect(fake.init).toHaveBeenCalledTimes(1);
  });

  it('track() is a no-op before init / consent', () => {
    analytics.track('app_install_started', { publication_id: 'x' });
    expect(fake.capture).not.toHaveBeenCalled();
  });

  it('track() merges common props (surface, app_edition, organization_id)', () => {
    grantConsent();
    analytics.identifyUser('user-uuid', 'org-uuid');
    analytics.track('app_install_started', { publication_id: 'pub-1' });
    expect(fake.capture).toHaveBeenCalledWith(
      'app_install_started',
      expect.objectContaining({
        publication_id: 'pub-1',
        surface: 'frontend',
        organization_id: 'org-uuid',
      }),
    );
  });

  it('identifyUser() forwards the stable id and org, and lazily inits', () => {
    grantConsent();
    analytics.identifyUser('user-uuid', 'org-uuid');
    expect(fake.init).toHaveBeenCalledTimes(1);
    expect(fake.identify).toHaveBeenCalledWith('user-uuid', { organization_id: 'org-uuid' });
  });

  it('resetAnalytics() clears identity only when initialized', () => {
    analytics.resetAnalytics();
    expect(fake.reset).not.toHaveBeenCalled();

    grantConsent();
    analytics.initAnalytics();
    analytics.resetAnalytics();
    expect(fake.reset).toHaveBeenCalledTimes(1);
  });

  it('disableAnalytics() opts out only when initialized', () => {
    analytics.disableAnalytics();
    expect(fake.opt_out_capturing).not.toHaveBeenCalled();

    grantConsent();
    analytics.initAnalytics();
    analytics.disableAnalytics();
    expect(fake.opt_out_capturing).toHaveBeenCalledTimes(1);
  });

  it('re-opts-in on Accept → Reject → Accept without re-initializing (regression)', () => {
    grantConsent();
    analytics.initAnalytics();   // accept
    analytics.disableAnalytics(); // reject
    analytics.initAnalytics();   // accept again

    expect(fake.init).toHaveBeenCalledTimes(1);
    expect(fake.opt_out_capturing).toHaveBeenCalledTimes(1);
    expect(fake.opt_in_capturing).toHaveBeenCalledTimes(1);
  });

  it('never emits tenant_id in event properties (PII guard)', () => {
    grantConsent();
    analytics.identifyUser('user-uuid', 'org-uuid');
    analytics.track('app_install_started', { publication_id: 'pub-1' });

    const props = fake.capture.mock.calls[0][1] as Record<string, unknown>;
    expect(props).not.toHaveProperty('tenant_id');
    expect(props).not.toHaveProperty('tenantId');
  });
});

describe('analytics facade (no key configured)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', '');
  });
  afterEach(() => {
    localStorage.clear();
    vi.stubEnv('NEXT_PUBLIC_POSTHOG_KEY', 'phc_test');
  });

  it('is a permanent no-op: not configured, track never throws', async () => {
    const analytics = await import('../analytics');
    expect(analytics.isAnalyticsConfigured()).toBe(false);
    grantConsent();
    analytics.initAnalytics();
    expect(() => analytics.track('app_install_started', { publication_id: 'x' })).not.toThrow();
  });
});
