'use client';

import React, { useState, useCallback } from 'react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { Plug, User, Plus, Sparkles } from 'lucide-react';
import Toast, { useToast } from '@/components/Toast';
import { customApiService } from '@/lib/api/orchestrator';
import type { CustomApiSummary, CustomApiDefinition, CustomApiListResponse } from '@/lib/api/orchestrator';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { PageHeader } from '@/components/settings';
import { useUserApi } from '@/lib/hooks/useStandardApi';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { CustomApiCard, CustomApiFormDialog, AskApiHelpDialog } from './components';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { ChatPanelContent } from '@/components/app/ChatPanelContent';
import { queueAiChatMessage } from '@/lib/sidePanelChat';

export default function CustomApisPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { loginWithRedirect } = useAuth();
  const t = useTranslations('customApis');
  const tSettings = useTranslations('settings');
  const { toasts, addToast, removeToast } = useToast();

  const { data, isLoading: loading, refetch } = useUserApi<CustomApiListResponse>(
    'custom-apis',
    () => customApiService.list(),
  );
  const apis = data?.apis || [];

  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [editingApi, setEditingApi] = useState<CustomApiSummary | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<CustomApiSummary | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isAskHelpOpen, setIsAskHelpOpen] = useState(false);
  const sidePanel = useSidePanelSafe();

  const handleAskHelpSubmit = useCallback(
    (apiDescription: string) => {
      const message = `${t('aiHelpModal.promptIntro')}\n\n${apiDescription}\n\n${t('aiHelpModal.promptOutro')}`;
      sidePanel?.openTab({
        id: 'ai-chat',
        label: 'AI Chat',
        icon: <Sparkles className="w-4 h-4" />,
        pinned: true,
        content: <ChatPanelContent />,
      });
      queueAiChatMessage(message);
      setIsAskHelpOpen(false);
    },
    [sidePanel, t]
  );

  const handleSave = useCallback(
    async (definition: CustomApiDefinition) => {
      setIsSubmitting(true);
      try {
        if (editingApi) {
          await customApiService.update(editingApi.id, definition);
          addToast({ type: 'success', title: t('updated'), message: t('updateWarning') });
        } else {
          await customApiService.register(definition);
          addToast({ type: 'success', title: t('created'), message: '' });
        }
        setIsDialogOpen(false);
        setEditingApi(null);
        await refetch();
      } catch {
        addToast({ type: 'error', title: t('error'), message: '' });
      } finally {
        setIsSubmitting(false);
      }
    },
    [editingApi, addToast, t, refetch]
  );

  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteTarget) return;
    setIsDeleting(true);
    try {
      await customApiService.remove(deleteTarget.id);
      addToast({ type: 'success', title: t('deleted'), message: '' });
      setDeleteTarget(null);
      await refetch();
    } catch {
      addToast({ type: 'error', title: t('error'), message: '' });
    } finally {
      setIsDeleting(false);
    }
  }, [deleteTarget, addToast, t, refetch]);

  if (isAuthChecking) {
    return (
      <div className="space-y-8">
        <PageHeader icon={Plug} title={t('title')} subtitle={t('subtitle')} />
        <div className="text-sm text-theme-secondary">{t('loading')}</div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <PageHeader icon={Plug} title={t('title')} subtitle={t('subtitle')} />
        <div className="min-h-[200px] flex items-center justify-center">
          <div className="text-center">
            <h2 className="text-lg font-semibold text-theme-primary mb-2">
              {tSettings('unauthorized')}
            </h2>
            <p className="text-sm text-theme-secondary mb-4">
              {tSettings('mustBeLoggedIn')}
            </p>
            <Button onClick={() => loginWithRedirect()}>
              <User className="h-3.5 w-3.5 mr-1" />
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
      <div className="flex items-center justify-between">
        <PageHeader icon={Plug} title={t('title')} subtitle={t('subtitle')} />
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => setIsAskHelpOpen(true)}>
            <Sparkles className="h-3.5 w-3.5 mr-1" />
            {t('aiHelp')}
          </Button>
          <Button
            onClick={() => {
              setEditingApi(null);
              setIsDialogOpen(true);
            }}
          >
            <Plus className="h-3.5 w-3.5 mr-1" />
            {t('create')}
          </Button>
        </div>
      </div>

      {/* Toasts */}
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast
              key={toast.id}
              id={toast.id}
              type={toast.type}
              title={toast.title}
              message={toast.message}
              onClose={removeToast}
            />
          ))}
        </div>
      )}

      {/* Content */}
      {loading ? (
        <div className="text-sm text-theme-secondary">{t('loading')}</div>
      ) : apis.length === 0 ? (
        <div className="border border-dashed border-theme rounded-lg p-8 text-center">
          <Plug className="h-8 w-8 text-theme-secondary mx-auto mb-3" />
          <p className="text-sm font-medium text-theme-primary mb-1">{t('empty')}</p>
          <p className="text-sm text-theme-secondary mb-4">{t('emptyHint')}</p>
          <Button
            variant="outline"
            onClick={() => {
              setEditingApi(null);
              setIsDialogOpen(true);
            }}
          >
            <Plus className="h-3.5 w-3.5 mr-1" />
            {t('create')}
          </Button>
        </div>
      ) : (
        <div className="space-y-3">
          {apis.map((api) => (
            <CustomApiCard
              key={api.id}
              api={api}
              onEdit={(a) => {
                setEditingApi(a);
                setIsDialogOpen(true);
              }}
              onDelete={(a) => setDeleteTarget(a)}
            />
          ))}
        </div>
      )}

      {/* Form Dialog */}
      <CustomApiFormDialog
        open={isDialogOpen}
        onOpenChange={setIsDialogOpen}
        api={editingApi}
        onSubmit={handleSave}
        isLoading={isSubmitting}
      />

      {/* Delete Confirmation */}
      <ConfirmDeleteModal
        isOpen={deleteTarget !== null}
        title={t('confirmDeleteTitle')}
        message={t('confirmDelete')}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
        isLoading={isDeleting}
      />

      {/* Ask AI for help */}
      <AskApiHelpDialog
        open={isAskHelpOpen}
        onOpenChange={setIsAskHelpOpen}
        onSubmit={handleAskHelpSubmit}
      />
    </div>
  );
}
