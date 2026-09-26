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
  // Mirror the real loader contract: the LIVE client is whatever sits on
  // window.posthog (null when nothing does), so tests can simulate array.js
  // swapping the stub for the real instance.
  const currentPosthog = () => {
    const ph = (window as unknown as { posthog?: unknown }).posthog as { capture?: unknown } | undefined;
    return ph && typeof ph.capture === 'function' ? ph : null;
  };
  return { loadPosthog: () => fake, currentPosthog, __fake: fake };
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

  afterEach(() => {
    localStorage.clear();
    delete (window as unknown as { posthog?: unknown }).posthog;
  });

  describe('regression: array.js replaces window.posthog after init (orphaned stub)', () => {
    // Before the fix the facade cached the queue stub handed to init(); once
    // array.js replayed the queue and swapped window.posthog for the real
    // SDK instance, every later track()/identify()/register() was pushed onto
    // the dead stub and never reached PostHog (0 product events in prod for
    // two months while SDK-captured $pageview kept flowing).
    function installLoadedSdk() {
      const loaded = {
        init: vi.fn(),
        capture: vi.fn(),
        identify: vi.fn(),
        register: vi.fn(),
        reset: vi.fn(),
        opt_in_capturing: vi.fn(),
        opt_out_capturing: vi.fn(),
        __loaded: true,
      };
      (window as unknown as { posthog?: unknown }).posthog = loaded;
      return loaded;
    }

    it('track() after the swap reaches the LIVE instance, not the stale stub', () => {
      grantConsent();
      analytics.initAnalytics();
      expect(fake.init).toHaveBeenCalledTimes(1);

      const loaded = installLoadedSdk();
      analytics.track('app_install_started', { publication_id: 'pub-1' });

      expect(loaded.capture).toHaveBeenCalledWith(
        'app_install_started',
        expect.objectContaining({ publication_id: 'pub-1' }),
      );
      expect(fake.capture).not.toHaveBeenCalled();
    });

    it('track() reports failure instead of throwing when the SDK does', () => {
      // Measuring an action must not be able to break it. Several call sites
      // run after the work has already succeeded (the onboarding completion
      // captures its event once the server has accepted the profile), so a
      // throw here would surface as a failure of something that worked.
      grantConsent();
      analytics.initAnalytics();
      const loaded = installLoadedSdk();
      loaded.capture.mockImplementation(() => {
        throw new Error('capture blew up');
      });

      let handedOver: boolean | undefined;
      expect(() => {
        handedOver = analytics.track('app_install_started', { publication_id: 'pub-1' });
      }).not.toThrow();
      // False, not true: a retrying caller must not believe the signal landed.
      expect(handedOver).toBe(false);
    });

    it('identify / org register / reset / opt-out after the swap all target the LIVE instance', () => {
      grantConsent();
      analytics.initAnalytics();
      // init() legitimately registers super-props on the stub (queued, replayed
      // by array.js); only what happens AFTER the swap is under test here.
      Object.values(fake).forEach((fn) => fn.mockClear());
      const loaded = installLoadedSdk();

      analytics.identifyUser('user-1', 'org-1');
      analytics.setAnalyticsOrganization('org-2');
      analytics.disableAnalytics();
      analytics.initAnalytics(); // re-opt-in path
      analytics.resetAnalytics();

      expect(loaded.identify).toHaveBeenCalledWith('user-1', { organization_id: 'org-1' }, {});
      expect(loaded.register).toHaveBeenCalledWith({ organization_id: 'org-2' });
      expect(loaded.opt_out_capturing).toHaveBeenCalledTimes(1);
      expect(loaded.opt_in_capturing).toHaveBeenCalledTimes(1);
      expect(loaded.reset).toHaveBeenCalledTimes(1);
      for (const fn of [fake.identify, fake.register, fake.opt_out_capturing, fake.opt_in_capturing, fake.reset]) {
        expect(fn).not.toHaveBeenCalled();
      }
    });

    it('does not re-run init() on the swapped instance (init stays idempotent)', () => {
      grantConsent();
      analytics.initAnalytics();
      const loaded = installLoadedSdk();
      analytics.initAnalytics();
      expect(loaded.init).not.toHaveBeenCalled();
      expect(fake.init).toHaveBeenCalledTimes(1);
    });
  });

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

  it('track() keeps the reserved common props even when a caller sends the same keys', () => {
    // Regression: trophy_viewed and the studio events used to send their own `surface`
    // ('grid', 'modal', ...), which silently replaced the frontend/backend split.
    grantConsent();
    analytics.identifyUser('user-uuid', 'org-uuid');
    analytics.track('app_install_started', {
      publication_id: 'pub-1',
      surface: 'modal',
      app_edition: 'spoofed',
      organization_id: 'other-org',
    });
    const props = fake.capture.mock.calls.at(-1)?.[1] as Record<string, unknown>;
    expect(props.surface).toBe('frontend');
    expect(props.app_edition).not.toBe('spoofed');
    expect(props.organization_id).toBe('org-uuid');
    expect(props.publication_id).toBe('pub-1');
  });

  it('identifyUser() forwards the stable id and org, and lazily inits', () => {
    grantConsent();
    analytics.identifyUser('user-uuid', 'org-uuid');
    expect(fake.init).toHaveBeenCalledTimes(1);
    // Third argument = $set_once landing intent (empty when the visitor never
    // touched a landing intent surface).
    expect(fake.identify).toHaveBeenCalledWith('user-uuid', { organization_id: 'org-uuid' }, {});
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

  it('setLandingIntent() registers first/latest super-properties and identify folds the FIRST into $set_once', () => {
    grantConsent();
    analytics.initAnalytics();
    const store: Record<string, unknown> = {};
    const loaded = {
      init: vi.fn(),
      capture: vi.fn(),
      identify: vi.fn(),
      register: vi.fn((p: Record<string, unknown>) => Object.assign(store, p)),
      register_once: vi.fn((p: Record<string, unknown>) => {
        for (const [k, v] of Object.entries(p)) if (!(k in store)) store[k] = v;
      }),
      get_property: vi.fn((k: string) => store[k]),
      reset: vi.fn(),
      opt_in_capturing: vi.fn(),
      opt_out_capturing: vi.fn(),
      __loaded: true,
    };
    (window as unknown as { posthog?: unknown }).posthog = loaded;

    analytics.setLandingIntent('landing_persona', 'ops');
    analytics.setLandingIntent('landing_persona', 'sales');
    analytics.identifyUser('user-9', null);

    expect(store.landing_persona_first).toBe('ops');
    expect(store.landing_persona).toBe('sales');
    expect(loaded.identify).toHaveBeenCalledWith('user-9', { organization_id: null }, { landing_persona: 'ops' });
  });

  it('resetAnalytics() forgets the landing intent so the next account on this browser is not stamped with it (regression)', () => {
    grantConsent();
    analytics.initAnalytics();
    const loaded = {
      init: vi.fn(), capture: vi.fn(), identify: vi.fn(), register: vi.fn(), register_once: vi.fn(),
      reset: vi.fn(), opt_in_capturing: vi.fn(), opt_out_capturing: vi.fn(), __loaded: true,
    };
    (window as unknown as { posthog?: unknown }).posthog = loaded;

    analytics.setLandingIntent('landing_cta', 'hero_start_free');
    analytics.identifyUser('user-A', null);
    analytics.resetAnalytics();
    analytics.identifyUser('user-B', null);

    expect(loaded.identify).toHaveBeenNthCalledWith(1, 'user-A', { organization_id: null }, { landing_cta: 'hero_start_free' });
    expect(loaded.identify).toHaveBeenNthCalledWith(2, 'user-B', { organization_id: null }, {});
    expect(localStorage.getItem('lc.landingIntent')).toBeNull();
  });

  it('setAppView() registers a bounded view and unregisters it on null (never the pathname)', () => {
    grantConsent();
    analytics.initAnalytics();
    const loaded = {
      init: vi.fn(), capture: vi.fn(), identify: vi.fn(), register: vi.fn(), unregister: vi.fn(),
      reset: vi.fn(), opt_in_capturing: vi.fn(), opt_out_capturing: vi.fn(), __loaded: true,
    };
    (window as unknown as { posthog?: unknown }).posthog = loaded;

    analytics.setAppView('marketplace', true);
    analytics.setAppView(null, false);

    expect(loaded.register).toHaveBeenCalledWith({ app_view: 'marketplace', is_detail_page: true });
    expect(loaded.unregister).toHaveBeenCalledWith('app_view');
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
