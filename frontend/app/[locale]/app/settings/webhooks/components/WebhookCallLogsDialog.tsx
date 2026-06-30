'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { ChevronLeft, ChevronRight, ScrollText } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { webhookSettingsService } from '@/lib/api/orchestrator';
import type { StandaloneWebhook, WebhookCallLog } from '@/lib/api/orchestrator';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface WebhookCallLogsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  webhook: StandaloneWebhook | null;
}

export function WebhookCallLogsDialog({ open, onOpenChange, webhook }: WebhookCallLogsDialogProps) {
  const t = useTranslations('webhookSettings');
  const [logs, setLogs] = useState<WebhookCallLog[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const fetchLogs = useCallback(async () => {
    if (!webhook) return;
    setLoading(true);
    try {
      const result = await webhookSettingsService.getCallLogs(webhook.id, page, 10);
      setLogs(result.content);
      setTotalPages(result.totalPages);
    } catch {
      setLogs([]);
    } finally {
      setLoading(false);
    }
  }, [webhook, page]);

  useEffect(() => {
    if (open && webhook) {
      fetchLogs();
    }
  }, [open, webhook, fetchLogs]);

  const statusColor = (status: string) => {
    if (status === 'triggered') return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400';
    if (status === 'auth_failed') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400';
    return 'bg-theme-secondary text-theme-secondary';
  };

  const handleClose = () => onOpenChange(false);

  if (!open || !mounted) return null;
  const titleId = 'webhook-call-history-title';

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={handleClose}
    >
      <div
        className="max-w-xl w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        {/* Header */}
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
            <ScrollText className="w-8 h-8 text-theme-primary" />
          </div>
          <h3 id={titleId} className="text-2xl font-semibold text-theme-primary">{t('callHistory')}</h3>
          {webhook && (
            <p className="text-sm text-theme-secondary mt-1">{webhook.name}</p>
          )}
        </div>

        {/* Content */}
        {loading ? (
          <div className="flex items-center justify-center py-10 gap-2 text-theme-secondary">
            <LoadingSpinner size="sm" />
            <span className="text-sm">{t('callHistory')}...</span>
          </div>
        ) : logs.length === 0 ? (
          <div className="text-center py-10">
            <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-3">
              <ScrollText className="w-6 h-6 text-theme-secondary" />
            </div>
            <p className="text-sm text-theme-secondary">{t('noLogs')}</p>
          </div>
        ) : (
          <div className="space-y-2">
            {logs.map((log) => (
              <div
                key={log.id}
                className="flex items-center gap-3 p-3 bg-theme-secondary rounded-xl border border-theme"
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-xs font-mono px-1.5 py-0.5 rounded bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
                      {log.requestMethod || '---'}
                    </span>
                    <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${statusColor(log.responseStatus)}`}>
                      {log.responseStatus}
                    </span>
                    {log.workflowsTriggered > 0 && (
                      <span className="text-xs text-theme-secondary">
                        {log.workflowsTriggered} {t('triggeredWorkflows')}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-theme-secondary">
                    {formatUtcDateTime(log.calledAt)}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-3 mt-4">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="h-8 w-8 p-0"
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <span className="text-xs text-theme-secondary font-medium">{page + 1} / {totalPages}</span>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              className="h-8 w-8 p-0"
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        )}

        {/* Close */}
        <div className="flex gap-3 mt-8">
          <Button
            variant="outline"
            onClick={handleClose}
            className="flex-1"
          >
            {t('close')}
          </Button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
