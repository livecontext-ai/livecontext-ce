/**
 * Form Endpoint Settings Service
 *
 * Handles CRUD operations for standalone form endpoints.
 */

import { apiClient } from '../api-client';

export interface StandaloneFormEndpoint {
  id: string;
  name: string;
  description?: string;
  token: string;
  formUrl: string;
  workflowId: string;
  workflowName?: string;
  formConfig?: Array<Record<string, unknown>>;
  successMessage?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFormEndpointRequest {
  name: string;
  description?: string;
  workflowId?: string;
  workflowName?: string;
  formConfig?: Array<Record<string, unknown>>;
  successMessage?: string;
  sourceNodeId?: string;
}

export interface UpdateFormEndpointRequest {
  name: string;
  description?: string;
  workflowId?: string;
  workflowName?: string;
  formConfig?: Array<Record<string, unknown>>;
  successMessage?: string;
}

export interface FormSubmissionLog {
  id: number;
  submissionData?: Record<string, unknown>;
  responseStatus: string;
  workflowsTriggered: number;
  ipAddress?: string;
  submittedAt: string;
}

export interface FormSubmissionLogsPage {
  content: FormSubmissionLog[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

export interface FormEndpointConfig {
  maxPerUser: number;
  currentCount: number;
}

export class FormEndpointSettingsService {
  async getAll(): Promise<StandaloneFormEndpoint[]> {
    return apiClient.get<StandaloneFormEndpoint[]>('/form-endpoints');
  }

  async create(data: CreateFormEndpointRequest): Promise<StandaloneFormEndpoint> {
    return apiClient.post<StandaloneFormEndpoint>('/form-endpoints', data);
  }

  async getById(id: string): Promise<StandaloneFormEndpoint> {
    return apiClient.get<StandaloneFormEndpoint>(`/form-endpoints/${id}`);
  }

  async update(id: string, data: UpdateFormEndpointRequest): Promise<StandaloneFormEndpoint> {
    return apiClient.put<StandaloneFormEndpoint>(`/form-endpoints/${id}`, data);
  }

  async delete(id: string): Promise<void> {
    await apiClient.delete(`/form-endpoints/${id}`);
  }

  async regenerateToken(id: string): Promise<StandaloneFormEndpoint> {
    return apiClient.post<StandaloneFormEndpoint>(`/form-endpoints/${id}/regenerate-token`, {});
  }

  async getSubmissionLogs(id: string, page = 0, size = 20): Promise<FormSubmissionLogsPage> {
    return apiClient.get<FormSubmissionLogsPage>(`/form-endpoints/${id}/logs`, {
      params: { page: String(page), size: String(size) },
    });
  }

  async getConfig(): Promise<FormEndpointConfig> {
    return apiClient.get<FormEndpointConfig>('/form-endpoints/config');
  }

  async updateWorkflowReference(endpointId: string, workflowId: string | null, workflowName: string | null): Promise<StandaloneFormEndpoint> {
    return apiClient.patch<StandaloneFormEndpoint>(`/form-endpoints/${endpointId}/workflow`, {
      workflowId: workflowId || null,
      workflowName: workflowName || null,
    });
  }
}

export const formEndpointSettingsService = new FormEndpointSettingsService();
