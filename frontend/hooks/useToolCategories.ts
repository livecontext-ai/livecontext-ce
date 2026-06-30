import { unifiedApiService, ToolCategory, ToolName } from '@/lib/api/unified-api-service';
import { useCatalogApi } from '@/lib/hooks/useStandardApi';

// Types etendus pour la compatibilite avec l'UI
export interface ToolCategoryExtended extends ToolCategory {
  icon: string;
  color: string;
  sortOrder: number;
}

export interface ToolNameExtended extends ToolName {
  subcategoryId?: string;
}

/**
 * Hook standardise pour les categories d'outils
 * Utilise le pattern React Query standardise
 */
export const useToolCategories = (subcategoryId?: string | null) => {
  // Utilisation du pattern standardise - evite le melange useState + useEffect
  const { data: categories = [], isLoading: loading, error } = useCatalogApi(
    'toolCategories',
    () => unifiedApiService.getToolCategories()
  );

  return {
    categories,
    loading,
    isLoading: loading, // Compatibilite avec l'ancien nom
    error: error?.message || null,
    hasError: !!error,
  };
};

/**
 * Hook pour les noms d'outils par categorie
 */
export const useToolNamesByCategory = (categoryId: string) => {
  const { data: toolNames = [], isLoading, error } = useCatalogApi(
    'toolNamesByCategory',
    () => unifiedApiService.getToolNamesByCategory(categoryId),
    {
      enabled: !!categoryId
    }
  );

  return {
    toolNames,
    isLoading,
    error: error?.message || null,
    hasError: !!error,
  };
};

/**
 * Hook pour tous les noms d'outils
 */
export const useAllToolNames = () => {
  const { data: allToolNames = [], isLoading, error } = useCatalogApi(
    'allToolNames',
    () => unifiedApiService.getToolNames()
  );

  return {
    toolNames: allToolNames, // Compatibilite avec l'ancien nom
    allToolNames,
    isLoading,
    error: error?.message || null,
    hasError: !!error,
  };
};

/**
 * Hook pour les noms d'outils par sous-categorie
 */
export const useToolNamesBySubcategory = (subcategoryId: string) => {
  const { data: toolNames = [], isLoading, error } = useCatalogApi(
    'toolNamesBySubcategory',
    () => unifiedApiService.getToolNamesBySubcategory(subcategoryId),
    {
      enabled: !!subcategoryId
    }
  );

  return {
    toolNames,
    isLoading,
    error: error?.message || null,
    hasError: !!error,
  };
};

/**
 * Hook pour les noms d'outils par categorie et sous-categorie
 */
export const useToolNamesByToolCategoryAndSubcategory = (toolCategoryId: string, subcategoryId: string) => {
  const { data: toolNames = [], isLoading, error } = useCatalogApi(
    'toolNamesByToolCategoryAndSubcategory',
    () => unifiedApiService.getToolNamesByToolCategoryAndSubcategory(toolCategoryId, subcategoryId),
    {
      enabled: !!(toolCategoryId && subcategoryId)
    }
  );

  return {
    toolNames,
    isLoading,
    error: error?.message || null,
    hasError: !!error,
  };
};

export default useToolCategories;