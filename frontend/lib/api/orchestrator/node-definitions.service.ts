/**
 * Node Definitions Service
 *
 * Fetches centralized node output schema definitions from the backend.
 * Single source of truth for node output schemas - replaces hardcoded frontend constants.
 */

import { apiClient } from '../api-client';

export interface NodeDefinitionOutputField {
  key: string;
  type: string;
  description?: string;
  children?: NodeDefinitionOutputField[];
  runtimeOnly?: boolean;
}

export interface NodeDefinitionDto {
  nodeType: string;
  label: string;
  category: string;
  variablePrefix: string;
  description: string;
  terminal: boolean;
  branching: boolean;
  keywords: string[];
  outputs: NodeDefinitionOutputField[];
}

export class NodeDefinitionService {
  async getAll(): Promise<NodeDefinitionDto[]> {
    return apiClient.get<NodeDefinitionDto[]>('/node-definitions');
  }
}

export const nodeDefinitionService = new NodeDefinitionService();
