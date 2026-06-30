'use client';

import { use, useState, useEffect, useCallback, useRef, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { ApplicationDetailView } from '@/components/views/application/ApplicationDetailView';
import { SetupRequiredState } from '@/components/views/application/SetupRequiredState';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { WorkflowLoadingState } from '@/components/views/workflow/WorkflowLoadingState';
import { checkMissingCredentialsAsync } from '@/lib/credentials/checkMissingCredentialsAsync';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { IS_CE } from '@/lib/edition';
import { resolveApplicationPublication } from './resolvePublication';
import type {
  MissingCredentialsResult,
  WorkflowPlanLike,
} from '@/lib/credentials/missingCredentials';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

interface ApplicationLayoutProps {
  children: React.ReactNode;
  params: Promise<{ publicationId: string }>;
}

/**
 * Layout for viewing an application by publication ID.
 * APPLICATION mode: runs against user's workflow plan (cloned for acquired,
 * original for published). EXPERIENCE mode was removed in workstream B / V272.
 */
export default function ApplicationLayout({ children, params }: ApplicationLayoutProps) {
  const { publicationId } = use(params);
  // key={publicationId} forces a clean remount of the entire data-loading +
  // provider tree when the URL param changes. Without it, `data` state holds
  // the previous publication's workflowId/runId across navigations: the
  // WorkflowModeProvider would mount with stale values (runId is locked at
  // mount via isProgrammaticRef) and never refresh when the new fetch lands.
  return <ApplicationLayoutInner key={publicationId} publicationId={publicationId}>{children}</ApplicationLayoutInner>;
}

// State machine for the layout:
//  - 'loading'  → spinner (initial fetch in flight)
//  - 'setup'    → pre-flight gate (acquired app needs credentials)
//  - 'ready'    → run resolved/created, render ApplicationDetailView
//  - 'error'    → terminal failure
type LayoutPhase = 'loading' | 'setup' | 'ready' | 'error';

interface SetupGateState {
  publication: WorkflowPublication;
  targetWorkflowId: string;
  plan: WorkflowPlanLike;
  missing: MissingCredentialsResult;
  // The setup gate only fires for genuine acquisitions, but carry the editable
  // mode explicitly so createRunAndReady records it after the wizard.
  canEdit: boolean;
  canPublish: boolean;
}

// Exported for unit testing the gating/threading (canEdit/canPublish derivation +
// targetWorkflowId binding) without going through the Suspense `use(params)` shell.
export function ApplicationLayoutInner({ publicationId, children }: { publicationId: string; children: ReactNode }) {
  const t = useTranslations('applications');
  // CE-cloud parity: a cloud-linked CE renders CLOUD publications whose publisher
  // ids live in the cloud user namespace (absent from the local auth DB). Mirror the
  // marketplace-preview computation and thread a `remote` flag so the Info-panel
  // publisher menu opens the CLOUD profile via the cloud proxy instead of a local
  // /app/u/{handle} (which 404s and bounces to the login page). ApplicationDetailView
  // also ORs in publication.remote (stamped by resolveApplicationPublication on the
  // cloud by-id fallback), so a cloud-acquired app is remote even before this resolves.
  const { isInstallCloudLinked } = useCeCloudLinkStatus();
  const remote = IS_CE && isInstallCloudLinked;
  const [phase, setPhase] = useState<LayoutPhase>('loading');
  const [data, setData] = useState<{
    workflowId: string;
    runId: string;
    title: string;
    publisherName?: string;
    publisherId?: string;
    publication: WorkflowPublication;
    // canEdit: the resolved workflow is editable in place by the caller, either
    //   their OWN acquired clone (acquirer, bound to the clone) OR their own
    //   publication's SOURCE workflow (publisher, bound to the source). The run/
    //   edit toggle is surfaced and the standard save-on-run persists via PUT /plan.
    // canPublish: the caller owns the publication (publisher) and may push their
    //   edited source to the live snapshot via "Publish update" (updatePublication).
    // Both false for the anonymous preview, which stays read-only.
    canEdit: boolean;
    canPublish: boolean;
  } | null>(null);
  const [setupGate, setSetupGate] = useState<SetupGateState | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Avoid duplicate auto-init when ApplicationLayoutInner re-renders mid-flow.
  const inFlightRef = useRef(false);
  // Separate lock for the run-creation step. The wizard fires onCredentialAdded
  // for each connection AND a final onComplete; if the second-to-last and last
  // events both observe count===0 they would otherwise race into two
  // concurrent executeWorkflow calls - leading to two run rows.
  const runCreationInFlightRef = useRef(false);

  /**
   * Create the run, then transition to 'ready'. Extracted so the pre-flight
   * gate can defer this step until the user finishes the setup wizard
   * (or skips it).
   */
  const createRunAndReady = useCallback(async (
    pub: WorkflowPublication,
    targetWorkflowId: string,
    plan: WorkflowPlanLike,
    canEdit: boolean,
    canPublish: boolean
  ) => {
    try {
      const result = await workflowService.executeWorkflow({
        workflowId: targetWorkflowId,
        planJson: JSON.stringify(plan),
        dataInputs: {},
        executionMode: 'automatic',
        source: 'application',
        publicationId,
      });
      if (!result?.runId) {
        setError(t('noRun'));
        setPhase('error');
        return;
      }
      setData({
        workflowId: targetWorkflowId,
        runId: result.runId,
        title: pub.title,
        publisherName: pub.publisherName,
        publisherId: pub.publisherId,
        publication: pub,
        canEdit,
        canPublish,
      });
      setPhase('ready');
    } catch (err) {
      console.error('[ApplicationLayout] Failed to auto-create run:', err);
      setError(t('startFailed'));
      setPhase('error');
    }
  }, [publicationId, t]);

  /**
   * Pre-flight credential check against the cloned workflow's plan. Only
   * fires for genuine acquisitions (the workflow id came from
   * acquiredApp.workflowId) - skip for publisher-self-view fallbacks where
   * the publisher already has their own creds.
   *
   * <p>Implementation lives in {@link checkMissingCredentialsAsync} so the
   * pre-flight gate and the panel-level {@code useMissingCredentials} hook
   * derive their results from the exact same code path.</p>
   */
  const checkMissingCredentials = useCallback(
    (plan: WorkflowPlanLike): Promise<MissingCredentialsResult> =>
      checkMissingCredentialsAsync(plan),
    []
  );

  const initializeApplication = useCallback(async (pub: WorkflowPublication) => {
    try {
      console.log('[AppLayout] initializeApplication start', { publicationId, showcaseRunId: pub.showcaseRunId, workflowId: pub.workflowId });

      const acquiredRes = await publicationService.getAcquiredApplications();

      // Determine execution workflowId
      const acquiredApp = acquiredRes.applications?.find(
        (app) => app.sourcePublicationId === publicationId
      );

      let targetWorkflowId: string | null = null;
      let isClonedAcquisition = false;
      let isOwnerSource = false;
      if (acquiredApp?.workflowId) {
        targetWorkflowId = acquiredApp.workflowId;
        isClonedAcquisition = true;
        console.log('[AppLayout] Using acquired workflowId:', targetWorkflowId);
      } else if (pub.ownedByMe && pub.workflowId) {
        // Publisher viewing their OWN publication: bind to the editable SOURCE
        // workflow (type=WORKFLOW), not the frozen preview clone. Editing the
        // preview clone would be a trap (it is overwritten from the source on
        // every republish); editing the source is what "Publish update" snapshots.
        targetWorkflowId = pub.workflowId;
        isOwnerSource = true;
        console.log('[AppLayout] Owner editing source workflowId:', targetWorkflowId);
      } else {
        const appWorkflow = await publicationService.getApplicationWorkflow(publicationId);
        targetWorkflowId = appWorkflow?.workflowId || pub.workflowId;
        console.log('[AppLayout] Using publisher workflowId:', targetWorkflowId);
      }

      // Run-only acquired applications (decouple-to-editable-workflow): the
      // acquirer's APPLICATION clone is NOT editable in place - editing lives in the
      // separate WORKFLOW twin that acquiring also creates (visible in /app/workflows).
      // So isClonedAcquisition no longer grants canEdit; the backend PUT /plan also
      // 409s an APPLICATION row. The PUBLISHER still edits their own SOURCE workflow
      // (type=WORKFLOW, not blocked) and may push to the live snapshot (Publish update).
      const canEdit = isOwnerSource;
      const canPublish = isOwnerSource;

      if (!targetWorkflowId) {
        setError(t('noWorkflow'));
        setPhase('error');
        return;
      }

      const existingRun = await workflowService.getApplicationRun(targetWorkflowId, publicationId);
      if (existingRun?.runId) {
        // Run already exists - skip the pre-flight gate (the user already
        // started this app once; if creds are still missing the panel-level
        // setup block in PublicationInfoPanel will still surface them).
        setData({
          workflowId: targetWorkflowId,
          runId: existingRun.runId,
          title: pub.title,
          publisherName: pub.publisherName,
          publisherId: pub.publisherId,
          publication: pub,
          canEdit,
          canPublish,
        });
        setPhase('ready');
        return;
      }

      // No existing run → fetch plan and decide whether to gate on creds.
      const workflow = await workflowService.getWorkflow(targetWorkflowId);
      const workflowPlan = workflow?.plan as WorkflowPlanLike | undefined;
      if (!workflowPlan) {
        setError(t('noSnapshot'));
        setPhase('error');
        return;
      }

      // Pre-flight only for cloned acquisitions. Publisher-self-view falls
      // through directly so the publisher's existing credential setup keeps
      // working without an extra hoop.
      if (isClonedAcquisition) {
        const missing = await checkMissingCredentials(workflowPlan);
        if (missing.wizardable.length + missing.manual.length > 0) {
          setSetupGate({
            publication: pub,
            targetWorkflowId,
            plan: workflowPlan,
            missing,
            canEdit,
            canPublish,
          });
          setPhase('setup');
          return;
        }
      }

      await createRunAndReady(pub, targetWorkflowId, workflowPlan, canEdit, canPublish);
    } catch (err) {
      console.error('[ApplicationLayout] Failed to initialize:', err);
      setError(t('loadFailed'));
      setPhase('error');
    }
  }, [publicationId, t, checkMissingCredentials, createRunAndReady]);

  const initialize = useCallback(async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    try {
      const pub = await resolveApplicationPublication(publicationId);
      await initializeApplication(pub);
    } catch (err) {
      console.error('[ApplicationLayout] Failed to initialize:', err);
      setError(t('loadFailed'));
      setPhase('error');
    } finally {
      inFlightRef.current = false;
    }
  }, [publicationId, t, initializeApplication]);

  useEffect(() => {
    initialize();
  }, [initialize]);

  /**
   * Re-evaluate the cred gate after the wizard finishes. The user may have
   * connected all of them (→ proceed to executeWorkflow) or only some (→ stay
   * on the gate with the remaining list).
   *
   * <p>Guarded by {@code runCreationInFlightRef} so two concurrent invocations
   * (wizard's per-cred {@code onCredentialAdded} firing back-to-back with the
   * final {@code onComplete}) cannot both observe count === 0 and race into
   * two {@code executeWorkflow} calls.</p>
   */
  const handleSetupGateUpdated = useCallback(async () => {
    if (!setupGate) return;
    const next = await checkMissingCredentials(setupGate.plan);
    if (next.wizardable.length + next.manual.length === 0) {
      if (runCreationInFlightRef.current) return;
      runCreationInFlightRef.current = true;
      const gate = setupGate;
      setSetupGate(null);
      try {
        await createRunAndReady(gate.publication, gate.targetWorkflowId, gate.plan, gate.canEdit, gate.canPublish);
      } finally {
        runCreationInFlightRef.current = false;
      }
      return;
    }
    setSetupGate({ ...setupGate, missing: next });
  }, [setupGate, checkMissingCredentials, createRunAndReady]);

  /**
   * "Skip" button on the gate - proceed with the run anyway. The run will
   * likely fail at the missing-cred step but the user is making an informed
   * choice (and the panel-level Setup block will continue to nag them).
   */
  const handleSetupGateSkip = useCallback(async () => {
    if (!setupGate) return;
    if (runCreationInFlightRef.current) return;
    runCreationInFlightRef.current = true;
    const gate = setupGate;
    setSetupGate(null);
    try {
      await createRunAndReady(gate.publication, gate.targetWorkflowId, gate.plan, gate.canEdit, gate.canPublish);
    } finally {
      runCreationInFlightRef.current = false;
    }
  }, [setupGate, createRunAndReady]);

  if (phase === 'error' && error) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-theme-muted">{error}</p>
      </div>
    );
  }

  if (phase === 'setup' && setupGate) {
    return (
      <SetupRequiredState
        appTitle={setupGate.publication.title}
        wizardable={setupGate.missing.wizardable}
        manual={setupGate.missing.manual}
        onConnectionsUpdated={handleSetupGateUpdated}
        onSkip={handleSetupGateSkip}
      />
    );
  }

  if (phase !== 'ready' || !data) {
    return <WorkflowLoadingState />;
  }

  return (
    <WorkflowModeProvider workflowId={data.workflowId} initialRunId={data.runId}>
      <WorkflowRunProvider>
        <ApplicationDetailView
          workflowId={data.workflowId}
          runId={data.runId}
          title={data.title}
          publisherName={data.publisherName}
          publisherId={data.publisherId}
          publication={data.publication}
          canEdit={data.canEdit}
          canPublish={data.canPublish}
          remote={remote}
        />
        {children}
      </WorkflowRunProvider>
    </WorkflowModeProvider>
  );
}
