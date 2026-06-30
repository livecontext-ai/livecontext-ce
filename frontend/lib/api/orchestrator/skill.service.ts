/**
 * Skill Service
 *
 * Handles skill CRUD operations and agent-skill assignments.
 * Single Responsibility: Only skill-related operations.
 */

import { apiClient } from '../api-client';
import type { Skill, AgentSkill, SkillFolder } from './types';

export interface SkillSummary {
  id: string;
  name: string;
  description: string;
  folderId: string | null;
  isActive: boolean;
  isDefaultActive: boolean;
}

export interface SkillFolderSummary {
  id: string;
  name: string;
  parentId: string | null;
  isGlobal: boolean;
}

export interface SkillsSummary {
  skills: SkillSummary[];
  folders: SkillFolderSummary[];
}

// ── Skill bundles (cloud → CE distribution, V374) ──────────────────────────
export interface SkillBundleSummary {
  id: number;
  version: number;
  schemaVersion: number;
  checksum: string;
  signingKeyId: string;
  issuer: string;
  skillCount: number;
  rawBytesSize: number;
  isActive: boolean;
  importedAt: string;
  activatedAt: string | null;
}

export interface SkillBundleListResponse {
  bundles: SkillBundleSummary[];
  signingKeyId: string | null;
  publicKeyBase64: string | null;
}

export interface SkillBundleSyncStatus {
  lastAppliedVersion: number | null;
  lastAppliedAt: string | null;
  lastFetchAt: string | null;
  lastFetchStatus: string | null;
  lastFetchError: string | null;
  consecutiveFailures: number;
  updatedAt: string | null;
  schedulerEnabled: boolean;
}

export class SkillService {
  /**
   * Get all skills for current user
   */
  async getSkills(): Promise<Skill[]> {
    return apiClient.get<Skill[]>('/skills');
  }

  /**
   * Get a single skill by ID
   */
  async getSkill(id: string): Promise<Skill> {
    return apiClient.get<Skill>(`/skills/${id}`);
  }

  /**
   * Create a new skill
   */
  async createSkill(skill: Partial<Skill>): Promise<Skill> {
    return apiClient.post<Skill>('/skills', skill);
  }

  /**
   * Update a skill
   */
  async updateSkill(id: string, skill: Partial<Skill>): Promise<Skill> {
    return apiClient.put<Skill>(`/skills/${id}`, skill);
  }

  /**
   * Delete a skill
   */
  async deleteSkill(id: string): Promise<void> {
    return apiClient.delete<void>(`/skills/${id}`);
  }

  /**
   * Move a skill to a folder (null = root)
   */
  async moveSkill(id: string, folderId: string | null): Promise<Skill> {
    return apiClient.put<Skill>(`/skills/${id}/move`, { folderId });
  }

  /**
   * Reset a default skill to its original content.
   * Only works for skills with a non-null defaultKey.
   */
  async resetSkill(id: string): Promise<Skill> {
    return apiClient.post<Skill>(`/skills/${id}/reset`, {});
  }

  // ============================================
  // Agent-Skill Assignments
  // ============================================

  /**
   * Get skills assigned to an agent (with full skill data)
   */
  async getAgentSkills(agentId: string): Promise<AgentSkill[]> {
    return apiClient.get<AgentSkill[]>(`/agents/${agentId}/skills`);
  }

  /**
   * Fleet batch - skill assignments for EVERY agent in the workspace in ONE call
   * (each row carries {@code agentId}). Replaces the per-agent getAgentSkills
   * fan-out the Agent Fleet canvas otherwise makes.
   */
  async getAllAgentSkills(): Promise<AgentSkill[]> {
    return apiClient.get<AgentSkill[]>('/agents/skills');
  }

  /**
   * Set/replace all skill assignments for an agent
   */
  async setAgentSkills(agentId: string, assignments: { skillId: string }[]): Promise<void> {
    return apiClient.put<void>(`/agents/${agentId}/skills`, assignments);
  }

  // ============================================
  // V275/V276 (2026-05-21) - per-user default-active toggle
  // ============================================

  /**
   * Set this user's per-skill override. The skill's owner/admin sets
   * {@code isDefaultActive} as the shared default; this method overrides that
   * default just for the calling user. Used when a user wants to opt out of
   * (or into) a global / org-shared skill without affecting teammates.
   */
  async setUserSkillActive(skillId: string, active: boolean): Promise<void> {
    return apiClient.put<void>(`/skills/${skillId}/user-active`, { active });
  }

  /**
   * Forget this user's override row so chat-time resolution falls back to
   * the skill's {@code isDefaultActive} flag. Idempotent.
   */
  async clearUserSkillActive(skillId: string): Promise<void> {
    return apiClient.delete<void>(`/skills/${skillId}/user-active`);
  }

  /**
   * Fetch every override the calling user has set, as a {skillId: active}
   * map. The SkillTab UI merges this with the skill list to render the
   * effective toggle state: `override ?? skill.isDefaultActive`.
   */
  async getMyOverrides(): Promise<Record<string, boolean>> {
    return apiClient.get<Record<string, boolean>>('/skills/me/overrides');
  }

  /**
   * Resolve the effective default-active skills for the current user/workspace.
   * This is the same source AgentContextBuilder uses when a general chat sends
   * no per-conversation override.
   */
  async getDefaultActiveSummary(): Promise<SkillsSummary> {
    return apiClient.get<SkillsSummary>('/skills/default-active/summary');
  }

  // ============================================
  // V275 (2026-05-21) - admin-only folder global toggle
  // ============================================

  /**
   * Toggle {@code isGlobal} on a folder. Backend gates on the ADMIN role and
   * returns 400 for non-admin callers; this method does not pre-check.
   */
  async setFolderGlobal(folderId: string, isGlobal: boolean): Promise<SkillFolder> {
    return apiClient.put<SkillFolder>(`/skill-folders/${folderId}/global`, { isGlobal });
  }

  // ============================================
  // V374 - Skill bundle distribution (cloud → CE)
  // ============================================

  /**
   * Cloud admin: list every built skill bundle (newest first) + the signing key.
   * Also reachable on CE (returns the locally-applied bundle history).
   */
  async listSkillBundles(): Promise<SkillBundleListResponse> {
    return apiClient.get<SkillBundleListResponse>('/model-config/skill-bundles');
  }

  /** Cloud admin: build a new (inactive) signed bundle from the current global skills. */
  async buildSkillBundle(): Promise<SkillBundleSummary> {
    return apiClient.post<SkillBundleSummary>('/model-config/skill-bundles', {});
  }

  /** Cloud admin: flip a bundle to active (deactivates the previous one). */
  async activateSkillBundle(id: number): Promise<SkillBundleSummary> {
    return apiClient.post<SkillBundleSummary>(`/model-config/skill-bundles/${id}/activate`, {});
  }

  /** CE admin: last fetch/apply outcome of the skill-bundle sync. */
  async getSkillBundleSyncStatus(): Promise<SkillBundleSyncStatus> {
    return apiClient.get<SkillBundleSyncStatus>('/model-config/skill-bundles/sync-status');
  }

  /** CE admin: force a sync tick now (instead of waiting for the cron). */
  async syncSkillBundlesNow(): Promise<SkillBundleSyncStatus> {
    return apiClient.post<SkillBundleSyncStatus>('/model-config/skill-bundles/sync-now', {});
  }
}

export const skillService = new SkillService();
