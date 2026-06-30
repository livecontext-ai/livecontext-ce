/**
 * Configuration React Query optimisee pour LiveContext
 * Cache intelligent, retry automatique, gestion d'erreurs
 */

import { QueryClient } from '@tanstack/react-query';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Cache pendant 5 minutes par defaut
      staleTime: 5 * 60 * 1000, // 5 minutes
      gcTime: 10 * 60 * 1000, // 10 minutes (anciennement cacheTime)

      // Note: staleTime peut être augmenté pour des données spécifiques via queryOptions
      // Exemples:
      // - Workflows/Datasources names: 15-30 minutes (changent rarement)
      // - User data: 5 minutes (changement modéré)
      // - Catalog data: 10-15 minutes (changement peu fréquent)

      // Retry automatique avec backoff exponentiel
      retry: (failureCount, error) => {
        // Ne pas retry pour les erreurs 4xx (sauf 408, 429)
        if (error instanceof Error && error.message.includes('HTTP 4')) {
          // Retry seulement pour les erreurs de timeout ou rate limit
          const statusCode = error.message.match(/HTTP (\d+)/)?.[1];
          return statusCode === '408' || statusCode === '429' ? failureCount < 2 : false;
        }

        // Retry pour les erreurs reseau et 5xx (jusqu'a 3 fois)
        return failureCount < 3;
      },

      // Retry delay avec jitter pour eviter les thundering herd
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000) + Math.random() * 1000,

      // Disable refetch on window focus to avoid cascading API calls on Alt-Tab
      refetchOnWindowFocus: false,

      // Refetch quand on reconnecte internet
      refetchOnReconnect: 'always',

      // Desactiver le refetch automatique (on contrôle manuellement)
      refetchOnMount: false,

      // Gestion d'erreur silencieuse (on gere les erreurs dans les composants)
      throwOnError: false,

      // Placeholder data pour une meilleure UX
      placeholderData: (previousData) => previousData,
    },

    mutations: {
      // Retry automatique pour les mutations aussi
      retry: 1,

      // Retry delay pour les mutations
      retryDelay: 1000,

      // Gestion d'erreur pour les mutations
      throwOnError: false,
    },
  },
});

// Query keys pour une organisation coherente
export const queryKeys = {
  // Authentification
  auth: {
    user: ['auth', 'user'] as const,
    profile: ['auth', 'profile'] as const,
  },

  // Plans et pricing
  plans: {
    all: ['plans'] as const,
    detail: (planCode: string) => ['plans', planCode] as const,
    features: ['plans', 'features'] as const,
  },

  // Subscriptions
  subscriptions: {
    all: ['subscriptions'] as const,
    current: ['subscriptions', 'current'] as const,
    billing: ['subscriptions', 'billing'] as const,
  },

  // Categories
  categories: {
    all: ['categories'] as const,
    detail: (id: string) => ['categories', id] as const,
    subcategories: (categoryId: string) => ['categories', categoryId, 'subcategories'] as const,
  },

  // Outils
  tools: {
    all: ['tools'] as const,
    detail: (id: string) => ['tools', id] as const,
    search: (query: string) => ['tools', 'search', query] as const,
    category: (categoryId: string) => ['tools', 'category', categoryId] as const,
  },

  // Developpeur
  developer: {
    profile: ['developer', 'profile'] as const,
    apis: ['developer', 'apis'] as const,
    tools: ['developer', 'tools'] as const,
  },

  // Conversations
  conversations: {
    all: ['conversations'] as const,
    page: (page: number, pageSize: number) => ['conversations', page, pageSize] as const,
    detail: (id: string) => ['conversations', id] as const,
    search: (term: string, type: 'title' | 'content') => ['conversations', 'search', term, type] as const,
  },

  // Messages
  messages: {
    all: ['messages'] as const,
    conversation: (conversationId: string, page: number, pageSize: number) =>
      ['messages', conversationId, page, pageSize] as const,
    detail: (messageId: string) => ['messages', messageId] as const,
  },
} as const;