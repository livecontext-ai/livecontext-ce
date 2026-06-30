/**
 * Webhook Settings Service
 *
 * Handles CRUD operations for standalone webhooks.
 */

import { apiClient } from '../api-client';

export interface StandaloneWebhook {
  id: string;
  name: string;
  description?: string;
  token: string;
  webhookUrl: string;
  httpMethod: string;
  authType: string;
  authConfig?: Record<string, string>;
  isActive: boolean;
  workflowId?: string;
  workflowName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWebhookRequest {
  name: string;
  description?: string;
  httpMethod?: string;
  authType?: string;
  authConfig?: Record<string, string>;
  workflowId?: string;
  workflowName?: string;
  sourceNodeId?: string;
}

export interface UpdateWebhookRequest {
  name: string;
  description?: string;
  httpMethod?: string;
  authType?: string;
  authConfig?: Record<string, string>;
  workflowId?: string;
  workflowName?: string;
}

export interface WebhookCallLog {
  id: number;
  requestMethod: string;
  requestPayload?: Record<string, unknown>;
  responseStatus: string;
  workflowsTriggered: number;
  calledAt: string;
}

export interface WebhookCallLogsPage {
  content: WebhookCallLog[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

function normalizeWebhookCallLogsPage(value: unknown): WebhookCallLogsPage {
  if (Array.isArray(value)) {
    return {
      content: value as WebhookCallLog[],
      totalPages: 1,
      totalElements: value.length,
      number: 0,
      size: value.length,
    };
  }

  const record = value && typeof value === 'object'
    ? value as Partial<WebhookCallLogsPage>
    : {};
  const content = Array.isArray(record.content) ? record.content : [];
  return {
    content,
    totalPages: record.totalPages ?? 1,
    totalElements: record.totalElements ?? content.length,
    number: record.number ?? 0,
    size: record.size ?? content.length,
  };
}

export interface WebhookConfig {
  maxPerUser: number;
  currentCount: number;
}

export class WebhookSettingsService {
  async getAll(): Promise<StandaloneWebhook[]> {
    return apiClient.get<StandaloneWebhook[]>('/webhooks');
  }

  async create(data: CreateWebhookRequest): Promise<StandaloneWebhook> {
    return apiClient.post<StandaloneWebhook>('/webhooks', data);
  }

  async getById(id: string): Promise<StandaloneWebhook> {
    return apiClient.get<StandaloneWebhook>(`/webhooks/${id}`);
  }

  async update(id: string, data: UpdateWebhookRequest): Promise<StandaloneWebhook> {
    return apiClient.put<StandaloneWebhook>(`/webhooks/${id}`, data);
  }

  async delete(id: string): Promise<void> {
    await apiClient.delete(`/webhooks/${id}`);
  }

  async regenerateToken(id: string): Promise<StandaloneWebhook> {
    return apiClient.post<StandaloneWebhook>(`/webhooks/${id}/regenerate-token`, {});
  }

  async getCallLogs(id: string, page = 0, size = 20): Promise<WebhookCallLogsPage> {
    const response = await apiClient.get<WebhookCallLogsPage | WebhookCallLog[]>(`/webhooks/${id}/logs`, {
      params: { page: String(page), size: String(size) },
    });
    return normalizeWebhookCallLogsPage(response);
  }

  async getConfig(): Promise<WebhookConfig> {
    return apiClient.get<WebhookConfig>('/webhooks/config');
  }

  async updateWorkflowReference(webhookId: string, workflowId: string | null, workflowName: string | null): Promise<StandaloneWebhook> {
    return apiClient.patch<StandaloneWebhook>(`/webhooks/${webhookId}/workflow`, {
      workflowId: workflowId || null,
      workflowName: workflowName || null,
    });
  }

}

export const webhookSettingsService = new WebhookSettingsService();
