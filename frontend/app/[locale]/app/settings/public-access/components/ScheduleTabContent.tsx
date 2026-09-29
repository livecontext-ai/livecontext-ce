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
import { useRefreshHomeStatus } from '@/hooks/useHomeStatus';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface ScheduleTabContentProps {
  isAuthenticated: boolean;
  addToast: (toast: { type: 'success' | 'error'; title: string; message: string }) => void;
}

export function ScheduleTabContent({ isAuthenticated, addToast }: ScheduleTabContentProps) {
  const t = useTranslations('triggerSettings');

  const [schedules, setSchedules] = useState<ScheduleOverview[]>([]);
  const [config, setConfig] = useState<ScheduleConfig | null>(null);
  // Arming, disarming or deleting a schedule here changes what the notification bell lists as
  // armed and what its imminent-fire ring pulses for. `fetchData` refreshes THIS page; that
  // payload is invalidated by nothing, so it needs asking for separately. Same endpoint as the
  // agenda's own toggle, which does the same.
  const refreshAutomations = useRefreshHomeStatus();

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
      refreshAutomations();
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
      refreshAutomations();
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

  /**
   * A schedule's own zone, not the reader's.
   *
   * <p>The card prints the cron expression next to its timezone ("0 9 * * * (Asia/Tokyo)"), so
   * rendering "next run" underneath in the reader's zone puts two zones in one row and invites
   * exactly the wrong arithmetic: the reader checks whether 01:00 matches "0 9" and concludes the
   * schedule is broken. The fire times are the schedule's zone; `createdAt` on the same card is an
   * event in the reader's own life and stays theirs, so the card is not single-zone.
   *
   * <p>A BLANK stored zone reads as UTC rather than falling through to the reader's. Falling
   * through was the old behaviour and it was visible, because every instant carried its zone label;
   * a label is now printed only for a PINNED zone, so the same fall-through would show a fire time
   * in the reader's zone with nothing saying so, under a detail line reading "0 9 * * * ()". UTC is
   * what a schedule with no zone fires in, so both halves of the card say UTC.
   */
  const zoneOf = (timeZone?: string) => (timeZone && timeZone.trim() ? timeZone : 'UTC');
  const formatDate = (dateStr?: string, timeZone?: string) =>
    formatUtcDateTime(dateStr, { timeZone: zoneOf(timeZone) });

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
              detailLine={`${schedule.cronExpression} (${zoneOf(schedule.timezone)})`}
              detailCopyable={false}
              extraInfo={
                <div className="flex items-center gap-3 text-xs text-theme-secondary">
                  <span>{t('executions', { count: schedule.executionCount })}</span>
                  {schedule.nextExecutionAt && (
                    <span>{t('nextRun')}: {formatDate(schedule.nextExecutionAt, schedule.timezone)}</span>
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
