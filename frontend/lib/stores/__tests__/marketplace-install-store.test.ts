// @vitest-environment jsdom
/**
 * Install state machine of the marketplace-install store (extracted from
 * AcquirePublicationModal so it survives modal close + page navigation):
 * simulated 5-10s easeOutCubic ramp capped at 95% until the acquire call
 * returns, snap-to-100 + 300ms hold before 'success', endpoint routing per
 * publication type / ceMode, error mapping (402 / 403 CLOUD_ACCOUNT_NOT_LINKED
 * / generic), single-flight guard, and clear() invalidating a pending machine.
 * Timing constants mirror AcquirePublicationModal.stateMachine.test.tsx.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));
const trackMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: trackMock }));

import { useMarketplaceInstallStore } from '../marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Cloud Thing',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    ...overrides,
  } as WorkflowPublication;
}

const store = () => useMarketplaceInstallStore.getState();

describe('marketplace-install store - state machine', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    store().clear();
    nowValue = 0;
    vi.useFakeTimers();
    // Deterministic 5000ms target duration (5000 + floor(0 * range)).
    vi.spyOn(Math, 'random').mockReturnValue(0);
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1', agentId: 'a1', resourceId: 'r1' });
    svc.acquireAgentPublication.mockResolvedValue({ agentId: 'a1' });
    svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'TABLE' });
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
  });

  afterEach(() => {
    store().clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function driveToSuccess() {
    nowValue = 6000; // past the 5000ms target so the interval self-clears at 95%
    await vi.advanceTimersByTimeAsync(6000); // visual-minimum wait + 300ms hold
  }

  it('startInstall enters installing at 0% and tracks app_install_started', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // never resolves
    store().startInstall(pub());

    const active = store().active!;
    expect(active.status).toBe('installing');
    expect(active.progress).toBe(0);
    expect(active.acquiredId).toBeNull();
    expect(trackMock).toHaveBeenCalledWith('app_install_started', expect.objectContaining({
      publication_id: 'pub-1',
      is_free: true,
    }));
  });

  it('eases the progress via easeOutCubic and caps at 95% until the acquire resolves', async () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {})); // call never returns
    store().startInstall(pub());

    // Half the duration: easeOutCubic gives 1-(0.5^3)=0.875 -> 83%, NOT the
    // linear 48% - proves the curve is non-linear.
    nowValue = 2500;
    await vi.advanceTimersByTimeAsync(60);
    expect(Math.round(store().active!.progress)).toBe(83);

    // Past the full duration the bar holds at the 95% cap (the final 5% is
    // reserved for the snap-to-100 once the HTTP call returns).
    nowValue = 6000;
    await vi.advanceTimersByTimeAsync(60);
    expect(store().active!.progress).toBe(95);
    expect(store().active!.status).toBe('installing');
  });

  it('completes to success at 100% with the acquired workflow id after the visual budget', async () => {
    store().startInstall(pub());
    await driveToSuccess();

    const active = store().active!;
    expect(active.status).toBe('success');
    expect(active.progress).toBe(100);
    expect(active.acquiredId).toBe('w1');
    expect(trackMock).toHaveBeenCalledWith('app_install_succeeded', expect.objectContaining({
      publication_id: 'pub-1',
      acquired_id: 'w1',
    }));
  });

  it('routes an AGENT install to the agent endpoint and returns agentId', async () => {
    store().startInstall(pub({ publicationType: 'AGENT' }));
    await driveToSuccess();

    expect(svc.acquireAgentPublication).toHaveBeenCalledWith('pub-1');
    expect(svc.acquirePublication).not.toHaveBeenCalled();
    expect(store().active!.acquiredId).toBe('a1');
  });

  it('routes a resource (INTERFACE) install to the resource endpoint and returns resourceId', async () => {
    svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'INTERFACE' });
    store().startInstall(pub({ publicationType: 'INTERFACE' }));
    await driveToSuccess();

    expect(svc.acquireResourcePublication).toHaveBeenCalledWith('pub-1');
    expect(store().active!.acquiredId).toBe('r1');
  });

  it('ceMode routes EVERY type through the unified remote acquire', async () => {
    store().startInstall(pub({ publicationType: 'TABLE' }), { ceMode: true });
    await driveToSuccess();

    expect(svc.acquireRemotePublication).toHaveBeenCalledWith('pub-1');
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
  });

  it('402 → insufficient-credits, 403 CLOUD_ACCOUNT_NOT_LINKED → link-required, generic → error', async () => {
    svc.acquirePublication.mockRejectedValue({ status: 402 });
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('insufficient-credits');

    store().clear();
    svc.acquirePublication.mockRejectedValue({ status: 403, code: 'CLOUD_ACCOUNT_NOT_LINKED' });
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('link-required');

    store().clear();
    svc.acquirePublication.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('error');
    expect(store().active!.error).toBe('boom');
    expect(trackMock).toHaveBeenCalledWith('app_install_failed', expect.objectContaining({ outcome: 'error' }));
  });

  it('is single-flight: a second startInstall while installing returns false and does nothing', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    expect(store().startInstall(pub())).toBe(true);
    expect(store().startInstall(pub({ id: 'pub-2' }))).toBe(false);

    expect(store().active!.publication.id).toBe('pub-1');
    expect(svc.acquirePublication).toHaveBeenCalledTimes(1);
  });

  it('records the inline origin flag (marketplace-card rendering vs full-modal consumers)', () => {
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    store().startInstall(pub(), { inline: true });
    expect(store().active!.inline).toBe(true);

    store().clear();
    store().startInstall(pub());
    expect(store().active!.inline).toBe(false);
  });

  it('consumeSuccess drops a matching terminal success but never touches a different/running install', async () => {
    // Matching success → consumed.
    store().startInstall(pub());
    await driveToSuccess();
    expect(store().active!.status).toBe('success');
    store().consumeSuccess('pub-1');
    expect(store().active).toBeNull();

    // Running install → consumeSuccess is a strict no-op (the guard is what
    // makes it safe to call from a stale async finally).
    svc.acquirePublication.mockReturnValue(new Promise(() => {}));
    store().startInstall(pub({ id: 'pub-2' }));
    store().consumeSuccess('pub-2'); // right id but not success
    store().consumeSuccess('pub-1'); // stale id
    expect(store().active!.publication.id).toBe('pub-2');
    expect(store().active!.status).toBe('installing');
  });

  it('a terminal error does NOT block a retry (startInstall runs again)', async () => {
    svc.acquirePublication.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }));
    store().startInstall(pub());
    await vi.advanceTimersByTimeAsync(10);
    expect(store().active!.status).toBe('error');

    store().startInstall(pub());
    expect(store().active!.status).toBe('installing');
    expect(svc.acquirePublication).toHaveBeenCalledTimes(2);
  });

  it('clear() kills a pending machine: a late acquire resolution cannot resurrect state', async () => {
    store().startInstall(pub());
    store().clear();
    expect(store().active).toBeNull();

    await driveToSuccess();
    expect(store().active).toBeNull();
    expect(trackMock).not.toHaveBeenCalledWith('app_install_succeeded', expect.anything());
  });
});
