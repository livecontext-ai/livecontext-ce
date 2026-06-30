/**
 * API service for chat message attachments.
 * Utility functions for attachment type detection, validation, and URL generation.
 * Uploads go through conversation-service AttachmentController (returns UUID storageId).
 */

import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgIdForRequest } from '@/lib/stores/current-org-store';

/**
 * Supported attachment types
 */
export type AttachmentType = 'IMAGE' | 'PDF' | 'TEXT' | 'OTHER';

/**
 * Reference to an uploaded attachment (for sending with messages)
 */
export interface AttachmentRef {
  storageId: string;
  type: AttachmentType;
  fileName: string;
  mimeType: string;
}

/**
 * Pending attachment state (for UI)
 * - 'pending': File selected but not yet uploaded (waiting for user to send)
 * - 'uploading': Upload in progress
 * - 'success': Upload completed successfully
 * - 'error': Upload failed
 */
export interface PendingAttachment {
  id: string;
  file: File;
  storageId?: string;
  uploadStatus: 'pending' | 'uploading' | 'success' | 'error';
  errorMessage?: string;
  preview?: string;
  type: AttachmentType;
  mimeType: string;
  sizeBytes: number;
}

// Allowed MIME types by category
const ALLOWED_IMAGE_TYPES = new Set([
  'image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/webp'
]);

const ALLOWED_PDF_TYPES = new Set([
  'application/pdf'
]);

const ALLOWED_TEXT_TYPES = new Set([
  'text/plain', 'text/markdown', 'text/csv', 'text/html',
  'application/json', 'application/xml',
  'text/javascript', 'text/css'
]);

// Max file size (50 MB)
const MAX_FILE_SIZE = 50 * 1024 * 1024;

/**
 * Attachment API service
 */
class AttachmentApiService {

  /**
   * Upload a file attachment via conversation-service.
   * Returns a UUID-based storageId (not an S3 key).
   *
   * @param file The file to upload
   * @returns Upload response with UUID storageId
   */
  async uploadAttachment(file: File): Promise<{ storageId: string; type: string; fileName: string; mimeType: string; sizeBytes: number }> {
    this.validateFile(file);

    const formData = new FormData();
    formData.append('file', file);

    const tokenProvider = apiClient.getTokenProvider();
    if (!tokenProvider) throw new Error('No token provider');
    const token = await tokenProvider();

    // Audit 2026-05-17 round-3 - raw fetch must carry X-Active-Organization-ID.
    const uploadHeaders: Record<string, string> = {
      'Authorization': `Bearer ${token}`,
    };
    const activeOrgId = getActiveOrgIdForRequest();
    if (activeOrgId) uploadHeaders['X-Active-Organization-ID'] = activeOrgId;

    const response = await fetch('/api/proxy/v3/chat/attachments', {
      method: 'POST',
      headers: uploadHeaders,
      body: formData,
    });

    if (!response.ok) {
      const errorBody = await response.json().catch(() => ({ error: 'Upload failed' }));
      throw new Error(errorBody.error || `Upload failed with status ${response.status}`);
    }

    return response.json();
  }

  /**
   * Get the URL to download/display an attachment.
   *
   * @param storageId The UUID storage ID from conversation-service
   * @returns URL string for the attachment
   */
  getDownloadUrl(storageId: string): string {
    return `/api/proxy/v3/chat/attachments/${encodeURIComponent(storageId)}`;
  }

  /**
   * Determine attachment type from MIME type.
   *
   * @param mimeType The MIME type of the file
   * @returns The attachment type classification
   */
  determineType(mimeType: string): AttachmentType {
    if (ALLOWED_IMAGE_TYPES.has(mimeType)) return 'IMAGE';
    if (ALLOWED_PDF_TYPES.has(mimeType)) return 'PDF';
    if (ALLOWED_TEXT_TYPES.has(mimeType)) return 'TEXT';
    return 'OTHER';
  }

  /**
   * Check if a file type is allowed.
   *
   * @param mimeType The MIME type to check
   * @returns true if the file type is supported
   */
  isAllowedType(mimeType: string): boolean {
    return ALLOWED_IMAGE_TYPES.has(mimeType) ||
           ALLOWED_PDF_TYPES.has(mimeType) ||
           ALLOWED_TEXT_TYPES.has(mimeType);
  }

  /**
   * Check if a file is an image.
   *
   * @param mimeType The MIME type to check
   * @returns true if the file is an image
   */
  isImage(mimeType: string): boolean {
    return ALLOWED_IMAGE_TYPES.has(mimeType);
  }

  /**
   * Validate a file before upload.
   *
   * @param file The file to validate
   * @throws Error if validation fails
   */
  private validateFile(file: File): void {
    if (!file || file.size === 0) {
      throw new Error('File is empty or missing');
    }

    if (file.size > MAX_FILE_SIZE) {
      throw new Error(`File exceeds maximum size of ${MAX_FILE_SIZE / (1024 * 1024)} MB`);
    }

    if (!this.isAllowedType(file.type)) {
      throw new Error(`File type not supported: ${file.type}`);
    }
  }

  /**
   * Create a preview URL for an image file.
   *
   * @param file The image file
   * @returns Object URL for preview (remember to revoke when done)
   */
  createPreviewUrl(file: File): string | undefined {
    if (this.isImage(file.type)) {
      return URL.createObjectURL(file);
    }
    return undefined;
  }

  /**
   * Revoke a preview URL to free memory.
   *
   * @param url The preview URL to revoke
   */
  revokePreviewUrl(url: string): void {
    URL.revokeObjectURL(url);
  }

  /**
   * Convert PendingAttachments to AttachmentRefs for sending.
   *
   * @param attachments List of pending attachments
   * @returns List of attachment references (only successfully uploaded)
   */
  toAttachmentRefs(attachments: PendingAttachment[]): AttachmentRef[] {
    return attachments
      .filter(a => a.uploadStatus === 'success' && a.storageId)
      .map(a => ({
        storageId: a.storageId!,
        type: a.type,
        fileName: a.file.name,
        mimeType: a.mimeType
      }));
  }

  /**
   * Format file size for display.
   *
   * @param bytes Size in bytes
   * @returns Formatted size string (e.g., "1.5 MB")
   */
  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}

export const attachmentApi = new AttachmentApiService();
