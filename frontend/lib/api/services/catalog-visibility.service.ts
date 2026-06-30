/**
 * Catalog Visibility Service
 * Single Responsibility: API and tool visibility toggles (is_active)
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';

export interface CredentialFieldDef {
  name: string;
  displayName?: string;
  type?: string;
  required?: boolean;
  description?: string;
}

export interface IntegrationVisibility {
  apiId: string;
  apiName: string;
  iconSlug: string;
  authType: string;
  credentialName?: string;
  isActive: boolean;
  toolCount: number;
  activeToolCount: number;
  category?: string;
  credentialFields?: string | null; // JSON string of CredentialFieldDef[]
}

export interface ToolVisibility {
  toolId: string;
  toolName: string;
  toolSlug: string;
  description?: string;
  isActive: boolean;
  method?: string;
}

export class CatalogVisibilityService {
  async getIntegrations(): Promise<IntegrationVisibility[]> {
    const response = await apiClient.get<{ integrations: IntegrationVisibility[] }>(
      '/catalog/api-visibility/integrations'
    );
    return response.integrations || [];
  }

  async toggleApi(apiId: string, isActive: boolean): Promise<void> {
    await apiClient.put(`/catalog/api-visibility/apis/${encodeURIComponent(apiId)}/toggle`, null, {
      params: { isActive: String(isActive) },
    });
  }

  async getApiTools(apiId: string): Promise<ToolVisibility[]> {
    return await apiClient.get<ToolVisibility[]>(
      `/catalog/api-visibility/apis/${encodeURIComponent(apiId)}/tools`
    );
  }

  async toggleTool(toolId: string, isActive: boolean): Promise<void> {
    await apiClient.put(`/catalog/api-visibility/tools/${encodeURIComponent(toolId)}/toggle`, null, {
      params: { isActive: String(isActive) },
    });
  }
}

export const catalogVisibilityService = new CatalogVisibilityService();
