import { apiClient } from '@/lib/api/api-client';

/** Permission level for a member restriction. DENY = fully blocked; READ = read-only. */
export type RestrictionPermission = 'DENY' | 'READ';

export interface ResourceRestriction {
  id: number;
  organizationId: string;
  memberUserId: string;
  resourceType: string;
  resourceId: string;
  restrictedBy: string;
  /** "DENY" (default/legacy) or "READ" (read-only). */
  permission?: RestrictionPermission;
  createdAt: string;
}

export interface SetRestrictionsRequest {
  resourceType: string;
  restrictedResourceIds: string[];
  /** Optional per-resource level (resourceId -> 'DENY'|'READ'); omitted ids default to DENY. */
  permissions?: Record<string, RestrictionPermission>;
}

export interface RestrictRequest {
  resourceType: string;
  resourceId: string;
}

export class OrgAccessService {
  async getMemberRestrictions(orgId: string, userId: string): Promise<ResourceRestriction[]> {
    return apiClient.get<ResourceRestriction[]>(`/org-access/${orgId}/members/${userId}/restrictions`);
  }

  async setRestrictions(
    orgId: string,
    userId: string,
    resourceType: string,
    restrictedIds: string[],
    permissions?: Record<string, RestrictionPermission>,
  ): Promise<void> {
    await apiClient.put(`/org-access/${orgId}/members/${userId}/restrictions`, {
      resourceType,
      restrictedResourceIds: restrictedIds,
      ...(permissions ? { permissions } : {}),
    } as SetRestrictionsRequest);
  }

  async restrictResource(orgId: string, userId: string, resourceType: string, resourceId: string): Promise<void> {
    await apiClient.post(`/org-access/${orgId}/members/${userId}/restrict`, {
      resourceType,
      resourceId,
    } as RestrictRequest);
  }

  async grantResource(orgId: string, userId: string, resourceType: string, resourceId: string): Promise<void> {
    await apiClient.delete(`/org-access/${orgId}/members/${userId}/restrict/${resourceType}/${resourceId}`);
  }
}

export const orgAccessService = new OrgAccessService();
