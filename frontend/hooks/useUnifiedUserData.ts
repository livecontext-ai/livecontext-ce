/**
 * Hook unifie pour les donnees utilisateur
 * Utilise React Query et les Resource Managers
 */

import { useAuthGuard } from './useAuthGuard';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useUserApi } from '@/lib/hooks/useStandardApi';

export interface UnifiedUserData {
  status: {
    userId?: number;
    firstLogin?: boolean;
    profileIncomplete?: boolean;
    email?: string;
    isAdmin?: boolean;
    roles?: string[];
  } | null;
  profile: {
    id: number;
    email: string;
    name?: string;
    avatar?: string;
    preferences?: Record<string, any>;
  } | null;
  monetization: {
    tools: any[];
    totalTools: number;
    userId: string;
  } | null;
  isLoading: boolean;
  error: string | null;
}

export function useUnifiedUserData() {
  const { isAuthenticated, isReady, user, isLoading: authLoading } = useAuthGuard();

  // Utilisation du pattern standardise - evite le melange avec Resource Managers
  const { data: status, isLoading: statusLoading, error: statusError } = useUserApi(
    'status',
    () => unifiedApiService.getUserStatus()
  );

  const { data: profile, isLoading: profileLoading, error: profileError } = useUserApi(
    'profile',
    () => unifiedApiService.getUserProfile()
  );

  const { data: monetization, isLoading: monetizationLoading, error: monetizationError } = useUserApi(
    'monetization',
    () => unifiedApiService.getMonetizationState()
  );

  const isLoading = authLoading || statusLoading || profileLoading || monetizationLoading;
  const error = statusError?.message || profileError?.message || monetizationError?.message || null;

  return {
    status,
    profile,
    monetization,
    isLoading,
    error,
  };
}

export default useUnifiedUserData;
