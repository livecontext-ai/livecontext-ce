/**
 * User API Service
 * Single Responsibility: User profile, status, and authentication operations
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from '../api-client';

/**
 * In-app profile returned by GET /users/public/by-handle|by-id (authenticated). Mirrors
 * auth-service PublicProfileDto - the chosen display name + public @handle (a URL-safe slug,
 * never the real first/last name nor the raw OAuth username), avatar, bio and join date.
 * No email, no roles. {@code userId} is for internal links only - the URL uses {@code handle}.
 */
export interface PublicProfile {
  userId: number;
  displayName?: string | null;
  handle?: string | null;
  avatarUrl?: string | null;
  bio?: string | null;
  joinedAt?: string | null;
  /**
   * Whether this account carries the verified badge (the blue check next to the
   * name). Resolved live by the backend from the ADMIN role plus the manual grant,
   * and always false on a self-hosted deployment - the badge is a managed-cloud
   * feature. Unrelated to e-mail verification, which is never exposed publicly.
   */
  verified?: boolean;
}

/**
 * Deletion state of the signed-in account, from GET /users/profile/deletion-status.
 * {@code deletionAt} is when the purge would run; it is {@code deactivatedAt} plus
 * {@code gracePeriodDays}, computed by the backend so the date shown always matches the
 * one the scheduler acts on. Both dates are null when nothing is scheduled.
 */
export interface AccountDeletionStatus {
  scheduledForDeletion: boolean;
  deactivatedAt: string | null;
  deletionAt: string | null;
  gracePeriodDays: number;
}

/**
 * Outcome of an admin verified-badge change (POST /admin/verified-accounts/set).
 *
 * `verifiedByRole` is true when the target is a platform admin, who carries the badge
 * from the role alone: revoking the manual flag then leaves the badge showing, and the
 * admin screen says so instead of implying a change nobody can see. `profileWithdrawn`
 * is the mirror case: the grant is stored but shows nowhere.
 */
export interface AdminVerifiedAccountResponse {
  userId: number;
  email: string | null;
  verified: boolean;
  verifiedByRole: boolean;
  /**
   * What a reader actually sees after the write. NOT `verified || verifiedByRole`: a
   * withdrawn profile page suppresses both, and the screen must not report a badge
   * nobody can see.
   */
  effectivelyVerified: boolean;
  /** The target set their profile to PRIVATE, so the grant is stored and dormant. */
  profileWithdrawn?: boolean;
}

/**
 * Body of PUT /users/profile/context: what the app knows about the signed-in person's
 * context (display locale, time zone, first-touch acquisition). Every field is optional.
 * `localeExplicit` is true only when the person picked a language in the UI; the backend
 * then lets it win over any locale the app merely displayed.
 */
export interface ProfileContextPayload {
  locale?: string;
  localeExplicit?: boolean;
  timeZone?: string;
  acquisition?: {
    utmSource?: string;
    utmMedium?: string;
    utmCampaign?: string;
    utmContent?: string;
    utmTerm?: string;
    referrer?: string;
    landingPath?: string;
    firstSeenAt?: string;
  };
}

/** GET /users/profile/marketing-consent. `updatedAt` is null until the person first chooses. */
export interface MarketingConsent {
  consent: boolean;
  updatedAt: string | null;
}

/** One authenticator app registered on the account (GET /me/mfa). */
export interface TotpDevice {
  id: string;
  label: string | null;
  createdAt: string | null;
}

/** The account's single-use recovery codes; counts are null when they could not be read. */
export interface RecoveryCodesStatus {
  remaining: number | null;
  total: number | null;
}

/**
 * GET /me/mfa. `available` is false where the account cannot hold a TOTP factor (CE today).
 * `required`: the account may not go without one (platform admin). `setupPending`: the
 * next sign-in will make the user enroll.
 */
export interface MfaStatus {
  available: boolean;
  totpEnabled: boolean;
  devices: TotpDevice[];
  required: boolean;
  setupPending: boolean;
  /** null when the account has no recovery codes. */
  recoveryCodes: RecoveryCodesStatus | null;
}

export class UserApiService {
  constructor() {}

  async getUserStatus(): Promise<any> {
    try {
      return await apiClient.get<any>('/users/status');
    } catch (error) {
      console.error('Error fetching user status:', error);
      throw error;
    }
  }

  async getUserProfile(): Promise<any> {
    try {
      return await apiClient.get<any>('/users/profile');
    } catch (error) {
      console.error('Error fetching user profile:', error);
      throw error;
    }
  }

  async updateUserProfile(profileData: any): Promise<any> {
    try {
      return await apiClient.put<any>('/users/profile', profileData);
    } catch (error) {
      console.error('Error updating user profile:', error);
      throw error;
    }
  }

  async checkUsername(username: string): Promise<{ available: boolean; message?: string }> {
    try {
      return await apiClient.get<{ available: boolean; message?: string }>('/users/check-username', { params: { username } });
    } catch (error) {
      console.error('Error checking username:', error);
      return { available: true };
    }
  }

  async checkDisplayName(displayName: string): Promise<{ available: boolean; displayName: string }> {
    try {
      return await apiClient.get<{ available: boolean; displayName: string }>('/users/check-display-name', { params: { displayName } });
    } catch (error) {
      console.error('Error checking display name:', error);
      return { available: true, displayName };
    }
  }

  async getDisplayNameStatus(): Promise<{ canChange: boolean; nextChangeDate: string | null }> {
    try {
      return await apiClient.get<{ canChange: boolean; nextChangeDate: string | null }>('/users/display-name-status');
    } catch (error) {
      console.error('Error fetching display name status:', error);
      throw error;
    }
  }

  /** @handle change status - same 1-change-per-week cooldown shape as display-name-status. */
  async getHandleStatus(): Promise<{ canChange: boolean; nextChangeDate: string | null }> {
    try {
      return await apiClient.get<{ canChange: boolean; nextChangeDate: string | null }>('/users/handle-status');
    } catch (error) {
      console.error('Error fetching handle status:', error);
      throw error;
    }
  }

  async deleteAccount(): Promise<void> {
    await apiClient.delete('/users/profile');
  }

  /**
   * Whether this account is scheduled for hard-deletion, and when that happens.
   * Reachable while the account is deactivated: the gateway allow-lists this path and
   * {@link restoreAccount}, otherwise the grace period we promise would be unusable
   * (the person is blocked the moment they ask for deletion).
   */
  async getAccountDeletionStatus(): Promise<AccountDeletionStatus> {
    return await apiClient.get<AccountDeletionStatus>('/users/profile/deletion-status');
  }

  /**
   * Cancels a scheduled deletion and re-enables the account. Idempotent: restoring an
   * account that is not scheduled returns {@code restored: false} rather than an error.
   */
  async restoreAccount(): Promise<AccountDeletionStatus & { restored: boolean }> {
    return await apiClient.post<AccountDeletionStatus & { restored: boolean }>('/users/profile/restore');
  }

  /** Report the signed-in person's context (locale, time zone, acquisition). Answers 204. */
  async reportProfileContext(payload: ProfileContextPayload): Promise<void> {
    await apiClient.put('/users/profile/context', payload);
  }

  /**
   * Record a language the person explicitly picked in the UI. Best-effort: switching the
   * language must never wait on, or fail because of, this call.
   */
  async reportExplicitLocale(locale: string): Promise<void> {
    try {
      await apiClient.put('/users/profile/context', { locale, localeExplicit: true });
    } catch {
      // Ignored on purpose: the next session's context report does not overwrite an
      // explicit choice, so the only cost of a lost call is the stored locale lagging.
    }
  }

  async getMarketingConsent(): Promise<MarketingConsent> {
    return apiClient.get<MarketingConsent>('/users/profile/marketing-consent');
  }

  async setMarketingConsent(consent: boolean): Promise<void> {
    await apiClient.put('/users/profile/marketing-consent', { consent });
  }

  async getMfaStatus(): Promise<MfaStatus> {
    return apiClient.get<MfaStatus>('/me/mfa');
  }

  /**
   * In-app profile by its public @handle - the canonical /app/u/{handle} lookup. Authenticated.
   * Throws on 404 (unknown / PRIVATE / disabled) so the page can show not-found.
   */
  async getPublicProfileByHandle(handle: string): Promise<PublicProfile> {
    return apiClient.get<PublicProfile>(`/users/public/by-handle/${encodeURIComponent(handle)}`);
  }

  /**
   * In-app profile by numeric user id - for internal links that already carry the id (e.g. a DM
   * thread). Authenticated. Throws on 404 (unknown / PRIVATE / disabled).
   */
  async getPublicProfileById(userId: string | number): Promise<PublicProfile> {
    return apiClient.get<PublicProfile>(`/users/public/by-id/${userId}`);
  }

  /**
   * Grant or revoke a user's verified badge. Platform admins already carry it from
   * their role, so this is how everyone else gets one. Admin-only and cloud-only:
   * the backend answers 403 without the ADMIN role and 503 on a self-hosted install.
   */
  async adminSetVerified(payload: {
    target_email?: string;
    target_user_id?: number;
    verified: boolean;
  }): Promise<AdminVerifiedAccountResponse> {
    return apiClient.post<AdminVerifiedAccountResponse>('/admin/verified-accounts/set', payload);
  }

  /**
   * Which of these users carry the verified badge - one request for a whole rendered
   * list. Answers only the ids that qualify; unknown and unverified ids are absent.
   *
   * Call it through `loadVerifiedFlag` (lib/api/verifiedUsers) rather than directly:
   * that wrapper is what collects a list's ids into a single batch.
   */
  async getVerifiedUserIds(userIds: Array<string | number>): Promise<string[]> {
    if (userIds.length === 0) return [];
    const response = await apiClient.get<{ verified: Array<string | number> }>(
      '/users/public/verified-badges',
      { params: { ids: userIds.join(',') } },
    );
    return (response?.verified ?? []).map((id) => String(id));
  }

  /**
   * CE-cloud: resolve a CLOUD user's public profile by id through the CE backend's
   * cloud proxy. On a cloud-linked CE rendering remote marketplace content, the
   * publisher/reviewer id is a CLOUD user id absent from the local auth DB, so the
   * local by-id read 404s. This routes to the cloud instead so "View profile" can
   * resolve the cloud {@code @handle} and deep-link to the cloud profile page.
   * Throws on 404 (unknown / PRIVATE / disabled).
   */
  async getRemotePublicProfileById(userId: string | number): Promise<PublicProfile> {
    return apiClient.get<PublicProfile>(`/publications/remote/users/${userId}/profile`);
  }

}
