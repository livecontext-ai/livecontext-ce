import { apiClient } from '../api-client';

/**
 * MCP server connection metadata + user API key management.
 *
 * The MCP Streamable HTTP endpoint (/mcp on the backend origin) is consumed by
 * external MCP clients (Claude Code, Cursor, ...) authenticated with the user's
 * lc_live_ API key (X-API-Key header, or Authorization: Bearer). This service
 * feeds the Settings > MCP Server page.
 */

export interface McpConnectionInfo {
  /** Externally reachable MCP endpoint, e.g. https://livecontext.ai/mcp */
  url: string;
  serverName: string;
  /** Canonical auth header expected by the endpoint (X-API-Key). */
  authHeader: string;
  toolCount: number;
}

export interface ApiKeyInfo {
  /** Plaintext key, present ONLY in the regenerate response (shown once). */
  apiKey: string | null;
  /** Hint such as "lc_live_...ab12", or null when no key exists yet. */
  maskedApiKey: string | null;
  createdAt: string | null;
  active: boolean;
}

export const mcpServerService = {
  getConnection(): Promise<McpConnectionInfo> {
    return apiClient.get<McpConnectionInfo>('/mcp-server/connection');
  },

  getCurrentApiKey(): Promise<ApiKeyInfo> {
    return apiClient.get<ApiKeyInfo>('/auth/api-keys/current');
  },

  /** Invalidates any previous key. The response carries the plaintext exactly once. */
  regenerateApiKey(): Promise<ApiKeyInfo> {
    return apiClient.post<ApiKeyInfo>('/auth/api-keys/regenerate');
  },
};
