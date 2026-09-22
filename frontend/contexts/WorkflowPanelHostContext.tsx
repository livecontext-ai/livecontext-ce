'use client';

import React, { createContext, useContext, type ReactNode } from 'react';

interface WorkflowPanelHost {
  workflowId: string;
  hostTabId?: string;
  /** Run snapshot/action channel paired with this host. */
  runSurfaceId?: string;
}

const WorkflowPanelHostContext = createContext<WorkflowPanelHost | null>(null);

export function WorkflowPanelHostProvider({
  workflowId,
  hostTabId,
  runSurfaceId,
  children,
}: WorkflowPanelHost & { children: ReactNode }) {
  return (
    <WorkflowPanelHostContext.Provider value={{ workflowId, hostTabId, runSurfaceId }}>
      {children}
    </WorkflowPanelHostContext.Provider>
  );
}

export function useWorkflowPanelHostSafe(): WorkflowPanelHost | null {
  return useContext(WorkflowPanelHostContext);
}
