import { useCategoriesContext } from '@/lib/hooks/smart-hooks-complete';

// Hook pour obtenir les categories et sous-categories dynamiques
export const useDynamicCategories = () => {
  const { categories, subcategories, getSubcategoriesByCategory } = useCategoriesContext();
  
  // Convertir les categories en format compatible avec les formulaires
  const categoryOptions = categories.map(category => ({
    value: category.name,
    label: category.name,
    icon: (category as any).icon || 'folder',
    color: (category as any).color || 'gray',
    description: (category as any).description || ''
  }));

  // Convertir les sous-categories en format compatible avec les formulaires
  const subcategoryOptions = subcategories.map(subcategory => ({
    value: subcategory.name,
    label: subcategory.name,
    icon: (subcategory as any).icon || 'file',
    color: (subcategory as any).color || 'gray',
    description: (subcategory as any).description || '',
    categoryId: subcategory.categoryId
  }));

  // Creer un objet groupe par categorie pour les sous-categories
  const subcategoriesByCategory = categories.reduce((acc, category) => {
    acc[category.name] = getSubcategoriesByCategory(category.id).map(sub => ({
      value: sub.name,
      label: sub.name,
      icon: (sub as any).icon || 'file',
      color: (sub as any).color || 'gray',
      description: (sub as any).description || ''
    }));
    return acc;
  }, {} as Record<string, any[]>);

  return {
    categoryOptions,
    subcategoryOptions,
    subcategoriesByCategory,
    categories,
    subcategories
  };
};

// Constantes statiques qui ne changent pas
export const methods = ['GET', 'POST', 'PUT', 'DELETE'];
export const parameterTypes = ['string', 'number', 'boolean', 'object', 'array'];

// Configuration initiale de l'API
export const initialApiConfig = {
  baseUrl: '',
  healthcheckEndpoint: '/health',
  authorization: {
    type: 'apikey' as const,
    headerName: 'X-MCPW-PROXY-SECRET',
    headerValue: '',
    description: 'API key required for authentication'
  },
  visibility: 'public' as const,
  rateLimit: {
    requests: 1000,
    period: 'hour' as const
  },
  pricing: 'free' as const,
  monetization: {
    freeRequests: 100,
    tokenValue: 1,
    currency: 'USD'
  },
  showPricingInfo: false,
  showRateLimitInfo: false,
  showPlansInfo: false,
  showTestSummaryInfo: false,
  selectedPlans: { basic: true, pro: true, ultra: true, mega: true },
  hardLimitBasic: true,
  hardLimitPro: true,
  hardLimitUltra: true,
  hardLimitMega: true
};
