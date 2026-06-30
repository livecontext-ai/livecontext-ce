'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { AppWindow, Package, X } from 'lucide-react';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import { applicationPanelTabId } from '@/lib/sidePanel/applicationPanelTab';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { WorkflowRunBlock } from './WorkflowRunBlock';
import LoadingSpinner from '@/components/LoadingSpinner';
import { PreviewActionMenu, ActionIcons } from './PreviewActionMenu';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { SimpleToast } from './SimpleToast';
import { useDeleteFlow } from '@/hooks/useDeleteFlow';
import { useAppRunAutoOpenStore } from '@/lib/stores/app-run-autoopen-store';
import { useAuth } from '@/lib/providers/smart-providers';

interface ApplicationVisualizeCardProps {
  publicationId: string;
  title?: string;
  /**
   * When set (4-field marker `[visualize:application:id:runId]` emitted by
   * ApplicationExecuteModule), the card renders a LIVE preview of THAT
   * execution's run/epoch instead of the publish-time showcase. Falls back
   * to the showcase pair when the live render fails (404 retention, 403
   * cross-tenant, network), and falls back to the node-icon card when
   * neither runId nor showcase data is usable.
   */
  runId?: string;
  onDelete?: () => void;
}

export function ApplicationVisualizeCard({ publicationId, title, runId, onDelete }: ApplicationVisualizeCardProps) {
  const t = useTranslations('chat');
  const [publication, setPublication] = useState<WorkflowPublication | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // When the live (runId-based) ShowcasePreview fails (404 retention, 403
  // cross-tenant, network), flip this and let the render path fall back to
  // the frozen publish-time showcase pair (or node-icons if absent).
  const [liveRenderFailed, setLiveRenderFailed] = useState(false);
  useEffect(() => { setLiveRenderFailed(false); }, [runId]);
  const sidePanel = useSidePanelSafe();
  const { numericUserId } = useAuth();

  const deleteFn = useCallback(async () => {
    if (!publication?.workflowId) throw new Error('No workflow');
    await publicationService.unpublishWorkflow(publication.workflowId);
  }, [publication?.workflowId]);

  const { isDeleted, showDeleteModal, isDeleting, toast, hideToast, handleDeleteClick, handleConfirmDelete, handleCancelDelete } = useDeleteFlow({
    deleteFn,
    successMessage: 'Application unpublished successfully',
    errorMessage: 'Failed to unpublish application',
    onDeleted: onDelete,
  });

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const pub = await publicationService.getPublicationById(publicationId);
        if (!cancelled) setPublication(pub as WorkflowPublication);
      } catch (err: any) {
        if (!cancelled) setError(err.message || 'Failed to load');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    if (!isDeleted) {
      load();
    }
    return () => { cancelled = true; };
  }, [publicationId, isDeleted]);

  // Scope the tab by runId so two cards for the same app but different
  // executions (each `application:execute` = a new run/epoch) open independent
  // panel tabs instead of collapsing onto one tab that shows a single epoch.
  const tabId = applicationPanelTabId(publicationId, runId);
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  // Open this application in the right side panel (LIVE mode - no
  // PublicationPreviewShell wrap). Acquired or self-published apps must run
  // against the user's own tenant - wrapping in the shell would force
  // `WorkflowModeProvider readOnly=true` and trip ApplicationTabContent's
  // preview-blocked toast on every interface action. ApplicationSidePanel
  // internally resolves the acquired/publisher workflow via its
  // `!inPreviewContext` branch, so no snapshot provider is needed here.
  const openInPanel = useCallback(() => {
    if (!sidePanel || !publication) return;
    sidePanel.openTab({
      id: tabId,
      label: publication.title || title || 'Application',
      icon: <AppWindow className="w-4 h-4" />,
      content: (
        <ApplicationPanelContent publicationId={publicationId} runId={runId} />
      ),
      preferredWidth: 0.35,
      onDelete: handleDeleteClick,
    });
  }, [sidePanel, publication, tabId, title, publicationId, runId, handleDeleteClick]);

  const handleClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    openInPanel();
  };

  // One-shot auto-open after an authorized `application:execute` (the user asked
  // that execute open the right side panel). Consumes the global flag so only the
  // first fresh run opens - reloads and later cards never auto-open.
  useEffect(() => {
    if (!runId || !publication || !sidePanel || isTabActive) return;
    if (useAppRunAutoOpenStore.getState().consume()) {
      openInPanel();
    }
  }, [runId, publication, sidePanel, isTabActive, openInPanel]);

  const handleOpenApplication = () => {
    if (publicationId) {
      window.open(`/app/applications/${publicationId}`, '_blank');
    }
  };

  if (isDeleted) {
    return null;
  }

  if (isLoading) {
    return (
      <div className="my-4">
        <div className="rounded-xl border border-theme overflow-hidden bg-theme-secondary h-[120px] flex items-center justify-center">
          <LoadingSpinner size="md" />
        </div>
      </div>
    );
  }

  if (error || !publication) {
    return (
      <div className="my-4">
        <div className="rounded-xl border border-theme overflow-hidden bg-theme-primary flex flex-col items-center justify-center min-h-[120px] text-theme-muted">
          <AppWindow className="w-8 h-8 mb-2 opacity-50" />
          <span className="text-sm">Application not found</span>
        </div>
      </div>
    );
  }

  const displayTitle = publication.title || title || 'Application';
  const displayMode = publication.displayMode || 'WORKFLOW';
  const hasInterfacePreview = displayMode !== 'WORKFLOW';
  // publisherId + numericUserId are both the numeric internal id → an own/published
  // app gets Open + Delete in the menu. Install/acquire is intentionally NOT offered
  // on the chat visualize card - it lives on the marketplace + applications surfaces.
  const isOwn = numericUserId != null && publication.publisherId === String(numericUserId);

  // Resolve the preview source. Priority order:
  //  1. runId + WORKFLOW mode → render a live workflow-run block (no interface).
  //  2. runId + interface available + live not yet failed → live ShowcasePreview
  //     against THIS execution's run (auth-gated; chat history is always authed).
  //  3. live failed but frozen showcase pair available → frozen ShowcasePreview.
  //  4. no runId + frozen showcase pair → existing back-compat path.
  //  5. else → node-icon card.
  const showcaseAvailable = !!(publication.showcaseRunId && publication.showcaseInterfaceId);
  const renderLiveWorkflow = !!runId && !hasInterfacePreview && publication.workflowId;
  const renderLiveShowcase = !!runId && hasInterfacePreview && !!publication.showcaseInterfaceId && !liveRenderFailed;
  const renderFrozenFallback = !!runId && !renderLiveShowcase && !renderLiveWorkflow && showcaseAvailable;
  const renderFrozenLegacy = !runId && showcaseAvailable;

  const menuItems = [
    {
      id: 'open',
      label: 'Open',
      icon: ActionIcons.open,
      onClick: handleOpenApplication,
    },
    // Delete = unpublish, which only applies to the user's OWN publication.
    ...(isOwn
      ? [{
          id: 'delete',
          label: 'Delete',
          icon: ActionIcons.delete,
          onClick: handleDeleteClick,
          variant: 'danger' as const,
        }]
      : []),
  ];

  return (
    <div
      onClick={handleClick}
      className="my-4 w-full cursor-pointer isolate relative"
    >
      {/* Active tab overlay */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-xl cursor-pointer">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}
      {/* Preview area */}
      {renderLiveWorkflow ? (
        // displayMode=WORKFLOW + runId: agent's execution had no interface, so
        // show the workflow-run block (live status of THIS run).
        <div className="rounded-xl overflow-hidden border border-theme">
          <WorkflowRunBlock workflowId={publication.workflowId!} runId={runId!} workflowName={displayTitle} />
        </div>
      ) : renderLiveShowcase ? (
        // displayMode!=WORKFLOW + runId + showcase interface: live preview of
        // THIS execution. onError flips liveRenderFailed → next render falls
        // through to the frozen showcase pair (or node icons).
        <div className="rounded-xl overflow-hidden border border-theme">
          <ShowcasePreview
            runId={runId!}
            interfaceId={publication.showcaseInterfaceId!}
            onError={() => setLiveRenderFailed(true)}
            suppressErrorUi
            className=""
          />
        </div>
      ) : renderFrozenFallback || renderFrozenLegacy ? (
        // Frozen publish-time showcase. Path 4 (legacy back-compat for 3-field
        // markers) and path 3 (live failed → graceful degrade) share this UI.
        <div className="rounded-xl overflow-hidden border border-theme">
          <ShowcasePreview
            runId={publication.showcaseRunId!}
            interfaceId={publication.showcaseInterfaceId!}
            publicationId={publication.id}
            className=""
          />
        </div>
      ) : (
        <div
          className="relative h-[140px] flex items-center justify-center overflow-hidden rounded-xl border border-theme bg-theme-primary"
        >
          {/* Node icons or fallback */}
          {publication.nodeIcons && publication.nodeIcons.length > 0 ? (
            <WorkflowNodeIcons nodeIcons={publication.nodeIcons} />
          ) : (
            <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center">
              <Package className="w-6 h-6 text-theme-tertiary" />
            </div>
          )}
        </div>
      )}

      {/* Footer - detached below preview */}
      <div className="flex items-center gap-2 px-1 pt-2">
        <AppWindow className="w-4 h-4 text-theme-muted shrink-0" />
        <span className="text-sm font-medium text-theme-primary truncate min-w-0 flex-1">{displayTitle}</span>
        {publication.nodeIcons && publication.nodeIcons.length > 0 && (
          <WorkflowNodeIcons
            nodeIcons={publication.nodeIcons}
            maxDisplay={3}
            prioritizeMcpAndTriggers
            size="inline"
            className="shrink-0"
          />
        )}
        <div onClick={e => e.stopPropagation()} className="shrink-0">
          <PreviewActionMenu items={menuItems} />
        </div>
      </div>

      {/* Delete confirmation modal */}
      <ConfirmDeleteModal
        isOpen={showDeleteModal}
        title="Delete Application"
        message={`Are you sure you want to delete "${displayTitle}"? This will unpublish the application.`}
        onConfirm={handleConfirmDelete}
        onCancel={handleCancelDelete}
        isLoading={isDeleting}
      />

      {/* Toast notification */}
      {toast && (
        <SimpleToast
          type={toast.type}
          message={toast.message}
          isVisible={!!toast}
          onClose={hideToast}
        />
      )}

    </div>
  );
}
