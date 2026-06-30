/**
 * Bridge Access Service
 *
 * Admin-only API surface for CLI bridge access policies (Claude Code / Codex /
 * Gemini CLI / Mistral Vibe). Bridges run on a shared OS-level session (the
 * admin's Pro/Plus subscription) so access has to be gated - without it any CE
 * user can exhaust the rate limits and break the bridge for everyone.
 *
 * Routes through the gateway's `auth-bridge-access` rule → auth-service's
 * {@code BridgeAccessController}. All endpoints require the ADMIN role and
 * return 403 otherwise.
 */

import { apiClient } from '../api-client';
import type {
  BridgeAccessPolicy,
  BridgeAccessView,
  BridgeAccessAllowlistEntry,
  UpdateBridgeAccessPolicyRequest,
} from './types';

export class BridgeAccessService {
  /** List every bridge policy (one row per supported bridge). */
  async listPolicies(): Promise<BridgeAccessPolicy[]> {
    const response = await apiClient.get<{ policies: BridgeAccessPolicy[] }>('/bridge-access');
    return response.policies ?? [];
  }

  /**
   * Fetch the admin aggregate view for a bridge: policy + allowlist + recent
   * usage. 404 if the bridge is unknown (caller should fall back to `null`).
   */
  async getPolicyView(bridgeProvider: string): Promise<BridgeAccessView | null> {
    try {
      return await apiClient.get<BridgeAccessView>(
        `/bridge-access/${encodeURIComponent(bridgeProvider)}`,
      );
    } catch (err: unknown) {
      if (err instanceof Error && /404/.test(err.message)) return null;
      throw err;
    }
  }

  async updatePolicy(
    bridgeProvider: string,
    request: UpdateBridgeAccessPolicyRequest,
  ): Promise<BridgeAccessPolicy> {
    return apiClient.put<BridgeAccessPolicy>(
      `/bridge-access/${encodeURIComponent(bridgeProvider)}`,
      request,
    );
  }

  async grantAccess(bridgeProvider: string, userId: string): Promise<BridgeAccessAllowlistEntry> {
    return apiClient.post<BridgeAccessAllowlistEntry>(
      `/bridge-access/${encodeURIComponent(bridgeProvider)}/allowlist/${encodeURIComponent(userId)}`,
      {},
    );
  }

  async revokeAccess(bridgeProvider: string, userId: string): Promise<{ removed: boolean }> {
    return apiClient.delete<{ removed: boolean }>(
      `/bridge-access/${encodeURIComponent(bridgeProvider)}/allowlist/${encodeURIComponent(userId)}`,
    );
  }
}

export const bridgeAccessService = new BridgeAccessService();
