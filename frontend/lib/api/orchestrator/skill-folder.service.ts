/**
 * Skill Folder Service
 *
 * Handles skill folder CRUD operations for hierarchical skill organization.
 * Single Responsibility: Only skill folder-related operations.
 */

import { apiClient } from '../api-client';
import type { SkillFolder, SkillFolderContents } from './types';

export class SkillFolderService {
  /**
   * Get all folders for current user (flat list)
   */
  async getAllFolders(): Promise<SkillFolder[]> {
    return apiClient.get<SkillFolder[]>('/skill-folders');
  }

  /**
   * Create a new folder
   */
  async createFolder(name: string, parentId?: string | null): Promise<SkillFolder> {
    return apiClient.post<SkillFolder>('/skill-folders', { name, parentId: parentId || null });
  }

  /**
   * Rename a folder
   */
  async renameFolder(id: string, name: string): Promise<SkillFolder> {
    return apiClient.put<SkillFolder>(`/skill-folders/${id}`, { name });
  }

  /**
   * Delete a folder (subfolders cascade, skills move to root)
   */
  async deleteFolder(id: string): Promise<void> {
    return apiClient.delete<void>(`/skill-folders/${id}`);
  }

  /**
   * Move a folder to a new parent (null = root)
   */
  async moveFolder(id: string, parentId: string | null): Promise<SkillFolder> {
    return apiClient.put<SkillFolder>(`/skill-folders/${id}/move`, { parentId });
  }

  /**
   * Get contents of a folder (subfolders + skills)
   */
  async getFolderContents(folderId: string): Promise<SkillFolderContents> {
    return apiClient.get<SkillFolderContents>(`/skill-folders/${folderId}/contents`);
  }

  /**
   * Get root contents (folders and skills with no parent folder)
   */
  async getRootContents(): Promise<SkillFolderContents> {
    return apiClient.get<SkillFolderContents>('/skill-folders/root/contents');
  }
}

export const skillFolderService = new SkillFolderService();
