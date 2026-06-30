import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { useAppStore } from '../app-store';

// Snapshot the initial state so we can restore it
const initialState = {
  auth: { isAuthenticated: false, isLoading: true },
  ui: {
    currentRoute: '/',
    activeProviders: new Set<string>(),
    loadingStates: {},
    errors: {},
  },
  cache: {
    lastFetchTimes: {},
    staleTimes: {},
  },
};

beforeEach(() => {
  // Reset store to initial state before each test
  useAppStore.setState({
    auth: { ...initialState.auth },
    ui: {
      ...initialState.ui,
      activeProviders: new Set<string>(),
      loadingStates: {},
      errors: {},
    },
    cache: {
      lastFetchTimes: {},
      staleTimes: {},
    },
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useAppStore', () => {
  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------
  describe('initial state', () => {
    it('should have auth.isAuthenticated as false', () => {
      expect(useAppStore.getState().auth.isAuthenticated).toBe(false);
    });

    it('should have auth.isLoading as true', () => {
      expect(useAppStore.getState().auth.isLoading).toBe(true);
    });

    it('should have auth.userId as undefined', () => {
      expect(useAppStore.getState().auth.userId).toBeUndefined();
    });

    it('should have ui.currentRoute as "/"', () => {
      expect(useAppStore.getState().ui.currentRoute).toBe('/');
    });

    it('should have ui.activeProviders as an empty Set', () => {
      expect(useAppStore.getState().ui.activeProviders.size).toBe(0);
    });

    it('should have ui.loadingStates as an empty object', () => {
      expect(useAppStore.getState().ui.loadingStates).toEqual({});
    });

    it('should have ui.errors as an empty object', () => {
      expect(useAppStore.getState().ui.errors).toEqual({});
    });

    it('should have cache.lastFetchTimes as an empty object', () => {
      expect(useAppStore.getState().cache.lastFetchTimes).toEqual({});
    });

    it('should have cache.staleTimes as an empty object', () => {
      expect(useAppStore.getState().cache.staleTimes).toEqual({});
    });
  });

  // ---------------------------------------------------------------------------
  // Auth actions
  // ---------------------------------------------------------------------------
  describe('setAuth', () => {
    it('should update isAuthenticated', () => {
      useAppStore.getState().setAuth({ isAuthenticated: true });
      expect(useAppStore.getState().auth.isAuthenticated).toBe(true);
    });

    it('should update isLoading', () => {
      useAppStore.getState().setAuth({ isLoading: false });
      expect(useAppStore.getState().auth.isLoading).toBe(false);
    });

    it('should set userId', () => {
      useAppStore.getState().setAuth({ userId: 'user-123' });
      expect(useAppStore.getState().auth.userId).toBe('user-123');
    });

    it('should support partial updates preserving existing fields', () => {
      useAppStore.getState().setAuth({ isAuthenticated: true, userId: 'user-1' });
      useAppStore.getState().setAuth({ isLoading: false });

      const auth = useAppStore.getState().auth;
      expect(auth.isAuthenticated).toBe(true);
      expect(auth.isLoading).toBe(false);
      expect(auth.userId).toBe('user-1');
    });

    it('should handle transition from loading to authenticated', () => {
      useAppStore.getState().setAuth({
        isAuthenticated: true,
        isLoading: false,
        userId: 'user-abc',
      });

      const auth = useAppStore.getState().auth;
      expect(auth.isAuthenticated).toBe(true);
      expect(auth.isLoading).toBe(false);
      expect(auth.userId).toBe('user-abc');
    });

    it('should handle transition from authenticated to unauthenticated', () => {
      useAppStore.getState().setAuth({ isAuthenticated: true, userId: 'user-abc' });
      useAppStore.getState().setAuth({ isAuthenticated: false, userId: undefined });

      const auth = useAppStore.getState().auth;
      expect(auth.isAuthenticated).toBe(false);
      expect(auth.userId).toBeUndefined();
    });
  });

  // ---------------------------------------------------------------------------
  // UI actions - currentRoute and provider cleanup
  // ---------------------------------------------------------------------------
  describe('setCurrentRoute', () => {
    it('should update the current route', () => {
      useAppStore.getState().setCurrentRoute('/dashboard');
      expect(useAppStore.getState().ui.currentRoute).toBe('/dashboard');
    });

    it('should clean up providers not needed for the new route', () => {
      // Add providers typical for dashboard
      useAppStore.getState().addActiveProvider('quotas');
      useAppStore.getState().addActiveProvider('plans');
      useAppStore.getState().addActiveProvider('user-status');
      // Add a provider from another route
      useAppStore.getState().addActiveProvider('categories');

      // Navigate to /dashboard
      useAppStore.getState().setCurrentRoute('/dashboard');

      const providers = useAppStore.getState().ui.activeProviders;
      // dashboard needs: quotas, plans, user-status
      expect(providers.has('quotas')).toBe(true);
      expect(providers.has('plans')).toBe(true);
      expect(providers.has('user-status')).toBe(true);
      // categories is only for /catalog or /developers, should be removed
      expect(providers.has('categories')).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------
  // Route-based provider cleanup (testing getProvidersForRoute indirectly)
  // ---------------------------------------------------------------------------
  describe('getProvidersForRoute (tested indirectly via setCurrentRoute)', () => {
    it('should keep quotas, plans, user-status for /dashboard routes', () => {
      useAppStore.getState().addActiveProvider('quotas');
      useAppStore.getState().addActiveProvider('plans');
      useAppStore.getState().addActiveProvider('user-status');
      useAppStore.getState().addActiveProvider('mcp');

      useAppStore.getState().setCurrentRoute('/dashboard/overview');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('quotas')).toBe(true);
      expect(providers.has('plans')).toBe(true);
      expect(providers.has('user-status')).toBe(true);
      expect(providers.has('mcp')).toBe(false);
    });

    it('should keep categories for /catalog routes', () => {
      useAppStore.getState().addActiveProvider('categories');
      useAppStore.getState().addActiveProvider('quotas');

      useAppStore.getState().setCurrentRoute('/catalog/browse');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('categories')).toBe(true);
      expect(providers.has('quotas')).toBe(false);
    });

    it('should keep categories for /developers routes', () => {
      useAppStore.getState().addActiveProvider('categories');
      useAppStore.getState().addActiveProvider('quotas');

      useAppStore.getState().setCurrentRoute('/developers');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('categories')).toBe(true);
      expect(providers.has('quotas')).toBe(false);
    });

    it('should keep subscriptions and billing for /billing routes', () => {
      useAppStore.getState().addActiveProvider('subscriptions');
      useAppStore.getState().addActiveProvider('billing');
      useAppStore.getState().addActiveProvider('mcp');

      useAppStore.getState().setCurrentRoute('/billing');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('subscriptions')).toBe(true);
      expect(providers.has('billing')).toBe(true);
      expect(providers.has('mcp')).toBe(false);
    });

    it('should keep subscriptions and billing for routes containing "subscription"', () => {
      useAppStore.getState().addActiveProvider('subscriptions');
      useAppStore.getState().addActiveProvider('billing');

      useAppStore.getState().setCurrentRoute('/my-subscription/details');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('subscriptions')).toBe(true);
      expect(providers.has('billing')).toBe(true);
    });

    it('should keep mcp for /chat routes', () => {
      useAppStore.getState().addActiveProvider('mcp');
      useAppStore.getState().addActiveProvider('quotas');

      useAppStore.getState().setCurrentRoute('/chat');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('mcp')).toBe(true);
      expect(providers.has('quotas')).toBe(false);
    });

    it('should remove all providers for a route with no special providers', () => {
      useAppStore.getState().addActiveProvider('quotas');
      useAppStore.getState().addActiveProvider('categories');
      useAppStore.getState().addActiveProvider('mcp');

      useAppStore.getState().setCurrentRoute('/settings');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.size).toBe(0);
    });

    it('should handle root route (/)', () => {
      useAppStore.getState().addActiveProvider('quotas');
      useAppStore.getState().setCurrentRoute('/');

      const providers = useAppStore.getState().ui.activeProviders;
      expect(providers.has('quotas')).toBe(false);
    });
  });

  // ---------------------------------------------------------------------------
  // UI actions - providers
  // ---------------------------------------------------------------------------
  describe('addActiveProvider', () => {
    it('should add a provider to the active set', () => {
      useAppStore.getState().addActiveProvider('quotas');
      expect(useAppStore.getState().ui.activeProviders.has('quotas')).toBe(true);
    });

    it('should handle adding the same provider twice (Set deduplication)', () => {
      useAppStore.getState().addActiveProvider('mcp');
      useAppStore.getState().addActiveProvider('mcp');
      expect(useAppStore.getState().ui.activeProviders.size).toBe(1);
    });
  });

  describe('removeActiveProvider', () => {
    it('should remove a provider from the active set', () => {
      useAppStore.getState().addActiveProvider('quotas');
      useAppStore.getState().removeActiveProvider('quotas');
      expect(useAppStore.getState().ui.activeProviders.has('quotas')).toBe(false);
    });

    it('should be safe to remove a provider that does not exist', () => {
      useAppStore.getState().removeActiveProvider('non-existent');
      expect(useAppStore.getState().ui.activeProviders.size).toBe(0);
    });
  });

  // ---------------------------------------------------------------------------
  // UI actions - loading states
  // ---------------------------------------------------------------------------
  describe('setLoading', () => {
    it('should set a loading state to true', () => {
      useAppStore.getState().setLoading('workflows', true);
      expect(useAppStore.getState().ui.loadingStates['workflows']).toBe(true);
    });

    it('should remove a loading state when set to false', () => {
      useAppStore.getState().setLoading('workflows', true);
      useAppStore.getState().setLoading('workflows', false);
      expect(useAppStore.getState().ui.loadingStates['workflows']).toBeUndefined();
    });

    it('should track multiple loading states independently', () => {
      useAppStore.getState().setLoading('a', true);
      useAppStore.getState().setLoading('b', true);
      useAppStore.getState().setLoading('a', false);

      expect(useAppStore.getState().ui.loadingStates['a']).toBeUndefined();
      expect(useAppStore.getState().ui.loadingStates['b']).toBe(true);
    });

    it('should be safe to set loading false on a key that was never set', () => {
      useAppStore.getState().setLoading('unknown', false);
      expect(useAppStore.getState().ui.loadingStates['unknown']).toBeUndefined();
    });
  });

  // ---------------------------------------------------------------------------
  // UI actions - errors
  // ---------------------------------------------------------------------------
  describe('setError', () => {
    it('should set an error for a key', () => {
      useAppStore.getState().setError('fetch-users', 'Network error');
      expect(useAppStore.getState().ui.errors['fetch-users']).toBe('Network error');
    });

    it('should remove an error when set to null', () => {
      useAppStore.getState().setError('fetch-users', 'Network error');
      useAppStore.getState().setError('fetch-users', null);
      expect(useAppStore.getState().ui.errors['fetch-users']).toBeUndefined();
    });

    it('should track multiple errors independently', () => {
      useAppStore.getState().setError('a', 'Error A');
      useAppStore.getState().setError('b', 'Error B');
      useAppStore.getState().setError('a', null);

      expect(useAppStore.getState().ui.errors['a']).toBeUndefined();
      expect(useAppStore.getState().ui.errors['b']).toBe('Error B');
    });

    it('should overwrite existing error message', () => {
      useAppStore.getState().setError('key', 'First error');
      useAppStore.getState().setError('key', 'Updated error');
      expect(useAppStore.getState().ui.errors['key']).toBe('Updated error');
    });
  });

  // ---------------------------------------------------------------------------
  // Cache actions
  // ---------------------------------------------------------------------------
  describe('updateLastFetch', () => {
    it('should store a fetch time for a key', () => {
      const now = 1700000000000;
      useAppStore.getState().updateLastFetch('workflows', now);
      expect(useAppStore.getState().cache.lastFetchTimes['workflows']).toBe(now);
    });

    it('should use Date.now() when no time is provided', () => {
      const before = Date.now();
      useAppStore.getState().updateLastFetch('key');
      const after = Date.now();

      const stored = useAppStore.getState().cache.lastFetchTimes['key'];
      expect(stored).toBeGreaterThanOrEqual(before);
      expect(stored).toBeLessThanOrEqual(after);
    });

    it('should overwrite previous fetch time', () => {
      useAppStore.getState().updateLastFetch('key', 1000);
      useAppStore.getState().updateLastFetch('key', 2000);
      expect(useAppStore.getState().cache.lastFetchTimes['key']).toBe(2000);
    });
  });

  describe('isStale', () => {
    it('should return true when no fetch time exists for the key', () => {
      expect(useAppStore.getState().isStale('never-fetched')).toBe(true);
    });

    it('should return false when data was fetched recently', () => {
      useAppStore.getState().updateLastFetch('key', Date.now());
      expect(useAppStore.getState().isStale('key')).toBe(false);
    });

    it('should return true when data is older than default stale time (5 min)', () => {
      const sixMinutesAgo = Date.now() - 6 * 60 * 1000;
      useAppStore.getState().updateLastFetch('key', sixMinutesAgo);
      expect(useAppStore.getState().isStale('key')).toBe(true);
    });

    it('should return false when data is within default stale time (5 min)', () => {
      const fourMinutesAgo = Date.now() - 4 * 60 * 1000;
      useAppStore.getState().updateLastFetch('key', fourMinutesAgo);
      expect(useAppStore.getState().isStale('key')).toBe(false);
    });

    it('should use custom stale time from staleTimes when present', () => {
      // Set a custom stale time of 1 second
      useAppStore.setState((state) => {
        state.cache.staleTimes['short-lived'] = 1000;
      });

      // Fetch 2 seconds ago
      const twoSecondsAgo = Date.now() - 2000;
      useAppStore.getState().updateLastFetch('short-lived', twoSecondsAgo);
      expect(useAppStore.getState().isStale('short-lived')).toBe(true);
    });

    it('should return false with custom stale time when data is fresh', () => {
      // Set a custom stale time of 10 minutes
      useAppStore.setState((state) => {
        state.cache.staleTimes['long-lived'] = 10 * 60 * 1000;
      });

      const sixMinutesAgo = Date.now() - 6 * 60 * 1000;
      useAppStore.getState().updateLastFetch('long-lived', sixMinutesAgo);
      // 6 min < 10 min => not stale
      expect(useAppStore.getState().isStale('long-lived')).toBe(false);
    });

    it('should return true at exact boundary of stale time', () => {
      const exactlyFiveMinAgo = Date.now() - 5 * 60 * 1000 - 1;
      useAppStore.getState().updateLastFetch('key', exactlyFiveMinAgo);
      expect(useAppStore.getState().isStale('key')).toBe(true);
    });
  });
});
