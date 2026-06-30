/**
 * Organization API Service
 * Handles organization member management, invitations, and team operations.
 */

import { apiClient } from './api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';

export type OrganizationRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'EXPIRED' | 'CANCELLED';

export interface Organization {
  id: string;
  name: string;
  slug: string;
  isPersonal: boolean;
  avatarUrl: string | null;
  createdAt: string;
  updatedAt: string;
  currentUserRole: OrganizationRole;
  isDefault: boolean;
  memberCount: number;
  members?: OrganizationMember[];
  // Plan & team fields
  planCode?: string;
  maxMembers?: number;
  canInvite?: boolean;
  pendingInvitationCount?: number;
  /**
   * "Dormant" team org for the current user: the owner is no longer on a team
   * plan and this user is not the owner. Render it as paused (visible + leave-able)
   * and disable switching into it - the gateway also rejects entering it.
   */
  paused?: boolean;
  /**
   * Workspace the OWNER soft-deleted that is still in its grace window. Render it
   * disabled with a "Restore" action; switching into it is rejected by the gateway.
   */
  pendingDeletion?: boolean;
  /** When the soft-deleted workspace will be hard-purged (ISO). Set only when pendingDeletion. */
  purgeAt?: string | null;
}

export interface OrganizationMember {
  userId: number;
  email: string;
  displayName: string;
  avatarUrl: string | null;
  role: OrganizationRole;
  joinedAt: string;
  isOwner: boolean;
}

export interface Invitation {
  id: string;
  email: string;
  role: OrganizationRole;
  status: InvitationStatus;
  invitedByName: string;
  createdAt: string;
  expiresAt: string;
  /** PR4b inbox: org display name so the user sees WHICH org they're joining. */
  organizationName?: string;
  organizationId?: string;
  /**
   * CE invite-by-link only: the raw invitation token, so a CE admin can build a
   * copyable accept link. Present only under embedded auth (the backend omits it
   * in cloud, where the token is delivered by email).
   */
  token?: string;
}

/**
 * Public (unauthenticated) view of an invitation resolved from its token, for the
 * accept page to prefill the email and choose register-vs-login. `valid:false`
 * carries no other detail (missing/unknown/expired/cancelled/accepted token).
 */
export interface InvitationInfo {
  valid: boolean;
  email?: string;
  organizationName?: string;
  role?: OrganizationRole;
  /** Whether a local account already exists for the invitation email. */
  hasAccount?: boolean;
}

export interface OrganizationSamlConnection {
  configured: boolean;
  organizationId: string;
  idpAlias: string;
  displayName: string;
  idpEntityId: string;
  ssoUrl: string;
  status: 'NOT_CONFIGURED' | 'DRAFT' | 'ACTIVE' | 'ERROR' | 'DISABLED';
  hideOnLoginPage: boolean;
  ssoStartPath: string;
  serviceProviderEntityId: string;
  assertionConsumerServiceUrl: string;
  serviceProviderMetadataUrl: string;
  certificateFingerprintSha256: string | null;
  lastSyncedAt: string | null;
  lastError: string | null;
}

export interface OrganizationSamlUpsert {
  displayName: string;
  idpEntityId: string;
  ssoUrl: string;
  x509Certificate: string;
  hideOnLoginPage: boolean;
}

class OrganizationApiService {
  async getOrganizations(): Promise<Organization[]> {
    return await apiClient.get<Organization[]>('/organizations/me');
  }

  async getOrganization(orgId: string): Promise<Organization> {
    return await apiClient.get<Organization>(`/organizations/${orgId}`);
  }

  async getCurrentOrganization(): Promise<Organization> {
    return await apiClient.get<Organization>('/organizations/current');
  }

  async updateOrganization(orgId: string, data: { name: string }): Promise<Organization> {
    return await apiClient.put<Organization>(`/organizations/${orgId}`, data);
  }

  async setDefaultOrganization(orgId: string): Promise<void> {
    await apiClient.post(`/organizations/${orgId}/set-default`, {});
  }

  async inviteMember(orgId: string, email: string, role: string): Promise<Invitation> {
    return await apiClient.post<Invitation>(`/organizations/${orgId}/members/invite`, { email, role });
  }

  async getPendingInvitations(orgId: string): Promise<Invitation[]> {
    return await apiClient.get<Invitation[]>(`/organizations/${orgId}/invitations`);
  }

  /**
   * PR4b - fetch the current user's incoming PENDING invitations (the ones
   * other orgs sent them). Used by the /app/invitations inbox page after
   * silent auto-accept was killed in PR4a.
   */
  async getMyPendingInvitations(): Promise<Invitation[]> {
    return await apiClient.get<Invitation[]>(`/organizations/invitations/mine`);
  }

  /**
   * PR4b - accept an invitation by its UUID for the authenticated user.
   * Server validates that the invitation's email matches the caller's.
   * Returns the joined Organization.
   */
  async acceptInvitationById(invitationId: string): Promise<Organization> {
    return await apiClient.post<Organization>(
      `/organizations/invitations/${invitationId}/accept-by-id`,
      null
    );
  }

  async declineInvitationById(invitationId: string): Promise<Invitation> {
    return await apiClient.post<Invitation>(
      `/organizations/invitations/${invitationId}/decline-by-id`,
      null
    );
  }

  async cancelInvitation(orgId: string, invitationId: string): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}/invitations/${invitationId}`);
  }

  async acceptInvitation(token: string): Promise<Organization> {
    return await apiClient.post<Organization>(`/organizations/invitations/accept`, null, {
      params: { token }
    });
  }

  /**
   * Public lookup of an invitation by token (no auth). Used by the accept page
   * to prefill the email and decide between register-vs-login. Returns
   * `{valid:false}` for any unusable token.
   *
   * Uses a RAW fetch, not the authenticated apiClient: the accept page is reached
   * by a LOGGED-OUT invitee, where apiClient has no token provider and would no-op
   * ("No token provider configured") so the lookup never fired and the register
   * form never showed. The endpoint is public (gateway/monolith allow-listed) and
   * the proxy forwards the non-JWT `?token=` to the backend.
   */
  async getInvitationInfo(token: string): Promise<InvitationInfo> {
    try {
      const res = await fetch(
        `/api/proxy/organizations/invitations/info?token=${encodeURIComponent(token)}`,
        { headers: { Accept: 'application/json' } },
      );
      if (!res.ok) return { valid: false };
      return (await res.json()) as InvitationInfo;
    } catch {
      return { valid: false };
    }
  }

  async removeMember(orgId: string, userId: number): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}/members/${userId}`);
  }

  async changeMemberRole(orgId: string, userId: number, role: string): Promise<OrganizationMember> {
    return await apiClient.put<OrganizationMember>(`/organizations/${orgId}/members/${userId}/role`, { role });
  }

  // ── PR-4a - leave organization ─────────────────────────────────
  async leaveOrganization(orgId: string): Promise<void> {
    await apiClient.post(`/organizations/${orgId}/leave`, null);
  }

  // ── PR-4c - transfer ownership ─────────────────────────────────
  async transferOwnership(orgId: string, newOwnerUserId: number): Promise<void> {
    await apiClient.post(`/organizations/${orgId}/transfer-ownership`, { newOwnerUserId });
  }

  // ── PR-cascade simplified - soft-delete the org (OWNER + name confirm) ──
  async deleteOrganization(orgId: string, confirmName: string): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}`, { confirmName });
  }

  // ── Restore a soft-deleted workspace within its grace window (OWNER) ──
  async restoreOrganization(orgId: string): Promise<void> {
    await apiClient.post(`/organizations/${orgId}/restore`, null);
  }

  // ── Create an additional workspace (gated by the plan's max_workspaces cap) ──
  async createOrganization(name: string): Promise<Organization> {
    return await apiClient.post<Organization>(`/organizations`, { name });
  }

  // ── PR-4b - audit log (OWNER + ADMIN) ──────────────────────────
  async getAuditLog(orgId: string, options?: {
    category?: string;
    page?: number;
    size?: number;
  }): Promise<AuditLogPage> {
    const params: Record<string, string> = {};
    if (options?.category) params.category = options.category;
    if (options?.page !== undefined) params.page = String(options.page);
    if (options?.size !== undefined) params.size = String(options.size);
    return await apiClient.get<AuditLogPage>(`/organizations/${orgId}/audit-log`, { params });
  }

  async getSamlConnection(orgId: string): Promise<OrganizationSamlConnection> {
    return await apiClient.get<OrganizationSamlConnection>(`/organizations/${orgId}/saml-sso`);
  }

  async saveSamlConnection(orgId: string, body: OrganizationSamlUpsert): Promise<OrganizationSamlConnection> {
    return await apiClient.put<OrganizationSamlConnection>(`/organizations/${orgId}/saml-sso`, body);
  }

  async deleteSamlConnection(orgId: string): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}/saml-sso`);
  }

  // ── Workspace avatar (OWNER/ADMIN) ─────────────────────────────
  // Multipart upload bypasses apiClient (which forces application/json), so it
  // uses a raw fetch with the OIDC token - same pattern as the user AvatarGallery.
  async uploadAvatar(orgId: string, file: File): Promise<OrgAvatarUploadResult> {
    const tokenProvider = apiClient.getTokenProvider();
    const token = tokenProvider ? await tokenProvider() : null;

    const formData = new FormData();
    formData.append('file', file, file.name);

    const res = await fetch(`/api/proxy/organizations/${orgId}/avatar`, {
      method: 'POST',
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...getActiveOrgHeaderForRequest(),
      },
      body: formData,
    });

    if (!res.ok) {
      let message = 'Failed to upload avatar';
      if (res.status === 413) {
        message = 'Image too large';
      } else {
        try {
          const err = await res.json();
          message = err.error || message;
        } catch {
          /* non-JSON error body - keep the generic message */
        }
      }
      throw new Error(message);
    }
    return (await res.json()) as OrgAvatarUploadResult;
  }

  async deleteAvatar(orgId: string): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}/avatar`);
  }
}

export interface OrgAvatarUploadResult {
  storageId: string;
  avatarUrl: string;
}

export interface AuditLogEntry {
  id: number;
  eventType: string;
  actorUserId: number | null;
  eventData: Record<string, unknown>;
  createdAt: string;
}

export interface AuditLogPage {
  items: AuditLogEntry[];
  totalCount: number;
  page: number;
  size: number;
  /** Map of user id → display name for every user referenced on this page
   *  (actor + target/owner ids), so the UI shows names instead of "user #id". */
  userNames?: Record<string, string>;
}

// ===== PR11c - per-member quota cap CRUD =====

/**
 * Wire shape mirroring backend {@code MemberQuotaDto}. NULL cap on a
 * dimension = "no cap on that dim". Server-controlled fields
 * (createdByUserId, timestamps) are read-only.
 */
export interface MemberQuota {
  orgId: string;
  userId: number;
  periodCredits: number | null;
  periodStorageBytes: number | null;
  periodLlmTokens: number | null;
  resetCadence: string;
  createdByUserId: number;
  createdAt: string;
  updatedAt: string;
}

/** PUT body - all fields nullable for partial updates (NULL clears that dim). */
export interface MemberQuotaUpsert {
  periodCredits: number | null;
  periodStorageBytes: number | null;
  periodLlmTokens: number | null;
}

class MemberQuotaApiService {
  /**
   * GET one cap row. Returns null when no cap has been configured
   * (server returns 200 with empty body in that case - distinguish
   * from a 404 which means org/target not found).
   */
  async get(orgId: string, userId: number): Promise<MemberQuota | null> {
    const result = await apiClient.get<MemberQuota | Record<string, never>>(
      `/organizations/${orgId}/members/${userId}/quota`,
    );
    // Server returns {} when no row - guard via the discriminator field.
    if (!result || typeof (result as MemberQuota).orgId !== 'string') return null;
    return result as MemberQuota;
  }

  /** PUT (upsert). Partial-write semantics: NULL on a dim clears that dim's cap. */
  async upsert(orgId: string, userId: number, body: MemberQuotaUpsert): Promise<MemberQuota> {
    return await apiClient.put<MemberQuota>(
      `/organizations/${orgId}/members/${userId}/quota`,
      body,
    );
  }

  /** DELETE the entire row. Idempotent on the server (no-op delete returns 204). */
  async remove(orgId: string, userId: number): Promise<void> {
    await apiClient.delete(`/organizations/${orgId}/members/${userId}/quota`);
  }

  /** List all cap rows in an org - admin-panel listing. */
  async list(orgId: string): Promise<MemberQuota[]> {
    return await apiClient.get<MemberQuota[]>(`/organizations/${orgId}/quotas`);
  }
}

export const memberQuotaApi = new MemberQuotaApiService();

export const organizationApi = new OrganizationApiService();
