'use client';

import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Pin } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import LoadingSpinner from '@/components/LoadingSpinner';
import { orchestratorApi } from '@/lib/api';
import type { WorkflowBoardCard, WorkflowPlanVersion } from '@/lib/api/orchestrator/types';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface PinVersionModalProps {
  card: WorkflowBoardCard;
  onCancel: () => void;
  onConfirm: (version: number) => Promise<void>;
}

/**
 * Modal shown when a workflow is dragged from the "draft" column to "production".
 * The user must pick a version (one with at least one run) to pin as the production version.
 * Style mirrors the pin confirmation modal in WorkflowVersionHistory.
 */
export const PinVersionModal: React.FC<PinVersionModalProps> = ({ card, onCancel, onConfirm }) => {
  const t = useTranslations();
  const [mounted, setMounted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [versions, setVersions] = useState<WorkflowPlanVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    orchestratorApi.listVersions(card.workflowId)
      .then((data) => {
        if (cancelled) return;
        const withRuns = (data.versions || []).filter(v => (v.runCount ?? 0) > 0);
        withRuns.sort((a, b) => b.version - a.version);
        setVersions(withRuns);
        if (withRuns.length > 0) setSelectedVersion(withRuns[0].version);
      })
      .catch(() => { if (!cancelled) setVersions([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [card.workflowId]);

  const handleConfirm = async () => {
    if (selectedVersion === null) return;
    setSubmitting(true);
    try {
      await onConfirm(selectedVersion);
    } finally {
      setSubmitting(false);
    }
  };

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDateTime(dateStr);
    } catch {
      return dateStr;
    }
  };

  if (!mounted) return null;

  return createPortal(
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onCancel}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-hidden flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="text-center mb-6 flex-shrink-0">
          <div className="w-14 h-14 bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
            <Pin className="w-7 h-7 text-amber-600 dark:text-amber-400" />
          </div>
          <h3 className="text-lg font-semibold text-theme-primary">
            {t('workflowBoard.pinModal.title', { name: card.name })}
          </h3>
          <p className="text-sm text-theme-secondary mt-2">
            {t('workflowBoard.pinModal.description')}
          </p>
        </div>

        {/* Version select */}
        <div className="mb-6">
          {loading ? (
            <div className="flex items-center justify-center py-6">
              <LoadingSpinner size="sm" />
            </div>
          ) : versions.length === 0 ? (
            <div className="py-6 text-center">
              <p className="text-sm text-theme-secondary">
                {t('workflowBoard.pinModal.emptyTitle')}
              </p>
              <p className="text-xs text-theme-secondary mt-1">
                {t('workflowBoard.pinModal.emptyDescription')}
              </p>
            </div>
          ) : (
            <Select
              value={selectedVersion !== null ? String(selectedVersion) : undefined}
              onValueChange={(v) => setSelectedVersion(Number(v))}
            >
              <SelectTrigger>
                <SelectValue placeholder={t('workflowBoard.pinModal.selectPlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                {versions.map((v) => (
                  <SelectItem key={v.version} value={String(v.version)}>
                    <div className="flex items-center gap-2">
                      <span className="font-medium">v{v.version}</span>
                      {v.label && (
                        <span className="text-xs text-theme-secondary truncate">
                          {v.label}
                        </span>
                      )}
                      <span className="text-xs text-theme-secondary">
                        {formatDate(v.createdAt)} &middot; {v.runCount} {t('versionHistory.runs')}
                      </span>
                    </div>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

        {/* Actions */}
        <div className="flex gap-3 flex-shrink-0">
          <Button variant="outline" onClick={onCancel} className="flex-1" disabled={submitting}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={handleConfirm}
            className="flex-1"
            disabled={selectedVersion === null || submitting || versions.length === 0}
          >
            {submitting ? <LoadingSpinner size="xs" /> : t('versionHistory.pin')}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
};
