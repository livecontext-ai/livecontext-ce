'use client';

import React, { useState, useCallback, useRef, useEffect, useId } from 'react';
import { LayoutDashboard } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import type { TriggerDataForPanel } from '@/app/workflows/builder/components/WorkflowBuilder';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import { ApplicationCarousel } from '@/components/chat/ApplicationCarousel';
import type { WorkflowPublication } from '@/lib/api';
import { WorkflowRunCanvas, type RunInfoChangeData } from '@/components/workflow/WorkflowRunCanvas';
import { WorkflowModeProvider, useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  WorkflowPanelContent,
  setPendingActivateTab,
} from '@/components/app/WorkflowPanelContent';
import { OPEN_RUN_PANEL_EVENT, type OpenRunPanelDetail } from '@/components/workflow/run-panel/runPanelBus';
import { useInterfacePaginationStore, carouselKeyFor } from '@/lib/stores/interface-pagination-store';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { isNavigateRef, navigateTargetLabel } from '@/app/workflows/builder/utils/interfaceActionRefs';
import { PublicationInfoPanel } from '@/components/marketplace/PublicationInfoPanel';
import { ApplicationSettingsMenu } from '@/components/marketplace/ApplicationSettingsMenu';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { VerifiedBadge } from '@/components/profile/VerifiedBadge';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

import { WorkflowLoadingState } from '../workflow/WorkflowLoadingState';
import { WorkflowUnauthorizedState } from '../workflow/WorkflowUnauthorizedState';
import { useAutoCollapseSidebar } from '../workflow/hooks';
import { OPEN_TRIGGER_TAB_EVENT, findTriggerTabConfig, type OpenTriggerTabDetail } from '@/lib/workflow/triggerTabEvent';
import { APPLICATION_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';

// ============================================
// Constants
// ============================================


// ============================================
// Types
// ============================================

interface ApplicationDetailViewProps {
  workflowId: string;
  runId: string;
  title?: string;
  publisherName?: string;
  publisherId?: string;
  planOverride?: any;
  publication?: WorkflowPublication;
  showInfoPanel?: boolean;
  /**
   * Anonymous-visitor marketplace preview. Skips the {@code isAuthenticated}
   * redirect so the full shell (ApplicationCarousel + side-panel workflow
   * canvas) renders for signed-out users. Upstream must have:
   * <ul>
   *   <li>wrapped the tree in {@code PublicationSnapshotProvider} with
   *       {@code publicationId} + {@code showcaseRunId}, so
   *       {@code useInterfaceRender} &amp; friends route to the public
   *       {@code /showcase-render} endpoint instead of the auth'd one;</li>
   *   <li>passed {@code planOverride} (the publication's frozen planSnapshot)
   *       so the workflow canvas renders without hitting the tenant's plan API.</li>
   * </ul>
   * Any stray auth'd fetch from inner hooks is already wrapped in a
   * try/catch and fails silently - no visible error, no cascade.
   */
  publicPreviewMode?: boolean;
  /**
   * This page is bound to a workflow the caller may CHANGE, which is only ever the
   * publisher's own SOURCE workflow (`isOwnerSource` in ApplicationLayout). When
   * true the embedded canvas surfaces the run/edit toggle and persists through the
   * normal save. False for an INSTALLED application - its clone is frozen and the
   * backend refuses the plan write (409) - and false for a preview, which is
   * read-only. The side panel derives the same answer the same way.
   */
  canEdit?: boolean;
  /**
   * The caller owns this publication (publisher). It surfaces nothing today: the
   * "Publish update" button was removed and only its logic is kept (see the note
   * on the render). The layout sets it from the same `isOwnerSource` as canEdit,
   * so the two move together; it stays separate because publishing is a different
   * right from editing, and a caller could hold one without the other.
   */
  canPublish?: boolean;
  /**
   * This page is bound to the caller's INSTALLED clone rather than to a source
   * workflow. Only the caller's own install answers it, so only the layout - which
   * did the acquired lookup - can: a publication being an application, or being
   * someone else's, says nothing about whether THIS workspace installed it. It
   * decides one thing: whether "reset the data", which always targets that clone,
   * has a target on this screen. Omitted (false) by the preview and by the
   * shared-link page: that page CAN bind an install, but it binds the OWNER's, and
   * its visitor must not be handed a button that wipes someone else's data.
   */
  isInstalledClone?: boolean;
  /**
   * The publication is sourced from the CLOUD marketplace (a cloud-linked CE
   * rendering remote content): publisher / reviewer ids are then CLOUD user ids
   * absent from this install's auth DB. Threaded down to the Info panel so its
   * publisher menu resolves the profile through the cloud proxy and opens the
   * CLOUD profile page, never a local {@code /app/u/{handle}} (which 404s and
   * bounces to the login page). Callers compute it from the install cloud-link
   * status ({@code IS_CE && isInstallCloudLinked}); when omitted the component
   * falls back to {@code publication.remote}. A truthy value from EITHER source
   * wins, so a remote publisher is never downgraded to the broken local route.
   */
  remote?: boolean;
}

// ============================================
// Component
// ============================================

/**
 * ApplicationDetailView - Interface-first view for application mode.
 * Main view: ApplicationCarousel (interface iframes).
 * SidePanel: single "Application Panel" tab (keepMounted) with sub-tabs: AI Chat, Triggers, Workflow canvas.
 */

/**
 * Diagnostic wrapper that emits a single MOUNT/UNMOUNT log to confirm the
 * SidePanel actually mounts the keepMounted tab content. Pairs with the
 * WorkflowPanelContent / WorkflowRunCanvas / WorkflowBuilder mount logs to
 * pinpoint where the chain breaks on the marketplace preview route. Remove
 * once the application-carousel-blank bug is root-caused.
 */
function TabContentDebugWrapper({ children, tabContentKey }: { children: React.ReactNode; tabContentKey: string }) {
  useEffect(() => {
    console.log('[AppDebug] tab content wrapper MOUNT', { tabContentKey });
    return () => console.log('[AppDebug] tab content wrapper UNMOUNT', { tabContentKey });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <>{children}</>;
}

export function ApplicationDetailView({ workflowId, runId, title, publisherName, publisherId, planOverride, publication, showInfoPanel = true, publicPreviewMode = false, canEdit = false, canPublish = false, isInstalledClone = false, remote }: ApplicationDetailViewProps) {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { setRunId: setContextRunId, isPreviewOnly } = useWorkflowMode();
  const sidePanel = useSidePanelSafe();
  const t = useTranslations('common');
  const tApp = useTranslations('applications');
  const [isPublishing, setIsPublishing] = useState(false);

  /**
   * Publisher pushes their edited source to the live snapshot. Save-then-publish:
   * persist the source plan (the same workflowViewSave the canvas already handles),
   * then updatePublication re-snapshots it (and refreshes the publisher's preview
   * clone). Metadata is re-sent unchanged so only the plan moves. Listed apps
   * (PUBLIC/UNLISTED) re-enter review, so we confirm first.
   */
  const handlePublishUpdate = useCallback(async () => {
    if (!publication?.id || isPublishing) return;
    const listed = publication.visibility === 'PUBLIC' || publication.visibility === 'UNLISTED';
    const confirmMsg = listed ? tApp('publishUpdateConfirmReview') : tApp('publishUpdateConfirm');
    if (typeof window !== 'undefined' && !window.confirm(confirmMsg)) return;
    setIsPublishing(true);
    try {
      // 1. Persist the source edits first. The canvas listens for workflowViewSave
      //    and emits workflowViewSaveComplete with { success }. The timeout guards
      //    the not-dirty / no-listener case (nothing to save -> proceed).
      const saveOk = await new Promise<boolean>((resolve) => {
        let settled = false;
        let timer: ReturnType<typeof setTimeout>;
        const finish = (ok: boolean) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          window.removeEventListener('workflowViewSaveComplete', onComplete);
          resolve(ok);
        };
        const onComplete = (ev: Event) => {
          // Default true when the flag is absent; abort only on an explicit failure.
          finish((ev as CustomEvent).detail?.success !== false);
        };
        window.addEventListener('workflowViewSaveComplete', onComplete);
        window.dispatchEvent(new CustomEvent('workflowViewSave', { detail: { workflowId } }));
        timer = setTimeout(() => finish(true), 4000);
      });
      if (!saveOk) {
        // The source plan failed to save - do NOT republish a stale snapshot.
        window.dispatchEvent(new CustomEvent('workflowToast', {
          detail: { type: 'error', message: tApp('publishUpdateError') },
        }));
        return;
      }
      // 2. Re-snapshot the source into the live publication (metadata unchanged).
      // Known limitation: re-sending the same showcaseRunId leaves showcaseChanged
      // false, so the marketplace PREVIEW render is not re-captured (only the
      // functional plan snapshot is). New installs get the new plan; the preview
      // image refreshes when the publisher re-picks a showcase run.
      const displayMode = (['WORKFLOW', 'INTERFACE', 'APPLICATION'] as const)
        .find((m) => m === publication.displayMode) ?? 'APPLICATION';
      await orchestratorApi.updatePublication(publication.id, {
        title: publication.title,
        description: publication.description,
        showcaseInterfaceId: publication.showcaseInterfaceId,
        showcaseRunId: publication.showcaseRunId,
        categoryId: publication.category?.id,
        creditsPerUse: publication.creditsPerUse,
        visibility: publication.visibility,
        displayMode,
      });
      window.dispatchEvent(new CustomEvent('workflowToast', {
        detail: { type: 'success', message: tApp('publishUpdateSuccess') },
      }));
    } catch (err) {
      console.error('[ApplicationDetailView] Publish update failed:', err);
      window.dispatchEvent(new CustomEvent('workflowToast', {
        detail: { type: 'error', message: tApp('publishUpdateError') },
      }));
    } finally {
      setIsPublishing(false);
    }
  }, [publication, workflowId, isPublishing, tApp]);

  // Stable per-instance id used as the inner React key on the SidePanel tab
  // content wrapper. SidePanelContext merges tabs by static `id` and React
  // reconciles `<div key={tab.id}>{tab.content}</div>` so the inner subtree
  // is reused across navigations to the SAME publication (same workflowId+runId
  // → identical key when we used `${workflowId}:${runId}`). The reused subtree
  // captures the previous parent's `setApplicationConfigs` closure → carousel
  // never receives configs → blank screen on revisit. `useId()` flips the key
  // on every fresh ApplicationDetailView mount, forcing React to unmount the
  // pass-1 WorkflowBuilder and mount a fresh one bound to the new parent.
  const instanceId = useId();

  // Ensure WorkflowModeContext is in run mode
  useEffect(() => {
    if (runId) setContextRunId(runId);
  }, [runId, setContextRunId]);

  // Notify ChatHeader of the active workflow+run so Logs button works
  // Store on window for late-mounting listeners, dispatch event for already-mounted ones
  useEffect(() => {
    if (workflowId && runId) {
      (window as any).__applicationWorkflow = { workflowId, runId };
      window.dispatchEvent(new CustomEvent('applicationWorkflowReady', {
        detail: { workflowId, runId },
      }));
    }
    return () => { (window as any).__applicationWorkflow = null; };
  }, [workflowId, runId]);

  useAutoCollapseSidebar(workflowId);

  // ── State ──
  const [workflowName, setWorkflowName] = useState<string | undefined>(title);
  const [triggerData, setTriggerData] = useState<TriggerDataForPanel | null>(null);
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>([]);

  // Sound. The application starts MUTED and the visitor turns it on from the
  // application controls: an app that plays audio the moment its page opens is
  // startling, and on a shared link the visitor did not choose to be there yet.
  //
  // The speaker itself lives in the controls toolbar, next to pagination and
  // fullscreen - it is a view control, peer to those, rather than an action. Only
  // the interface's own frame can tell whether there is anything to hear (it is
  // sandboxed and cross-origin), so the toolbar reads that from the frame directly
  // and this page only owns the state.
  const [soundMuted, setSoundMuted] = useState(true);
  const toggleSound = useCallback(() => setSoundMuted((muted) => !muted), []);

  // Phase 6c (2026-05-19) - clear workflow-bound config arrays on
  // workspace switch. The application view can remain on the same
  // (workflowId, runId) URL while the user switches workspace; without
  // this reset the previous workspace's application/trigger configs
  // linger in the carousel until WorkflowBuilder re-emits.
  useOrgScopedReset(() => {
    setApplicationConfigs([]);
    setTriggerData(null);
    setWorkflowName(title);
  });

  // Refs passed to WorkflowRunCanvas (parent needs access for application action handler)
  const executeTriggerRef = useRef<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>(null);
  const applicationActionRef = useRef<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>(null);
  const canvasNodesRef = useRef<Node<BuilderNodeData>[]>([]);

  // ── Callbacks ──
  const handleRunInfoChange = useCallback((_data: RunInfoChangeData) => {
    // Status badge removed - callback kept for WorkflowRunCanvas contract
  }, []);

  const handleWorkflowLoaded = useCallback((info: { name?: string; id?: string }) => {
    setWorkflowName(info.name || title);
  }, [title]);

  // ── Diagnostic: log applicationConfigs lifecycle so we can confirm whether
  // a blank preview is "configs never populated" vs "carousel mounted but empty".
  // Remove once the marketplace-preview blank-screen bug is root-caused. ──
  useEffect(() => {
    console.log('[AppDebug] ApplicationDetailView mount/update', {
      workflowId, runId, publicPreviewMode,
      planOverridePresent: !!planOverride,
      planOverrideShape: planOverride ? {
        triggers: Array.isArray(planOverride.triggers) ? planOverride.triggers.length : 'NOT_ARRAY',
        mcps: Array.isArray(planOverride.mcps) ? planOverride.mcps.length : 'NOT_ARRAY',
        edges: Array.isArray(planOverride.edges) ? planOverride.edges.length : 'NOT_ARRAY',
        interfaces: Array.isArray(planOverride.interfaces) ? planOverride.interfaces.length : 'NOT_ARRAY',
      } : null,
    });
  }, [workflowId, runId, publicPreviewMode, planOverride]);

  // Instance lifecycle marker. Pairs with WorkflowBuilder's MOUNT/UNMOUNT log
  // so we can tell whether a blank preview corresponds to a parent remount that
  // didn't propagate, vs a child remount that ran in isolation.
  useEffect(() => {
    console.log('[AppDebug] ApplicationDetailView INSTANCE MOUNT', { workflowId, runId, publicPreviewMode });
    return () => console.log('[AppDebug] ApplicationDetailView INSTANCE UNMOUNT', { workflowId, runId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    console.log('[AppDebug] applicationConfigs changed', {
      length: applicationConfigs.length,
      ids: applicationConfigs.map(c => c.interfaceId),
      labels: applicationConfigs.map(c => c.label),
    });
  }, [applicationConfigs]);

  // ── Register SidePanel tab: Application Panel (keepMounted, with embedded workflow canvas) ──
  const hasRegisteredTabRef = useRef(false);
  useEffect(() => {
    console.log('[AppDebug] SidePanel registration effect fired', {
      sidePanelAvailable: !!sidePanel,
      alreadyRegistered: hasRegisteredTabRef.current,
      workflowId, runId,
    });
    if (!sidePanel || hasRegisteredTabRef.current) return;
    hasRegisteredTabRef.current = true;
    console.log('[AppDebug] SidePanel.addTab(application-panel) called');

    // Application Panel - triggers + AI chat + workflow canvas as internal sub-tab
    //
    // CRITICAL: the inner `key` on the content wrapper. SidePanel renders
    // keepMounted tabs with `<div key={tab.id}>{tab.content}</div>` and the
    // tab id is static ('application-panel'). When the user navigates to a
    // different publication, ApplicationDetailView remounts (outer key on
    // page-level Inner), calls addTab again, SidePanelContext merges the new
    // JSX over the old - but React reconciles based on the static tab.id,
    // so it REUSES the existing WorkflowPanelContent / WorkflowRunCanvas
    // instances. Their `useState(initialProp | cachedData...)` initializers
    // never re-run → state stays locked on the FIRST opened publication.
    // The inner `key={runId}` flips the keep-mounted subtree's identity, so
    // React unmounts the old tree and mounts a fresh one with clean state.
    // Composite key: instanceId is fresh per mount (forces remount on revisit
    // to same publication) - workflowId:runId concatenated for log debuggability.
    const tabContentKey = `${instanceId}:${workflowId}:${runId}`;
    sidePanel.addTab({
      id: APPLICATION_PANEL_TAB_ID,
      label: t('applicationPanel'),
      icon: <LayoutDashboard className="w-4 h-4" />,
      pinned: true,
      keepMounted: true,
      preferredWidth: 0.4,
      scope: ['/app/applications/*', '/app/marketplace/*/preview'],
      content: (
        <TabContentDebugWrapper key={tabContentKey} tabContentKey={tabContentKey}>
        <div className="flex-1 min-h-0 flex flex-col">
          <WorkflowPanelContent
            workflowId={workflowId}
            runId={runId}
            hostTabId={APPLICATION_PANEL_TAB_ID}
            runSurfaceId={instanceId}
            isPreviewOnly={isPreviewOnly}
            /* The same answer the canvas below uses for its edit toggle. An
               acquired application resolves to a workflow the caller may not
               change, and the panel's Share / version controls would be refused
               there. */
            canEditWorkflow={canEdit}
            workflowCanvasSlot={
              <WorkflowModeProvider workflowId={workflowId} initialRunId={runId} readOnly={isPreviewOnly}>
                <div className="h-full w-full relative overflow-x-auto">
                  <WorkflowRunCanvas
                    workflowId={workflowId}
                    runId={runId}
                    surfaceId={instanceId}
                    planOverride={planOverride}
                    hideToggle={!canEdit}
                    onWorkflowLoaded={handleWorkflowLoaded}
                    onRunInfoChange={handleRunInfoChange}
                    onTriggerConfigsChange={setTriggerData}
                    onApplicationConfigsChange={setApplicationConfigs}
                    executeTriggerRef={executeTriggerRef}
                    applicationActionRef={applicationActionRef}
                    nodesRef={canvasNodesRef}
                  />
                </div>
              </WorkflowModeProvider>
            }
          />
        </div>
        </TabContentDebugWrapper>
      ),
    });

    // Open panel
    sidePanel.setActiveTab(APPLICATION_PANEL_TAB_ID);
    sidePanel.open();
  }, [sidePanel]); // eslint-disable-line react-hooks/exhaustive-deps -- register once, all refs/callbacks are stable

  // ── Application action handler (navigate + regular actions) ──
  const handleApplicationAction = useCallback(async (
    triggerRef: string,
    data: Record<string, unknown>
  ) => {
    if (isNavigateRef(triggerRef)) {
      const targetLabel = navigateTargetLabel(triggerRef);
      if (targetLabel) {
        const normalizedTarget = normalizeLabel(targetLabel);
        const idx = applicationConfigs.findIndex(c =>
          normalizeLabel(c.label) === normalizedTarget
        );
        if (idx >= 0) {
          useInterfacePaginationStore.getState().setCarouselIndex(carouselKeyFor(workflowId, runId), idx);
          return;
        }
        console.warn('[ApplicationDetailView] Navigate target not found:', targetLabel);
      }
      return;
    }

    try {
      if (applicationActionRef.current) {
        await applicationActionRef.current(triggerRef, data);
      }
    } catch (err) {
      console.error('[ApplicationDetailView] Application action failed:', err);
    }
    // workflowId/runId: the carousel page is addressed per SURFACE now, so this
    // closure reads them and must not capture a stale pair.
  }, [applicationConfigs, workflowId, runId]);

  // ── Event bridges: dispatch data to WorkflowPanelContent ──
  useEffect(() => {
    const detail = {
      // Include workflowId in the dispatched detail AND in the cache so
      // late-mounting subscribers can validate the cache against THEIR own
      // workflowId - without this scoping, a user navigating from app A to a
      // visualize-popup of app B would have B's ApplicationTabContent read
      // A's stale cache and render A's trigger tabs.
      workflowId,
      configs: triggerData?.configs ?? [],
      activeTriggerId: triggerData?.activeTriggerId,
      readySteps: triggerData?.readySteps ?? new Set(),
      runStatus: triggerData?.runStatus,
      isStepByStepMode: triggerData?.isStepByStepMode,
    };
    // Cache on window so late-mounting subscribers (e.g. ApplicationTabContent
    // when the carousel page index switches) can read the current state without
    // missing the most recent dispatch. Mirrors __applicationWorkflow pattern
    // already used by ChatHeader for workflowId/runId.
    (window as any).__applicationTriggerData = detail;
    window.dispatchEvent(new CustomEvent('workflowPanelTriggerDataChange', { detail }));
  }, [triggerData, workflowId]);

  // Forward applicationConfigs to the side-panel WorkflowPanelContent so it can
  // surface an Application tab alongside the Workflow tab. Without this, the
  // side panel never learns the configs (only WorkflowDetailView dispatches
  // them) and the "Application Mode" button on an interface node has no
  // destination to navigate to.
  //
  // Mirrors WorkflowDetailView's `runIdProp ? configs : []` gating so route
  // transitions don't leak configs from a stale run.
  useEffect(() => {
    const configs = runId ? applicationConfigs : [];
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId, configs },
    }));
  }, [applicationConfigs, runId, workflowId]);

  // ── Intercept tab open events ──
  useEffect(() => {
    const handleOpenTriggerTab = (event: CustomEvent<OpenTriggerTabDetail>) => {
      const match = findTriggerTabConfig(triggerData?.configs, event.detail);
      if (match) setPendingActivateTab(match.triggerId, workflowId);
      // Switch to Application Panel tab and open SidePanel
      sidePanel?.setActiveTab(APPLICATION_PANEL_TAB_ID);
      if (!sidePanel?.isOpen) sidePanel?.open();
    };

    const handleOpenApplicationTab = (event: CustomEvent<{ interfaceId: string }>) => {
      const idx = applicationConfigs.findIndex(c => c.interfaceId === event.detail.interfaceId);
      if (idx >= 0) {
        useInterfacePaginationStore.getState().setCarouselIndex(carouselKeyFor(workflowId, runId), idx);
      }
    };

    window.addEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => {
      window.removeEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
      window.removeEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    };
  }, [sidePanel, triggerData, applicationConfigs, workflowId, runId]);

  // ── Run tab requests from the embedded canvas (history button / version chip) ──
  // The application panel is keepMounted, so its own WorkflowPanelContent handles
  // the sub-tab switch; what it cannot do is bring the side panel itself forward.
  // The application's run is frozen to the one this page opened, so the Run tab
  // shows that run's epochs and steps (no history navigation - see allowHistory).
  //
  // Deliberately NO `setPendingActivateTab`, unlike the workflow page: this panel
  // is keepMounted, so its body already exists and switches its own sub-tab. A
  // pending value would never be consumed here (that effect only re-runs on an
  // application-configs push) and would then yank the user back to the Run tab on
  // the NEXT push - which, on a live run, is every epoch.
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<OpenRunPanelDetail>).detail ?? {};
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      sidePanel?.setActiveTab(APPLICATION_PANEL_TAB_ID);
      if (!sidePanel?.isOpen) sidePanel?.open();
    };
    window.addEventListener(OPEN_RUN_PANEL_EVENT, handler);
    return () => window.removeEventListener(OPEN_RUN_PANEL_EVENT, handler);
  }, [sidePanel, workflowId]);

  // ── Early returns ──
  if (isAuthChecking) return <WorkflowLoadingState />;
  // publicPreviewMode: anonymous marketplace preview. Inner hooks already
  // re-route interface fetches to the public endpoint via
  // getActivePublicPreview(); everything else tolerates 401 in silent try/catch.
  if (!isAuthenticated && !publicPreviewMode) return <WorkflowUnauthorizedState />;

  // Cloud-linked CE: a truthy `remote` from EITHER the caller-computed flag OR the
  // publication's own `remote` stamp (set by resolveApplicationPublication on the
  // cloud by-id fallback) routes the Info-panel publisher menu to the cloud profile.
  // OR (never `??`) so a remote publisher is never downgraded to the broken local
  // /app/u/{handle} route, even before the link status resolves.
  const effectiveRemote = remote || publication?.remote || false;

  // The two template actions are gated SEPARATELY, because only one of them depends
  // on which workflow this page is bound to.
  //
  // Loading the example values only fills the forms of the interface on screen, so it
  // is useful to anyone looking at a data-less run - the publisher very much included:
  // their own page opens just as empty as an acquirer's.
  //
  // Resetting the data is different: the endpoint always resolves the caller's own
  // INSTALLED clone, so the button belongs on the page exactly when that clone is what
  // this page is bound to. On a SOURCE workflow it would write to tables the screen
  // does not show, and with no install at all it has nothing to resolve (404).
  //
  // Ownership is NOT that question, and reading it as one was wrong in both directions:
  // it withheld the reset from a publisher who installed their own app - the clone on
  // screen being theirs - and offered it to a visitor who installed nothing. The layout
  // already knows which of the two it bound, and says so through `isInstalledClone`.
  const templateSource = !publicPreviewMode && publication?.id
    ? {
        publicationId: publication.id,
        remote: effectiveRemote,
        canReset: isInstalledClone,
      }
    : undefined;

  // The workflow id to hand the Info panel for its credential-setup block. Shaped on
  // the publication rather than on the install: it is a READ, and it is wanted on the
  // publisher's own page too. Not evidence of an acquisition - `isInstalledClone` is
  // the only thing that answers that.
  const acquiredWorkflowId =
    !publicPreviewMode && publication?.displayMode === 'APPLICATION' && workflowId
      ? workflowId
      : undefined;

  // "Create an editable copy" lives in the settings cog of the controls toolbar, not in the Info
  // panel: making a copy is an action on the application, not part of reading its
  // description. The endpoint copies the caller's INSTALLED clone and refuses with
  // "Application is not installed in this workspace" when there is none, so the offer
  // follows `isInstalledClone` - the same answer the reset uses, for the same reason.
  //
  // Who PUBLISHED it is not part of that question: the endpoint resolves the caller's
  // install and never looks at the publisher. Asking it offered the copy to a visitor
  // who had installed nothing - page bound to the publisher's preview clone, endpoint
  // refuses - and would deny it to anyone holding an install the publisher happens to
  // be, which the ownership test cannot tell apart.
  const canCreateEditableCopy = isInstalledClone && !!acquiredWorkflowId;

  // The cog is handed to the application controls toolbar (the central toggle at the
  // bottom of the app) instead of floating in its own corner: settings sit with every
  // other application control. Nothing is mounted when there is no copy to offer.
  const settingsControl = publication && canCreateEditableCopy ? (
    <ApplicationSettingsMenu
      key="settings"
      publicationId={publication.id}
      remote={effectiveRemote}
      canCreateEditableCopy={canCreateEditableCopy}
    />
  ) : undefined;

  return (
    <div className="absolute inset-0 overflow-hidden flex flex-col">
      {/* Publish-update button intentionally not rendered. The publish logic
          (handlePublishUpdate + canPublish/isPublishing state) is kept in place
          on purpose; only its UI trigger is removed. */}

      {/* Publication info panel (hidden when ChatHeader hosts it) */}
      {/* The workflow id is forwarded for any non-preview application page, the
          publisher's own included: it drives a credential-setup READ, not an
          acquisition-only action. Public previews never load auth'd user creds. */}
      {(() => {
        if (!showInfoPanel) return null;
        if (isPreviewOnly) {
          return (
            <div className="absolute top-4 right-4 z-[40]">
              {publication ? (
                <PublicationInfoPanel
                  publication={publication}
                  acquiredWorkflowId={acquiredWorkflowId}
                  hideActivationButton
                  remote={effectiveRemote}
                />
              ) : (
                <div className="flex items-center gap-2 px-3 sm:px-4 py-2 bg-white dark:bg-gray-800 rounded-full shadow-sm">
                  {publisherName && (
                    <div className="flex items-center gap-1.5">
                      <PublisherAvatar userId={publisherId} name={publisherName} size={16} variant="neutral" />
                      <span className="text-xs text-gray-600 dark:text-gray-400">{publisherName}</span>
                      <VerifiedBadge userId={publisherId} size="xs" />
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        }
        return (
          <div className="absolute top-4 right-4 z-[40]">
            {publication && (
              <PublicationInfoPanel
                publication={publication}
                acquiredWorkflowId={acquiredWorkflowId}
                hideActivationButton
                remote={effectiveRemote}
              />
            )}
          </div>
        );
      })()}

      {/* Interface content - main view */}
      {applicationConfigs.length === 0 ? (
        (() => {
          console.log('[AppDebug] MAIN VIEW rendering BLANK div (applicationConfigs is empty)', {
            workflowId, runId, publicPreviewMode,
          });
          // No interface, so no controls toolbar to carry the cog: keep it reachable
          // at the same bottom-centre spot the toolbar would occupy.
          return (
            <div className="flex-1 relative">
              {settingsControl && (
                <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-30">{settingsControl}</div>
              )}
            </div>
          );
        })()
      ) : (
        (() => {
          console.log('[AppDebug] MAIN VIEW rendering ApplicationCarousel', {
            configsLength: applicationConfigs.length,
          });
          return (
            <ApplicationCarousel
              configs={applicationConfigs}
              runId={runId}
              workflowId={workflowId}
              runSurfaceId={instanceId}
              onAction={handleApplicationAction}
              previewMode={publicPreviewMode}
              // Here the application IS the page: its visitors want the latest
              // result, not a pager spanning every fire the workflow ever had.
              openOnLatestEpoch
              templateSource={templateSource}
              mediaMuted={soundMuted}
              onToggleMediaMuted={toggleSound}
              settingsControl={settingsControl}
            />
          );
        })()
      )}
    </div>
  );
}
