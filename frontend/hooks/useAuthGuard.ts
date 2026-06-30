'use client';

import { useContext } from 'react';
import { AuthContext } from '@/lib/providers/smart-providers';
import { useSharedConversation } from '@/contexts/SharedConversationContext';

/**
 * Hook pour gerer l'authentification de maniere robuste.
 *
 * On shared conversation pages (ShareToken context), auth is handled by the gateway
 * which resolves the share token to the owner's userId. Components on those pages
 * must not redirect to login - they are already "authenticated" via the share token.
 */
export function useAuthGuard() {
  const sharedCtx = useSharedConversation();

  // On shared pages, the gateway has already authenticated the request via ShareToken.
  // Return a stable "authenticated" shape so panel components render normally.
  if (sharedCtx) {
    return {
      isAuthenticated: true as const,
      isAuthChecking: false as const,
      isLoading: false as const,
      isReady: true as const,
      user: null,
      token: null,
      numericUserId: null,
      avatarUrl: null,
      loginWithRedirect: async () => {},
      logout: async () => {},
      getAccessToken: async () => `ShareToken ${sharedCtx.token}`,
      getAccessTokenSilently: async () => `ShareToken ${sharedCtx.token}`,
      updateAvatarUrl: () => {},
      requireAuth: () => true,
      hasRole: () => false,
      hasPermission: () => false,
    };
  }

  // Regular authenticated context
  const context = useContext(AuthContext);
  if (context === undefined) {
    // Fallback: not inside AppDataProvider (should not happen outside share pages)
    return {
      isAuthenticated: false as const,
      isAuthChecking: true as const,
      isLoading: true as const,
      isReady: false as const,
      user: null,
      token: null,
      numericUserId: null,
      avatarUrl: null,
      loginWithRedirect: async () => {},
      logout: async () => {},
      getAccessToken: async () => { throw new Error('No auth'); },
      getAccessTokenSilently: async () => { throw new Error('No auth'); },
      updateAvatarUrl: () => {},
      requireAuth: () => false,
      hasRole: () => false,
      hasPermission: () => false,
    };
  }
  return context;
}
