import { apiClient } from '../api-client';

/**
 * MCP server connection metadata + user API key management.
 *
 * The MCP Streamable HTTP endpoint (/mcp on the backend origin) is consumed by
 * external MCP clients (Claude Code, Cursor, ...) authenticated with the user's
 * lc_live_ API key (X-API-Key header, or Authorization: Bearer). This service
 * feeds the Settings > MCP Server page.
 */

/** One selectable tool scope, surfaced by the connection endpoint for the create dialog. */
export interface McpScopeOption {
  /** Tool name, e.g. "workflow" - the value stored on a scoped key. */
  name: string;
  /** Human-readable tool description (truncated server-side). */
  description: string;
}

export interface McpConnectionInfo {
  /** Externally reachable MCP endpoint, e.g. https://livecontext.ai/mcp */
  url: string;
  serverName: string;
  /** Canonical auth header expected by the endpoint (X-API-Key). */
  authHeader: string;
  toolCount: number;
  /** Selectable tool scopes for a scoped key (name + description). */
  availableScopes?: McpScopeOption[];
}

export interface ApiKeyInfo {
  /** Plaintext key, present ONLY in the regenerate response (shown once). */
  apiKey: string | null;
  /** Hint such as "lc_live_...ab12", or null when no key exists yet. */
  maskedApiKey: string | null;
  createdAt: string | null;
  active: boolean;
}

/** A named, optionally-scoped key in the multi-key list (never carries plaintext). */
export interface ApiKeyEntry {
  id: string;
  name: string;
  /** Hint such as "lc_live_...ab12". */
  maskedApiKey: string;
  /** Tool scopes, or null for a full-access key. */
  scopes: string[] | null;
  createdAt: string | null;
  lastUsedAt: string | null;
}

/** The create response: the entry plus the one-time plaintext key. */
export interface CreatedApiKey extends ApiKeyEntry {
  apiKey: string;
}

export interface CreateApiKeyRequest {
  name: string;
  /** null = full access; otherwise the selected tool scopes. */
  scopes: string[] | null;
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

  /** All active named keys for the user (newest first). Never includes plaintext. */
  listKeys(): Promise<ApiKeyEntry[]> {
    return apiClient.get<ApiKeyEntry[]>('/auth/api-keys');
  },

  /** Create a named key. The response carries the plaintext exactly once. */
  createKey(body: CreateApiKeyRequest): Promise<CreatedApiKey> {
    return apiClient.post<CreatedApiKey>('/auth/api-keys', body);
  },

  /** Revoke (permanently disable) a named key by id. */
  revokeKey(id: string): Promise<void> {
    return apiClient.delete<void>(`/auth/api-keys/${id}`);
  },
};
