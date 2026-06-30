'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'next/navigation';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { Globe, Webhook, User, ScrollText, RotateCw, Trash2, MessagesSquare, AppWindow } from 'lucide-react';
import { Button } from '@/components/ui/button';
import Toast, { useToast } from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { PageHeader } from '@/components/settings';
import { webhookSettingsService } from '@/lib/api/orchestrator';
import type { StandaloneWebhook, WebhookSettingsConfig } from '@/lib/api/orchestrator';
import { WebhookCallLogsDialog } from '../webhooks/components/WebhookCallLogsDialog';
import { CurlExamplePopover } from '@/components/webhook/CurlExamplePopover';
import { TriggerTypeTabs, type TriggerTab } from './components/TriggerTypeTabs';
import { TriggerCard, type TriggerAction } from './components/TriggerCard';
import { DeleteTriggerDialog } from './components/DeleteTriggerDialog';
import { TriggerUsageGauge } from './components/TriggerUsageGauge';
import { TriggerEmptyState } from './components/TriggerEmptyState';
import { ChatTabContent } from './components/ChatTabContent';
import { FormTabContent } from './components/FormTabContent';
import { ScheduleTabContent } from './components/ScheduleTabContent';
import { SharedLinksTabContent } from './components/SharedLinksTabContent';
import { useAcquiredAppWorkflowIds } from '@/hooks/useAcquiredAppWorkflowIds';

export default function TriggersSettingsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations('triggerSettings');
  const tWebhook = useTranslations('webhookSettings');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();
  const searchParams = useSearchParams();

  const initialTab = (searchParams.get('tab') as TriggerTab) || 'webhook';
  const [activeTab, setActiveTab] = useState<TriggerTab>(initialTab);

  // Decorate webhook cards whose workflow is an acquired application
  // (App badge). Hydrates on mount; empty set during fetch is safe.
  const { workflowIds: appWorkflowIds } = useAcquiredAppWorkflowIds();

  // Webhook state (kept here for webhook tab)
  const [webhooks, setWebhooks] = useState<StandaloneWebhook[]>([]);
  const [webhookConfig, setWebhookConfig] = useState<WebhookSettingsConfig | null>(null);
  const [webhookLoading, setWebhookLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [deletingWebhook, setDeletingWebhook] = useState<StandaloneWebhook | null>(null);
  const [logsDialogOpen, setLogsDialogOpen] = useState(false);
  const [logsWebhook, setLogsWebhook] = useState<StandaloneWebhook | null>(null);

  const fetchWebhookData = useCallback(async () => {
    try {
      const [webhooksData, configData] = await Promise.all([
        webhookSettingsService.getAll(),
        webhookSettingsService.getConfig(),
      ]);
      setWebhooks(webhooksData);
      setWebhookConfig(configData);
    } catch {
      // Silently fail
    } finally {
      setWebhookLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isAuthChecking) return;
    if (isAuthenticated) fetchWebhookData();
    else setWebhookLoading(false);
  }, [isAuthenticated, isAuthChecking, fetchWebhookData]);

  // Webhook handlers
  const handleRegenerateToken = async (webhook: StandaloneWebhook) => {
    if (!confirm(tWebhook('regenerateConfirm'))) return;
    try {
      await webhookSettingsService.regenerateToken(webhook.id);
      addToast({ type: 'success', title: tWebhook('tokenRegenerated'), message: '' });
      fetchWebhookData();
    } catch { addToast({ type: 'error', title: 'Error', message: '' }); }
  };
  const handleDeleteClick = (webhook: StandaloneWebhook) => { setDeletingWebhook(webhook); setDeleteDialogOpen(true); };
  const handleDeleteConfirm = async () => {
    if (!deletingWebhook) return;
    setActionLoading(true);
    try {
      await webhookSettingsService.delete(deletingWebhook.id);
      addToast({ type: 'success', title: tWebhook('deleted'), message: '' });
      setDeleteDialogOpen(false);
      setDeletingWebhook(null);
      fetchWebhookData();
    } catch { addToast({ type: 'error', title: 'Error', message: '' }); }
    finally { setActionLoading(false); }
  };
  const handleViewLogs = (webhook: StandaloneWebhook) => { setLogsWebhook(webhook); setLogsDialogOpen(true); };

  const getAuthLabel = (webhook: StandaloneWebhook) => {
    const map: Record<string, string> = {
      none: tWebhook('authNone'),
      basic: tWebhook('authBasic'),
      header: tWebhook('authHeader'),
      jwt: tWebhook('authJwt'),
    };
    return map[webhook.authType] || webhook.authType;
  };

  const buildWebhookActions = (webhook: StandaloneWebhook): TriggerAction[] => [
    { label: tWebhook('viewLogs'), icon: ScrollText, onClick: () => handleViewLogs(webhook) },
    { label: tWebhook('regenerateToken'), icon: RotateCw, onClick: () => handleRegenerateToken(webhook) },
    { label: tWebhook('delete'), icon: Trash2, onClick: () => handleDeleteClick(webhook), variant: 'destructive' },
  ];

  if (isAuthChecking) {
    return (
      <div className="space-y-6">
        <PageHeader icon={Globe} title={t('title')} subtitle={t('subtitle')} />
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-24 bg-slate-100 dark:bg-slate-800 rounded-lg animate-pulse" />
          ))}
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-6">
        <PageHeader icon={Globe} title={t('title')} subtitle={t('subtitle')} />
        <div className="min-h-[300px] flex items-center justify-center">
          <div className="text-center">
            <h1 className="text-2xl font-bold text-theme-primary mb-4">{tSettings('unauthorized')}</h1>
            <p className="text-theme-secondary mb-6">{tSettings('mustBeLoggedIn')}</p>
            <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
              <User className="w-4 h-4 mr-1" />
              {tSettings('signIn')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <PageHeader icon={Globe} title={t('title')} subtitle={t('subtitle')} />

      {/* Toasts */}
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast key={toast.id} id={toast.id} type={toast.type} title={toast.title} message={toast.message} onClose={removeToast} />
          ))}
        </div>
      )}

      {/* Tabs */}
      <div className="flex justify-center">
        <TriggerTypeTabs activeTab={activeTab} onTabChange={setActiveTab} />
      </div>

      {/* Tab Content */}
      {activeTab === 'webhook' && (
        <div className="space-y-4">
          {webhookLoading ? (
            <div className="space-y-3">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-24 bg-slate-100 dark:bg-slate-800 rounded-lg animate-pulse" />
              ))}
            </div>
          ) : (
            <>
              {webhookConfig && (
                <TriggerUsageGauge
                  currentCount={webhookConfig.currentCount}
                  maxPerUser={webhookConfig.maxPerUser}
                />
              )}
              {webhooks.length === 0 ? (
                <TriggerEmptyState
                  icon={Webhook}
                  title={t('noWebhooksEmpty')}
                  description={t('noWebhooksEmptyDesc')}
                />
              ) : (
                <div className="space-y-3">
                  {webhooks.map((webhook) => (
                    <TriggerCard
                      key={webhook.id}
                      name={webhook.name}
                      isActive={webhook.isActive}
                      workflowId={webhook.workflowId}
                      workflowName={webhook.workflowName}
                      isApplication={webhook.workflowId ? appWorkflowIds.has(webhook.workflowId) : false}
                      createdAt={webhook.createdAt}
                      detailLine={webhook.webhookUrl}
                      badges={
                        <>
                          <span className="inline-flex items-center px-1.5 py-0.5 text-xs font-mono rounded bg-theme-tertiary text-theme-primary">
                            {webhook.httpMethod}
                          </span>
                          {webhook.authType !== 'none' && (
                            <span className="inline-flex items-center px-1.5 py-0.5 text-xs rounded bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
                              {getAuthLabel(webhook)}
                            </span>
                          )}
                          <CurlExamplePopover
                            webhookUrl={webhook.webhookUrl}
                            httpMethod={webhook.httpMethod}
                            authType={webhook.authType}
                            authConfig={webhook.authConfig}
                            label="cURL"
                            variant="icon"
                          />
                        </>
                      }
                      actions={buildWebhookActions(webhook)}
                    />
                  ))}
                </div>
              )}
            </>
          )}
          <DeleteTriggerDialog
            open={deleteDialogOpen}
            onOpenChange={setDeleteDialogOpen}
            triggerName={deletingWebhook?.name || ''}
            triggerType="webhook"
            triggerDetail={deletingWebhook?.webhookUrl}
            workflowId={deletingWebhook?.workflowId}
            workflowName={deletingWebhook?.workflowName}
            onConfirm={handleDeleteConfirm}
            isLoading={actionLoading}
          />
          <WebhookCallLogsDialog open={logsDialogOpen} onOpenChange={setLogsDialogOpen} webhook={logsWebhook} />
        </div>
      )}

      {activeTab === 'chat' && (
        // -mt-4 pulls the sub-view toggle up close under the trigger-type
        // toggle (two stacked toggles, tight like the pricing page) instead of
        // the page's full space-y-6 gap.
        <div className="space-y-4 -mt-4">
          <ChatTabContent isAuthenticated={isAuthenticated} addToast={addToast} />
        </div>
      )}

      {activeTab === 'form' && (
        // -mt-4: same tight stacked-toggle spacing as the chat tab (sub-view
        // toggle sits close under the trigger-type toggle).
        <div className="space-y-4 -mt-4">
          <FormTabContent isAuthenticated={isAuthenticated} addToast={addToast} />
        </div>
      )}

      {activeTab === 'schedule' && (
        <div className="space-y-4">
          <ScheduleTabContent isAuthenticated={isAuthenticated} addToast={addToast} />
        </div>
      )}

      {activeTab === 'conversations' && (
        <div className="space-y-4">
          <SharedLinksTabContent
            isAuthenticated={isAuthenticated}
            resourceType="CONVERSATION"
            emptyIcon={MessagesSquare}
            emptyTitle={t('noConversations')}
            emptyDescription={t('noConversationsDesc')}
            addToast={addToast}
          />
        </div>
      )}

      {activeTab === 'applications' && (
        <div className="space-y-4">
          <SharedLinksTabContent
            isAuthenticated={isAuthenticated}
            resourceType="APPLICATION"
            emptyIcon={AppWindow}
            emptyTitle={t('noApplications')}
            emptyDescription={t('noApplicationsDesc')}
            addToast={addToast}
          />
        </div>
      )}
    </div>
  );
}
