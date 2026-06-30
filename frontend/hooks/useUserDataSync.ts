import { useState, useEffect, useCallback } from 'react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { unifiedApiService } from '@/lib/api/unified-api-service';

export interface UserProfileData {
    userId: number;
    username: string;
    email: string;
    firstName: string;
    lastName: string;
    avatarUrl: string;
    planCode: string;
    planName: string;
    subscriptionStatus: string;
    currentPeriodStart: string;
    currentPeriodEnd: string;
    cancelAtPeriodEnd: boolean;
    tools?: Array<{
        id: string;
        name: string;
        apiName: string;
        apiSlug: string;
        endpoint: string;
        method: string;
        toolCategories?: {
            id: string;
            name: string;
            description: string;
            icon: string;
            color: string;
            sortOrder: number;
            isActive: boolean;
            createdAt: number;
            updatedAt: number;
        } | null;
        monetization: Array<{
            id: string;
            apiToolId: string;
            monetizationType: string;
            planName: string | null;
            rateLimitRequests: number;
            rateLimitPeriod: string;
            freeRequests: number | null;
            freeRequestsType: string | null;
            mauValue: number | null;
            pricePerMau: number | null;
            calls: number;
            quota: number | null;
            price: number | null;
            overusageCost: number | null;
            hardLimit: boolean | null;
            createdAt: number;
            updatedAt: number;
        }>;
    }>;
}

export interface MonetizationData {
    tools: Array<{
        id: string;
        name: string;
        apiName: string;
        apiSlug: string;
        endpoint: string;
        method: string;
        toolCategories?: {
            id: string;
            name: string;
            description: string;
            icon: string;
            color: string;
            sortOrder: number;
            isActive: boolean;
            createdAt: number;
            updatedAt: number;
        } | null;
        monetization: Array<{
            id: string;
            apiToolId: string;
            monetizationType: string;
            planName: string | null;
            rateLimitRequests: number;
            rateLimitPeriod: string;
            freeRequests: number | null;
            freeRequestsType: string | null;
            mauValue: number | null;
            pricePerMau: number | null;
            calls: number;
            quota: number | null;
            price: number | null;
            overusageCost: number | null;
            hardLimit: boolean | null;
            createdAt: number;
            updatedAt: number;
        }>;
    }>;
    totalTools: number;
    userId: string;
}

export interface SyncedUserData {
    profile: UserProfileData | null;
    monetization: MonetizationData | null;
    isLoading: boolean;
    error: string | null;
    lastSync: Date | null;
}

export function useUserDataSync() {
    const { isAuthenticated, user } = useAuthGuard();
    const [syncedData, setSyncedData] = useState<SyncedUserData>({
        profile: null,
        monetization: null,
        isLoading: false,
        error: null,
        lastSync: null
    });

    const fetchUserProfile = useCallback(async (): Promise<UserProfileData | null> => {
        try {
            const response = await unifiedApiService.getUserProfile();
            return response as UserProfileData;
        } catch (error) {
            throw error;
        }
    }, []);

    const fetchMonetizationData = useCallback(async (): Promise<MonetizationData | null> => {
        try {
            const response = await unifiedApiService.getMonetizationState();
            return response as MonetizationData;
        } catch (error) {
            throw error;
        }
    }, []);

  const syncUserData = useCallback(async () => {
    if (!isAuthenticated) {
      return;
    }

    setSyncedData(prev => ({ ...prev, isLoading: true, error: null }));

    try {
      // Utiliser les donnees du cache React Query au lieu de faire de nouvelles requetes
      // Les donnees sont deja chargees par AppDataProvider
      const [profileData, monetizationData] = await Promise.all([
        fetchUserProfile().catch(() => null), // Fallback si pas en cache
        fetchMonetizationData().catch(() => null) // Fallback si pas en cache
      ]);
    
      setSyncedData({
        profile: profileData,
        monetization: monetizationData,
        isLoading: false,
        error: null,
        lastSync: new Date()
      });

    } catch (error) {
      setSyncedData(prev => ({
        ...prev,
        isLoading: false,
        error: error instanceof Error ? error.message : 'Unknown error occurred'
      }));
    }
  }, [isAuthenticated, fetchUserProfile, fetchMonetizationData]);

    // Auto-sync when user becomes authenticated
    useEffect(() => {
        if (isAuthenticated && user) {
            syncUserData();
        }
    }, [isAuthenticated, user, syncUserData]);

    // Manual refresh function
    const refreshData = useCallback(async () => {
        await syncUserData();
    }, [syncUserData]);

    return {
        ...syncedData,
        refreshData,
        syncUserData
    };
}

export default useUserDataSync;
