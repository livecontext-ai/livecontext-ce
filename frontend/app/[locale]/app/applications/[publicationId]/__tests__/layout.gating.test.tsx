/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

/**
 * Pins the editable-application GATING + THREADING in the application layout: how it
 * derives canEdit / canPublish and which workflow it binds to, then hands them to
 * ApplicationDetailView. This is where the publisher-path showstopper lived (the
 * layout must consume the server's pub.ownedByMe). Covers all three roles AND both
 * setData branches (existing-run reuse + fresh createRunAndReady).
 *
 * Rules under test (decouple-to-editable-workflow):
 *  - acquirer (own clone, acquiredApp.workflowId) -> RUN-ONLY: NOT canEdit, NOT canPublish,
 *    still bound to the clone (editing lives in the separate WORKFLOW twin in /app/workflows).
 *  - publisher (pub.ownedByMe) -> canEdit AND canPublish, bound to the SOURCE (pub.workflowId).
 *  - non-owner / non-acquirer -> neither, bound to the preview clone.
 */

const detailProps = vi.hoisted(() => [] as Array<{ workflowId: string; canEdit?: boolean; canPublish?: boolean; remote?: boolean }>);
const getAcquiredApplications = vi.hoisted(() => vi.fn());
const getApplicationWorkflow = vi.hoisted(() => vi.fn());
const getApplicationRun = vi.hoisted(() => vi.fn());
const getWorkflow = vi.hoisted(() => vi.fn());
const executeWorkflow = vi.hoisted(() => vi.fn());
const resolveApplicationPublication = vi.hoisted(() => vi.fn());
const checkMissingCredentialsAsync = vi.hoisted(() => vi.fn());
// Mutable edition/link config so a test can pose as a cloud-linked CE (remote).
const cfg = vi.hoisted(() => ({ isCE: false, installLinked: false }));

// Stable t identity across renders, else the layout's initialize useCallback churns
// and its useEffect re-fires in a loop.
vi.mock('next-intl', () => {
  const t = (k: string) => k;
  return { useTranslations: () => t };
});
vi.mock('@/components/views/application/ApplicationDetailView', () => ({
  ApplicationDetailView: (p: { workflowId: string; canEdit?: boolean; canPublish?: boolean; remote?: boolean }) => {
    detailProps.push({ workflowId: p.workflowId, canEdit: p.canEdit, canPublish: p.canPublish, remote: p.remote });
    return null;
  },
}));
// IS_CE is a getter so a single test can flip to a cloud-linked CE.
vi.mock('@/lib/edition', () => ({ get IS_CE() { return cfg.isCE; } }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({
    status: null,
    isLoading: false,
    isCloudLinked: cfg.installLinked,
    isInstallCloudLinked: cfg.installLinked,
  }),
}));
vi.mock('@/components/views/application/SetupRequiredState', () => ({ SetupRequiredState: () => null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/views/workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => null }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getAcquiredApplications, getApplicationWorkflow },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getApplicationRun, getWorkflow, executeWorkflow },
}));
vi.mock('@/lib/credentials/checkMissingCredentialsAsync', () => ({ checkMissingCredentialsAsync }));
vi.mock('../resolvePublication', () => ({ resolveApplicationPublication }));

import { ApplicationLayoutInner } from '../layout';

const PUB_ID = 'pub-1';

function renderLayout() {
  // Test the inner component directly (publicationId already resolved) so we exercise
  // the gating/threading without the Suspense `use(params)` shell.
  render(<ApplicationLayoutInner publicationId={PUB_ID}>{null}</ApplicationLayoutInner>);
}

async function lastDetailProps() {
  await waitFor(() => expect(detailProps.length).toBeGreaterThan(0));
  return detailProps[detailProps.length - 1];
}

describe('ApplicationLayout - editable gating + threading', () => {
  beforeEach(() => {
    detailProps.length = 0;
    getAcquiredApplications.mockReset();
    getApplicationWorkflow.mockReset();
    getApplicationRun.mockReset();
    getWorkflow.mockReset();
    executeWorkflow.mockReset();
    resolveApplicationPublication.mockReset();
    checkMissingCredentialsAsync.mockReset();
    checkMissingCredentialsAsync.mockResolvedValue({ wizardable: [], manual: [] });
    cfg.isCE = false;
    cfg.installLinked = false;
  });

  afterEach(() => cleanup());

  it('publisher (ownedByMe) -> canEdit + canPublish, bound to the SOURCE workflow', async () => {
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: true });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getApplicationRun.mockResolvedValue({ runId: 'run-1' });

    renderLayout();

    expect(await lastDetailProps()).toEqual({ workflowId: 'src-wf', canEdit: true, canPublish: true, remote: false });
    // Bound to the editable source, NOT the frozen preview clone.
    expect(getApplicationWorkflow).not.toHaveBeenCalled();
  });

  it('acquirer (own clone) -> RUN-ONLY: NOT canEdit, NOT canPublish, still bound to the clone', async () => {
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: false });
    getAcquiredApplications.mockResolvedValue({ applications: [{ sourcePublicationId: PUB_ID, workflowId: 'clone-wf' }] });
    getApplicationRun.mockResolvedValue({ runId: 'run-2' });

    renderLayout();

    // The acquired APPLICATION clone is run-only; editing lives in the decoupled WORKFLOW twin.
    expect(await lastDetailProps()).toEqual({ workflowId: 'clone-wf', canEdit: false, canPublish: false, remote: false });
  });

  it('non-owner / non-acquirer -> neither, bound to the preview clone', async () => {
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: false });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getApplicationWorkflow.mockResolvedValue({ workflowId: 'preview-wf' });
    getApplicationRun.mockResolvedValue({ runId: 'run-3' });

    renderLayout();

    expect(await lastDetailProps()).toEqual({ workflowId: 'preview-wf', canEdit: false, canPublish: false, remote: false });
  });

  it('threads the flags through the fresh-run path (createRunAndReady), not only the existing-run reuse', async () => {
    // Publisher (ownedByMe) so canEdit/canPublish are BOTH true - this asserts the
    // flags survive the fresh-run path (createRunAndReady), not only the existing-run reuse.
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: true });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getApplicationRun.mockResolvedValue(null);
    getWorkflow.mockResolvedValue({ plan: { triggers: [] } });
    executeWorkflow.mockResolvedValue({ runId: 'run-4' });

    renderLayout();

    expect(await lastDetailProps()).toEqual({ workflowId: 'src-wf', canEdit: true, canPublish: true, remote: false });
    expect(executeWorkflow).toHaveBeenCalledTimes(1);
  });

  it('cloud-linked CE -> threads remote=true so the Info-panel publisher menu opens the CLOUD profile (regression)', async () => {
    // The publisher id of a cloud publication is a CLOUD user id absent from the local
    // auth DB. Pre-fix the layout never passed `remote`, so ApplicationDetailView fell
    // back to publication.remote (undefined) -> the publisher menu took the LOCAL route
    // and bounced to the login page. The layout now derives remote = IS_CE &&
    // isInstallCloudLinked and threads it down.
    cfg.isCE = true;
    cfg.installLinked = true;
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: true });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getApplicationRun.mockResolvedValue({ runId: 'run-5' });

    renderLayout();

    expect(await lastDetailProps()).toEqual({ workflowId: 'src-wf', canEdit: true, canPublish: true, remote: true });
  });

  it('CE that is NOT cloud-linked -> remote stays false (local profiles work, unchanged)', async () => {
    cfg.isCE = true;
    cfg.installLinked = false;
    resolveApplicationPublication.mockResolvedValue({ id: PUB_ID, title: 'T', workflowId: 'src-wf', ownedByMe: true });
    getAcquiredApplications.mockResolvedValue({ applications: [] });
    getApplicationRun.mockResolvedValue({ runId: 'run-6' });

    renderLayout();

    expect((await lastDetailProps()).remote).toBe(false);
  });
});
