/**
 * Storage API Service
 * Handles storage quota and usage operations.
 */

import { apiClient } from './api-client';
import { orgScopeRequestOptions } from '@/lib/stores/current-org-store';

export type QuotaStatus = 'OK' | 'SOFT_LIMIT_REACHED' | 'HARD_LIMIT_REACHED';

export interface StorageQuota {
  tenantId: string;
  usedBytes: number;
  maxBytes: number;
  softLimitBytes: number;
  hardLimitBytes: number;
  availableBytes: number;
  usagePercentage: number;
  status: QuotaStatus;
  /** Backend-driven unlimited flag (true in CE). Defaults to false when absent. */
  unlimited?: boolean;
}

export interface TenantStats {
  tenantId: string;
  workflowCount: number;
  interfaceCount: number;
  tableCount: number;
  agentCount: number;
}

// Storage Explorer types
export type SourceType = 'STEP_OUTPUT' | 'DECISION' | 'SIGNAL' | 'INTERFACE_ACTION' | 'CHAT_ATTACHMENT' | 'SKIPPED_NODE' | 'S3_FILE';
export type StorageType = 'JSON' | 'TEXT' | 'BINARY' | 'S3_FILE';

/**
 * One preview child of a folder for the iOS-style 3x3 tile. The frontend fetches inline bytes by
 * {@code id} for an image thumbnail, and uses {@code mimeType} / {@code fileName} to pick a
 * file-type icon for everything that is not an image (pdf, csv, archives, ...).
 */
export interface StoragePreviewFile {
  id: string;
  mimeType?: string | null;
  fileName?: string | null;
}

export interface StorageExplorerEntry {
  id: string;
  storageType: StorageType;
  sourceType: SourceType | null;
  fileName: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
  formattedSize: string;
  createdAt: string;
  workflowId: string | null;
  workflowName: string | null;
  projectId: string | null;
  runId: string | null;
  stepKey: string | null;
  epoch: number | null;
  s3Key: string | null;
  contentType: string | null;
  /**
   * V313 manual folders: true when this row is a folder (its name is in
   * {@code fileName}, no {@code s3Key}). Absent on legacy flat listings → defaults
   * to a falsy/file row when consumed.
   */
  isFolder?: boolean;
  /** Manual folder this row is filed under (null = top level). */
  parentFolderId?: string | null;
  /** FOLDER rows only - exact number of direct children (null/absent for files). */
  childCount?: number | null;
  /**
   * FOLDER rows only - up to 9 child files (any type, newest first), used to build
   * the iOS-style 3×3 folder tile: an image thumbnail for images, a file-type icon
   * for everything else (pdf, csv, ...). Null/absent for files.
   */
  previewFiles?: StoragePreviewFile[] | null;
  /**
   * Phase 2b VIRTUAL workflow folders only - the computed-grouping navigation key
   * ({@code "wf:<id>"}, {@code "wf:<id>/e<n>"}, {@code ".../s<n>"}, {@code ".../i<n>"}).
   * A virtual folder has {@code id: null} + {@code isFolder: true}; navigate into it
   * by this key (NOT its id). Real rows (files + V313 manual folders) leave this
   * null/absent - they navigate by {@code id}.
   */
  virtualId?: string | null;
  /**
   * VIRTUAL folders only - which level of the workflow → run → epoch → spawn → iteration
   * tree this grouping is. Null/absent on real rows. For a RUN folder, {@code epoch} carries
   * the 1-based run number (oldest-first) used for the "Run N" label.
   */
  virtualKind?: 'WORKFLOW' | 'RUN' | 'EPOCH' | 'SPAWN' | 'ITERATION' | null;
  /** VIRTUAL SPAWN/ITERATION folders - the 0-based spawn index. Null/absent otherwise. */
  spawn?: number | null;
  /** VIRTUAL ITERATION folders - the 0-based split iteration index. Null/absent otherwise. */
  itemIndex?: number | null;
}

export interface StorageExplorerParams {
  page?: number;
  size?: number;
  search?: string;
  sourceType?: string;
  storageType?: string;
  workflowId?: string;
  runId?: string;
  dateFrom?: string;
  dateTo?: string;
  /**
   * File-type category (images|pdf|documents|spreadsheets|presentations|video|
   * audio|archives|text|code). Filters server-side over the FULL DB set by
   * mime-type/extension - not a narrowing of the current page. Omit/'_all' = all.
   */
  fileType?: string;
  /**
   * When true, restrict the result to real files ({@code file_name IS NOT NULL}) -
   * the full-page Files browser sets this so machine JSON step-output blobs (no
   * file name) don't flood the view. Omitted/false keeps the legacy all-rows
   * behaviour used by the side-panel explorer.
   */
  filesOnly?: boolean;
  /**
   * When true, restrict to real object-storage files ({@code s3_key IS NOT NULL}).
   * Only the full-page Files browser sets this - it hides DB-resident pseudo-files
   * (agent observability TEXT blobs like `tool_call_result.txt`, BINARY chat
   * attachments/avatars). Stronger than {@code filesOnly}; the two compose.
   */
  s3Only?: boolean;
  /**
   * V313 folder-aware listing. {@code 'root'} lists top-level folders then loose
   * files; a folder UUID lists that folder's direct children (folders first, then
   * files). OMITTING the param keeps the legacy flat listing (side-panel explorer).
   */
  parentFolderId?: string;
  /**
   * Phase 2b: opt into the VIRTUAL workflow folder tree. When true, a root listing
   * also returns computed workflow-grouping folders (workflow → epoch → spawn →
   * iteration), and a {@code parentFolderId} that is a virtual key ({@code "wf:…"})
   * lists that grouping's children. Only the full-page Files browser sets it.
   */
  virtualWorkflowFolders?: boolean;
}

/**
 * Single source of truth for every FILE-SELECTION / file-config surface - the
 * Files page (app/file), the workflow Data Input picker, the agent file
 * allow-list, the project file picker, and the org member file-ACL. They must
 * all list the SAME set the user thinks of as "their files": real object-storage
 * uploads only. Spread this into a {@link StorageExplorerParams} request (or the
 * {@link useStorageExplorer} options) instead of re-specifying the flags inline,
 * so a future change to what counts as a "file" lands in ONE place.
 *
 * - {@code filesOnly}: drop machine step-output JSON blobs (no file_name).
 * - {@code s3Only}: drop DB-resident pseudo-files (agent observability TEXT blobs
 *   like tool_call_result.txt, BINARY chat attachments, avatars) - keep only rows
 *   physically in object storage (s3_key IS NOT NULL). Stronger than filesOnly;
 *   the two compose.
 */
export const S3_FILES_FILTER = { filesOnly: true, s3Only: true } as const;

export interface StorageExplorerPage {
  content: StorageExplorerEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface StoragePreview {
  id: string;
  storageType: string;
  sourceType: string | null;
  sizeBytes: number | null;
  skeleton?: string;
  text?: string;
  downloadUrl?: string;
  s3Key?: string;
  fileName?: string;
  mimeType?: string;
}

export interface StorageExplorerStat {
  sourceType: string;
  storageType: string;
  count: number;
  totalBytes: number;
}

export interface StorageBreakdown {
  category: string;
  usedBytes: number;
  itemCount: number;
  calculatedAt: string;
}

export type StorageCategory =
  | 'STEP_OUTPUTS'
  | 'FILES'
  | 'EXECUTION_DATA'
  | 'AGENTS'
  | 'INTERFACES'
  | 'CONVERSATIONS'
  | 'CONFIGURATION'
  | 'DATATABLES'
  | 'PUBLICATIONS';

export const STORAGE_CATEGORY_COLORS: Record<StorageCategory, string> = {
  STEP_OUTPUTS: 'bg-blue-500',
  FILES: 'bg-green-500',
  EXECUTION_DATA: 'bg-purple-500',
  AGENTS: 'bg-orange-500',
  INTERFACES: 'bg-pink-500',
  CONVERSATIONS: 'bg-cyan-500',
  CONFIGURATION: 'bg-gray-500',
  DATATABLES: 'bg-yellow-500',
  PUBLICATIONS: 'bg-indigo-500',
};

/** Hex colors for Recharts (light / dark theme) */
export const STORAGE_CATEGORY_HEX: Record<StorageCategory, { light: string; dark: string }> = {
  STEP_OUTPUTS:    { light: '#3b82f6', dark: '#60a5fa' },
  FILES:           { light: '#22c55e', dark: '#4ade80' },
  EXECUTION_DATA:  { light: '#a855f7', dark: '#c084fc' },
  AGENTS:          { light: '#f97316', dark: '#fb923c' },
  INTERFACES:      { light: '#ec4899', dark: '#f472b6' },
  CONVERSATIONS:   { light: '#06b6d4', dark: '#22d3ee' },
  CONFIGURATION:   { light: '#6b7280', dark: '#9ca3af' },
  DATATABLES:      { light: '#eab308', dark: '#facc15' },
  PUBLICATIONS:    { light: '#6366f1', dark: '#818cf8' },
};

export interface StorageHistoryPoint {
  snapshotDate: string;
  category: string;
  usedBytes: number;
  itemCount: number;
}

class StorageApiService {
  /**
   * Get storage quota information for the current tenant.
   *
   * @param orgId optional workspace override - scope the quota to a workspace
   *   OTHER than the globally-active one (Storage page workspace filter). Omit to
   *   use the active workspace. See {@link orgScopeRequestOptions}.
   */
  async getQuota(orgId?: string | null): Promise<StorageQuota> {
    const opts = orgScopeRequestOptions(orgId);
    return opts
      ? await apiClient.get<StorageQuota>('/storage/quota', opts)
      : await apiClient.get<StorageQuota>('/storage/quota');
  }

  /**
   * Force full reconciliation of storage usage.
   * Recalculates all categories from scratch.
   *
   * @param orgId optional workspace override (see {@link getQuota}).
   */
  async recalculateUsage(orgId?: string | null): Promise<StorageQuota> {
    return await apiClient.post<StorageQuota>('/storage/quota/recalculate', {}, orgScopeRequestOptions(orgId));
  }

  /**
   * Get per-category storage breakdown.
   *
   * @param orgId optional workspace override (see {@link getQuota}).
   */
  async getBreakdown(orgId?: string | null): Promise<StorageBreakdown[]> {
    const opts = orgScopeRequestOptions(orgId);
    return opts
      ? await apiClient.get<StorageBreakdown[]>('/storage/quota/breakdown', opts)
      : await apiClient.get<StorageBreakdown[]>('/storage/quota/breakdown');
  }

  /**
   * Get storage usage history for trend charts.
   *
   * @param orgId optional workspace override (see {@link getQuota}).
   */
  async getHistory(days: number = 30, orgId?: string | null): Promise<StorageHistoryPoint[]> {
    return await apiClient.get<StorageHistoryPoint[]>('/storage/quota/history', {
      params: { days: String(days) },
      ...(orgScopeRequestOptions(orgId) ?? {}),
    });
  }

  /**
   * Get tenant statistics (workflow, interface, table, agent counts).
   *
   * @param orgId optional workspace override (see {@link getQuota}).
   */
  async getStats(orgId?: string | null): Promise<TenantStats> {
    const opts = orgScopeRequestOptions(orgId);
    return opts
      ? await apiClient.get<TenantStats>('/stats', opts)
      : await apiClient.get<TenantStats>('/stats');
  }

  // ========== Storage Explorer ==========

  /**
   * Search storage entries with filtering and pagination.
   */
  async getExplorerEntries(params: StorageExplorerParams = {}): Promise<StorageExplorerPage> {
    const queryParams: Record<string, string> = {};
    if (params.page !== undefined) queryParams.page = String(params.page);
    if (params.size !== undefined) queryParams.size = String(params.size);
    if (params.search) queryParams.search = params.search;
    if (params.sourceType) queryParams.sourceType = params.sourceType;
    if (params.storageType) queryParams.storageType = params.storageType;
    if (params.workflowId) queryParams.workflowId = params.workflowId;
    if (params.runId) queryParams.runId = params.runId;
    if (params.dateFrom) queryParams.dateFrom = params.dateFrom;
    if (params.dateTo) queryParams.dateTo = params.dateTo;
    if (params.fileType && params.fileType !== '_all') queryParams.fileType = params.fileType;
    if (params.filesOnly) queryParams.filesOnly = 'true';
    if (params.s3Only) queryParams.s3Only = 'true';
    // V313: opt into folder-aware listing. Sending the param ('root', a folder
    // UUID, or a Phase 2b virtual key 'wf:…') switches the backend out of the
    // legacy flat listing.
    if (params.parentFolderId) queryParams.parentFolderId = params.parentFolderId;
    // Phase 2b: also surface the computed virtual workflow folder tree at root and
    // navigate into it via the 'wf:…' virtual keys.
    if (params.virtualWorkflowFolders) queryParams.virtualWorkflowFolders = 'true';

    return await apiClient.get<StorageExplorerPage>('/storage/explorer', {
      params: queryParams,
    });
  }

  /**
   * Get preview for a storage entry.
   */
  async getEntryPreview(id: string): Promise<StoragePreview> {
    return await apiClient.get<StoragePreview>(`/storage/explorer/${id}/preview`);
  }

  /**
   * Resolve display names for a batch of storage entry IDs in ONE request
   * ({@code {id -> fileName}}). Only accessible, named entries are returned;
   * unknown/inaccessible IDs are omitted (caller falls back to an ID-slice).
   * Replaces N per-file {@link #getEntryPreview} calls when only names are needed
   * (e.g. the Agent Fleet canvas resolving labels for files attached to agents).
   */
  async getEntryNames(ids: string[]): Promise<Record<string, string>> {
    if (ids.length === 0) return {};
    return await apiClient.post<Record<string, string>>(
      '/storage/explorer/names',
      { ids },
    );
  }

  /**
   * Get aggregate stats for the Storage Explorer.
   */
  async getExplorerStats(): Promise<StorageExplorerStat[]> {
    return await apiClient.get<StorageExplorerStat[]>('/storage/explorer/stats');
  }

  /**
   * Create a manual folder (V313). {@code parentFolderId} null = top level;
   * otherwise the new folder is nested under that folder. Returns the created
   * folder row (note the response uses {@code name}, not {@code fileName}).
   */
  async createFolder(
    name: string,
    parentFolderId: string | null,
  ): Promise<{ id: string; name: string; isFolder: true; parentFolderId: string | null }> {
    return await apiClient.post<{ id: string; name: string; isFolder: true; parentFolderId: string | null }>(
      '/storage/explorer/folders',
      { name, parentFolderId },
    );
  }

  /**
   * Every manual folder in the active workspace (flat list) for the Files
   * "Move to…" tree picker, which builds the folder arborescence client-side from
   * {@code parentFolderId} (null/absent = top level). Member-restricted folders are
   * excluded server-side. Use {@code moveEntries} to commit the chosen destination.
   */
  async getAllFolders(): Promise<{ id: string; name: string; parentFolderId: string | null }[]> {
    return await apiClient.get<{ id: string; name: string; parentFolderId: string | null }[]>(
      '/storage/explorer/folders',
    );
  }

  /**
   * Move storage rows into a folder (V313). {@code parentFolderId} null = move to
   * root. Per-id; returns {@code movedCount} and a {@code failed} list (e.g. a
   * cycle, a restricted row, or a bad id) - a partial failure is not an error.
   */
  async moveEntries(
    ids: string[],
    parentFolderId: string | null,
  ): Promise<{ movedCount: number; failed: { id: string; reason: string }[] }> {
    return await apiClient.post<{ movedCount: number; failed: { id: string; reason: string }[] }>(
      '/storage/explorer/move',
      { ids, parentFolderId },
    );
  }

  /**
   * Delete a single storage entry by ID. For a FOLDER the server re-parents its
   * children up one level (never cascade-deletes files).
   */
  async deleteEntry(id: string): Promise<void> {
    await apiClient.delete(`/storage/explorer/${id}`);
  }

  /**
   * Rename a storage entry's display name. Only `file_name` changes - the s3
   * object key is immutable, so the blob and existing by-id URLs are untouched.
   */
  async renameEntry(id: string, fileName: string): Promise<{ id: string; fileName: string }> {
    return await apiClient.put<{ id: string; fileName: string }>(
      `/storage/explorer/${id}/rename`,
      { fileName }
    );
  }

  /**
   * Delete all S3 files within a date range (batch by day).
   */
  async deleteEntriesByDateRange(dateFrom: string, dateTo: string): Promise<{ deletedCount: number }> {
    return await apiClient.post<{ deletedCount: number }>(
      '/storage/explorer/batch-delete-by-date',
      { dateFrom, dateTo }
    );
  }

  /**
   * Delete multiple storage entries by IDs (batch).
   */
  async deleteEntries(ids: string[]): Promise<{ deletedCount: number; requestedCount: number }> {
    return await apiClient.post<{ deletedCount: number; requestedCount: number }>(
      '/storage/explorer/batch-delete',
      { ids }
    );
  }

  /**
   * Delete a VIRTUAL workflow folder (a computed grouping with no real id, e.g. `wf:<id>` or a
   * deeper `wf:<id>/e<n>/...`). Removes every file the folder groups - the server scopes the delete
   * to the workflow/epoch/spawn/iteration coordinates in the ref. Files moved into a manual folder
   * are preserved. Pass the folder's `virtualId` as `folderRef`.
   */
  async deleteVirtualFolder(folderRef: string): Promise<{ deletedCount: number }> {
    return await apiClient.post<{ deletedCount: number }>(
      '/storage/explorer/batch-delete-by-virtual',
      { folderRef }
    );
  }
}

export const storageApi = new StorageApiService();
