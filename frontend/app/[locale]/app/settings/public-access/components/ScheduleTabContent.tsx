'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Clock, Trash2, Power, PowerOff } from 'lucide-react';
import { useTranslations } from 'next-intl';
import {
  scheduleSettingsService,
  type ScheduleOverview,
  type ScheduleConfig,
} from '@/lib/api/orchestrator/schedule-settings.service';
import { TriggerCard, type TriggerAction } from './TriggerCard';
import { DeleteTriggerDialog } from './DeleteTriggerDialog';
import { TriggerUsageGauge } from './TriggerUsageGauge';
import { TriggerEmptyState } from './TriggerEmptyState';
import { useAcquiredAppWorkflowIds } from '@/hooks/useAcquiredAppWorkflowIds';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface ScheduleTabContentProps {
  isAuthenticated: boolean;
  addToast: (toast: { type: 'success' | 'error'; title: string; message: string }) => void;
}

export function ScheduleTabContent({ isAuthenticated, addToast }: ScheduleTabContentProps) {
  const t = useTranslations('triggerSettings');

  const [schedules, setSchedules] = useState<ScheduleOverview[]>([]);
  const [config, setConfig] = useState<ScheduleConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState<ScheduleOverview | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  // Decorate trigger cards whose workflow is an acquired application -
  // empty set on initial render, hydrates on mount, no-op on error.
  const { workflowIds: appWorkflowIds } = useAcquiredAppWorkflowIds();

  const fetchData = useCallback(async () => {
    try {
      const [schedulesData, configData] = await Promise.all([
        scheduleSettingsService.getAll(),
        scheduleSettingsService.getConfig(),
      ]);
      setSchedules(schedulesData);
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

  const handleToggle = async (schedule: ScheduleOverview) => {
    try {
      await scheduleSettingsService.toggle(schedule.id, !schedule.enabled);
      addToast({
        type: 'success',
        title: schedule.enabled ? t('scheduleDisabled') : t('scheduleEnabled'),
        message: '',
      });
      fetchData();
    } catch {
      addToast({ type: 'error', title: 'Error', message: '' });
    }
  };

  const handleDeleteClick = (schedule: ScheduleOverview) => {
    setDeleting(schedule);
    setDeleteOpen(true);
  };

  const handleDeleteConfirm = async () => {
    if (!deleting) return;
    setActionLoading(true);
    try {
      await scheduleSettingsService.delete(deleting.id);
      addToast({ type: 'success', title: t('scheduleDeleted'), message: '' });
      setDeleteOpen(false);
      setDeleting(null);
      fetchData();
    } catch {
      addToast({ type: 'error', title: 'Error', message: '' });
    } finally {
      setActionLoading(false);
    }
  };

  const formatDate = (dateStr?: string) => formatUtcDateTime(dateStr);

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2].map((i) => (
          <div key={i} className="h-24 bg-slate-100 dark:bg-slate-800 rounded-lg animate-pulse" />
        ))}
      </div>
    );
  }

  const buildActions = (schedule: ScheduleOverview): TriggerAction[] => [
    {
      label: schedule.enabled ? t('disable') : t('enable'),
      icon: schedule.enabled ? PowerOff : Power,
      onClick: () => handleToggle(schedule),
    },
    { label: t('delete'), icon: Trash2, onClick: () => handleDeleteClick(schedule), variant: 'destructive' },
  ];

  return (
    <>
      {config && (
        <TriggerUsageGauge currentCount={config.currentCount} maxPerUser={config.maxPerUser} />
      )}

      {schedules.length === 0 ? (
        <TriggerEmptyState icon={Clock} title={t('noSchedules')} description={t('noSchedulesDesc')} />
      ) : (
        <div className="space-y-3">
          {schedules.map((schedule) => (
            <TriggerCard
              key={schedule.id}
              name={schedule.name || schedule.triggerId?.replace('trigger:', '') || 'Schedule'}
              isActive={schedule.enabled}
              workflowId={schedule.workflowId}
              workflowName={schedule.workflowName}
              isApplication={schedule.workflowId ? appWorkflowIds.has(schedule.workflowId) : false}
              createdAt={schedule.createdAt}
              detailLine={`${schedule.cronExpression} (${schedule.timezone})`}
              detailCopyable={false}
              extraInfo={
                <div className="flex items-center gap-3 text-xs text-theme-secondary">
                  <span>{t('executions', { count: schedule.executionCount })}</span>
                  {schedule.nextExecutionAt && (
                    <span>{t('nextRun')}: {formatDate(schedule.nextExecutionAt)}</span>
                  )}
                </div>
              }
              actions={buildActions(schedule)}
            />
          ))}
        </div>
      )}

      <DeleteTriggerDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        triggerName={deleting?.name || deleting?.triggerId?.replace('trigger:', '') || ''}
        triggerType="schedule"
        triggerDetail={deleting?.cronExpression}
        workflowId={deleting?.workflowId}
        workflowName={deleting?.workflowName}
        onConfirm={handleDeleteConfirm}
        isLoading={actionLoading}
      />
    </>
  );
}
