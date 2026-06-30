'use client';

import React, { createContext, useContext, useCallback, useMemo, useRef, useState, useEffect } from 'react';
import { useRouter, usePathname } from '@/i18n/navigation';
import { removeLocalePrefix } from '@/lib/utils/locale';

interface NavigationGuardContextType {
  registerGuard: (guard: NavigationGuard) => () => void;
  navigate: (path: string) => void;
  pendingNavigation: string | null;
  confirmNavigation: () => void;
  cancelNavigation: () => void;
  isNavigating: boolean;
}

interface NavigationGuard {
  isDirty: () => boolean;
  onBlock: (destination: string) => void;
}

const NavigationGuardContext = createContext<NavigationGuardContextType | null>(null);

export function NavigationGuardProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const guardsRef = useRef<Set<NavigationGuard>>(new Set());
  const [pendingNavigation, setPendingNavigation] = useState<string | null>(null);
  const [isNavigating, setIsNavigating] = useState(false);
  const navigationTargetRef = useRef<string | null>(null);

  // Clear navigation loading when pathname changes
  useEffect(() => {
    if (isNavigating && navigationTargetRef.current) {
      // Check if we've arrived at the target (or close to it)
      const target = navigationTargetRef.current;
      // Remove locale prefix for comparison (shared helper - covers all locales)
      const normalizedPathname = removeLocalePrefix(pathname);
      const normalizedTarget = removeLocalePrefix(target);

      if (normalizedPathname === normalizedTarget || normalizedPathname.startsWith(normalizedTarget)) {
        setIsNavigating(false);
        navigationTargetRef.current = null;
      }
    }
  }, [pathname, isNavigating]);

  // Timeout fallback to clear loading state
  useEffect(() => {
    if (isNavigating) {
      const timeout = setTimeout(() => {
        setIsNavigating(false);
        navigationTargetRef.current = null;
      }, 5000);
      return () => clearTimeout(timeout);
    }
  }, [isNavigating]);

  const registerGuard = useCallback((guard: NavigationGuard) => {
    guardsRef.current.add(guard);
    return () => {
      guardsRef.current.delete(guard);
    };
  }, []);

  const navigate = useCallback((path: string) => {
    // Check all registered guards
    for (const guard of guardsRef.current) {
      if (guard.isDirty()) {
        setPendingNavigation(path);
        guard.onBlock(path);
        return;
      }
    }
    // No guards blocking, proceed with navigation
    setIsNavigating(true);
    navigationTargetRef.current = path;
    router.push(path);
  }, [router]);

  const confirmNavigation = useCallback(() => {
    if (pendingNavigation) {
      const path = pendingNavigation;
      setPendingNavigation(null);
      setIsNavigating(true);
      navigationTargetRef.current = path;
      router.push(path);
    }
  }, [pendingNavigation, router]);

  const cancelNavigation = useCallback(() => {
    setPendingNavigation(null);
  }, []);

  const value = useMemo<NavigationGuardContextType>(() => ({
    registerGuard,
    navigate,
    pendingNavigation,
    confirmNavigation,
    cancelNavigation,
    isNavigating,
  }), [registerGuard, navigate, pendingNavigation, confirmNavigation, cancelNavigation, isNavigating]);

  return (
    <NavigationGuardContext.Provider value={value}>
      {children}
    </NavigationGuardContext.Provider>
  );
}

export function useNavigationGuardContext() {
  const context = useContext(NavigationGuardContext);
  if (!context) {
    throw new Error('useNavigationGuardContext must be used within NavigationGuardProvider');
  }
  return context;
}

// Hook for components that want to guard navigation
export function useNavigationGuard(options: {
  isDirty: boolean;
  onBlock: (destination: string) => void;
}) {
  const { registerGuard, confirmNavigation, cancelNavigation } = useNavigationGuardContext();
  const optionsRef = useRef(options);
  optionsRef.current = options;

  React.useEffect(() => {
    const guard: NavigationGuard = {
      isDirty: () => optionsRef.current.isDirty,
      onBlock: (dest) => optionsRef.current.onBlock(dest),
    };
    return registerGuard(guard);
  }, [registerGuard]);

  return { confirmNavigation, cancelNavigation };
}

// Hook for components that want to navigate safely
export function useSafeNavigate() {
  const context = useContext(NavigationGuardContext);
  const router = useRouter();

  // If no context available, fallback to regular router.push
  const fallbackNavigate = useCallback((path: string) => {
    router.push(path);
  }, [router]);

  return context?.navigate ?? fallbackNavigate;
}
