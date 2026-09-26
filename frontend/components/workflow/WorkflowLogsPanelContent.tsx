'use client';

import React, { useEffect, useState } from 'react';
import { ArrowLeft, ListTree, Play, Table2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { WorkflowLogsExplorer } from './WorkflowLogsExplorer';
import { Breadcrumb, type BreadcrumbItem } from '@/components/ui/breadcrumb';
import { orchestratorApi, type WorkflowRun } from '@/lib/api';
import { RunSummaryBar } from '@/components/workflow/run-panel/RunSummaryBar';
import { ToggleGroup } from '@/components/ui/toggle-group';

interface WorkflowLogsPanelContentProps {
  workflowId: string;
  runId: string;
  initialStepAlias?: string;
  onBack: () => void;
}

/**
 * Run-scoped workflow logs rendered directly in the unified side panel.
 * The back action returns to the parent run view inside the same workflow tab.
 */
export function WorkflowLogsPanelContent({
  workflowId,
  runId,
  initialStepAlias,
  onBack,
}: WorkflowLogsPanelContentProps) {
  const t = useTranslations();
  const [breadcrumbItems, setBreadcrumbItems] = useState<BreadcrumbItem[]>([]);
  const [view, setView] = useState<'simple' | 'table'>('simple');
  const [runState, setRunState] = useState<{
    runId: string;
    run: WorkflowRun | null;
    loadFailed: boolean;
  }>(() => ({
    runId,
    run: null,
    loadFailed: false,
  }));
  const run = runState.runId === runId ? runState.run : null;
  const runLoadFailed = runState.runId === runId && runState.loadFailed;

  useEffect(() => {
    let cancelled = false;
    orchestratorApi.getRun(runId)
      .then((result) => {
        if (!cancelled) setRunState({ runId, run: result, loadFailed: false });
      })
      .catch(() => {
        if (!cancelled) setRunState({ runId, run: null, loadFailed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  const displayDate = run?.lastFireAt ?? run?.startedAt;
  const displayedBreadcrumbItems = breadcrumbItems.map((item, index) => (
    index === 0 ? { ...item, label: t('workflow.logs.root') } : item
  ));

  return (
    <div className="flex h-full min-h-0 flex-col bg-theme-primary">
      <header data-testid="workflow-logs-header" className="flex-shrink-0 border-b border-theme">
        <RunSummaryBar
          currentRunInfo={{
            runId,
            status: run?.status,
            metadata: run?.metadata,
            planVersion: run?.planVersion,
            startedAt: displayDate,
          }}
          showStatus={!runLoadFailed}
          size="panel"
          leading={(
            <button
              type="button"
              onClick={onBack}
              className="flex h-6 min-w-0 flex-shrink-0 items-center gap-1.5 rounded-lg border border-theme px-1.5 text-sm font-medium text-theme-secondary transition-colors hover:bg-theme-secondary hover:text-theme-primary"
              title={t('workflow.logs.backToRun')}
              aria-label={t('workflow.logs.backToRun')}
            >
              <ArrowLeft className="h-3.5 w-3.5 flex-shrink-0" />
              <Play className="h-3.5 w-3.5 flex-shrink-0" />
              <span className="truncate">{t('sidePanel.runTab')}</span>
            </button>
          )}
        />

        {runLoadFailed && (
          <div
            data-testid="workflow-logs-run-error"
            role="alert"
            className="border-t border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300 sm:px-4"
          >
            {t('workflow.logs.loadRunError')}
          </div>
        )}

        <div data-testid="workflow-logs-breadcrumb" className="flex min-h-9 min-w-0 flex-wrap items-center justify-between gap-2 border-t border-theme px-3 py-1.5 sm:px-4">
          {displayedBreadcrumbItems.length > 0 && (
            <Breadcrumb
              items={displayedBreadcrumbItems}
              variant="minimal"
              separator="slash"
              className="mb-0 min-w-0"
            />
          )}
          <ToggleGroup
            value={view}
            onValueChange={value => setView(value === 'table' ? 'table' : 'simple')}
            ariaLabel={t('workflow.logs.view')}
            variant="pill"
            hasBorder={false}
            className="h-9 shrink-0 gap-0.5 rounded-[14px] p-0.5"
            options={[
              {
                value: 'simple',
                label: <span className="sr-only">{t('workflow.logs.simple')}</span>,
                icon: <span title={t('workflow.logs.simple')}><ListTree aria-hidden="true" className="h-4 w-4" /></span>,
                className: 'h-8 w-8 px-0 rounded-xl focus-visible:ring-offset-2 focus-visible:ring-offset-theme-primary',
              },
              {
                value: 'table',
                label: <span className="sr-only">{t('workflow.logs.table')}</span>,
                icon: <span title={t('workflow.logs.table')}><Table2 aria-hidden="true" className="h-4 w-4" /></span>,
                className: 'h-8 w-8 px-0 rounded-xl focus-visible:ring-offset-2 focus-visible:ring-offset-theme-primary',
              },
            ]}
          />
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-hidden p-3 sm:p-4">
        <WorkflowLogsExplorer
          workflowId={workflowId}
          runId={runId}
          initialStepAlias={initialStepAlias}
          view={view}
          onBreadcrumbChange={setBreadcrumbItems}
        />
      </div>
    </div>
  );
}
