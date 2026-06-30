/**
 * Workflow Service
 *
 * Handles workflow CRUD operations, execution, and streaming.
 * Single Responsibility: Only workflow-related operations.
 */

import { apiClient } from '../api-client';
import type { Workflow, WorkflowRun, WorkflowStep, PagedStepsResponse, WorkflowBoardResponse, WorkflowsPage, ApplicationRunVersionEntry } from './types';

export interface WorkflowsListOptions {
  /** Zero-based page index. */
  page?: number;
  /** Page size (default 25 server-side, max 100). */
  size?: number;
  /** Server-side search term - matches name + description (case-insensitive). */
  q?: string;
  /** Server-side sort key: 'name' (A->Z) or 'lastModified' (default, updatedAt desc). */
  sort?: string;
  /** Server-side visibility filter derived from publication status: all | public | private. */
  visibility?: 'all' | 'public' | 'private';
}

export class WorkflowService {
  /**
   * Get a page of workflows for current user (tenant from JWT).
   * Returns the full page envelope (workflows + totalCount + page + size).
   */
  async getWorkflowsPage(options: WorkflowsListOptions = {}): Promise<WorkflowsPage> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    if (options.sort) params.sort = options.sort;
    if (options.visibility) params.visibility = options.visibility;
    const data = await apiClient.get<any>('/workflows', { params });
    return {
      workflows: data.workflows ?? [],
      count: data.count ?? (data.workflows?.length ?? 0),
      totalCount: data.totalCount ?? data.count ?? (data.workflows?.length ?? 0),
      page: data.page ?? 0,
      size: data.size ?? 25,
    };
  }

  /**
   * Back-compat helper: returns just the workflow array.
   * Prefer {@link getWorkflowsPage} when pagination metadata is needed.
   */
  async getWorkflows(options: WorkflowsListOptions = {}): Promise<Workflow[]> {
    const page = await this.getWorkflowsPage(options);
    return page.workflows;
  }

  /**
   * Get the workflow board with pre-classified columns (draft, production, needsReview, paused).
   * The page only contains the workflows for the requested slice; `totalCount` reflects the full tenant.
   */
  async getWorkflowBoard(options: WorkflowsListOptions = {}): Promise<WorkflowBoardResponse> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    return apiClient.get<WorkflowBoardResponse>('/workflows/board', { params });
  }

  /**
   * Load a single board column page for lazy loading. Each kanban column scrolls
   * independently and asks for the next page when the user reaches its bottom.
   */
  async getWorkflowBoardColumn(
    column: 'draft' | 'production' | 'needsReview' | 'paused',
    options: { page?: number; size?: number } = {}
  ): Promise<{ column: string; items: import('./types').WorkflowBoardCard[]; totalCount: number; page: number; size: number }> {
    const params: Record<string, string> = { column };
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    const data = await apiClient.get<any>('/workflows/board/column', { params });
    return {
      column: data.column,
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 20,
    };
  }

  /**
   * Applications board column - identical shape to {@link getWorkflowBoardColumn} but sourced from
   * APPLICATION-type workflows (acquired + published apps). Same 4 columns and per-column pagination.
   */
  async getApplicationBoardColumn(
    column: 'draft' | 'production' | 'needsReview' | 'paused',
    options: { page?: number; size?: number } = {}
  ): Promise<{ column: string; items: import('./types').WorkflowBoardCard[]; totalCount: number; page: number; size: number }> {
    const params: Record<string, string> = { column };
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    const data = await apiClient.get<any>('/workflows/applications/board/column', { params });
    return {
      column: data.column,
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 20,
    };
  }

  /**
   * Get a single workflow by ID
   */
  async getWorkflow(id: string): Promise<Workflow> {
    return apiClient.get<Workflow>(`/workflows/${id}`);
  }

  /**
   * Create a new workflow
   */
  async createWorkflow(workflow: Partial<Workflow>): Promise<Workflow> {
    return apiClient.post<Workflow>('/workflows', workflow);
  }

  /**
   * Update an existing workflow
   */
  async updateWorkflow(id: string, workflow: Partial<Workflow>): Promise<Workflow> {
    return apiClient.put<Workflow>(`/workflows/${id}`, workflow);
  }

  /**
   * Delete a workflow
   */
  async deleteWorkflow(id: string): Promise<void> {
    return apiClient.delete<void>(`/v2/workflows/dag/${id}`);
  }

  /**
   * Clone a workflow
   */
  async cloneWorkflow(id: string): Promise<{ id: string; name: string }> {
    return apiClient.post<{ id: string; name: string }>(`/v2/workflows/dag/${id}/clone`, {});
  }

  /**
   * Reset an APPLICATION workflow's plan to its basePlan (immutable reference).
   */
  async resetApplicationPlan(workflowId: string): Promise<{ success: boolean; workflowId: string }> {
    return apiClient.post<{ success: boolean; workflowId: string }>(`/v2/workflows/dag/${workflowId}/reset-plan`, {});
  }

  /**
   * Update workflow lifecycle status (DRAFT <-> ACTIVE toggle)
   */
  async updateWorkflowStatus(id: string, status: 'DRAFT' | 'ACTIVE'): Promise<{ success: boolean; status: string }> {
    return apiClient.patch<{ success: boolean; status: string }>(`/v2/workflows/dag/${id}/status`, { status });
  }

  /**
   * Save workflow plan (DAG)
   */
  async saveWorkflowPlan(plan: any): Promise<any> {
    return apiClient.post<any>('/v2/workflows/dag', plan);
  }

  /**
   * Update workflow plan (DAG) and optionally schedule
   */
  async updateWorkflowPlan(id: string, plan: any, schedule?: { cron?: string } | null): Promise<any> {
    return apiClient.put<any>(`/v2/workflows/dag/${id}/plan`, { plan, schedule });
  }

  /**
   * Validate workflow
   */
  async validateWorkflow(workflow: any): Promise<any> {
    return apiClient.post<any>('/v2/workflows/dag/validate', workflow);
  }

  /**
   * Execute workflow (DAG)
   */
  async executeWorkflow(workflowData: any): Promise<{ runId: string; status?: string; webhookTokens?: Record<string, string> }> {
    return apiClient.post<{ runId: string; status?: string; webhookTokens?: Record<string, string> }>('/v2/workflows/dag/execute', workflowData);
  }

  /**
   * Execute workflow (legacy v2)
   */
  async executeWorkflowLegacy(workflowData: any): Promise<{ runId: string }> {
    return apiClient.post<{ runId: string }>('/v2/workflows/execute', workflowData);
  }

  /**
   * Get workflow run status (used for polling webhook workflows)
   */
  async getRunStatus(runId: string): Promise<any> {
    return apiClient.get<any>(`/workflows/runs/${runId}`);
  }

  /**
   * Get optimized status counts for polling (lightweight endpoint)
   */
  async getStatusCounts(runId: string): Promise<{
    runId: string;
    status: string;
    epoch: number;
    nodes: Record<string, Record<string, number>>;
    edges: Record<string, Record<string, number>>;
    updatedAt: string;
  }> {
    return apiClient.get(`/workflows/runs/${runId}/status-counts`);
  }

  /**
   * Calculate workflow levels (for layout)
   */
  async calculateLevels(workflow: any): Promise<any> {
    return apiClient.post<any>('/v2/workflows/dag/calculate-levels', workflow);
  }

  // ========================================
  // Workflow Runs
  // ========================================

  /**
   * Get runs for a workflow with pagination
   */
  async getWorkflowRuns(workflowId: string, limit: number = 15, offset: number = 0): Promise<WorkflowRun[]> {
    return apiClient.get<WorkflowRun[]>(`/workflows/${workflowId}/runs?limit=${limit}&offset=${offset}`);
  }

  /**
   * Get the latest run for a workflow (optimized - returns only one run)
   */
  async getLatestWorkflowRun(workflowId: string): Promise<WorkflowRun | null> {
    try {
      return await apiClient.get<WorkflowRun>(`/workflows/${workflowId}/runs/latest`);
    } catch {
      return null;
    }
  }

  /**
   * Get the pinned (production) run for a workflow.
   * Uses the same ProductionRunResolver logic as schedule/webhook/workflow triggers.
   * Returns null if the workflow has no pinned version or no run at that version.
   */
  async getPinnedWorkflowRun(workflowId: string): Promise<WorkflowRun | null> {
    try {
      return await apiClient.get<WorkflowRun>(`/workflows/${workflowId}/runs/pinned`);
    } catch {
      return null;
    }
  }

  /**
   * Get a single run by ID (public runId)
   */
  async getRun(runId: string): Promise<WorkflowRun> {
    return apiClient.get<WorkflowRun>(`/workflows/runs/${runId}`);
  }

  /**
   * Start a workflow run that is in PENDING state
   */
  async startWorkflowRun(workflowId: string, runId: string): Promise<{ success: boolean; runId: string; status: string }> {
    return apiClient.post<{ success: boolean; runId: string; status: string }>(
      `/v2/workflows/dag/${workflowId}/runs/${runId}/start`,
      {}
    );
  }

  /**
   * Get all steps for a run (by workflowRunId)
   */
  async getAllRunSteps(workflowRunId: string): Promise<WorkflowStep[]> {
    return apiClient.get<WorkflowStep[]>(`/workflows/runs/${workflowRunId}/steps`);
  }

  /**
   * Get steps for a run, paginated by stepAlias (most recent first).
   * Returns one item at a time for efficient lazy loading.
   */
  async getRunStepsPaged(
    workflowRunId: string,
    stepAlias: string,
    page: number = 0,
    size: number = 1,
    epoch?: number | null,
    status?: string | null,
  ): Promise<PagedStepsResponse> {
    const params: Record<string, string> = { stepAlias, page: String(page), size: String(size) };
    if (epoch != null) params.epoch = String(epoch);
    if (status != null && status !== '') params.status = status;
    return apiClient.get<PagedStepsResponse>(`/workflows/runs/${workflowRunId}/steps/paged`, { params });
  }

  /**
   * Get steps for a run
   */
  async getRunSteps(runId: string): Promise<WorkflowStep[]> {
    return apiClient.get<WorkflowStep[]>(`/workflows/runs/${runId}/steps`);
  }

  /**
   * Get step snapshot (for demo)
   */
  async getStepSnapshot(runId: string, stepId: string): Promise<any> {
    return apiClient.get<any>('/orchestrator/demo/step-snapshot', {
      params: { run_id: runId, step_id: stepId }
    });
  }

  /**
   * Get aggregated steps for a run
   */
  async getAggregatedSteps(runId: string): Promise<any[]> {
    return apiClient.get<any[]>(`/v2/workflows/dag/instances/${runId}/steps/aggregated`);
  }

  /**
   * Find the application-dedicated run for a workflow + publication pair.
   * Returns null if no application run exists yet.
   */
  async getApplicationRun(workflowId: string, publicationId: string): Promise<WorkflowRun | null> {
    try {
      return await apiClient.get<WorkflowRun>(`/workflows/${workflowId}/runs/application`, {
        params: { publicationId },
      });
    } catch {
      return null;
    }
  }

  /**
   * Batch sibling of getApplicationRun + versionService.listVersions: resolves, per workflowId, the
   * application run id + last-executed timestamp + pinned version in ONE request, so the Applications
   * page renders ~200 cards without firing two HTTP calls EACH (the old N+1). A workflowId with no
   * run/pinned row is absent from the result (the card hides the badge). Best-effort: a failure yields
   * {} (cards render without the badge), matching the old per-item try/catch.
   */
  async getApplicationRunVersionBatch(workflowIds: string[]): Promise<Record<string, ApplicationRunVersionEntry>> {
    const ids = Array.from(new Set(workflowIds.filter(Boolean)));
    if (ids.length === 0) return {};
    try {
      return await apiClient.post<Record<string, ApplicationRunVersionEntry>>(
        '/workflows/applications/run-version-batch',
        { workflowIds: ids },
      );
    } catch {
      return {};
    }
  }

  /**
   * Delete a run
   */
  async deleteRun(workflowId: string, runId: string): Promise<void> {
    return apiClient.delete<void>(`/workflows/${workflowId}/runs/${runId}`);
  }
}

export const workflowService = new WorkflowService();
