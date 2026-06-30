/**
 * Store global minimal avec Zustand
 * Contient seulement l'etat UI et de navigation, pas de donnees metier
 */

import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { devtools } from 'zustand/middleware';
import { enableMapSet } from 'immer';

// Activer le support MapSet pour Immer
enableMapSet();

interface AuthState {
  isAuthenticated: boolean;
  isLoading: boolean;
  userId?: string;
}

interface UIState {
  currentRoute: string;
  activeProviders: Set<string>;
  loadingStates: Record<string, boolean>;
  errors: Record<string, string | null>;
}

interface CacheState {
  lastFetchTimes: Record<string, number>;
  staleTimes: Record<string, number>;
}

interface AppState {
  auth: AuthState;
  ui: UIState;
  cache: CacheState;
}

interface AppActions {
  // Auth actions
  setAuth: (auth: Partial<AuthState>) => void;

  // UI actions
  setCurrentRoute: (route: string) => void;
  addActiveProvider: (provider: string) => void;
  removeActiveProvider: (provider: string) => void;
  setLoading: (key: string, loading: boolean) => void;
  setError: (key: string, error: string | null) => void;
  
  // Cache actions
  updateLastFetch: (key: string, time?: number) => void;
  isStale: (key: string) => boolean;
}

export const useAppStore = create<AppState & AppActions>()(
  devtools(
    immer((set, get) => ({
      // Initial state
      auth: {
        isAuthenticated: false,
        isLoading: true,
      },
      ui: {
        currentRoute: '/',
        activeProviders: new Set(),
        loadingStates: {},
        errors: {},
      },
      cache: {
        lastFetchTimes: {},
        staleTimes: {},
      },

      // Auth actions
      setAuth: (authUpdate) =>
        set((state) => {
          Object.assign(state.auth, authUpdate);
        }),

      // UI actions
      setCurrentRoute: (route) =>
        set((state) => {
          state.ui.currentRoute = route;
          
          // Auto-cleanup providers non necessaires
          const neededProviders = getProvidersForRoute(route);
          const currentProviders = Array.from(state.ui.activeProviders);
          
          currentProviders.forEach((provider) => {
            if (!neededProviders.includes(provider)) {
              state.ui.activeProviders.delete(provider);
            }
          });
        }),

      addActiveProvider: (provider) =>
        set((state) => {
          state.ui.activeProviders.add(provider);
        }),

      removeActiveProvider: (provider) =>
        set((state) => {
          state.ui.activeProviders.delete(provider);
        }),

      setLoading: (key, loading) =>
        set((state) => {
          if (loading) {
            state.ui.loadingStates[key] = true;
          } else {
            delete state.ui.loadingStates[key];
          }
        }),

      setError: (key, error) =>
        set((state) => {
          if (error) {
            state.ui.errors[key] = error;
          } else {
            delete state.ui.errors[key];
          }
        }),

      // Cache actions
      updateLastFetch: (key, time = Date.now()) =>
        set((state) => {
          state.cache.lastFetchTimes[key] = time;
        }),

      isStale: (key) => {
        const state = get();
        const lastFetch = state.cache.lastFetchTimes[key];
        const staleTime = state.cache.staleTimes[key] || 5 * 60 * 1000; // 5 min par defaut
        
        if (!lastFetch) return true;
        return Date.now() - lastFetch > staleTime;
      },
    })),
    {
      name: 'app-store',
    }
  )
);

/**
 * Determine quels providers sont necessaires pour une route donnee
 */
function getProvidersForRoute(route: string): string[] {
  const providers: string[] = [];
  
  if (route.startsWith('/dashboard')) {
    providers.push('quotas', 'plans', 'user-status');
  }
  
  if (route.startsWith('/catalog') || route.startsWith('/developers')) {
    providers.push('categories');
  }
  
  if (route.startsWith('/billing') || route.includes('subscription')) {
    providers.push('subscriptions', 'billing');
  }
  
  if (route.startsWith('/chat')) {
    providers.push('mcp');
  }
  
  return providers;
}

// Actions isolees - selecteurs individuels pour eviter la recreation d'objets
const useSetAuth = () => useAppStore((state) => state.setAuth);
export const useSetCurrentRoute = () => useAppStore((state) => state.setCurrentRoute);
const useAddActiveProvider = () => useAppStore((state) => state.addActiveProvider);
const useRemoveActiveProvider = () => useAppStore((state) => state.removeActiveProvider);
const useSetLoading = () => useAppStore((state) => state.setLoading);
const useSetError = () => useAppStore((state) => state.setError);
const useUpdateLastFetch = () => useAppStore((state) => state.updateLastFetch);
const useIsStale = () => useAppStore((state) => state.isStale);

// Actions groupees avec useMemo pour eviter la boucle infinie
export const useAppActions = () => {
  const setAuth = useSetAuth();
  const setCurrentRoute = useSetCurrentRoute();
  const addActiveProvider = useAddActiveProvider();
  const removeActiveProvider = useRemoveActiveProvider();
  const setLoading = useSetLoading();
  const setError = useSetError();
  const updateLastFetch = useUpdateLastFetch();
  const isStale = useIsStale();

  return {
    setAuth,
    setCurrentRoute,
    addActiveProvider,
    removeActiveProvider,
    setLoading,
    setError,
    updateLastFetch,
    isStale,
  };
};
