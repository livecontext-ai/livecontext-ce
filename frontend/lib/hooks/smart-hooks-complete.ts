/**
 * Smart Hooks Complete - Remplacement complet des anciens hooks
 * Utilise les Resource Managers avec la meme logique de proxy/headers
 */

import React, { useCallback, useMemo, useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { unifiedApiService } from '../api/unified-api-service';
import { queryKeys } from '../api/query-keys';

// ========== REMPLACEMENT COMPLET DES ANCIENS HOOKS ==========

/**
 * Remplace useCategories de CategoriesContext
 * EXACTEMENT la meme interface que l'ancien hook
 */
export function useCategories() {
  const { data: categories, isLoading, error, refetch } = useQuery({
    queryKey: ['catalog', 'categories'],
    queryFn: () => unifiedApiService.getCategories(),
    staleTime: 15 * 60 * 1000, // 15 minutes - les categories changent rarement
    retry: 3,
  });

  // Interface identique a l'ancien CategoriesContext
  return {
    categories: categories || [],
    loading: isLoading,
    error: error?.message || null,
    clearError: () => {}, // Pas necessaire avec React Query
    refetch,
  };
}

/**
 * Remplace useCategoriesContext
 * EXACTEMENT la meme interface que l'ancien context
 */
export function useCategoriesContext() {
  const { data: categories, isLoading: categoriesLoading, error: categoriesError } = useQuery({
    queryKey: ['catalog', 'categories'],
    queryFn: async () => {
      try {
        const response = await unifiedApiService.getCategories();
        return Array.isArray(response) ? response : [];
      } catch (error) {
        console.error('❌ [useCategoriesContext] Failed to fetch categories:', error);
        throw error;
      }
    },
    staleTime: 15 * 60 * 1000,
  });

  const [subcategories, setSubcategories] = React.useState<any[]>([]);

  const getSubcategoriesByCategory = useCallback((categoryId: string) => {
    return subcategories.filter((sub: any) => sub.categoryId === categoryId);
  }, [subcategories]);

  const fetchSubcategoriesForCategory = useCallback(async (categoryId: string) => {
    try {
      const subs = await unifiedApiService.getSubcategories(categoryId);
      setSubcategories(prev => {
        const existing = prev.filter(sub => sub.categoryId !== categoryId);
        return [...existing, ...subs];
      });
    } catch (error) {
      console.error('Failed to fetch subcategories:', error);
    }
  }, []);

  // Interface identique a l'ancien CategoriesContext
  return {
    categories: categories || [],
    subcategories,
    categoriesLoading,
    subcategoriesLoading: false,
    categoriesError: categoriesError?.message || null,
    subcategoriesError: null,
    getSubcategoriesByCategory,
    fetchSubcategoriesForCategory,
    
    // Nouvelles methodes pour compatibilite
    categoryOptions: (categories || []).map(cat => ({
      value: cat.name,
      label: cat.name,
      icon: 'folder',
      color: '#3B82F6',
      description: cat.description || '',
      id: cat.id,
      isCustom: false,
    })),
    subcategoryOptions: subcategories.map(sub => ({
      value: sub.name,
      label: sub.name,
      icon: 'folder-open',
      color: '#10B981',
      description: sub.description || '',
      categoryId: sub.categoryId,
      id: sub.id,
      isCustom: false,
    })),
    getSubcategoriesByCategoryName: (categoryName: string) => {
      const category = categories?.find(cat => cat.name === categoryName);
      if (!category) return [];
      return subcategories
        .filter(sub => sub.categoryId === category.id)
        .map(sub => ({
          value: sub.name,
          label: sub.name,
          icon: 'folder-open',
          color: '#10B981',
          description: sub.description || '',
          categoryId: sub.categoryId,
          id: sub.id,
          isCustom: false,
        }));
    },
    addCustomCategory: (categoryName: string) => {
      // Pour compatibilite - pas d'implementation necessaire
      return {
        value: categoryName,
        label: categoryName,
        icon: 'folder',
        color: '#F59E0B',
        description: 'Custom category',
        id: `custom-${Date.now()}`,
        isCustom: true,
      };
    },
    addCustomSubcategory: (subcategoryName: string, categoryName: string) => {
      // Pour compatibilite - pas d'implementation necessaire
      return {
        value: subcategoryName,
        label: subcategoryName,
        icon: 'folder-open',
        color: '#F59E0B',
        description: 'Custom subcategory',
        categoryId: `custom-${Date.now()}`,
        id: `custom-${Date.now()}`,
        isCustom: true,
      };
    },
  };
}

/**
 * Hook pour les subscriptions - Remplace useSubscription
 * Interface compatible avec l'ancien systeme
 */
export function useSubscription() {
  const { user, isAuthenticated, isReady } = useAuthGuard();

  // Utiliser les query keys centralisees pour eviter les requetes multiples
  const queryKey = useMemo(() =>
    queryKeys.user.subscription(user?.sub || 'anonymous'),
    [user?.sub]
  );

  const { data: subscription, isLoading, error, refetch } = useQuery({
    queryKey,
    queryFn: async () => {
      // Appel direct a l'API
      const response = await unifiedApiService.getBillingData();
      return response;
    },
    enabled: !!(isAuthenticated && isReady && user?.sub),
    staleTime: 2 * 60 * 1000,
    // Configuration coherente pour eviter les requetes multiples
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    retry: 3,
  });

  const createSubscription = useCallback(async (planData: any) => {
    const response = await unifiedApiService.createCheckout(planData);
    await refetch(); // Refresh la subscription
    return response;
  }, [refetch]);

  const forceLoadSubscription = useCallback(async () => {
    await refetch();
  }, [refetch]);

  return {
    subscription: subscription || null,
    isLoading,
    error: error?.message || null,
    createSubscription,
    isProcessingCheckout: false, // Pour compatibilite
    forceLoadSubscription: () => {
      return refetch();
    },
  };
}

/**
 * Hook pour les plans - Remplace usePlans
 * Interface compatible avec l'ancien systeme
 */
export function usePlans() {
  const { isAuthenticated } = useAuthGuard();
  
  // Utiliser les query keys centralisees pour eviter les requetes multiples
  const queryKey = useMemo(() => queryKeys.plans.all(), []);

  const { data: plans, isLoading, error } = useQuery({
    queryKey,
    queryFn: async () => {
      // Appel direct a l'API
      const response = await unifiedApiService.getAvailablePlans();
      return response;
    },
    enabled: isAuthenticated, // Attendre l'authentification
    staleTime: 10 * 60 * 1000, // 10 minutes - les plans changent rarement
    retry: 3,
    // Configuration coherente pour eviter les requetes multiples
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Convertir l'objet plans en tableau
  const plansArray = useMemo(() => {
    if (!plans || typeof plans !== 'object') return [];
    return Object.values(plans);
  }, [plans]);

  // Methodes utilitaires pour compatibilite
  const isUpgrade = useCallback((fromPlan: string, toPlan: string) => {
    // Logique de comparaison des plans
    const planOrder: Record<string, number> = {
      'FREE': 0,
      'PAYG': 1,
      'STARTER': 2,
      'PRO': 3,
      'TEAM': 4,
      'ENTERPRISE_BASIC': 5,
      'ENTERPRISE_STANDARD': 6,
      'ENTERPRISE_PREMIUM': 7,
      'ENTERPRISE_ULTIMATE': 8,
    };
    const fromOrder = planOrder[fromPlan];
    const toOrder = planOrder[toPlan];
    if (fromOrder === undefined || toOrder === undefined) return false;
    return fromOrder < toOrder;
  }, []);

  const isDowngrade = useCallback((fromPlan: string, toPlan: string) => {
    const planOrder: Record<string, number> = {
      'FREE': 0,
      'PAYG': 1,
      'STARTER': 2,
      'PRO': 3,
      'TEAM': 4,
      'ENTERPRISE_BASIC': 5,
      'ENTERPRISE_STANDARD': 6,
      'ENTERPRISE_PREMIUM': 7,
      'ENTERPRISE_ULTIMATE': 8,
    };
    const fromOrder = planOrder[fromPlan];
    const toOrder = planOrder[toPlan];
    if (fromOrder === undefined || toOrder === undefined) return false;
    return fromOrder > toOrder;
  }, []);

  const getPlanOrder = useCallback((planCode: string) => {
    const planOrder: Record<string, number> = {
      'FREE': 0,
      'PAYG': 1,
      'STARTER': 2,
      'PRO': 3,
      'TEAM': 4,
      'ENTERPRISE_BASIC': 5,
      'ENTERPRISE_STANDARD': 6,
      'ENTERPRISE_PREMIUM': 7,
      'ENTERPRISE_ULTIMATE': 8,
    };
    return planOrder[planCode] || 0;
  }, []);

  const getPlansByOrder = useCallback(() => {
    return plansArray.sort((a: any, b: any) => getPlanOrder(a.code) - getPlanOrder(b.code));
  }, [plansArray, getPlanOrder]);

  const getPlan = useCallback((planName: string) => {
    // Recherche par code ou nom
    return plansArray.find((plan: any) => plan.code === planName || plan.name === planName);
  }, [plansArray]);

  const formatPrice = useCallback((planName: string, cycle: 'monthly' | 'yearly') => {
    const plan = getPlan(planName) as any;
    if (!plan?.prices?.[cycle]) return '0$';
    return `$${plan.prices[cycle].amount_dollars}/${cycle === 'yearly' ? 'year' : 'month'}`;
  }, [getPlan]);

  const formatStorage = useCallback((bytes: number) => {
    if (bytes === 0) return 'Unlimited';
    const gb = bytes / (1024 * 1024 * 1024);
    if (gb >= 1000) return `${Math.round(gb / 1000)}TB`;
    return `${Math.round(gb)}GB`;
  }, []);

  return {
    plans: plansArray,
    isLoading,
    error: error?.message || null,
    isUpgrade,
    isDowngrade,
    getPlanOrder,
    getPlansByOrder,
    getPlan,
    formatPrice,
    formatStorage,
  };
}

/**
 * Hook for fetching user credit balance
 * Used in sidebar to display remaining credits
 */
export function useCreditBalance() {
  const { user, isAuthenticated, isReady } = useAuthGuard();

  const queryKey = useMemo(() =>
    queryKeys.user.creditBalance(user?.sub || 'anonymous'),
    [user?.sub]
  );

  const { data, isLoading, error } = useQuery({
    queryKey,
    queryFn: async () => {
      const { quotaApi } = await import('../api/services/quota-api.service');
      return quotaApi.getBalance();
    },
    enabled: !!(isAuthenticated && isReady && user?.sub),
    staleTime: 60 * 1000, // 1 minute - balance changes on usage
    refetchOnMount: false,
    refetchOnWindowFocus: true, // Refresh when user comes back
    retry: 2,
  });

  return {
    balance: data?.balance ?? null,
    // V250 - bucket breakdown for the wallet UI. {@code null} when the
    // request is in-flight or no active subscription. Sum always equals
    // {@code balance} when both are non-null.
    subBalance: data?.subBalance ?? null,
    paygBalance: data?.paygBalance ?? null,
    delinquent: data?.delinquent ?? false,
    isLoading,
    error: error?.message || null,
  };
}

/**
 * V250 - Fetch the PAYG top-up tier catalog. Cached aggressively (5min) since
 * tier amounts only change via ops {@code UPDATE auth.price ...} which is
 * out-of-band. The hook surfaces {@code configured} (the AND of every tier's
 * Stripe wiring) so the wallet can render a single "PAYG soon" empty state
 * rather than 3 disabled cards.
 */
export function usePaygTiers(options: { enabled?: boolean } = {}) {
  const { isAuthenticated, isReady } = useAuthGuard();
  const enabled = options.enabled ?? true;

  const { data, isLoading, error } = useQuery({
    queryKey: queryKeys.billing.paygTiers(),
    queryFn: () => unifiedApiService.getPaygTiers(),
    enabled: !!(enabled && isAuthenticated && isReady),
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: 1,
  });

  return {
    tiers: data?.tiers ?? [],
    configured: data?.configured ?? false,
    isLoading,
    error: error?.message || null,
  };
}

/**
 * V250 - One-time PAYG top-up. Returns a mutation that POSTs to
 * {@code /api/billing/payg-checkout} and resolves to the Stripe URL.
 * The caller is responsible for the redirect (so they can surface a
 * confirmation modal first). On Stripe webhook completion the user's
 * PAYG bucket is credited and the gateway cache is fanned-out - the
 * frontend just needs to refetch {@code useCreditBalance} after returning
 * from Stripe.
 */
export function usePaygCheckout() {
  return useMutation({
    mutationFn: (tier: 'small' | 'medium' | 'large') =>
      unifiedApiService.createPaygCheckout(tier),
  });
}
