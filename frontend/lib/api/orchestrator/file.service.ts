/**
 * File Storage Service
 * Handles file downloads and URL generation for files stored in S3/MinIO
 */

import { apiClient } from '../api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

/**
 * FileRef represents a reference to a file stored in S3/MinIO.
 * This is the structure returned by workflow nodes when they produce files.
 */
export interface FileRef {
  _type: 'file';
  path: string;
  name: string;
  mimeType: string;
  size: number;
  /** storage.storage row UUID - the opaque handle for /api/proxy/files/by-id/{id}/raw.
   *  Present on files produced after the opaque-URL cutover; absent on legacy refs. */
  id?: string;
}

/**
 * Type guard to check if an object is a FileRef.
 * Accepts:
 *  - canonical format: { _type:'file', path, name, mimeType, size }
 *  - legacy 'key' instead of 'path'
 *  - DB/flattened format from DownloadFileOutputSchemaMapper: { file_url, file_name, file_size, content_type }
 *    Only matched when the object has no envelope fields (_status, _duration_ms) to avoid
 *    treating an entire step output as a FileRef.
 */
export function isFileRef(obj: unknown): obj is FileRef {
  if (!obj || typeof obj !== 'object') return false;
  const candidate = obj as Record<string, unknown>;

  // Canonical format (with _type:'file')
  const hasPathOrKey = typeof candidate.path === 'string' || typeof candidate.key === 'string';
  if (
    candidate._type === 'file' &&
    hasPathOrKey &&
    typeof candidate.name === 'string' &&
    typeof candidate.mimeType === 'string' &&
    typeof candidate.size === 'number'
  ) {
    return true;
  }

  // DB/flattened format (file_url, file_name, file_size, content_type)
  // Reject if envelope fields are present - means this is a full step output, not a standalone FileRef
  if (
    typeof candidate.file_url === 'string' &&
    typeof candidate.file_name === 'string' &&
    typeof candidate.content_type === 'string' &&
    typeof candidate.file_size === 'number' &&
    !('_status' in candidate)
  ) {
    return true;
  }

  return false;
}

/**
 * Normalize a FileRef-like object to canonical FileRef shape.
 * Handles canonical, legacy 'key', and DB/flattened formats.
 */
export function normalizeFileRef(obj: FileRef): FileRef {
  const raw = obj as any;

  // DB/flattened format - extract storage key + the opaque id from file_url
  if (raw.file_url && !raw._type) {
    let storagePath = '';
    // id survives every normalization hop: prefer an explicit id, else recover it from an opaque
    // /api/proxy/files/by-id/{id}/raw file_url so the canonical ref can still build the by-id URL.
    let fileId: string | undefined = raw.id ?? raw.file_id;
    try {
      const url = new URL(raw.file_url, 'http://localhost');
      storagePath = url.searchParams.get('key') || '';
      if (!fileId) {
        const m = url.pathname.match(/\/files\/by-id\/([^/]+)\/raw/);
        if (m) fileId = decodeURIComponent(m[1]);
      }
    } catch {
      storagePath = raw.file_url;
    }
    return {
      _type: 'file',
      path: storagePath,
      name: raw.file_name || '',
      mimeType: raw.content_type || 'application/octet-stream',
      size: raw.file_size ?? 0,
      ...(fileId ? { id: fileId } : {}),
    };
  }

  return obj;
}

/**
 * Get the file path from a FileRef (supports both 'path' and legacy 'key')
 */
export function getFilePath(fileRef: FileRef): string {
  const normalized = normalizeFileRef(fileRef);
  return normalized.path || (normalized as any).key || '';
}

/**
 * Opaque, id-based file URL - addresses the file by its {@code storage.storage} row UUID, never by
 * the s3 key, so the tenant id never appears in the URL. Carries NO credential: the URL is meant to
 * be fetched with the {@code Authorization: Bearer} header (see {@code useAuthedObjectUrl} /
 * {@code fetchAuthedBlobUrl}) and rendered from an in-memory {@code blob:} URL - the session token is
 * NEVER stamped into the URL itself (it is a long-lived, full-scope bearer that would leak via
 * copy/paste, CDN / proxy / analytics logs, or browser history). Prefer {@link fileRefToUrl} when you
 * hold a {@link FileRef}.
 */
export function getFileUrlById(fileId: string, options?: { inline?: boolean }): string {
  const url = `/api/proxy/files/by-id/${encodeURIComponent(fileId)}/raw`;
  return url + (options?.inline ? '?disposition=inline' : '?disposition=attachment');
}

/**
 * Anonymous avatar URL - the ONE file class servable without auth, because avatars must render
 * from a plain {@code <img>} for viewers who are not the uploader (marketplace cards, shared
 * applications, widget embeds). The backend only resolves files uploaded through the generic
 * {@code avatar} category with an image mime; anything else 404s. Store THIS URL as an agent's
 * {@code avatarUrl} after an avatar upload - never {@link getFileUrlById}, which requires the
 * bearer header an {@code <img>} cannot send.
 */
export function getPublicAvatarUrlById(fileId: string): string {
  return `/api/proxy/files/avatar/${encodeURIComponent(fileId)}`;
}

/**
 * Build the opaque, id-based URL for a {@link FileRef} - the canonical way to address a file in the
 * frontend after the opaque-URL cutover. Uses the FileRef's storage UUID ({@code id}); a legacy ref
 * without an id cannot be rendered (returns '' - re-run/republish to regenerate it with an id). The
 * URL carries no credential - render it with {@code useAuthedObjectUrl} (header-authenticated blob).
 */
export function fileRefToUrl(ref: { id?: string }, options?: { inline?: boolean }): string {
  return ref?.id ? getFileUrlById(ref.id, options) : '';
}

/**
 * Check if a FileRef is an image
 */
export function isImageFile(fileRef: FileRef): boolean {
  return fileRef.mimeType.startsWith('image/');
}

export function isAudioFile(fileRef: FileRef): boolean {
  return fileRef.mimeType.startsWith('audio/');
}

export function isVideoFile(fileRef: FileRef): boolean {
  return fileRef.mimeType.startsWith('video/');
}

/**
 * Recursively finds all FileRef objects in a data structure
 */
export function findFileRefs(data: unknown, path: string[] = []): Array<{ path: string[]; fileRef: FileRef }> {
  const results: Array<{ path: string[]; fileRef: FileRef }> = [];

  if (isFileRef(data)) {
    results.push({ path, fileRef: data });
    return results;
  }

  if (Array.isArray(data)) {
    data.forEach((item, index) => {
      results.push(...findFileRefs(item, [...path, String(index)]));
    });
    return results;
  }

  if (data && typeof data === 'object') {
    Object.entries(data).forEach(([key, value]) => {
      results.push(...findFileRefs(value, [...path, key]));
    });
  }

  return results;
}

/**
 * Represents a file upload in progress for UI tracking.
 */
export interface PendingFileUpload {
  fieldName: string;
  file: File;
  status: 'pending' | 'uploading' | 'success' | 'error';
  fileRef?: FileRef;
  error?: string;
}

/**
 * Response from generic-upload endpoint (no workflow context).
 */
export interface GenericUploadResponse {
  /** Opaque, id-based URL ({@link getFileUrlById}) - no tenant id, no s3 key. */
  url: string;
  /** {@code storage.storage} row UUID - the opaque handle used to build file URLs. */
  id: string;
  storageKey: string;
  fileName: string;
  mimeType: string;
  size: number;
}

class FileService {
  /**
   * Uploads a file to S3/MinIO via the backend and returns a FileRef.
   * Used by form triggers, chat triggers, and interface nodes to convert
   * non-serializable File objects into JSON-serializable FileRef objects.
   *
   * Uploads through the Next.js proxy which streams multipart to the Gateway.
   */
  async uploadFile(
    file: File,
    context: { workflowId: string; runId: string; stepAlias?: string }
  ): Promise<FileRef> {
    const tokenProvider = apiClient.getTokenProvider();
    const token = tokenProvider ? await tokenProvider() : null;
    if (!token) throw new Error('Authentication required');

    const formData = new FormData();
    formData.append('file', file);
    formData.append('workflowId', context.workflowId);
    formData.append('runId', context.runId);
    if (context.stepAlias) formData.append('stepAlias', context.stepAlias);

    // Upload through Next.js proxy (multipart is streamed through)
    const response = await fetch('/api/proxy/files/upload', {
      method: 'POST',
      // Audit 2026-05-17 round-5 - attach X-Active-Organization-ID so upload
      // routes into the caller's active workspace, not their default org.
      headers: { 'Authorization': `Bearer ${token}`, ...getActiveOrgHeaderForRequest() },
      body: formData,
    });

    if (!response.ok) {
      const errorBody = await response.text().catch(() => response.statusText);
      throw new Error(`Upload failed: ${errorBody}`);
    }
    return response.json();
  }

  /**
   * Generic upload without workflow context.
   * Used for DataTable files, avatars, chat attachments, etc.
   * Files are stored in S3 under {tenantId}/general/{category}/
   */
  async uploadGeneric(
    file: File,
    category: string = 'general',
    parentFolderId?: string | null,
  ): Promise<GenericUploadResponse> {
    const tokenProvider = apiClient.getTokenProvider();
    const token = tokenProvider ? await tokenProvider() : null;
    if (!token) throw new Error('Authentication required');

    const formData = new FormData();
    formData.append('file', file);
    formData.append('category', category);
    // V313 folder-aware upload: land the file in the caller's current manual folder
    // (the backend validates it's a folder in the workspace and drops to root if not).
    if (parentFolderId) formData.append('parentFolderId', parentFolderId);

    const response = await fetch('/api/proxy/files/generic-upload', {
      method: 'POST',
      // Audit 2026-05-17 round-5 - attach X-Active-Organization-ID so upload
      // routes into the caller's active workspace, not their default org.
      headers: { 'Authorization': `Bearer ${token}`, ...getActiveOrgHeaderForRequest() },
      body: formData,
    });

    if (!response.ok) {
      const errorBody = await response.text().catch(() => response.statusText);
      throw new Error(`Upload failed: ${errorBody}`);
    }
    return response.json();
  }

  /**
   * Downloads a file by its opaque {@code storage.storage} row id (no tenant id / s3 key).
   */
  private async downloadById(id: string): Promise<Blob> {
    const token = await apiClient.getTokenProvider()?.();
    if (!token) {
      throw new Error('Authentication required');
    }

    const response = await fetch(`/api/proxy/files/by-id/${encodeURIComponent(id)}/raw?disposition=attachment`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`,
        ...getActiveOrgHeaderForRequest(),
      }
    });

    if (!response.ok) {
      throw new Error(`Download failed: ${response.statusText}`);
    }

    return response.blob();
  }

  /**
   * Downloads a file and triggers a browser save. Addresses the file by its opaque
   * storage id ({@code ref.id}); a legacy ref without an id cannot be downloaded
   * (re-run/republish to regenerate it).
   */
  async downloadAndSave(ref: { id?: string; path?: string; name?: string }, filename?: string): Promise<void> {
    if (!ref?.id) {
      throw new Error('File has no storage id - re-run or republish to regenerate it');
    }
    const blob = await this.downloadById(ref.id);
    const url = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = url;
    link.download = filename || ref.name || this.extractFilename(ref.path || '');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    URL.revokeObjectURL(url);
  }

  /**
   * Extract filename from storage path
   * Path format: {tenant}/{workflow}/{run}/{step}/{uuid}_{filename}
   */
  private extractFilename(path: string): string {
    const parts = path.split('/');
    const lastPart = parts[parts.length - 1];

    // Remove UUID prefix (format: uuid_filename)
    const underscoreIndex = lastPart.indexOf('_');
    if (underscoreIndex > 0 && underscoreIndex < 10) {
      return lastPart.substring(underscoreIndex + 1);
    }

    return lastPart || 'download';
  }

  /**
   * Format file size for display
   */
  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';

    const units = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    const size = bytes / Math.pow(1024, i);

    return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
  }

  /**
   * Get file icon based on MIME type
   */
  getFileIcon(mimeType: string): 'image' | 'video' | 'audio' | 'document' | 'file' {
    if (mimeType.startsWith('image/')) return 'image';
    if (mimeType.startsWith('video/')) return 'video';
    if (mimeType.startsWith('audio/')) return 'audio';
    if (mimeType.includes('pdf') || mimeType.includes('document') || mimeType.includes('spreadsheet')) {
      return 'document';
    }
    return 'file';
  }
}

export const fileService = new FileService();
