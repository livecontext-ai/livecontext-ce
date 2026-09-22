// @vitest-environment jsdom
/**
 * Admin demo-install mode of the marketplace-install store.
 *
 * The point of the mode is that the animation is REAL and the acquisition is
 * not, so these tests pin both halves: the same ramp/timings a normal install
 * has, and the total absence of any backend call, analytics event or acquired
 * id. The acquire methods fire their request the moment they are called, so
 * "not called" is the whole guarantee, not a detail.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const svc = vi.hoisted(() => ({
  acquireRemotePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
  createEditableWorkflowCopy: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));
const trackMock = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: trackMock }));

import { useMarketplaceInstallStore } from '../marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Invoice to Client',
    creditsPerUse: 0,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    ...overrides,
  } as WorkflowPublication;
}

const store = () => useMarketplaceInstallStore.getState();

describe('marketplace-install store - admin demo mode', () => {
  let nowValue = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    store().clear();
    nowValue = 0;
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0); // deterministic 5000ms budget
    vi.spyOn(performance, 'now').mockImplementation(() => nowValue);
    svc.acquirePublication.mockResolvedValue({ workflowId: 'w1' });
    svc.acquireAgentPublication.mockResolvedValue({ agentId: 'a1' });
    svc.acquireResourcePublication.mockResolvedValue({ resourceId: 'r1', type: 'TABLE' });
    svc.acquireRemotePublication.mockResolvedValue({ workflowId: 'w1' });
    svc.createEditableWorkflowCopy.mockResolvedValue({ workflowId: 'wf-copy', created: true });
  });

  afterEach(() => {
    store().clear();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function driveToSuccess() {
    nowValue = 6000;
    await vi.advanceTimersByTimeAsync(6000);
  }

  it('never calls any acquire endpoint', async () => {
    store().startInstall(pub(), { demo: true });
    await driveToSuccess();

    expect(svc.acquirePublication).not.toHaveBeenCalled();
    expect(svc.acquireAgentPublication).not.toHaveBeenCalled();
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
  });

  it('never calls acquire for an AGENT publication either', async () => {
    store().startInstall(pub({ publicationType: 'AGENT' }), { demo: true });
    await driveToSuccess();
    expect(svc.acquireAgentPublication).not.toHaveBeenCalled();
  });

  it('never calls acquire for a resource publication either', async () => {
    store().startInstall(pub({ publicationType: 'TABLE' }), { demo: true });
    await driveToSuccess();
    expect(svc.acquireResourcePublication).not.toHaveBeenCalled();
  });

  it('never calls the remote acquire in CE cloud mode either', async () => {
    store().startInstall(pub(), { demo: true, ceMode: true });
    await driveToSuccess();
    expect(svc.acquireRemotePublication).not.toHaveBeenCalled();
  });

  it('refuses to create the editable copy, which would be a real backend write', async () => {
    store().startInstall(pub(), { demo: true, withEditableCopy: true });
    await driveToSuccess();

    expect(svc.createEditableWorkflowCopy).not.toHaveBeenCalled();
    const active = store().active!;
    expect(active.withEditableCopy).toBe(false);
    expect(active.editableCopyWorkflowId).toBeNull();
    // A copy that was never requested must not be reported as having failed.
    expect(active.editableCopyFailed).toBe(false);
  });

  it('emits no install analytics at all', async () => {
    store().startInstall(pub(), { demo: true });
    await driveToSuccess();
    expect(trackMock).not.toHaveBeenCalled();
  });

  it('still runs the real ramp: 0%, capped at 95% mid-flight, then 100% on success', async () => {
    store().startInstall(pub(), { demo: true });
    expect(store().active!.progress).toBe(0);

    nowValue = 2500;
    await vi.advanceTimersByTimeAsync(60);
    const mid = store().active!.progress;
    expect(mid).toBeGreaterThan(0);
    expect(mid).toBeLessThanOrEqual(95);
    expect(store().active!.status).toBe('installing');

    await driveToSuccess();
    expect(store().active!.status).toBe('success');
    expect(store().active!.progress).toBe(100);
  });

  it('reports no acquired id, so consumers cannot offer to open something that does not exist', async () => {
    store().startInstall(pub(), { demo: true });
    await driveToSuccess();

    const active = store().active!;
    expect(active.demo).toBe(true);
    expect(active.acquiredId).toBeNull();
  });

  it('summarises with the counts the publication itself declares, skipping the empty ones', async () => {
    store().startInstall(
      pub({ workflowCount: 2, interfaceCount: 1, datasourceCount: 3, agentCount: 0 }),
      { demo: true },
    );
    await driveToSuccess();

    expect(store().active!.resources).toEqual({ workflows: 2, interfaces: 1, tables: 3 });
  });

  it('does not invent an extra agent for an agent publication', async () => {
    // A publication's agentCount COUNTS the root agent; the acquire tally reports
    // only the sub-agents cloned beside it. A plain agent must therefore summarise
    // as nothing at all, not as "1 agent".
    store().startInstall(pub({ publicationType: 'AGENT', agentCount: 1 }), { demo: true });
    await driveToSuccess();
    expect(store().active!.resources).toEqual({});
  });

  it('reports only the sub-agents of an agent publication', async () => {
    store().startInstall(pub({ publicationType: 'AGENT', agentCount: 3 }), { demo: true });
    await driveToSuccess();
    expect(store().active!.resources).toEqual({ agents: 2 });
  });

  it('keeps the declared agent count as-is for a non-agent publication', async () => {
    store().startInstall(pub({ publicationType: 'WORKFLOW', agentCount: 2 }), { demo: true });
    await driveToSuccess();
    expect(store().active!.resources).toEqual({ agents: 2 });
  });

  it('leaves a real install untouched: it still calls acquire and still tracks', async () => {
    store().startInstall(pub());
    await driveToSuccess();

    expect(svc.acquirePublication).toHaveBeenCalledWith('pub-1');
    expect(store().active!.demo).toBe(false);
    expect(store().active!.acquiredId).toBe('w1');
    expect(trackMock).toHaveBeenCalledWith('app_install_started', expect.anything());
  });
});
