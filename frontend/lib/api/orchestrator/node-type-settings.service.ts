/**
 * Node Type Settings Service
 *
 * Handles global node type enable/disable settings (admin-only).
 * Single Responsibility: Only node type settings operations.
 */

import { apiClient } from '../api-client';

export interface NodeTypeSetting {
  type: string;
  label: string;
  category: string;
  description: string;
  variablePrefix: string | null;
  parameters: Record<string, unknown> | null;
  enabled: boolean;
}

export interface ToggleResponse {
  success: boolean;
  nodeType: string;
  enabled: boolean;
}

export class NodeTypeSettingsService {
  async getAll(): Promise<NodeTypeSetting[]> {
    return apiClient.get<NodeTypeSetting[]>('/node-type-settings');
  }

  async toggle(nodeType: string, enabled: boolean): Promise<ToggleResponse> {
    return apiClient.put<ToggleResponse>(`/node-type-settings/${encodeURIComponent(nodeType)}/toggle`, { enabled });
  }
}

export const nodeTypeSettingsService = new NodeTypeSettingsService();
