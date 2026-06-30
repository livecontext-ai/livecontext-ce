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
  // Advanced fields
  toolCategory?: string;
  nextHint?: string;
  synthesis?: CustomApiSynthesis;
  pagination?: CustomApiPagination;
  execution?: CustomApiExecution;
  outputSchema?: CustomApiOutputField[];
}

export interface CustomApiDefinition {
  apiName: string;
  baseUrl: string;
  apiDescription?: string;
  authType?: string;
  apiCategory?: string;
  iconUrl?: string;
  iconSlug?: string;
  endpoints: CustomApiEndpoint[];
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
