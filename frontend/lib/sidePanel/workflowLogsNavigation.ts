export interface WorkflowLogsNavigationTarget {
  targetTabId: string;
  workflowId: string;
  runId: string;
  initialStepAlias?: string;
}

export const WORKFLOW_PANEL_OPEN_LOGS_EVENT = 'workflowPanelOpenLogs';

const pendingTargets = new Map<string, WorkflowLogsNavigationTarget>();

export function requestWorkflowPanelLogs(target: WorkflowLogsNavigationTarget): void {
  pendingTargets.set(target.targetTabId, target);
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(WORKFLOW_PANEL_OPEN_LOGS_EVENT, { detail: target }));
  }
}

export function consumePendingWorkflowPanelLogs(targetTabId: string): WorkflowLogsNavigationTarget | null {
  const target = pendingTargets.get(targetTabId) ?? null;
  pendingTargets.delete(targetTabId);
  return target;
}

/** Workspace changes must not replay a pending log target from the previous org. */
export function clearPendingWorkflowPanelLogs(): void {
  pendingTargets.clear();
}
