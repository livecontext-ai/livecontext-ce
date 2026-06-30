/**
 * Workflow Version Service
 *
 * Handles workflow plan version history operations.
 * Single Responsibility: Only version-related operations.
 */

import { apiClient } from '../api-client';
import type {
  WorkflowVersionsResponse,
  WorkflowVersionDetail,
  RestoreVersionResponse,
} from './types';

export class VersionService {
  /**
   * List all versions for a workflow (metadata only, no plan body).
   */
  async listVersions(workflowId: string): Promise<WorkflowVersionsResponse> {
    return apiClient.get<WorkflowVersionsResponse>(
      `/v2/workflows/dag/${workflowId}/versions`
    );
  }

  /**
   * Get a specific version with its full plan.
   */
  async getVersion(workflowId: string, version: number): Promise<WorkflowVersionDetail> {
    return apiClient.get<WorkflowVersionDetail>(
      `/v2/workflows/dag/${workflowId}/versions/${version}`
    );
  }

  /**
   * Restore a version: copies the versioned plan back to workflows.plan.
   * Creates a new version from the current plan before restoring.
   */
  async restoreVersion(workflowId: string, version: number): Promise<RestoreVersionResponse> {
    return apiClient.post<RestoreVersionResponse>(
      `/v2/workflows/dag/${workflowId}/versions/${version}/restore`,
      {}
    );
  }

  /**
   * Rename a version (set/update its label).
   */
  async renameVersion(
    workflowId: string,
    version: number,
    label: string | null
  ): Promise<{ success: boolean; version: number; label: string | null }> {
    return apiClient.patch<{ success: boolean; version: number; label: string | null }>(
      `/v2/workflows/dag/${workflowId}/versions/${version}`,
      { label }
    );
  }

  /**
   * Pin a version as production (triggers will use this version's plan).
   * Pass null to unpin (revert to latest behavior).
   */
  async pinVersion(
    workflowId: string,
    version: number | null
  ): Promise<{
    success: boolean;
    pinnedVersion: number | null;
    /**
     * Public runId of the production run that backs this pin. `null` on unpin
     * or when the pinned version has no trusted run yet. Frontend uses this to
     * auto-redirect to `/run/{id}` after pinning so the user can watch scheduled
     * fires live (builder edit URL doesn't subscribe to the WS channel - see
     * WorkflowModeContext).
     */
    productionRunIdPublic: string | null;
  }> {
    return apiClient.patch<{
      success: boolean;
      pinnedVersion: number | null;
      productionRunIdPublic: string | null;
    }>(`/v2/workflows/dag/${workflowId}/pin`, { version });
  }
}

export const versionService = new VersionService();
