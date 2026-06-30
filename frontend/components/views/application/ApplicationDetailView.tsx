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
import { WorkflowPanelContent, setPendingActivateTab } from '@/components/app/WorkflowPanelContent';
import { useInterfacePaginationStore } from '@/lib/stores/interface-pagination-store';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { PublicationInfoPanel } from '@/components/marketplace/PublicationInfoPanel';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

import { WorkflowLoadingState } from '../workflow/WorkflowLoadingState';
import { WorkflowUnauthorizedState } from '../workflow/WorkflowUnauthorizedState';
import { useAutoCollapseSidebar } from '../workflow/hooks';

// ============================================
// Constants
// ============================================

const APPLICATION_PANEL_TAB_ID = 'application-panel';

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
   * The caller owns this acquired clone (isClonedAcquisition) and may edit it in
   * place. When true, the embedded workflow canvas surfaces the standard run/edit
   * toggle so the owner edits like any workflow and persists via the normal save
   * (PUT /plan, which the backend now accepts for an owned APPLICATION instance).
   * False for the publisher-self-view fallback and anonymous preview, which stay
   * run-locked / read-only.
   */
  canEdit?: boolean;
  /**
   * The caller owns this publication (publisher). Surfaces "Publish update": save
   * the edited source workflow, then re-snapshot it into the live publication
   * (updatePublication). Acquirers (canEdit but not canPublish) never see it.
   */
  canPublish?: boolean;
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

export function ApplicationDetailView({ workflowId, runId, title, publisherName, publisherId, planOverride, publication, showInfoPanel = true, publicPreviewMode = false, canEdit = false, canPublish = false, remote }: ApplicationDetailViewProps) {
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
            isPreviewOnly={isPreviewOnly}
            workflowCanvasSlot={
              <WorkflowModeProvider workflowId={workflowId} initialRunId={runId} readOnly={isPreviewOnly}>
                <div className="h-full w-full relative overflow-x-auto">
                  <WorkflowRunCanvas
                    workflowId={workflowId}
                    runId={runId}
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
    if (triggerRef.endsWith(':navigate')) {
      const parts = triggerRef.split(':');
      const targetLabel = parts.length >= 3 ? parts.slice(1, -1).join(':') : null;
      if (targetLabel) {
        const normalizedTarget = normalizeLabel(targetLabel);
        const idx = applicationConfigs.findIndex(c =>
          normalizeLabel(c.label) === normalizedTarget
        );
        if (idx >= 0) {
          useInterfacePaginationStore.getState().setCarouselIndex(idx);
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
  }, [applicationConfigs]);

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
    const handleOpenTriggerTab = (event: CustomEvent<{ nodeId: string; triggerType: 'chat' | 'form' | 'webhook' }>) => {
      const match = triggerData?.configs?.find(c => c.type === event.detail.triggerType);
      if (match) setPendingActivateTab(match.triggerId, workflowId);
      // Switch to Application Panel tab and open SidePanel
      sidePanel?.setActiveTab(APPLICATION_PANEL_TAB_ID);
      if (!sidePanel?.isOpen) sidePanel?.open();
    };

    const handleOpenApplicationTab = (event: CustomEvent<{ interfaceId: string }>) => {
      const idx = applicationConfigs.findIndex(c => c.interfaceId === event.detail.interfaceId);
      if (idx >= 0) {
        useInterfacePaginationStore.getState().setCarouselIndex(idx);
      }
    };

    window.addEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => {
      window.removeEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
      window.removeEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    };
  }, [sidePanel, triggerData, applicationConfigs]);

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

  return (
    <div className="absolute inset-0 overflow-hidden flex flex-col">
      {/* Publish-update button intentionally not rendered. The publish logic
          (handlePublishUpdate + canPublish/isPublishing state) is kept in place
          on purpose; only its UI trigger is removed. */}

      {/* Publication info panel (hidden when ChatHeader hosts it) */}
      {/* The acquired-workflow id is forwarded ONLY for genuine acquisitions
          (APPLICATION display mode + non-preview). Public previews never
          load auth'd user creds. */}
      {(() => {
        const acquiredWorkflowId =
          !publicPreviewMode &&
          publication?.displayMode === 'APPLICATION' &&
          workflowId
            ? workflowId
            : undefined;
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
          return <div className="flex-1" />;
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
              onAction={handleApplicationAction}
              previewMode={publicPreviewMode}
            />
          );
        })()
      )}
    </div>
  );
}
