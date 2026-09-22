import { apiClient } from '../api-client';

export interface CustomApiSummary {
  id: string;
  name: string;
  description: string;
  baseUrl: string;
  iconSlug: string | null;
  iconUrl: string | null;
  source: string;
  visibility: string;
  toolCount: number;
}

export interface CustomApiListResponse {
  apis: CustomApiSummary[];
  count: number;
}

export interface CustomApiRegistrationResponse {
  success: boolean;
  apiId: string;
  apiName: string;
  error?: string;
}

export interface CustomApiEndpointParam {
  name: string;
  in: 'query' | 'path' | 'body';
  type: string;
  required: boolean;
  description: string;
  // Advanced fields
  location?: string;
  hidden?: boolean;
  default?: string;
  example?: string;
}

export interface CustomApiSynthesis {
  resource?: string;
  action?: string;
  summary?: string;
  summaryExtended?: string;
  keywordsPrimary?: string[];
  keywordsSecondary?: string[];
}

export interface CustomApiPagination {
  type?: 'cursor' | 'offset';
  cursorParam?: string;
  cursorPath?: string;
  limitParam?: string;
  maxLimit?: number;
}

export interface CustomApiExecution {
  mode?: 'sync' | 'async_poll' | 'upload' | 'streaming';
  request?: { bodyType?: string };
  response?: { type?: string; binaryHandling?: string; rootPath?: string };
}

export interface CustomApiOutputField {
  key: string;
  type: string;
  description?: string;
  children?: CustomApiOutputField[];
}

export interface CustomApiFixture {
  endpointName: string;
  request?: Record<string, unknown>;
  response?: Record<string, unknown>;
}

export interface CustomApiEndpoint {
  name: string;
  endpoint: string;
  method: string;
  description: string;
  params: CustomApiEndpointParam[];
  /**
   * Constant headers sent on this endpoint, as { name: value }.
   *
   * Only CONSTANTS come back here. A header the caller fills per request comes back as an
   * ordinary param declared `in: "header"`, which update_api accepts. The split is by the
   * hidden flag, and it matters: projecting both as constants used to delete a declared
   * header param that had no default, and freeze one that did. Anything that rebuilds an
   * endpoint field by field must carry BOTH through, or they are dropped on the next save
   * with no type error to warn about it.
   */
  headers?: Record<string, string>;
  // Advanced fields
  toolCategory?: string;
  nextHint?: string;
  synthesis?: CustomApiSynthesis;
  pagination?: CustomApiPagination;
  execution?: CustomApiExecution;
  outputSchema?: CustomApiOutputField[];
}

/**
 * Where the credential is placed on the wire, and, for OAuth2, the provider endpoints the
 * connection needs. Declared by whoever registered the API; this form cannot author either block,
 * so it carries them through untouched rather than dropping them on the next save.
 */
export interface CustomApiAuthEntry {
  type?: string;
  apiKeyConfig?: {
    location?: string;
    headerName?: string;
    queryParamName?: string;
    keyName?: string;
    prefix?: string;
  };
  oauth2Config?: Record<string, unknown>;
}

export interface CustomApiDefinition {
  apiName: string;
  baseUrl: string;
  apiDescription?: string;
  authType?: string;
  /** Preserved verbatim on edit - see CustomApiAuthEntry. */
  auth?: CustomApiAuthEntry[];
  apiCategory?: string;
  iconUrl?: string;
  iconSlug?: string;
  endpoints: CustomApiEndpoint[];
  /** Constant headers sent on EVERY endpoint, as { name: value } (an API version pin). */
  requiredHeaders?: Record<string, string>;
  // Advanced fields
  apiVersion?: string;
  documentation?: string;
  rateLimits?: { requestsPerSecond?: number; requestsPerDay?: number };
  apiFixtures?: CustomApiFixture[];
}

export interface CustomApiDetails {
  id: string;
  apiName: string;
  description: string;
  baseUrl: string;
  authType: string;
  categoryName: string;
  iconUrl?: string;
  iconSlug?: string;
  auth?: CustomApiAuthEntry[];
  endpoints: CustomApiEndpoint[];
  // Advanced fields
  apiVersion?: string;
  documentation?: string;
  rateLimits?: { requestsPerSecond?: number; requestsPerDay?: number };
}

class CustomApiService {

  async list(): Promise<CustomApiListResponse> {
    return apiClient.get<CustomApiListResponse>('/catalog/custom-apis');
  }

  async getById(apiId: string): Promise<CustomApiDetails> {
    return apiClient.get<CustomApiDetails>(`/catalog/custom-apis/${apiId}`);
  }

  async register(definition: CustomApiDefinition): Promise<CustomApiRegistrationResponse> {
    return apiClient.post<CustomApiRegistrationResponse>('/catalog/custom-apis', definition);
  }

  async update(apiId: string, definition: CustomApiDefinition): Promise<CustomApiRegistrationResponse> {
    return apiClient.put<CustomApiRegistrationResponse>(`/catalog/custom-apis/${apiId}`, definition);
  }

  async remove(apiId: string): Promise<{ success: boolean }> {
    return apiClient.delete<{ success: boolean }>(`/catalog/custom-apis/${apiId}`);
  }
}

export const customApiService = new CustomApiService();
