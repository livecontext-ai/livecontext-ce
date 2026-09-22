'use client';

import React, { useState, useEffect } from 'react';
import { AlertCircle } from 'lucide-react';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { orchestratorApi } from '@/lib/api';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { getActivePublicPreview, usePublicationSnapshot } from '@/contexts/PublicationSnapshotContext';
import { type ApplicationConfig, type ApplicationTemplateSource } from '@/components/chat/ApplicationTabContent';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import LoadingSpinner from '@/components/LoadingSpinner';
import { applicationPanelTabId } from '@/lib/sidePanel/tabResource';

// Behind React.lazy: this pulls in the whole workflow builder, and the surfaces
// that merely OFFER an application (a chat card, the tab picker, a project page)
// must not carry that chunk. It is also the import shape the builder panel is
// reached by everywhere else, which keeps it out of the static module cycle that
// once left an inspector column undefined in a production build.
const WorkflowBuilderPanelContent = React.lazy(() =>
  import('@/components/app/WorkflowBuilderPanelContent')
    .then((m) => ({ default: m.WorkflowBuilderPanelContent })),
);

// ── Tab content: resolves the publication, then hands it to the shared
//    side-panel workflow composition (canvas + run + triggers + AI chat). ──

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
    /**
     * Every interface of the application, in plan order, as the carousel's
     * starting list. The canvas replaces it with its own (canvas x-order) as
     * soon as it has loaded; this only exists so the Application sub-tab is
     * there from the first frame instead of a second later.
     */
    appConfigs: ApplicationConfig[];
    /** Frozen publication plan - the only one a preview visitor may render. */
    planOverride?: any;
    /**
     * The caller may CHANGE the workflow this panel bound: it drives the canvas'
     * edit toggle and the Share / Save bar. True only for the publisher's
     * own SOURCE workflow - never for an installed application (a frozen clone
     * the backend refuses to write) and never for someone else's publication.
     */
    canEdit: boolean;
    /**
     * The workflows this tenant can reach from the application are its own, so a
     * sub-workflow opened from the canvas may be edited even when the application
     * itself is frozen. False for a publication that is neither owned nor installed
     * (its whole graph belongs to the publisher) and in a preview, which edits
     * nothing at all.
     */
    tenantOwnsWorkflows: boolean;
    /**
     * Names the publication the toolbar's template actions read from. It enables
     * loading the example values; the reset needs `canReset` on top of it, because
     * that one writes. Withheld entirely in a preview context - a read-only
     * showcase has no tenant data to seed or wipe.
     */
    templateSource?: ApplicationTemplateSource;
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
        // An INSTALLED application binds the acquired APPLICATION clone, which is
        // frozen: the backend refuses every plan write on it (409, "it is a frozen
        // acquired marketplace clone"). The clone lives in the caller's own tenant,
        // so ownership of SOMETHING is not the question - what matters is whether
        // this workflow is an install, which is tracked separately and wins below.
        let isClonedAcquisition = false;
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
            if (match?.workflowId) {
              effectiveWorkflowId = match.workflowId;
              isClonedAcquisition = true;
            }
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
              isClonedAcquisition = true;
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

        if (!cancelled) {
          // The display format is not passed down: it belongs to the interface, and
          // ApplicationTabContent resolves it from the render/interface it already loads.
          //
          // Every interface, not just the entry one: the panel now shows the
          // application as the carousel does everywhere else, and the entry page
          // is the one the carousel opens on (it reads `isEntryInterface`).
          const appConfigs: ApplicationConfig[] = interfaces.length > 0
            ? interfaces.map((i: any) => ({
              interfaceId: i?.id || '',
              label: i?.label || pub.title || 'Application',
              actionMapping: i?.actionMapping || {},
              nodeId: `interface:${normalizeLabel(i?.label || '')}`,
              isEntryInterface: i?.isEntryInterface === true,
            }))
            // No plan reachable at all (a preview whose snapshot is empty): the
            // publication's own showcase interface is the only thing left to
            // render. There is no entry flag to consult here - the list this
            // would have searched is the empty one that led to this branch.
            : [{
              interfaceId: pub.showcaseInterfaceId || '',
              label: pub.title || 'Application',
              actionMapping: {},
            }];
          setPanelData({
            runId,
            workflowId: effectiveWorkflowId,
            appConfigs,
            planOverride: inPreviewContext
              ? (planFromSnapshot ?? pub.planSnapshot ?? undefined)
              : undefined,
            // Exactly the rule the application page draws (`canEdit = isOwnerSource`):
            // only the publisher's own SOURCE workflow is editable here. An
            // installed application is a frozen clone whose plan the backend
            // refuses to write, and a publication the caller neither owns nor
            // installed resolves to the PUBLISHER's workflow, where a save is
            // refused too - offering an edit toggle and a Save on either is a
            // promise the surface cannot keep.
            canEdit: !inPreviewContext && !isClonedAcquisition && pub.ownedByMe === true,
            tenantOwnsWorkflows: !inPreviewContext && (isClonedAcquisition || pub.ownedByMe === true),
            // The example values help anyone whose run has no data yet, the publisher
            // included. The RESET is offered on exactly one condition: this panel is
            // bound to the installed clone, whose tables are the ones the endpoint
            // rewrites. Keying it on "not the publisher" was the same conflation the
            // edit gate above just lost: it offered the reset to anyone who is not the
            // publisher, install or no install, so a visitor merely looking at an
            // application got a button whose endpoint has no clone to resolve (404) -
            // and, on a shared link, one pointed at the OWNER's install. The
            // application page took the same correction, through `isInstalledClone`.
            templateSource: !inPreviewContext
              ? { publicationId, remote: !!pub.remote, canReset: isClonedAcquisition }
              : undefined,
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

  // The panel shows the application the way every other application surface does
  // (ApplicationDetailView, the marketplace preview): the carousel of its pages,
  // with the workflow canvas, the run, the triggers and the AI chat one sub-tab
  // away. It used to render a single interface with no sub-tabs at all, so an
  // application opened here could not be watched running.
  //
  // WorkflowBuilderPanelContent owns the composition (canvas + run binding +
  // its own providers); `applicationFirst` is what makes the Application the
  // tab it opens on rather than the canvas.
  const previewActive = !!getActivePublicPreview();
  return (
    <React.Suspense fallback={<div className="flex items-center justify-center h-full"><LoadingSpinner /></div>}>
      <WorkflowBuilderPanelContent
        workflowId={panelData.workflowId}
        runId={panelData.runId}
        hostTabId={applicationPanelTabId(publicationId, runIdOverride)}
        readOnly={previewActive}
        /* False for the two workflows a save cannot reach: an INSTALLED
           application (a frozen APPLICATION clone the backend refuses to write)
           and someone else's publication (which resolves to the PUBLISHER's
           workflow). The application itself stays fully interactive either way -
           that is what the panel is for; only the workflow behind it is locked.

           It also withholds the canvas Run button, which the backend WOULD accept
           on an installed clone (the execute path skips its pre-run auto-save for
           exactly this type). That is deliberate, and it is what the application
           page does: an installed application is started from its own triggers,
           the ones its author exposed, not by firing the raw workflow from a
           canvas its owner cannot edit. Offering a Run beside a canvas with no
           Save would also be the odd one out of a bar that is otherwise gone. */
        canEditWorkflow={panelData.canEdit}
        /* What a sub-workflow node opens is a DIFFERENT question: an install
           freezes the application's own plan, but the sub-workflows it calls were
           cloned as ordinary workflows in this tenant and are writable. What must
           not be editable is a publication that is neither owned nor installed,
           where every workflow reachable from here is the publisher's. */
        canEditRelatedWorkflows={panelData.tenantOwnsWorkflows}
        applicationFirst
        initialApplicationConfigs={panelData.appConfigs}
        applicationTemplateSource={panelData.templateSource}
        planOverride={panelData.planOverride}
      />
    </React.Suspense>
  );
}
