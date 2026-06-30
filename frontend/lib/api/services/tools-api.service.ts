/**
 * Tools API Service
 * Single Responsibility: Tool operations and testing
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';
import type { ToolRuntimeMetadata } from '@/types/runtimeMetadata';

export interface ToolCategoryInfo {
  id: string;
  name: string;
  slug: string;
  description: string;
  icon: string;
  color: string;
  sortOrder: number;
  isActive: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface ParameterResponse {
  id: string;
  name: string;
  type: string;
  description: string;
  required: boolean;
  defaultValue?: string;
  exampleValue?: string;
  parameterType?: string;
}

export interface MonetizationResponse {
  id: string;
  monetizationType: string;
  planName: string;
  price: number;
  quota: number;
  overusageCost: number;
  hardLimit: boolean;
  rateLimitRequests: number;
  rateLimitPeriod: string;
  createdAt: number;
  updatedAt: number;
}

export interface ToolResponse {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  method: string;
  protocol?: string;
  runtimeMetadata?: ToolRuntimeMetadata;
  isActive: boolean;
  createdAt: number;
  updatedAt: number;
  parameters: ParameterResponse[];
  monetization?: MonetizationResponse[];
  status?: string;
  toolCategories?: ToolCategoryInfo;
}

export interface ToolTestResult {
  id: string;
  toolId: string;
  endpoint: string;
  method: string;
  success: boolean;
  responseTime: number;
  statusCode: number;
  errorMessage?: string;
  createdAt: string;
}

export interface ToolPerformanceMetrics {
  toolId: string;
  averageResponseTime: number;
  successRate: number;
  totalRequests: number;
  lastTested: string;
}

export class ToolsApiService {
  constructor() {}

  async getToolResponses(toolId: string): Promise<any[]> {
    try {
      return await apiClient.get<any[]>(`/tool-responses/tool/${toolId}`);
    } catch (error) {
      console.error('Error fetching tool responses:', error);
      throw error;
    }
  }

  async updateToolResponse(toolId: string, responseId: string, responseData: any): Promise<any> {
    try {
      return await apiClient.put<any>(`/tool-responses/${responseId}`, responseData);
    } catch (error) {
      console.error('Error updating tool response:', error);
      throw error;
    }
  }

  async createToolResponse(toolId: string, responseData: any): Promise<any> {
    try {
      return await apiClient.post<any>(`/tool-responses`, responseData);
    } catch (error) {
      console.error('Error creating tool response:', error);
      throw error;
    }
  }

  async testExternalEndpoint(endpoint: string, method: string, headers: any, body: any): Promise<any> {
    try {
      return await apiClient.post<any>('/external-proxy', {
        url: endpoint,
        method,
        headers,
        body
      });
    } catch (error) {
      console.error('Error testing external endpoint:', error);
      throw error;
    }
  }

}
