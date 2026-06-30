'use client';

import { useCallback } from 'react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useErrorHandler } from './useErrorHandler';
import { useUserApi } from '@/lib/hooks/useStandardApi';

export interface UserProfile {
  id: string;
  username: string;
  displayName?: string;
  email: string;
  name?: string;
  bio?: string;
  picture?: string;
  avatarUrl?: string | null;
  // In-app profile fields (from auth.user_profiles), edited in account settings.
  handle?: string | null;
  profileVisibility?: 'PUBLIC' | 'PRIVATE';
  createdAt: string;
  updatedAt: string;
}

export function useUserProfile() {
  const { isAuthenticated, user, isLoading: authLoading } = useAuthGuard();
  const { handleError, clearError } = useErrorHandler();
  
  // Utilisation du hook standardise - evite le melange useState + useQuery
  const { data: profile = null, isLoading, error, refetch } = useUserApi(
    'profile',
    () => unifiedApiService.getUserProfile()
  );

  const updateUserProfile = useCallback(async (profileData: Partial<UserProfile>): Promise<UserProfile | null> => {
    if (!isAuthenticated || !user?.sub) {
      return null;
    }

    clearError('updateProfile');
    
    try {
      const response = await unifiedApiService.updateUserProfile(profileData);
      // Invalider le cache pour forcer un refetch
      refetch();
      return response;
    } catch (err) {
      handleError(err, 'updateProfile');
      return null;
    }
  }, [isAuthenticated, user?.sub, handleError, clearError, refetch]);

  const getUserStatus = useCallback(async (): Promise<any> => {
    if (!isAuthenticated || !user?.sub) {
      return null;
    }

    try {
      const response = await unifiedApiService.getUserStatus();
      return response;
    } catch (err) {
      handleError(err, 'userStatus');
      return null;
    }
  }, [isAuthenticated, user?.sub, handleError]);


  return {
    profile,
    isLoading: isLoading || authLoading,
    error: error?.message || null,
    hasError: !!error,
    fetchUserProfile: refetch,
    updateUserProfile,
    getUserStatus,
    clearError: () => {
      clearError('userProfile');
      clearError('updateProfile');
      clearError('userStatus');
    },
    clearResult: () => refetch()
  };
}

export default useUserProfile;
