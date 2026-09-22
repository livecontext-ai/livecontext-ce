'use client';

import { useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { usePathname } from 'next/navigation';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  requestWorkflowPanelLogs,
} from '@/lib/sidePanel/workflowLogsNavigation';
import { WORKFLOW_PANEL_TAB_ID, workflowPanelTabId } from '@/lib/sidePanel/tabResource';
import { workflowIdFromPathname } from '@/lib/workflow/runRoutePath';
import { openWorkflowBuilderTab } from '@/lib/sidePanel/openWorkflowBuilderTab';
import { useWorkflowPanelHostSafe } from '@/contexts/WorkflowPanelHostContext';
import { getCachedRunPanelData, requestBindRun } from '@/components/workflow/run-panel/runPanelBus';

interface OpenWorkflowLogsOptions {
  workflowId: string;
  runId: string;
  workflowName?: string | null;
  initialStepAlias?: string;
  /** Reuse the active workflow container when the requested run is already its current run. */
  reuseActiveWorkflowTab?: boolean;
  /** Explicit outer host for global controls rendered outside the workflow panel tree. */
  hostTabId?: string;
}

/** Single entry point for opening general or node-scoped workflow logs. */
export function useWorkflowLogsSidePanel() {
  const sidePanel = useSidePanelSafe();
  const t = useTranslations();
  const pathname = usePathname();
  const panelHost = useWorkflowPanelHostSafe();

  const openWorkflowLogs = useCallback((options: OpenWorkflowLogsOptions) => {
    if (!sidePanel || !options.workflowId || !options.runId) return;

    const runTabId = workflowPanelTabId(options.workflowId, options.runId);
    const builderTabId = workflowPanelTabId(options.workflowId);
    const explicitHostTabId = options.reuseActiveWorkflowTab
      && options.hostTabId
      && sidePanel.tabs.some((tab) => tab.id === options.hostTabId)
      ? options.hostTabId
      : null;
    const contextualHostTabId = options.reuseActiveWorkflowTab
      && panelHost?.workflowId === options.workflowId
      && panelHost.hostTabId
      && sidePanel.tabs.some((tab) => tab.id === panelHost.hostTabId)
      ? panelHost.hostTabId
      : null;
    const pinnedPanelMatches = options.reuseActiveWorkflowTab
      && sidePanel.tabs.some((tab) => tab.id === WORKFLOW_PANEL_TAB_ID)
      && workflowIdFromPathname(pathname) === options.workflowId;
    const activeWorkflowTabId = explicitHostTabId
      ?? contextualHostTabId
      ?? (sidePanel.activeTabId === runTabId
        || (options.reuseActiveWorkflowTab && sidePanel.activeTabId === builderTabId)
        ? sidePanel.activeTabId
        : pinnedPanelMatches
          ? WORKFLOW_PANEL_TAB_ID
          : null);
    const existingTargetTabId = activeWorkflowTabId
      ?? (sidePanel.tabs.some((tab) => tab.id === runTabId) ? runTabId : null);
    const targetTabId = existingTargetTabId ?? runTabId;

    // A run tab can browse history and bind another run without changing its
    // outer id. When the picker later targets the id's original run, realign the
    // tab first so Logs and its Back destination describe the same run.
    if (existingTargetTabId === runTabId) {
      const boundRun = getCachedRunPanelData(options.workflowId, runTabId).runId;
      if (boundRun && boundRun !== options.runId) {
        requestBindRun({
          workflowId: options.workflowId,
          runId: options.runId,
          surfaceId: runTabId,
        });
      }
    }

    requestWorkflowPanelLogs({
      targetTabId,
      workflowId: options.workflowId,
      runId: options.runId,
      initialStepAlias: options.initialStepAlias,
    });

    if (existingTargetTabId) {
      sidePanel.setActiveTab(existingTargetTabId);
      sidePanel.open();
      return;
    }

    openWorkflowBuilderTab(sidePanel, {
      workflowId: options.workflowId,
      runId: options.runId,
      workflowName: options.workflowName || t('common.workflow'),
    });
  }, [panelHost, pathname, sidePanel, t]);

  return { openWorkflowLogs, canOpenWorkflowLogs: !!sidePanel };
}
