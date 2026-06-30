'use client';

import { useCatalogApi } from '@/lib/hooks/useStandardApi';
import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Hook standardise pour les donnees de catalog
 * Remplace les Resource Managers deprecies
 */

export function useCategories() {
  return useCatalogApi(
    'categories',
    () => unifiedApiService.getCategories()
  );
}
