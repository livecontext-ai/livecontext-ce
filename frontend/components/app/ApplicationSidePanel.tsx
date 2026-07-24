'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { AlertCircle } from 'lucide-react';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { orchestratorApi } from '@/lib/api';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';
import { useWorkflowStreaming } from '@/app/workflows/builder/hooks/execution';
import { getActivePublicPreview, usePublicationSnapshot } from '@/contexts/PublicationSnapshotContext';
import { ApplicationTabContent, type ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import LoadingSpinner from '@/components/LoadingSpinner';

// ── Tab content: delegates to ApplicationTabContent with full interaction ──

interface ApplicationPanelContentProps {
  publicationId: string;
  /**
   * Live runId override. When provided, skips the showcase-snapshot path
   * and renders the application against THIS specific run - used by
   * ApplicationVisualizeCard so the agent's execute marker opens a panel
   * tab showing the actual execution's interface (instead of the frozen
   * publish-time showcase that may be empty / out-of-date).
   */
  runId?: string;
}

export function ApplicationPanelContent({ publicationId, runId: runIdOverride }: ApplicationPanelContentProps) {
  const [panelData, setPanelData] = useState<{
    runId: string;
    workflowId: string;
    appConfig: ApplicationConfig;
  } | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Read planSnapshot lookups from the publication preview context (mounted by
  // PublicationPreviewShell upstream). When non-null we MUST stay on snapshot
  // data and never call live tenant endpoints - the publisher viewing their
  // own card must see exactly what an anonymous visitor sees.
  const snapshotCtx = usePublicationSnapshot();

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const previewCtx = getActivePublicPreview();
        const inPreviewContext = !!previewCtx;

        // 1. Publication metadata. Marketplace preview stays anonymous and
        // sanitized; application/share contexts keep the active auth or share token.
        // A cloud-linked CE preview routes the public read through the cloud proxy
        // (publicCtx.remote) - the cloud id is absent from the local DB.
        const pub = inPreviewContext
          ? await publicationService.getPublicationByIdPublic(publicationId, previewCtx!.remote)
          : await publicationService.getPublicationById(publicationId);
        if (!pub.workflowId) { setError('No workflow'); return; }

        // 2. Determine the rendering surface.
        // When the publication snapshot context is active (we're inside a
        // PublicationPreviewShell - chat card, marketplace card, marketplace
        // preview page), we ALWAYS render against the publisher's
        // showcaseRunId via the public endpoints. No acquired-app lookup, no
        // live latest-run resolution, no cross-tenant getWorkflow.
        //
        // When the context is null we're on /app/applications/{publicationId}
        // - the user's own acquired application. There the user is allowed to
        // see their own runs, so we resolve the cloned workflow + latest run.
        let effectiveWorkflowId = pub.workflowId;
        let runId: string | undefined = pub.showcaseRunId ?? undefined;
        const planFromSnapshot = snapshotCtx?.planSnapshot ?? null;
        let interfaces: any[] = Array.isArray(planFromSnapshot?.interfaces)
          ? planFromSnapshot.interfaces
          : [];

        // Live-runId override path: caller passed an explicit runId (e.g.
        // ApplicationVisualizeCard wants to render THIS execution's
        // interface, not the frozen showcase). Use the live workflow lookup
        // for interfaces but pin the runId to the override - bypasses
        // showcase resolution entirely.
        if (runIdOverride) {
          runId = runIdOverride;
          try {
            const acquired = await publicationService.getAcquiredApplications();
            const match = acquired.applications?.find(
              (app) => app.sourcePublicationId === publicationId
            );
            if (match?.workflowId) effectiveWorkflowId = match.workflowId;
          } catch { /* keep publisher's workflowId */ }
          if (interfaces.length === 0) {
            try {
              const workflow = await orchestratorApi.getWorkflow(effectiveWorkflowId);
              interfaces = (workflow as any)?.plan?.interfaces || [];
            } catch {
              interfaces = pub.planSnapshot?.interfaces || [];
            }
          }
        } else if (!inPreviewContext) {
          try {
            const acquired = await publicationService.getAcquiredApplications();
            const match = acquired.applications?.find(
              (app) => app.sourcePublicationId === publicationId
            );
            if (match?.workflowId) {
              effectiveWorkflowId = match.workflowId;
            }
          } catch {
            // Not acquired - keep publisher's workflow id (published variant).
          }

          // Find-or-create the application run - SAME contract as the full
          // application views (SharedApplication + the /app/applications layout):
          // prefer the application's own run, and create the first one on demand
          // instead of dead-ending on "No run available". This is what makes the
          // chat panel render the interface live, like a real interface, even for
          // an app whose workflow was never run (showcaseRunId absent).
          try {
            const appRun = await workflowService.getApplicationRun(effectiveWorkflowId, publicationId);
            if (appRun?.runId) runId = appRun.runId;
          } catch {
            // No application run yet - keep the showcaseRunId seed; create below.
          }

          // Fetch the plan once: it feeds BOTH the interface list and the
          // executeWorkflow fallback (avoids a second getWorkflow round-trip).
          let workflowPlan: any = undefined;
          if (interfaces.length === 0 || !runId) {
            try {
              const workflow = await orchestratorApi.getWorkflow(effectiveWorkflowId);
              workflowPlan = (workflow as any)?.plan;
              if (interfaces.length === 0) {
                interfaces = workflowPlan?.interfaces || [];
              }
            } catch {
              // Can't reach live workflow - fall back to publication snapshot.
              if (interfaces.length === 0) {
                interfaces = pub.planSnapshot?.interfaces || [];
              }
            }
          }

          if (!runId && workflowPlan) {
            // No showcase run, no application run → create the first run
            // (automatic, source='application'), exactly like SharedApplication.
            try {
              const created = await workflowService.executeWorkflow({
                workflowId: effectiveWorkflowId,
                planJson: JSON.stringify(workflowPlan),
                dataInputs: {},
                executionMode: 'automatic',
                source: 'application',
                publicationId,
              });
              if (created?.runId) runId = created.runId;
            } catch {
              // Creation failed - fall through to the no-run guard below.
            }
          }
        } else if (interfaces.length === 0) {
          // Preview context but provider's planSnapshot is empty - pick it up
          // straight off the publication entity.
          interfaces = pub.planSnapshot?.interfaces || [];
        }

        if (!runId) { setError('No run available'); return; }

        // The app's declared ENTRY page wins (same rule as the showcase/card previews:
        // ApplicationShowcaseResolver = flagged entry, else first). Only when no entry
        // is flagged fall back to the first interactive interface, then the showcase id.
        const iface = interfaces.find((i: any) => i?.isEntryInterface === true)
          ?? interfaces.find((i: any) =>
            i?.actionMapping && Object.keys(i.actionMapping).length > 0
          );

        if (!cancelled) {
          const interfaceId = iface?.id || pub.showcaseInterfaceId || '';
          // The display format is not passed down: it belongs to the interface, and
          // ApplicationTabContent resolves it from the render/interface it already loads.
          setPanelData({
            runId,
            workflowId: effectiveWorkflowId,
            appConfig: {
              interfaceId,
              label: iface?.label || pub.title || 'Application',
              actionMapping: iface?.actionMapping || {},
            },
          });
        }
      } catch {
        if (!cancelled) setError('Failed to load application');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [publicationId, snapshotCtx?.planSnapshot, runIdOverride]);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <LoadingSpinner />
      </div>
    );
  }

  if (error || !panelData) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center p-8">
        <AlertCircle className="h-8 w-8 text-red-500 mb-3" />
        <p className="text-sm text-red-600 dark:text-red-400">{error || 'Failed to load'}</p>
      </div>
    );
  }

  // WorkflowModeProvider with initialRunId activates run mode (isRunMode=true)
  // WorkflowRunProvider is already in the app layout (or in PublicationPreviewShell when preview)
  const previewActive = !!getActivePublicPreview();
  return (
    <WorkflowModeProvider workflowId={panelData.workflowId} initialRunId={panelData.runId} readOnly={previewActive}>
      <ApplicationPanelInner config={panelData.appConfig} runId={panelData.runId} workflowId={panelData.workflowId} />
    </WorkflowModeProvider>
  );
}

/** Inner component - has access to WorkflowRunContext from the layout */
function ApplicationPanelInner({ config, runId, workflowId }: { config: ApplicationConfig; runId: string; workflowId: string }) {
  const [, runContext] = useRun(runId);
  const previewActive = !!getActivePublicPreview();
  useWorkflowStreaming(runId, !previewActive);

  const handleAction = useCallback(async (triggerRef: string, data: Record<string, unknown>) => {
    if (!runContext) return;
    // No actions in preview context - the showcase clone is read-only.
    if (getActivePublicPreview()) return;

    // Parse triggerRef: "trigger:label:actiontype" → triggerKey + triggerType
    // Same logic as WorkflowBuilder.handleApplicationAction
    const parts = triggerRef.split(':');
    const actionType = parts.length >= 3 ? parts[parts.length - 1] : 'click';
    const triggerType = actionType === 'submit' ? 'form' :
                        actionType === 'message' ? 'chat' : 'manual';
    const triggerKey = parts.length >= 3 ? parts.slice(0, -1).join(':') : triggerRef;

    await runContext.executeStep(runId, triggerKey, data, triggerType);
  }, [runId, runContext]);

  return (
    <ApplicationTabContent config={config} runId={runId} workflowId={workflowId} onAction={handleAction} />
  );
}
