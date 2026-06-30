/**
 * Regression guard for the run-page mount fetch storm (prod 2026-05-02:
 * 3 successive `/state` GETs at mount, 14s perceived load on
 * run_<id>). Concurrent callers (manager init,
 * useWorkflowLoader hydration, WS-driven refresh, historical chat block)
 * must share one HTTP. The Map auto-clears on settle so retries fire fresh.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ExecutionService } from '../execution.service';
import { apiClient } from '../../api-client';

vi.mock('../../api-client', () => ({
  apiClient: {
    get: vi.fn(),
  },
}));

const mockedGet = vi.mocked(apiClient.get);

describe('ExecutionService.getRunState - coalescer', () => {
  let service: ExecutionService;

  beforeEach(() => {
    service = new ExecutionService();
    vi.clearAllMocks();
  });

  it('coalescesConcurrentCallsForSameRunId', async () => {
    let resolveFetch: (v: any) => void = () => {};
    const fetchPromise = new Promise((resolve) => { resolveFetch = resolve; });
    mockedGet.mockReturnValueOnce(fetchPromise as any);

    const calls = [
      service.getRunState('run_1'),
      service.getRunState('run_1'),
      service.getRunState('run_1'),
    ];

    expect(mockedGet).toHaveBeenCalledTimes(1);

    const payload = { seq: 42, steps: [], edges: [] };
    resolveFetch(payload);
    const results = await Promise.all(calls);

    expect(results[0]).toBe(payload);
    expect(results[1]).toBe(payload);
    expect(results[2]).toBe(payload);
  });

  it('doesNotCoalesceDifferentRunIds', async () => {
    mockedGet.mockResolvedValueOnce({ id: 'a' } as any);
    mockedGet.mockResolvedValueOnce({ id: 'b' } as any);

    const [a, b] = await Promise.all([
      service.getRunState('run_a'),
      service.getRunState('run_b'),
    ]);

    expect(mockedGet).toHaveBeenCalledTimes(2);
    expect((a as any).id).toBe('a');
    expect((b as any).id).toBe('b');
  });

  it('clearsMapOnResolve_sequentialCallsHitNetwork', async () => {
    mockedGet.mockResolvedValueOnce({ seq: 1 } as any);
    mockedGet.mockResolvedValueOnce({ seq: 2 } as any);

    await service.getRunState('run_1');
    await service.getRunState('run_1');

    expect(mockedGet).toHaveBeenCalledTimes(2);
  });

  it('clearsMapOnReject_retryHitsNetworkFresh', async () => {
    const failure = new Error('network down');
    mockedGet.mockRejectedValueOnce(failure);
    mockedGet.mockResolvedValueOnce({ seq: 99 } as any);

    await expect(service.getRunState('run_1')).rejects.toThrow('network down');

    // Map must have cleared the entry - next call hits network fresh, not the rejected promise.
    const second = await service.getRunState('run_1');
    expect((second as any).seq).toBe(99);
    expect(mockedGet).toHaveBeenCalledTimes(2);
  });

  it('rejectFanOut_allConcurrentCallersReceiveSameRejection', async () => {
    const failure = new Error('boom');
    mockedGet.mockRejectedValueOnce(failure);

    const calls = [
      service.getRunState('run_1').catch((e) => e),
      service.getRunState('run_1').catch((e) => e),
      service.getRunState('run_1').catch((e) => e),
    ];

    expect(mockedGet).toHaveBeenCalledTimes(1);
    const results = await Promise.all(calls);
    results.forEach((r) => expect(r).toBe(failure));
  });

  it('coalesces50Callers_zeroLeaks', async () => {
    let resolveFetch: (v: any) => void = () => {};
    const fetchPromise = new Promise((resolve) => { resolveFetch = resolve; });
    mockedGet.mockReturnValueOnce(fetchPromise as any);

    const callers = Array.from({ length: 50 }, () => service.getRunState('run_1'));
    expect(mockedGet).toHaveBeenCalledTimes(1);

    const payload = { seq: 7 };
    resolveFetch(payload);
    const results = await Promise.all(callers);

    expect(results.every((r) => r === (payload as any))).toBe(true);
    // After settle, follow-up call must hit network - proves no leak in the Map.
    mockedGet.mockResolvedValueOnce({ seq: 8 } as any);
    await service.getRunState('run_1');
    expect(mockedGet).toHaveBeenCalledTimes(2);
  });

  it('forceFlagSafetyNote_callersShareResponseObject', async () => {
    // Documents the contract referenced in WorkflowRunManager.refreshStateInternal:
    // multiple callers receive THE SAME runState reference. Each runs its own
    // post-await application path, so a force=true caller still bypasses the
    // seq guard correctly (the guard lives in the manager, not here).
    const payload = { seq: 5 };
    mockedGet.mockResolvedValueOnce(payload as any);

    const [a, b] = await Promise.all([
      service.getRunState('run_1'),
      service.getRunState('run_1'),
    ]);

    expect(a).toBe(payload);
    expect(b).toBe(payload);
    expect(a).toBe(b);
  });
});
