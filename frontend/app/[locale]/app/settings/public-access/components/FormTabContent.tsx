'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { FileText, RefreshCw, Trash2, Share2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import {
  formEndpointSettingsService,
  type StandaloneFormEndpoint,
  type FormEndpointConfig,
} from '@/lib/api/orchestrator/form-endpoint-settings.service';
import { TriggerCard, type TriggerAction } from './TriggerCard';
import { DeleteTriggerDialog } from './DeleteTriggerDialog';
import { TriggerUsageGauge } from './TriggerUsageGauge';
import { TriggerEmptyState } from './TriggerEmptyState';
import { ShareLinkDialog } from '@/components/sharing/ShareLinkDialog';
import { SharedLinksTabContent } from './SharedLinksTabContent';
import { SubViewToggle, type SubView } from './SubViewToggle';
import { useAcquiredAppWorkflowIds } from '@/hooks/useAcquiredAppWorkflowIds';

interface FormTabContentProps {
  isAuthenticated: boolean;
  addToast: (toast: { type: 'success' | 'error'; title: string; message: string }) => void;
}

export function FormTabContent({ isAuthenticated, addToast }: FormTabContentProps) {
  const t = useTranslations('triggerSettings');

  const { workflowIds: appWorkflowIds } = useAcquiredAppWorkflowIds();
  const [subView, setSubView] = useState<SubView>('endpoints');
  const [endpoints, setEndpoints] = useState<StandaloneFormEndpoint[]>([]);
  const [config, setConfig] = useState<FormEndpointConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState<StandaloneFormEndpoint | null>(null);
  const [shareOpen, setShareOpen] = useState(false);
  const [sharing, setSharing] = useState<StandaloneFormEndpoint | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const [endpointsData, configData] = await Promise.all([
        formEndpointSettingsService.getAll(),
        formEndpointSettingsService.getConfig(),
      ]);
      setEndpoints(endpointsData);
      setConfig(configData);
    } catch {
      // Silently fail
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isAuthenticated) fetchData();
    else setLoading(false);
  }, [isAuthenticated, fetchData]);

  const handleRegenerate = async (ep: StandaloneFormEndpoint) => {
    if (!confirm(t('regenerateConfirm'))) return;
    try {
      await formEndpointSettingsService.regenerateToken(ep.id);
      addToast({ type: 'success', title: t('tokenRegenerated'), message: '' });
      fetchData();
    } catch {
      addToast({ type: 'error', title: 'Error', message: '' });
    }
  };

  const handleDeleteClick = (ep: StandaloneFormEndpoint) => { setDeleting(ep); setDeleteOpen(true); };
  const handleDeleteConfirm = async () => {
    if (!deleting) return;
    setActionLoading(true);
    try {
      await formEndpointSettingsService.delete(deleting.id);
      addToast({ type: 'success', title: t('formDeleted'), message: '' });
      setDeleteOpen(false);
      setDeleting(null);
      fetchData();
    } catch {
      addToast({ type: 'error', title: 'Error', message: '' });
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2].map((i) => (
          <div key={i} className="h-24 bg-slate-100 dark:bg-slate-800 rounded-lg animate-pulse" />
        ))}
      </div>
    );
  }

  const handleShareClick = (ep: StandaloneFormEndpoint) => { setSharing(ep); setShareOpen(true); };

  const buildActions = (ep: StandaloneFormEndpoint): TriggerAction[] => [
    { label: t('share'), icon: Share2, onClick: () => handleShareClick(ep) },
    { label: t('regenerateToken'), icon: RefreshCw, onClick: () => handleRegenerate(ep) },
    { label: t('delete'), icon: Trash2, onClick: () => handleDeleteClick(ep), variant: 'destructive' },
  ];

  return (
    <>
      <SubViewToggle activeView={subView} onViewChange={setSubView} />

      {subView === 'endpoints' ? (
        <>
          {config && (
            <TriggerUsageGauge currentCount={config.currentCount} maxPerUser={config.maxPerUser} />
          )}

          {endpoints.length === 0 ? (
            <TriggerEmptyState icon={FileText} title={t('noForms')} description={t('noFormsDesc')} />
          ) : (
            <div className="space-y-3">
              {endpoints.map((ep) => (
                <TriggerCard
                  key={ep.id}
                  name={ep.name}
                  isActive={ep.isActive}
                  workflowId={ep.workflowId}
                  workflowName={ep.workflowName}
                  isApplication={ep.workflowId ? appWorkflowIds.has(ep.workflowId) : false}
                  createdAt={ep.createdAt}
                  detailLine={ep.formUrl}
                  actions={buildActions(ep)}
                />
              ))}
            </div>
          )}

          <DeleteTriggerDialog
            open={deleteOpen}
            onOpenChange={setDeleteOpen}
            triggerName={deleting?.name || ''}
            triggerType="form"
            triggerDetail={deleting?.formUrl}
            workflowId={deleting?.workflowId}
            workflowName={deleting?.workflowName}
            onConfirm={handleDeleteConfirm}
            isLoading={actionLoading}
          />

          <ShareLinkDialog
            open={shareOpen}
            onOpenChange={setShareOpen}
            resourceType="FORM"
            resourceToken={sharing?.token || ''}
            resourceName={sharing?.name || ''}
          />
        </>
      ) : (
        <SharedLinksTabContent
          isAuthenticated={isAuthenticated}
          resourceType="FORM"
          emptyIcon={FileText}
          emptyTitle={t('noSharedForms')}
          emptyDescription={t('noSharedFormsDesc')}
          addToast={addToast}
        />
      )}
    </>
  );
}
