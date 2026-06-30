/**
 * Catalog API Service
 * Single Responsibility: Categories, subcategories, and catalog operations
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';

export interface CategoryResponse {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface SubcategoryResponse {
  id: string;
  categoryId: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface ToolCategory {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface ToolName {
  id: string;
  name: string;
  description: string;
  category: string;
  createdAt: string;
  updatedAt: string;
  endpointPattern?: string;
}

export interface CatalogTool {
  id: string;
  name: string;
  description?: string;
  category?: string;
  [key: string]: any;
}

export interface CatalogToolListResponse {
  tools: CatalogTool[];
  total?: number;
  limit?: number;
  offset?: number;
}

export interface CatalogToolExecutionPayload {
  parameters?: Record<string, any>;
  metadata?: Record<string, any>;
  context?: string;
}

export interface CatalogIntentResolutionResponse {
  intents: Array<{
    toolId: string;
    score: number;
    [key: string]: any;
  }>;
}

export class CatalogApiService {
  constructor() {}

  async getCategories(): Promise<CategoryResponse[]> {
    try {
      return await apiClient.get<CategoryResponse[]>('/catalog/categories');
    } catch (error) {
      console.error('Error fetching categories:', error);
      throw error;
    }
  }

  async getSubcategories(categoryId: string): Promise<SubcategoryResponse[]> {
    try {
      return await apiClient.get<SubcategoryResponse[]>(`/catalog/categories/${categoryId}/subcategories`);
    } catch (error) {
      console.error('Error fetching subcategories:', error);
      throw error;
    }
  }

  async getToolNames(): Promise<ToolName[]> {
    try {
      return await apiClient.get<ToolName[]>('/tool-categories/tool-names');
    } catch (error) {
      console.error('Error fetching tool names:', error);
      throw error;
    }
  }

  async getToolCategories(): Promise<ToolCategory[]> {
    try {
      return await apiClient.get<ToolCategory[]>('/tool-categories');
    } catch (error) {
      console.error('Error fetching tool categories:', error);
      throw error;
    }
  }

  async getCatalogTools(options: { limit?: number; category?: string; search?: string } = {}): Promise<CatalogToolListResponse> {
    const params: Record<string, string> = {};
    if (options.limit !== undefined) params.limit = String(options.limit);
    if (options.category) params.category = options.category;
    if (options.search) params.search = options.search;
    return await apiClient.get<CatalogToolListResponse>('/catalog/v1/tools', { params });
  }

  async executeCatalogTool(toolId: string, payload: CatalogToolExecutionPayload = {}): Promise<any> {
    const body = {
      parameters: payload.parameters ?? {},
      metadata: payload.metadata ?? {},
      context: payload.context ?? 'frontend'
    };
    return await apiClient.post<any>(`/catalog/v1/tools/${toolId}/execute`, body);
  }

  async resolveCatalogIntent(query: string, limit: number = 5): Promise<CatalogIntentResolutionResponse> {
    const params: Record<string, string> = { q: query };
    if (limit !== undefined) params.limit = String(limit);
    return await apiClient.get<CatalogIntentResolutionResponse>('/catalog/v1/intents/resolve', { params });
  }

  async getToolNamesByCategory(categoryId: string): Promise<ToolName[]> {
    try {
      return await apiClient.get<ToolName[]>(`/tool-categories/${categoryId}/tool-names`);
    } catch (error) {
      console.error('Error fetching tool names by category:', error);
      throw error;
    }
  }

  async getToolNamesBySubcategory(subcategoryId: string): Promise<ToolName[]> {
    try {
      return await apiClient.get<ToolName[]>(`/tool-categories/by-subcategory/${subcategoryId}`);
    } catch (error) {
      console.error('Error fetching tool names by subcategory:', error);
      throw error;
    }
  }

  async getToolNamesByToolCategoryAndSubcategory(toolCategory: string, subcategoryId: string): Promise<ToolName[]> {
    try {
      return await apiClient.get<ToolName[]>(`/tool-categories/${toolCategory}/subcategory/${subcategoryId}/tool-names`);
    } catch (error) {
      console.error('Error fetching tool names by category and subcategory:', error);
      throw error;
    }
  }
}
