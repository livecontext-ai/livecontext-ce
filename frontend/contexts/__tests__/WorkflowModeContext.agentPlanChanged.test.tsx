/**
 * @vitest-environment jsdom
 *
 * An agent that writes a new version moves HEAD without any canvas save event. The provider
 * re-reads the versions so the version chip and the page's "is the canvas on the run's version"
 * check are not stuck on the number loaded at mount.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, render } from '@testing-library/react';

let mockPathname = '/app/workflow/wf-1';
const listVersions = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({
  usePathname: () => mockPathname,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { listVersions } }));
vi.mock('@/lib/stores/pending-interfaces-store', () => ({
  usePendingInterfacesStore: { getState: () => ({ clear: vi.fn() }) },
}));
vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useInterfacePaginationStore: { getState: () => ({ clear: vi.fn(), clearForRunSwitch: vi.fn() }) },
}));

import { WorkflowModeProvider, useWorkflowMode } from '../WorkflowModeContext';

let ctx: ReturnType<typeof useWorkflowMode> | null = null;
function Probe() {
  ctx = useWorkflowMode();
  return null;
}

async function agentWrote(detail: Record<string, unknown>) {
  await act(async () => {
    window.dispatchEvent(new CustomEvent('sidePanelAutoOpen', { detail }));
  });
}

async function mount(path: string, props: { readOnly?: boolean } = {}) {
  mockPathname = path;
  window.history.pushState({}, '', path);
  await act(async () => {
    render(<WorkflowModeProvider workflowId="wf-1" readOnly={props.readOnly}><Probe /></WorkflowModeProvider>);
  });
}

beforeEach(() => {
  ctx = null;
  listVersions.mockReset();
  listVersions.mockResolvedValueOnce({ pinnedVersion: null, currentVersion: 11 });
});

afterEach(() => cleanup());

describe('WorkflowModeContext - an agent writes a new version', () => {
  it('moves HEAD and the version shown when editing', async () => {
    await mount('/app/workflow/wf-1');
    listVersions.mockResolvedValueOnce({ pinnedVersion: null, currentVersion: 13 });

    await agentWrote({ type: 'workflow', id: 'wf-1', planChanged: true });

    expect(ctx!.currentVersion).toBe(13);
    expect(ctx!.activeVersion).toBe(13);
  });

  it('moves HEAD only while a run is shown (the canvas still shows the run version)', async () => {
    await mount('/app/workflow/wf-1/run/run-1');
    listVersions.mockResolvedValueOnce({ pinnedVersion: null, currentVersion: 13 });

    await agentWrote({ type: 'workflow', id: 'wf-1', planChanged: true });

    expect(ctx!.currentVersion).toBe(13);
    expect(ctx!.activeVersion).toBe(11);
  });

  it('ignores markers that did not change the plan, other workflows and the read-only preview', async () => {
    await mount('/app/workflow/wf-1');
    await agentWrote({ type: 'workflow', id: 'wf-1', planChanged: false });
    await agentWrote({ type: 'workflow', id: 'wf-other', planChanged: true });
    await agentWrote({ type: 'workflow_run', id: 'wf-1', runId: 'r', planChanged: true });
    expect(listVersions).toHaveBeenCalledTimes(1); // the mount only
    cleanup();

    listVersions.mockResolvedValueOnce({ pinnedVersion: null, currentVersion: 11 });
    await mount('/app/workflow/wf-1', { readOnly: true });
    await agentWrote({ type: 'workflow', id: 'wf-1', planChanged: true });
    expect(listVersions).toHaveBeenCalledTimes(2); // both mounts, no refresh in preview
  });

  it('keeps the versions it had when the refresh fails', async () => {
    await mount('/app/workflow/wf-1');
    listVersions.mockRejectedValueOnce(new Error('offline'));

    await agentWrote({ type: 'workflow', id: 'wf-1', planChanged: true });

    expect(ctx!.currentVersion).toBe(11);
  });
});
