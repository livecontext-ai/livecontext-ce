'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import { Workflow, X } from 'lucide-react';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import type { NodeIconData } from '@/lib/api/orchestrator/types';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';
import LoadingSpinner from '@/components/LoadingSpinner';
import { PreviewActionMenu, ActionIcons } from './PreviewActionMenu';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { SimpleToast } from './SimpleToast';
import { useDeleteFlow } from '@/hooks/useDeleteFlow';

interface WorkflowPreviewBlockProps {
  workflowId: string;
  onError?: (error: string) => void;
  onDelete?: () => void;
  onRun?: (workflowId: string) => void;
  readOnly?: boolean;
}

interface WorkflowData {
  id: string;
  name: string;
  description?: string;
  nodeCount?: number;
  status?: string;
  nodeIcons?: NodeIconData[];
}

export function WorkflowPreviewBlock({ workflowId, onError, onDelete, onRun, readOnly = false }: WorkflowPreviewBlockProps) {
  const t = useTranslations('chat.workflowBlock');
  const [workflowData, setWorkflowData] = useState<WorkflowData | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const sidePanel = useSidePanelSafe();

  const tabId = `workflow-${workflowId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const deleteFn = useCallback(async () => { await orchestratorApi.deleteWorkflow(workflowId); }, [workflowId]);
  const { isDeleted, showDeleteModal, isDeleting, toast, hideToast, handleDeleteClick, handleConfirmDelete, handleCancelDelete } = useDeleteFlow({
    deleteFn,
    successMessage: t('deleteSuccess'),
    errorMessage: t('deleteError'),
    onDeleted: onDelete,
  });

  // Fetch workflow data
  useEffect(() => {
    async function fetchWorkflow() {
      try {
        setLoading(true);
        const workflow = await orchestratorApi.getWorkflow(workflowId);

        // Count nodes from plan if available
        let nodeCount = 0;
        if (workflow.plan) {
          const plan = workflow.plan;
          nodeCount = (plan.triggers?.length || 0) + (plan.mcps?.length || 0) + (plan.tables?.length || 0) + (plan.cores?.length || 0);
        }

        setWorkflowData({
          id: workflow.id,
          name: workflow.name,
          description: workflow.description,
          nodeCount,
          status: workflow.status,
          nodeIcons: workflow.nodeIcons,
        });
      } catch (err: any) {
        console.error('Failed to load workflow:', err);
        const errorMessage = err.message || '';
        // Audit 2026-05-17 round-4 - distinguish 404 (truly deleted) from
        // 403 (access denied in this workspace) so the user gets a real
        // "switch workspace" hint instead of a misleading "deleted" badge.
        if (errorMessage.includes('403') || errorMessage.toLowerCase().includes('forbidden')) {
          setNotFound(true);
          // Surface the scope hint via onError so the parent chat can render
          // a "switch workspace" affordance if it wants. The card still falls
          // back to its `notFound` empty state for now.
          onError?.('SCOPE: workflow not accessible in current workspace');
        } else if (errorMessage.includes('404') || errorMessage.includes('not found')) {
          setNotFound(true);
        } else {
          onError?.(err.message || 'Failed to load workflow');
        }
      } finally {
        setLoading(false);
      }
    }

    if (workflowId && !isDeleted) {
      fetchWorkflow();
    }
  }, [workflowId, onError, isDeleted]);

  const handleOpenWorkflow = () => {
    window.open(`/app/workflow/${workflowId}`, '_blank');
  };

  const handleClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    // Singleton: remove any existing workflow tab before opening the new one
    for (const tab of sidePanel.tabs) {
      if (tab.id.startsWith('workflow-') && tab.id !== tabId) {
        sidePanel.removeTab(tab.id);
      }
    }

    sidePanel.openTab({
      id: tabId,
      label: workflowData?.name || 'Workflow',
      icon: <Workflow className="w-4 h-4" />,
      content: <WorkflowBuilderPanelContent workflowId={workflowId} readOnly={readOnly} />,
      preferredWidth: 0.35,
      onDelete: handleDeleteClick,
    });
  };

  // Don't render if deleted
  if (isDeleted) {
    return null;
  }

  if (loading) {
    return (
      <div className="my-4 rounded-[18px] border border-theme overflow-hidden bg-theme-secondary">
        <div className="h-[120px] flex items-center justify-center">
          <LoadingSpinner size="md" />
        </div>
      </div>
    );
  }

  if (notFound || !workflowData) {
    return (
      <div className="my-6 rounded-[18px] border border-theme overflow-hidden bg-theme-primary">
        <div className="flex flex-col items-center justify-center min-h-[120px] text-theme-muted">
          <Workflow className="w-8 h-8 mb-2 opacity-50" />
          <span className="text-sm">{t('notFound')}</span>
        </div>
      </div>
    );
  }

  const menuItems = [
    {
      id: 'open',
      label: t('open'),
      icon: ActionIcons.open,
      onClick: handleOpenWorkflow,
    },
    {
      id: 'delete',
      label: t('delete'),
      icon: ActionIcons.delete,
      onClick: handleDeleteClick,
      variant: 'danger' as const,
    },
  ];

  return (
    <div
      onClick={handleClick}
      className={`my-4 w-full rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 isolate relative${sidePanel ? ' cursor-pointer' : ''}`}
    >
      {/* Active tab overlay */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-[18px] cursor-pointer">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}
      {/* Background with ReactFlow-style dots pattern */}
      <div
        className="relative h-[140px] flex items-center justify-center overflow-hidden bg-slate-50 dark:bg-slate-900"
      >
        {/* Dots pattern background */}
        <div
          className="absolute inset-0"
          style={{
            backgroundImage: `radial-gradient(circle, #cbd5e1 1px, transparent 1px)`,
            backgroundSize: '16px 16px',
          }}
        />
        <div
          className="hidden dark:block absolute inset-0"
          style={{
            backgroundImage: `radial-gradient(circle, #475569 1px, transparent 1px)`,
            backgroundSize: '16px 16px',
          }}
        />

        {/* Node icons or fallback workflow icon */}
        <div className="relative z-10">
          {workflowData.nodeIcons && workflowData.nodeIcons.length > 0 ? (
            <WorkflowNodeIcons nodeIcons={workflowData.nodeIcons} />
          ) : (
            <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center">
              <Workflow className="w-7 h-7 text-theme-primary" />
            </div>
          )}
        </div>
      </div>

      {/* Footer - always visible */}
      <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <Workflow className="w-4 h-4 text-theme-muted shrink-0" />
              <span className="text-sm font-medium text-theme-primary truncate">{workflowData.name}</span>
              {workflowData.nodeCount !== undefined && workflowData.nodeCount > 0 && (
                <span className="text-xs text-theme-muted shrink-0">
                  {t('nodeCount', { count: workflowData.nodeCount })}
                </span>
              )}
            </div>
            {workflowData.description && (
              <p className="text-xs text-theme-muted truncate mt-0.5">{workflowData.description}</p>
            )}
          </div>
          <div onClick={e => e.stopPropagation()}>
            <PreviewActionMenu items={menuItems} />
          </div>
        </div>
      </div>

      {/* Delete confirmation modal */}
      <ConfirmDeleteModal
        isOpen={showDeleteModal}
        title={t('deleteTitle')}
        message={t('deleteConfirm', { name: workflowData?.name || 'this workflow' })}
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
