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
