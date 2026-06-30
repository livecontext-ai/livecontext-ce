/**
 * Schedule Settings Service
 *
 * Handles schedule overview operations for the Triggers settings page.
 */

import { apiClient } from '../api-client';

export interface ScheduleOverview {
  id: string;
  name: string;
  workflowId: string | null;
  workflowName: string | null;
  triggerId: string | null;
  cronExpression: string;
  timezone: string;
  enabled: boolean;
  maxExecutions?: number;
  executionCount: number;
  nextExecutionAt?: string;
  lastExecutionAt?: string;
  createdAt: string;
  description?: string;
  isActive: boolean;
}

export interface ScheduleConfig {
  currentCount: number;
  maxPerUser: number;
}

class ScheduleSettingsService {
  async getAll(): Promise<ScheduleOverview[]> {
    return apiClient.get<ScheduleOverview[]>('/schedules');
  }

  async getConfig(): Promise<ScheduleConfig> {
    return apiClient.get<ScheduleConfig>('/schedules/config');
  }

  async toggle(scheduleId: string, enabled: boolean): Promise<void> {
    await apiClient.post(`/schedules/${scheduleId}/toggle`, { enabled });
  }

  async delete(scheduleId: string): Promise<void> {
    await apiClient.delete(`/schedules/${scheduleId}`);
  }

  async create(data: { name: string; cron: string; timezone?: string; description?: string; sourceNodeId?: string }): Promise<ScheduleOverview> {
    return apiClient.post<ScheduleOverview>('/schedules', data);
  }

  async update(id: string, data: { name?: string; cron?: string; timezone?: string; maxExecutions?: number; description?: string }): Promise<ScheduleOverview> {
    return apiClient.put<ScheduleOverview>(`/schedules/${id}`, data);
  }

  /**
   * Validate a cron expression and get the human-readable description plus
   * the next 3 firings. Single source of truth - the inspector renders this
   * verbatim (no client-side cron parsing).
   *
   * Backed by trigger-service ScheduleCronParser via orchestrator at
   * POST /api/schedules/validate-cron. Strict against step values exceeding
   * the cron field maximum (the minute-field 120-step footgun).
   */
  async validateCron(cron: string, timezone?: string): Promise<{
    valid: boolean;
    description?: string;
    nextExecutions?: string[];
  }> {
    return apiClient.post('/schedules/validate-cron', { cron, timezone });
  }
}

export const scheduleSettingsService = new ScheduleSettingsService();
