'use client';

import { useCatalogApi } from '@/lib/hooks/useStandardApi';
import { unifiedApiService } from '@/lib/api/unified-api-service';

/**
 * Hook standardise pour les sous-categories
 * Remplace les patterns useState + useEffect + useCallback
 */
export function useSubcategories(categoryId?: string) {
  return useCatalogApi(
    'subcategories',
    () => unifiedApiService.getSubcategories(categoryId!),
    {
      enabled: !!categoryId
    }
  );
}

export default useSubcategories;
