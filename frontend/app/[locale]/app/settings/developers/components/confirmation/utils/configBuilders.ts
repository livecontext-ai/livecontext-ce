import { ApiConfig, McpTool, MonetizationConfig } from '../../../types';
import { ApiConfigurationRequest } from '@/types/apiConfiguration';

interface CategoryInfo {
  id: string;
  name: string;
}

interface SubcategoryInfo {
  id: string;
  name: string;
  categoryId: string;
}

interface BuildConfigParams {
  apiName: string;
  apiDescription: string;
  selectedCategory: string;
  selectedSubcategory: string;
  apiConfig: ApiConfig;
  monetizationConfig: MonetizationConfig;
  mcpTools: McpTool[];
  categories: CategoryInfo[];
  subcategories: SubcategoryInfo[];
}

/**
 * Ensures all tools have complete monetization config with defaults
 */
export function ensureCompleteMonetizationConfig(
  tools: McpTool[],
  config: MonetizationConfig
): MonetizationConfig {
  const toolNames = tools.map(tool => tool.name);
  const updatedConfig = { ...config };

  // Set default pricing if not set
  if (!updatedConfig.pricing) {
    updatedConfig.pricing = 'freemium';
  }

  // Set default rate limiting for Freemium and Paid
  if ((updatedConfig.pricing === 'freemium' || updatedConfig.pricing === 'paid') && !updatedConfig.rateLimit) {
    updatedConfig.rateLimit = {
      requests: 1000,
      period: 'hour'
    };
  }

  // Set default free requests for Freemium and Paid
  if ((updatedConfig.pricing === 'freemium' || updatedConfig.pricing === 'paid') && !updatedConfig.freeRequestsPerUser) {
    updatedConfig.freeRequestsPerUser = 1000;
  }

  // Ensure toolFreeRequests covers all tools for Freemium and Paid
  if (updatedConfig.pricing === 'freemium' || updatedConfig.pricing === 'paid') {
    if (!updatedConfig.toolFreeRequests) {
      updatedConfig.toolFreeRequests = {};
    }
    toolNames.forEach(toolName => {
      if (!updatedConfig.toolFreeRequests[toolName]) {
        updatedConfig.toolFreeRequests[toolName] = 1000;
        updatedConfig.toolFreeRequests[`${toolName}_type`] = 'per-user';
      }
    });
  }

  // Ensure toolRateLimits covers all tools for Freemium and Paid
  if (updatedConfig.pricing === 'freemium' || updatedConfig.pricing === 'paid') {
    if (!updatedConfig.toolRateLimits) {
      updatedConfig.toolRateLimits = {};
    }
    toolNames.forEach(toolName => {
      if (!updatedConfig.toolRateLimits[toolName]) {
        updatedConfig.toolRateLimits[toolName] = {
          requests: 1000,
          period: 'hour'
        };
      }
    });
  }

  // Ensure toolPricing covers all tools for Freemium
  if (updatedConfig.pricing === 'freemium') {
    if (!updatedConfig.toolPricing) {
      updatedConfig.toolPricing = {};
    }
    toolNames.forEach(toolName => {
      if (!updatedConfig.toolPricing[toolName]) {
        updatedConfig.toolPricing[toolName] = {
          mauValue: 1,
          price: 0.005,
          currency: 'USD',
          calls: 1
        };
      }
    });
  }

  // For PAID pricing, ensure planTools covers all tools
  if (config.pricing === 'paid') {
    if (!updatedConfig.planTools) {
      updatedConfig.planTools = {
        basic: [],
        pro: [],
        ultra: [],
        mega: []
      };
    }
    const plans = ['basic', 'pro', 'ultra', 'mega'];
    plans.forEach(plan => {
      if (!updatedConfig.planTools[plan]) {
        updatedConfig.planTools[plan] = [];
      }
      // Add all tools to each plan by default
      const missingTools = toolNames.filter(toolName => !updatedConfig.planTools[plan].includes(toolName));
      updatedConfig.planTools[plan] = [...updatedConfig.planTools[plan], ...missingTools];
    });
  }

  return updatedConfig;
}

/**
 * Builds the API configuration request for backend submission
 */
export function buildApiConfigurationRequest(params: BuildConfigParams): ApiConfigurationRequest {
  const {
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    apiConfig,
    monetizationConfig,
    mcpTools,
    categories,
    subcategories
  } = params;

  // Get IDs from localStorage
  const categoryId = typeof window !== 'undefined' ? localStorage.getItem('livecontext_selectedCategoryId') || '' : '';
  const subcategoryId = typeof window !== 'undefined' ? localStorage.getItem('livecontext_selectedSubcategoryId') || '' : '';

  // Fallback: Find category and subcategory UUIDs if not found in localStorage
  const category = categories.find(cat => cat.id === categoryId) || categories.find(cat => cat.name === selectedCategory);
  const subcategory = subcategories.find(sub => sub.id === subcategoryId) || subcategories.find(sub =>
    sub.name === selectedSubcategory && sub.categoryId === category?.id
  );

  // Use original monetization config with real values from localStorage
  const originalMonetizationConfig = monetizationConfig;

  return {
    apiName,
    apiDescription,
    selectedCategory,
    selectedSubcategory,
    categoryId: categoryId || category?.id,
    subcategoryId: subcategoryId || subcategory?.id,
    apiConfig: {
      baseUrl: apiConfig.baseUrl,
      healthcheckEndpoint: apiConfig.healthcheckEndpoint,
      authorization: {
        type: apiConfig.authorization.type,
        description: apiConfig.authorization.description,
        headerName: apiConfig.authorization.headerName,
        headerValue: apiConfig.authorization.headerValue
      },
      visibility: apiConfig.visibility
    },
    monetization: {
      pricing: originalMonetizationConfig.pricing,
      selectedPricingModels: originalMonetizationConfig.selectedPricingModels,

      // Rate Limiting: Always send toolRateLimits, fill with global value if in global mode
      toolRateLimits: (() => {
        if (originalMonetizationConfig.toolRateLimits && Object.keys(originalMonetizationConfig.toolRateLimits).length > 0) {
          return originalMonetizationConfig.toolRateLimits as any;
        } else {
          const globalRateLimits: Record<string, any> = {};
          mcpTools.forEach(tool => {
            globalRateLimits[tool.name] = originalMonetizationConfig.rateLimit || { requests: 1000, period: 'hour' };
          });
          return globalRateLimits;
        }
      })(),

      // Free Requests: Always send toolFreeRequests, fill with global value if in global mode
      toolFreeRequests: (() => {
        if (originalMonetizationConfig.toolFreeRequests && Object.keys(originalMonetizationConfig.toolFreeRequests).length > 0) {
          return originalMonetizationConfig.toolFreeRequests as any;
        } else {
          const globalFreeRequests: Record<string, any> = {};
          mcpTools.forEach(tool => {
            const freeRequestsValue = originalMonetizationConfig.freeRequestsPerUser || 1000;
            globalFreeRequests[tool.name] = freeRequestsValue;
            globalFreeRequests[`${tool.name}_type`] = 'per-user';
          });
          return globalFreeRequests;
        }
      })(),

      // Pricing: Always send toolPricing, fill with global value if in global mode
      toolPricing: (() => {
        if (originalMonetizationConfig.toolPricing && Object.keys(originalMonetizationConfig.toolPricing).length > 0) {
          return originalMonetizationConfig.toolPricing as any;
        } else {
          const globalPricing: Record<string, any> = {};
          mcpTools.forEach(tool => {
            const mauValue = originalMonetizationConfig.uniformToolPrice || 1;
            const calls = (originalMonetizationConfig as any).uniformCalls || 1;
            globalPricing[tool.name] = {
              mauValue: mauValue,
              price: 0.005,
              currency: 'USD',
              calls: calls
            };
          });
          return globalPricing;
        }
      })(),

      // For PAID: send plan configuration
      selectedPlans: originalMonetizationConfig.selectedPlans,
      priceBasic: originalMonetizationConfig.priceBasic,
      pricePro: originalMonetizationConfig.pricePro,
      priceUltra: originalMonetizationConfig.priceUltra,
      priceMega: originalMonetizationConfig.priceMega,
      quotaBasic: originalMonetizationConfig.quotaBasic,
      quotaPro: originalMonetizationConfig.quotaPro,
      quotaUltra: originalMonetizationConfig.quotaUltra,
      quotaMega: originalMonetizationConfig.quotaMega,
      rpsBasic: originalMonetizationConfig.rpsBasic,
      rpsPro: originalMonetizationConfig.rpsPro,
      rpsUltra: originalMonetizationConfig.rpsUltra,
      rpsMega: originalMonetizationConfig.rpsMega,
      rpsPeriodBasic: originalMonetizationConfig.rpsPeriodBasic,
      rpsPeriodPro: originalMonetizationConfig.rpsPeriodPro,
      rpsPeriodUltra: originalMonetizationConfig.rpsPeriodUltra,
      rpsPeriodMega: originalMonetizationConfig.rpsPeriodMega,
      overusageCostBasic: originalMonetizationConfig.overusageCostBasic,
      overusageCostPro: originalMonetizationConfig.overusageCostPro,
      overusageCostUltra: originalMonetizationConfig.overusageCostUltra,
      overusageCostMega: originalMonetizationConfig.overusageCostMega,
      hardLimitBasic: originalMonetizationConfig.hardLimitBasic,
      hardLimitPro: originalMonetizationConfig.hardLimitPro,
      hardLimitUltra: originalMonetizationConfig.hardLimitUltra,
      hardLimitMega: originalMonetizationConfig.hardLimitMega,
      planTools: originalMonetizationConfig.planTools
    },
    mcpTools: mcpTools.map(tool => ({
      name: tool.name,
      description: tool.description,
      endpoint: tool.endpoint,
      method: tool.method,
      toolCategory: tool.toolCategory,
      toolNameId: tool.toolNameId,
      isCustomCategory: tool.isCustomCategory,
      isCustomToolName: tool.isCustomToolName,
      headers: tool.headers as any,
      pathParameters: tool.pathParameters as any,
      queryParameters: tool.queryParameters as any,
      bodyParams: tool.bodyParams as any,
      bodySchema: tool.bodySchema,
      response: tool.response as any
    }))
  };
}
