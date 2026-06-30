/**
 * Developer API Service
 * Single Responsibility: API management, developer profiles, and monetization
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';
import type { ToolResponse, ToolCategoryInfo, ParameterResponse, MonetizationResponse } from './tools-api.service';

export interface UnifiedApiResponse {
  id: string;
  apiName: string;
  apiSlug?: string;
  description: string;
  baseUrl: string;
  categoryId: string;
  categoryName: string;
  subcategoryId: string;
  subcategoryName: string;
  isActive: boolean;
  isPublic?: boolean;
  createdAt: number;
  updatedAt: number;
  createdBy: string;
  tools: ToolResponse[];
  healthcheckEndpoint?: string;
  visibility?: string;
  authType?: string;
  authHeaderName?: string;
  authHeaderValue?: string;
  pricingModel?: string;
  status?: string;
}

export interface DeveloperProfile {
  id: string;
  userId: string;
  companyName: string;
  website: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface McpServer {
  id: string;
  name: string;
  description: string;
  baseUrl: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ApiResponse {
  data: any;
  success: boolean;
}

export class DeveloperApiService {
  constructor() {}

  async getUserApis(): Promise<UnifiedApiResponse[]> {
    try {
      return await apiClient.get<UnifiedApiResponse[]>('/apis/me');
    } catch (error) {
      console.error('Error fetching user APIs:', error);
      throw error;
    }
  }

  async getApiById(apiId: string): Promise<ApiResponse> {
    try {
      const apis = await apiClient.get<UnifiedApiResponse[]>('/apis/me');
      const foundApi = apis.find(api => api.id === apiId || api.apiSlug === apiId);

      if (!foundApi) {
        throw new Error('API not found');
      }

      return {
        data: foundApi,
        success: true
      };
    } catch (error) {
      console.error('Error fetching API by ID:', error);
      if (error instanceof Error) {
        throw error;
      } else if (typeof error === 'string') {
        throw new Error(error);
      } else {
        throw new Error('Failed to fetch API details');
      }
    }
  }

  async updateApiBasicInfo(apiId: string, basicInfo: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/basic-info`, basicInfo);
    } catch (error) {
      console.error('Error updating API basic info:', error);
      throw error;
    }
  }

  async updateApiConfig(apiId: string, config: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/config`, config);
    } catch (error) {
      console.error('Error updating API config:', error);
      throw error;
    }
  }

  async getMonetizationState(): Promise<any> {
    try {
      return await apiClient.get<any>('/apis/monetization/state');
    } catch (error) {
      console.error('Error fetching monetization state:', error);
      throw error;
    }
  }

  async updatePricingModels(apiId: string, pricingData: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/pricing-models`, pricingData);
    } catch (error) {
      console.error('Error updating pricing models:', error);
      throw error;
    }
  }

  async updateToolFreemiumConfig(apiId: string, toolId: string, config: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/tools/${toolId}/freemium-config`, config);
    } catch (error) {
      console.error('Error updating tool freemium config:', error);
      throw error;
    }
  }

  async updateBatchFreemiumConfig(apiId: string, config: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/tools/freemium-config/batch`, config);
    } catch (error) {
      console.error('Error updating batch freemium config:', error);
      throw error;
    }
  }

  async updateToolPaidConfig(apiId: string, toolId: string, config: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/tools/${toolId}/paid-config`, config);
    } catch (error) {
      console.error('Error updating tool paid config:', error);
      throw error;
    }
  }

  async updateBatchPaidConfig(apiId: string, config: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/tools/paid-config/batch`, config);
    } catch (error) {
      console.error('Error updating batch paid config:', error);
      throw error;
    }
  }

  async updatePaidPlans(apiId: string, plans: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/paid-plans`, plans);
    } catch (error) {
      console.error('Error updating paid plans:', error);
      throw error;
    }
  }

  async getMyTools(): Promise<ToolResponse[]> {
    try {
      return await apiClient.get<ToolResponse[]>('/apis/me');
    } catch (error) {
      console.error('Error fetching my tools:', error);
      throw error;
    }
  }

  async getPublicTools(): Promise<ToolResponse[]> {
    try {
      const apis = await apiClient.get<UnifiedApiResponse[]>('/apis');
      const publicApis = apis.filter(api => api.isActive && api.isPublic);

      const allTools: ToolResponse[] = [];
      publicApis.forEach(api => {
        if (api.tools && Array.isArray(api.tools)) {
          allTools.push(...api.tools);
        }
      });

      return allTools;
    } catch (error) {
      console.error('Error fetching public tools:', error);
      throw error;
    }
  }

  async getToolById(toolId: string): Promise<ToolResponse | null> {
    try {
      const apis = await apiClient.get<UnifiedApiResponse[]>('/apis/me');

      for (const api of apis) {
        if (api.tools && Array.isArray(api.tools)) {
          const tool = api.tools.find(t => t.id === toolId);
          if (tool) {
            return tool;
          }
        }
      }

      return null;
    } catch (error) {
      console.error('Error fetching tool by ID:', error);
      throw error;
    }
  }

  async updateApiTool(apiId: string, toolId: string, toolData: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/apis/${apiId}/tools/${toolId}`, toolData);
    } catch (error) {
      console.error('Error updating API tool:', error);
      throw error;
    }
  }

  async checkApiNameUniqueness(apiName: string): Promise<{ isUnique: boolean; message?: string }> {
    try {
      return await apiClient.get<{ isUnique: boolean; message?: string }>('/apis/check-name', { params: { name: apiName } });
    } catch (error) {
      console.error('Error checking API name uniqueness:', error);
      throw error;
    }
  }

  async processApiConfiguration(configuration: any, userId: string, accessToken: string): Promise<any> {
    try {
      return await apiClient.post<any>('/apis/configuration/process', configuration);
    } catch (error) {
      console.error('Error processing API configuration:', error);
      throw error;
    }
  }
}
