/**
 * Tests for the workflow variables API service - a thin wrapper over apiClient.
 * Asserts each method hits the right path with the right verb and payload, and
 * that responses/errors flow through unchanged (no shaping in the service).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  variablesApi,
  type UpsertWorkflowVariableRequest,
  type WorkflowVariable,
  type WorkflowVariableQuota,
} from '../variables-api.service';
import { apiClient } from '@/lib/api/api-client';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const mockGet = vi.mocked(apiClient.get);
const mockPost = vi.mocked(apiClient.post);
const mockPut = vi.mocked(apiClient.put);
const mockDelete = vi.mocked(apiClient.delete);

const VARIABLE: WorkflowVariable = {
  id: 7,
  name: 'api_base_url',
  value: 'https://api.example.com',
  type: 'STRING',
  description: 'Base URL for the partner API',
  scope: 'workspace',
  secret: false,
  createdAt: '2026-07-01T10:00:00Z',
  updatedAt: '2026-07-02T09:30:00Z',
};

describe('VariablesApiService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('list', () => {
    it('GETs /variables and returns the variables unchanged', async () => {
      mockGet.mockResolvedValue([VARIABLE]);

      const result = await variablesApi.list();

      expect(mockGet).toHaveBeenCalledTimes(1);
      expect(mockGet).toHaveBeenCalledWith('/variables');
      expect(result).toEqual([VARIABLE]);
    });

    it('returns an empty list as-is', async () => {
      mockGet.mockResolvedValue([]);

      await expect(variablesApi.list()).resolves.toEqual([]);
    });

    it('propagates errors from apiClient', async () => {
      mockGet.mockRejectedValue(new Error('Network Error'));

      await expect(variablesApi.list()).rejects.toThrow('Network Error');
    });
  });

  describe('getQuota', () => {
    it('GETs /variables/quota and returns the quota status', async () => {
      const quota: WorkflowVariableQuota = { used: 2, limit: 3, planCode: 'FREE' };
      mockGet.mockResolvedValue(quota);

      const result = await variablesApi.getQuota();

      expect(mockGet).toHaveBeenCalledWith('/variables/quota');
      expect(result).toEqual(quota);
    });

    it('passes through a null (unlimited) limit', async () => {
      mockGet.mockResolvedValue({ used: 10, limit: null, planCode: null });

      const result = await variablesApi.getQuota();

      expect(result.limit).toBeNull();
      expect(result.used).toBe(10);
    });

    it('propagates errors from apiClient', async () => {
      mockGet.mockRejectedValue(new Error('Unauthorized'));

      await expect(variablesApi.getQuota()).rejects.toThrow('Unauthorized');
    });
  });

  describe('create', () => {
    const request: UpsertWorkflowVariableRequest = {
      name: 'api_base_url',
      value: 'https://api.example.com',
      type: 'STRING',
      description: 'Base URL for the partner API',
    };

    it('POSTs the full request body to /variables', async () => {
      mockPost.mockResolvedValue(VARIABLE);

      const result = await variablesApi.create(request);

      expect(mockPost).toHaveBeenCalledTimes(1);
      expect(mockPost).toHaveBeenCalledWith('/variables', request);
      expect(result).toEqual(VARIABLE);
    });

    it('sends description null verbatim (explicit clear, not omitted)', async () => {
      mockPost.mockResolvedValue(VARIABLE);

      await variablesApi.create({ ...request, description: null });

      expect(mockPost).toHaveBeenCalledWith('/variables', { ...request, description: null });
    });

    it('propagates the 409 plan-limit rejection so callers can keep the row un-added', async () => {
      mockPost.mockRejectedValue(new Error('PLAN_RESOURCE_LIMIT_EXCEEDED'));

      await expect(variablesApi.create(request)).rejects.toThrow('PLAN_RESOURCE_LIMIT_EXCEEDED');
    });
  });

  describe('update', () => {
    const request: UpsertWorkflowVariableRequest = {
      name: 'api_base_url',
      value: '{"endpoint": "https://api.example.com"}',
      type: 'JSON',
    };

    it('PUTs the request body to /variables/{id}', async () => {
      const updated = { ...VARIABLE, type: 'JSON' as const, value: request.value };
      mockPut.mockResolvedValue(updated);

      const result = await variablesApi.update(7, request);

      expect(mockPut).toHaveBeenCalledTimes(1);
      expect(mockPut).toHaveBeenCalledWith('/variables/7', request);
      expect(result).toEqual(updated);
    });

    it('interpolates the numeric id into the path', async () => {
      mockPut.mockResolvedValue(VARIABLE);

      await variablesApi.update(12345, request);

      expect(mockPut).toHaveBeenCalledWith('/variables/12345', request);
    });

    it('propagates errors from apiClient', async () => {
      mockPut.mockRejectedValue(new Error('variable_name_conflict'));

      await expect(variablesApi.update(7, request)).rejects.toThrow('variable_name_conflict');
    });
  });

  describe('remove', () => {
    it('DELETEs /variables/{id}', async () => {
      mockDelete.mockResolvedValue(undefined);

      await variablesApi.remove(7);

      expect(mockDelete).toHaveBeenCalledTimes(1);
      expect(mockDelete).toHaveBeenCalledWith('/variables/7');
    });

    it('propagates errors from apiClient', async () => {
      mockDelete.mockRejectedValue(new Error('Not Found'));

      await expect(variablesApi.remove(404)).rejects.toThrow('Not Found');
    });
  });
});
