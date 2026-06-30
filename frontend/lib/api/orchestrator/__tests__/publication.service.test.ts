/**
 * Symmetric coalescer test for the showcase preview path. PublicationService
 * keys by publicationId in its own Map (separate namespace from
 * ExecutionService.inFlightStateGets which keys by runId - collision
 * impossible by construction).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PublicationService } from '../publication.service';
import { apiClient } from '../../api-client';

vi.mock('../../api-client', () => ({
  apiClient: {
    get: vi.fn(),
  },
}));

const mockedGet = vi.mocked(apiClient.get);

describe('PublicationService.getShowcaseRunState - coalescer', () => {
  let service: PublicationService;

  beforeEach(() => {
    service = new PublicationService();
    vi.clearAllMocks();
  });

  it('coalescesConcurrentCallsForSamePublicationId', async () => {
    let resolveFetch: (v: any) => void = () => {};
    const fetchPromise = new Promise((resolve) => { resolveFetch = resolve; });
    mockedGet.mockReturnValueOnce(fetchPromise as any);

    const calls = [
      service.getShowcaseRunState('pub_1'),
      service.getShowcaseRunState('pub_1'),
      service.getShowcaseRunState('pub_1'),
    ];

    expect(mockedGet).toHaveBeenCalledTimes(1);

    const payload = { seq: 1, steps: [] };
    resolveFetch(payload);
    const results = await Promise.all(calls);

    expect(results.every((r) => r === payload)).toBe(true);
  });

  it('doesNotCoalesceDifferentPublicationIds', async () => {
    mockedGet.mockResolvedValueOnce({ id: 'a' } as any);
    mockedGet.mockResolvedValueOnce({ id: 'b' } as any);

    await Promise.all([
      service.getShowcaseRunState('pub_a'),
      service.getShowcaseRunState('pub_b'),
    ]);

    expect(mockedGet).toHaveBeenCalledTimes(2);
  });

  it('clearsMapOnReject_retryHitsNetworkFresh', async () => {
    mockedGet.mockRejectedValueOnce(new Error('network'));
    mockedGet.mockResolvedValueOnce({ seq: 2 } as any);

    await expect(service.getShowcaseRunState('pub_1')).rejects.toThrow('network');
    const second = await service.getShowcaseRunState('pub_1');
    expect((second as any).seq).toBe(2);
    expect(mockedGet).toHaveBeenCalledTimes(2);
  });
});

describe('PublicationService.getSuggestedApplications - param serialization', () => {
  let service: PublicationService;

  beforeEach(() => {
    service = new PublicationService();
    vi.clearAllMocks();
  });

  it('commaJoinsArraysIncludesProfessionAndDefaultsLimitTo8', async () => {
    mockedGet.mockResolvedValueOnce({ count: 0, publications: [] } as any);

    await service.getSuggestedApplications({
      interests: ['sales-crm', 'ai-ml'],
      useCases: ['lead-generation'],
      profession: 'sales',
    });

    // Arrays are comma-joined so Spring binds them back to List<String>.
    expect(mockedGet).toHaveBeenCalledWith('/publications/suggestions', {
      params: {
        limit: '8',
        interests: 'sales-crm,ai-ml',
        useCases: 'lead-generation',
        profession: 'sales',
      },
    });
  });

  it('omitsEmptyArraysAndProfessionAndRespectsLimitOverride', async () => {
    mockedGet.mockResolvedValueOnce({ count: 0, publications: [] } as any);

    await service.getSuggestedApplications({ interests: [], limit: 4 });

    expect(mockedGet).toHaveBeenCalledWith('/publications/suggestions', {
      params: { limit: '4' },
    });
  });

  it('defaultsToLimit8WhenCalledWithNoArgs', async () => {
    mockedGet.mockResolvedValueOnce({ count: 0, publications: [] } as any);

    await service.getSuggestedApplications();

    expect(mockedGet).toHaveBeenCalledWith('/publications/suggestions', {
      params: { limit: '8' },
    });
  });
});
