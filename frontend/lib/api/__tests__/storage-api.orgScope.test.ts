import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the shared HTTP client so we can assert exactly what gets sent. The real
// `orgScopeRequestOptions` (from current-org-store) is intentionally NOT mocked -
// it is a pure helper and we want to prove storage-api wires it through verbatim.
const { getMock, postMock } = vi.hoisted(() => ({ getMock: vi.fn(), postMock: vi.fn() }));
vi.mock('../api-client', () => ({ apiClient: { get: getMock, post: postMock } }));

import { storageApi } from '../storage-api';

const ORG = 'org-7c21';
const headerOpts = { headers: { 'X-Active-Organization-ID': ORG } };

describe('storageApi - workspace-scope override (Storage page workspace filter)', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    getMock.mockResolvedValue({});
    postMock.mockResolvedValue({});
  });

  describe('getQuota', () => {
    it('sends the org header when an orgId is given', async () => {
      await storageApi.getQuota(ORG);
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota', headerOpts);
    });

    it('sends no second arg when no orgId (unchanged active-workspace behaviour)', async () => {
      await storageApi.getQuota();
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota');
    });

    it('treats null orgId as "no override"', async () => {
      await storageApi.getQuota(null);
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota');
    });
  });

  describe('getBreakdown', () => {
    it('sends the org header when scoped', async () => {
      getMock.mockResolvedValue([]);
      await storageApi.getBreakdown(ORG);
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota/breakdown', headerOpts);
    });

    it('sends no second arg unscoped', async () => {
      getMock.mockResolvedValue([]);
      await storageApi.getBreakdown();
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota/breakdown');
    });
  });

  describe('getStats', () => {
    it('sends the org header when scoped', async () => {
      await storageApi.getStats(ORG);
      expect(getMock).toHaveBeenLastCalledWith('/stats', headerOpts);
    });

    it('sends no second arg unscoped', async () => {
      await storageApi.getStats();
      expect(getMock).toHaveBeenLastCalledWith('/stats');
    });
  });

  describe('getHistory', () => {
    it('merges the org header alongside the days param', async () => {
      getMock.mockResolvedValue([]);
      await storageApi.getHistory(90, ORG);
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota/history', {
        params: { days: '90' },
        ...headerOpts,
      });
    });

    it('keeps only the days param when unscoped', async () => {
      getMock.mockResolvedValue([]);
      await storageApi.getHistory(30);
      expect(getMock).toHaveBeenLastCalledWith('/storage/quota/history', { params: { days: '30' } });
    });
  });

  describe('recalculateUsage', () => {
    it('passes the org header as the POST options when scoped', async () => {
      await storageApi.recalculateUsage(ORG);
      expect(postMock).toHaveBeenLastCalledWith('/storage/quota/recalculate', {}, headerOpts);
    });

    it('passes undefined options when unscoped', async () => {
      await storageApi.recalculateUsage();
      expect(postMock).toHaveBeenLastCalledWith('/storage/quota/recalculate', {}, undefined);
    });
  });
});
