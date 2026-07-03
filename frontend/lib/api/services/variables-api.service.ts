/**
 * Workflow Variables API Service
 *
 * CRUD for workflow variables - the org/personal key/value store referenced in
 * workflow expressions as {{$vars.name}} (canonical) or {{vars:name}} (alias).
 * Backed by auth-service /api/variables (gateway-scoped to the active
 * workspace via X-Organization-ID; personal scope when no workspace).
 *
 * Creation beyond the plan cap returns HTTP 409 with
 * error=PLAN_RESOURCE_LIMIT_EXCEEDED, which api-client already converts into
 * the global "plan-limit-exceeded" upgrade toast - callers only need to keep
 * the row un-added on rejection.
 */

import { apiClient } from '@/lib/api/api-client';

export type WorkflowVariableType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON';

export interface WorkflowVariable {
  id: number;
  name: string;
  /** MASKED to null when {@link secret} is true - secret values are write-only. */
  value: string | null;
  type: WorkflowVariableType;
  description: string | null;
  /** "workspace" when shared with the active workspace, "personal" otherwise. */
  scope: 'workspace' | 'personal';
  /** Write-only value: masked in every listing, must be re-entered on edit. */
  secret: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface UpsertWorkflowVariableRequest {
  name: string;
  value: string;
  type: WorkflowVariableType;
  description?: string | null;
  secret?: boolean;
}

/** {@code limit} null = unlimited (CE, enterprise tiers). */
export interface WorkflowVariableQuota {
  used: number;
  limit: number | null;
  planCode: string | null;
}

class VariablesApiService {
  async list(): Promise<WorkflowVariable[]> {
    return apiClient.get<WorkflowVariable[]>('/variables');
  }

  async getQuota(): Promise<WorkflowVariableQuota> {
    return apiClient.get<WorkflowVariableQuota>('/variables/quota');
  }

  async create(request: UpsertWorkflowVariableRequest): Promise<WorkflowVariable> {
    return apiClient.post<WorkflowVariable>('/variables', request);
  }

  async update(id: number, request: UpsertWorkflowVariableRequest): Promise<WorkflowVariable> {
    return apiClient.put<WorkflowVariable>(`/variables/${id}`, request);
  }

  async remove(id: number): Promise<void> {
    return apiClient.delete<void>(`/variables/${id}`);
  }
}

export const variablesApi = new VariablesApiService();
