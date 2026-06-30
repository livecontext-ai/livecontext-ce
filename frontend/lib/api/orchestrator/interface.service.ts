/**
 * Interface Service
 *
 * Handles interface CRUD and render operations.
 * Single Responsibility: Only interface-related operations.
 */

import { apiClient } from '../api-client';
import type { Interface, InterfaceRenderResult, InterfaceSnapshot } from './types';

export class InterfaceService {
  // ========================================
  // Interfaces CRUD
  // ========================================

  /**
   * Get all interfaces for current user
   */
  async getInterfaces(): Promise<Interface[]> {
    return apiClient.get<Interface[]>('/interfaces');
  }

  /**
   * Paged, DB-searchable, server-sorted + server-visibility-filtered list of interfaces.
   * Returns the envelope { items, totalCount, page, size, publicationStatuses }.
   * `q` is matched server-side against name + description; `sort` = name | lastModified;
   * `visibility` = all | public | private. `publicationStatuses` maps each shared id on the
   * page to { status, rejectionReason? } (absent = not shared), batched server-side - so the
   * card needs no separate getAllMyPublications sweep.
   */
  async getInterfacesPage(options: {
    page?: number;
    size?: number;
    q?: string;
    type?: 'html' | 'web_search';
    excludeTableAttached?: boolean;
    includeTemplates?: boolean;
    sort?: string;
    visibility?: 'all' | 'public' | 'private';
  } = {}): Promise<{
    items: Interface[];
    totalCount: number;
    page: number;
    size: number;
    publicationStatuses: Record<string, { status?: string; rejectionReason?: string | null }>;
  }> {
    const params: Record<string, string> = {};
    if (options.page != null) params.page = String(options.page);
    if (options.size != null) params.size = String(options.size);
    if (options.q && options.q.trim().length > 0) params.q = options.q.trim();
    if (options.type) params.type = options.type;
    if (options.excludeTableAttached) params.excludeTableAttached = 'true';
    params.includeTemplates = String(options.includeTemplates ?? false);
    if (options.sort) params.sort = options.sort;
    if (options.visibility) params.visibility = options.visibility;
    const data = await apiClient.get<any>('/interfaces/paged', { params });
    return {
      items: data.items ?? [],
      totalCount: data.totalCount ?? 0,
      page: data.page ?? 0,
      size: data.size ?? 25,
      publicationStatuses: data.publicationStatuses ?? {},
    };
  }

  /**
   * Get a single interface by ID
   */
  async getInterface(id: string): Promise<Interface> {
    return apiClient.get<Interface>(`/interfaces/${id}`);
  }

  /**
   * Create a new interface
   */
  async createInterface(iface: Partial<Interface>): Promise<Interface> {
    return apiClient.post<Interface>('/interfaces', iface);
  }

  /**
   * Update an interface
   */
  async updateInterface(id: string, iface: Partial<Interface>): Promise<Interface> {
    return apiClient.put<Interface>(`/interfaces/${id}`, iface);
  }

  /**
   * Delete an interface
   */
  async deleteInterface(id: string): Promise<void> {
    return apiClient.delete<void>(`/interfaces/${id}`);
  }

  /**
   * Clone an interface
   */
  async cloneInterface(id: string): Promise<Interface> {
    return apiClient.post<Interface>(`/interfaces/${id}/clone`, {});
  }

  // ========================================
  // Interface Render
  // ========================================

  /**
   * Render an interface for a specific workflow run
   */
  async renderInterface(
    interfaceId: string,
    runId: string,
    options: { page?: number; size?: number; epoch?: number; variablePages?: Record<string, number> } = {}
  ): Promise<InterfaceRenderResult> {
    const { page = 0, size = 10, epoch, variablePages } = options;
    const params: Record<string, string | number> = { runId, page, size };
    if (epoch != null) params.epoch = epoch;
    if (variablePages && Object.keys(variablePages).length > 0) {
      params.variablePages = JSON.stringify(variablePages);
    }
    return apiClient.get<InterfaceRenderResult>(`/interfaces/${interfaceId}/render`, {
      params
    });
  }

  /**
   * Get interface snapshot for a specific run
   */
  async getInterfaceSnapshot(interfaceId: string, runId: string): Promise<InterfaceSnapshot> {
    return apiClient.get<InterfaceSnapshot>(`/interfaces/${interfaceId}/snapshot`, {
      params: { runId }
    });
  }

  /**
   * Get all interface snapshots for a run
   */
  async getInterfaceSnapshotsForRun(runId: string): Promise<InterfaceSnapshot[]> {
    return apiClient.get<InterfaceSnapshot[]>('/interfaces/snapshots', {
      params: { runId }
    });
  }

  /**
   * Get the count of items for an interface in a run
   */
  async getInterfaceItemsCount(interfaceId: string, runId: string): Promise<number> {
    return apiClient.get<number>(`/interfaces/${interfaceId}/items-count`, {
      params: { runId }
    });
  }

  /**
   * Get a single item's resolved data for an interface in a workflow run.
   */
  async getInterfaceItem(
    interfaceId: string,
    runId: string,
    itemIndex: number,
    epoch: number = 0
  ): Promise<{ epoch: number; itemIndex: number; data: Record<string, unknown> }> {
    return apiClient.get<{ epoch: number; itemIndex: number; data: Record<string, unknown> }>(
      `/interfaces/${interfaceId}/items/${itemIndex}`,
      { params: { runId, epoch } }
    );
  }

  /**
   * Get run-info metadata for an interface (template, config, resolved indices).
   * Returns everything the frontend needs to start rendering without item data.
   */
  async getInterfaceRunInfo(
    interfaceId: string,
    runId: string
  ): Promise<{
    htmlTemplate: string;
    cssTemplate?: string;
    jsTemplate?: string;
    totalItems: number;
    resolvedItemIndices: number[];
    epochTimestamps?: (string | null)[];
  }> {
    return apiClient.get(`/interfaces/${interfaceId}/run-info`, {
      params: { runId }
    });
  }

  /**
   * Render an interface using its connected datasource (not workflow run)
   */
  async renderInterfaceWithDatasource(
    interfaceId: string,
    options: { page?: number; size?: number } = {}
  ): Promise<InterfaceRenderResult> {
    const { page = 0, size = 0 } = options;
    return apiClient.get<InterfaceRenderResult>(`/interfaces/${interfaceId}/render-datasource`, {
      params: { page, size }
    });
  }

  /**
   * Get the count of items in the datasource connected to an interface
   */
  async getDatasourceItemsCount(interfaceId: string): Promise<number> {
    return apiClient.get<number>(`/interfaces/${interfaceId}/datasource-items-count`);
  }

  // ========================================
  // Interface Actions
  // ========================================

  /**
   * Fire an interface action without resolving the signal.
   * The interface stays active in AWAITING_SIGNAL state.
   */
  async fireInterfaceAction(
    runId: string,
    nodeId: string,
    actionKey: string,
    data: Record<string, unknown> = {},
    itemIndex?: number
  ): Promise<{ status: string; targetKey: string }> {
    return apiClient.post(`/v2/workflows/dag/runs/${runId}/interface-actions/${nodeId}/fire`, {
      actionKey,
      data,
      ...(itemIndex != null ? { itemId: String(itemIndex), itemIndex } : {}),
    });
  }

  /**
   * List pending interface signals for a workflow run.
   */
  async listInterfaceSignals(
    runId: string
  ): Promise<Array<{ id: string; nodeId: string; interfaceId: string; mode: string; actionMapping: Record<string, string> }>> {
    return apiClient.get(`/v2/workflows/dag/runs/${runId}/interface-actions`);
  }
}

export const interfaceService = new InterfaceService();
