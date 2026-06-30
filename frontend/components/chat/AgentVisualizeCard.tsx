'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { Bot, X } from 'lucide-react';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { orchestratorApi } from '@/lib/api';
import type { Agent } from '@/lib/api/orchestrator/types';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import LoadingSpinner from '@/components/LoadingSpinner';
import { PreviewActionMenu, ActionIcons } from './PreviewActionMenu';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { SimpleToast } from './SimpleToast';
import { useDeleteFlow } from '@/hooks/useDeleteFlow';

interface AgentVisualizeCardProps {
  agentId: string;
  title?: string;
  onDelete?: () => void;
}

export function AgentVisualizeCard({ agentId, title, onDelete }: AgentVisualizeCardProps) {
  const t = useTranslations('chat.agentCard');
  const [agent, setAgent] = useState<Agent | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const sidePanel = useSidePanelSafe();

  const deleteFn = useCallback(async () => { await orchestratorApi.deleteAgent(agentId); }, [agentId]);
  const { isDeleted, showDeleteModal, isDeleting, toast, hideToast, handleDeleteClick, handleConfirmDelete, handleCancelDelete } = useDeleteFlow({
    deleteFn,
    successMessage: t('deleteSuccess'),
    errorMessage: t('deleteError'),
    onDeleted: onDelete,
  });

  useEffect(() => {
    let cancelled = false;
    agentService.getAgent(agentId)
      .then((data) => { if (!cancelled) setAgent(data); })
      .catch((err: any) => {
        const errorMessage = err?.message || '';
        // Audit 2026-05-17 round-6 - distinguish 403 (no workspace access)
        // from 404 (truly deleted). Both flip notFound for now; future PR
        // can render a distinct "switch workspace" affordance.
        if (errorMessage.includes('403') || errorMessage.toLowerCase().includes('forbidden')) {
          if (!cancelled) setNotFound(true);
        } else if (errorMessage.includes('404') || errorMessage.toLowerCase().includes('not found')) {
          if (!cancelled) setNotFound(true);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId]);

  const tabId = `agent-${agentId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const handleOpenAgent = () => {
    window.open(`/app/agent?id=${agentId}`, '_blank');
  };

  const handleClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    sidePanel.openTab({
      id: tabId,
      label: agent?.name || title || 'Agent',
      icon: <Bot className="w-4 h-4" />,
      content: <AgentPanelContent agentId={agentId} initialTab={AGENT_CONFIGURATION_TAB} />,
      preferredWidth: 0.35,
      onDelete: handleDeleteClick,
    });
  };

  const displayTitle = agent?.name || title || 'Agent';
  const modelInfo = [agent?.modelProvider, agent?.modelName].filter(Boolean).join(' / ');

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

  if (notFound || !agent) {
    return (
      <div className="my-4 rounded-[18px] border border-theme overflow-hidden bg-theme-primary">
        <div className="flex flex-col items-center justify-center min-h-[120px] text-theme-muted">
          <Bot className="w-8 h-8 mb-2 opacity-50" />
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
      onClick: handleOpenAgent,
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
      className={`my-4 w-full rounded-[18px] border border-theme overflow-hidden bg-theme-primary isolate relative${sidePanel ? ' cursor-pointer' : ''}`}
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

      {/* Visual area with avatar + dots pattern */}
      <div className="relative h-[140px] flex items-center justify-center overflow-hidden bg-slate-50 dark:bg-slate-900">
        {/* Dots pattern background */}
        <div
          className="absolute inset-0 dark:hidden"
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
        <div className="relative z-10 rounded-full">
          <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="xl" />
        </div>
      </div>

      {/* Footer */}
      <div className="bg-theme-primary border-t border-theme px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <Bot className="w-4 h-4 text-theme-secondary shrink-0" />
              <span className="text-sm font-medium text-theme-primary truncate">{displayTitle}</span>
            </div>
            {agent.description && (
              <p className="text-xs text-theme-muted truncate mt-0.5">{agent.description}</p>
            )}
            {modelInfo && (
              <p className="text-xs text-theme-muted/70 mt-0.5">{modelInfo}</p>
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
        message={t('deleteConfirm', { name: displayTitle })}
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
