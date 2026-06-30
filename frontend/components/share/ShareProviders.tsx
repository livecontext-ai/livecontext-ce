'use client';

import React, { useEffect, useRef } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, initShareApiClient, clearShareApiClient } from '@/lib/providers/smart-providers';
import { SharedConversationProvider } from '@/contexts/SharedConversationContext';

/**
 * Minimal provider stack for public shared conversation pages.
 *
 * Provides:
 * - QueryClientProvider  (React Query - required by panel components)
 * - AuthContext mock      (isAuthenticated=true so useAuthGuard / WorkflowDetailView don't redirect)
 * - SharedConversationContext (share token for deep components)
 * - Initializes apiClient with ShareToken so all panel API calls go through the gateway
 *   which resolves the token → owner's X-User-ID transparently.
 */

const SHARE_AUTH = {
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
  getAccessToken: async () => '',
  getAccessTokenSilently: async () => '',
  updateAvatarUrl: () => {},
  requireAuth: () => true,
  hasRole: () => false,
  hasPermission: () => false,
};

export function ShareProviders({
  token,
  children,
}: {
  token: string;
  children: React.ReactNode;
}) {
  // Initialize apiClient once with the share token
  const initialized = useRef(false);
  if (!initialized.current) {
    initShareApiClient(token);
    initialized.current = true;
  }

  // Update if token ever changes (shouldn't happen, but defensive), and
  // CRITICALLY restore the pre-share (JWT) provider on unmount. apiClient is a
  // process-wide singleton; without this cleanup, navigating away from a share
  // page leaves it stuck emitting `ShareToken …`, so every later request in the
  // authenticated app is treated as a read-only shared request (POSTs 403).
  useEffect(() => {
    initShareApiClient(token);
    return () => {
      clearShareApiClient();
    };
  }, [token]);

  const [queryClient] = React.useState(
    () => new QueryClient({
      defaultOptions: {
        queries: {
          staleTime: 5 * 60 * 1000,
          retry: false,
        },
      },
    })
  );

  return (
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={SHARE_AUTH}>
        <SharedConversationProvider token={token}>
          {children}
        </SharedConversationProvider>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
}
