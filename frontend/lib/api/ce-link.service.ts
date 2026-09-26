/**
 * Cloud-side CE-link service - talks to /api/ce-link/** on the cloud auth-service.
 *
 * Companion to {@link cloud-link.service.ts} (CE-side OAuth link). This service
 * is for the CLOUD UI where a user views which CE installs are bound to their
 * account and can revoke them. See `the project docs` §6.
 */

import { apiClient } from './api-client';

/** Mirrors `CeLinkSummary` (Java) + `shared/contracts/ce-link-summary.schema.json`. */
export interface CeLinkSummary {
  installId: string;
  label: string | null;
  status: 'ACTIVE' | 'REVOKED';
  scopes: string;
  createdAt: string;            // ISO-8601
  lastSeenAt: string | null;
  lastSeenCeVersion: string | null;
}

export interface CeLinkPage {
  content: CeLinkSummary[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export class CeLinkService {
  /** GET /api/ce-link/mine - paginated list of caller's ACTIVE installs. */
  async mine(page = 0, size = 20): Promise<CeLinkPage> {
    return apiClient.get<CeLinkPage>('/ce-link/mine', {
      params: { page: String(page), size: String(size) },
    });
  }

  /** DELETE /api/ce-link/{installId} - ownership-scoped revoke. */
  async revoke(installId: string): Promise<void> {
    await apiClient.delete(`/ce-link/${installId}`);
  }

  /**
   * POST /api/ce-link/squat-recovery/{token} - one-time HMAC token consume.
   * Public endpoint (token IS the auth), no JWT required - but apiClient is
   * still used so any future shared headers (request id, etc.) come along.
   */
  async consumeRecovery(token: string): Promise<void> {
    await apiClient.post(`/ce-link/squat-recovery/${encodeURIComponent(token)}`, {}, {
      skipAuth: true,
    });
  }
}

export const ceLinkService = new CeLinkService();
