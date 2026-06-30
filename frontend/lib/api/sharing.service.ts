import { apiClient } from '@/lib/api/api-client';

export interface SharedLink {
  id: string;
  token: string;
  resourceType: string;
  resourceToken: string;
  resourceId?: string;
  title?: string;
  description?: string;
  isActive: boolean;
  accessConfig?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  accessCount: number;
  lastAccessed?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * The public PATH for a shared link. EVERY resource type (FORM, CHAT, CONVERSATION,
 * APPLICATION) is reached through the unified `/s/{token}` resolver (ShareResolver),
 * which looks the token up in the share registry and dispatches by resourceType.
 *
 * A prior FORM special-case built `/f/{token}` - but `/f/` resolves a form's OWN `fm_`
 * token, NOT the `sl_` SHARE token a shared link carries, so a form shared link 404'd
 * ("form not found"). This is the single source of truth so the settings page and the
 * notification bell can never drift apart again.
 */
export function shareLinkPath(token: string): string {
  return `/s/${token}`;
}

/** Absolute public URL for a shared link (origin + {@link shareLinkPath}); SSR-safe. */
export function shareLinkUrl(token: string): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  return `${origin}${shareLinkPath(token)}`;
}

export interface SharedLinkUpdate {
  title?: string;
  description?: string;
  isActive?: boolean;
  accessConfig?: Record<string, unknown>;
}

export interface SharedLinkCreate {
  resourceType: string;
  resourceToken: string;
  resourceId?: string;
  title?: string;
  description?: string;
}

export interface SharedLinkConfig {
  maxPerUser: number;
  currentCount: number;
}

export interface SharedLinkCheckResult {
  link: SharedLink | null;
  config: SharedLinkConfig;
}

class SharingService {
  async getAll(resourceType?: string): Promise<SharedLink[]> {
    const params: Record<string, string> = {};
    if (resourceType) params.resourceType = resourceType;
    return apiClient.get<SharedLink[]>('/publications/shared-links', { params });
  }

  async create(data: SharedLinkCreate): Promise<SharedLink> {
    return apiClient.post<SharedLink>('/publications/shared-links', data);
  }

  async getById(id: string): Promise<SharedLink> {
    return apiClient.get<SharedLink>(`/publications/shared-links/${id}`);
  }

  async update(id: string, data: SharedLinkUpdate): Promise<SharedLink> {
    return apiClient.put<SharedLink>(`/publications/shared-links/${id}`, data);
  }

  async delete(id: string): Promise<void> {
    await apiClient.delete(`/publications/shared-links/${id}`);
  }

  async regenerateToken(id: string): Promise<SharedLink> {
    return apiClient.post<SharedLink>(`/publications/shared-links/${id}/regenerate-token`, {});
  }

  async getConfig(resourceType?: string): Promise<SharedLinkConfig> {
    const params: Record<string, string> = {};
    if (resourceType) params.resourceType = resourceType;
    return apiClient.get<SharedLinkConfig>('/publications/shared-links/config', { params });
  }

  async check(resourceToken: string, resourceId?: string): Promise<SharedLinkCheckResult> {
    const params: Record<string, string> = { resourceToken };
    if (resourceId) params.resourceId = resourceId;
    return apiClient.get<SharedLinkCheckResult>('/publications/shared-links/check', { params });
  }
}

export const sharingService = new SharingService();
