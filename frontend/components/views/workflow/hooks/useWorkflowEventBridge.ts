'use client';

import { useEffect, type RefObject } from 'react';
/**
 * Shared event bridge hook for WorkflowDetailView and ApplicationDetailView.
 * Listens for 3 CustomEvents dispatched by WorkflowPanelContent and forwards
 * them to the appropriate refs/services.
 *
 * Events handled:
 * - workflowExecuteTriggerRequest → executeTriggerRef
 * - workflowApplicationActionRequest → applicationActionRef
 * - workflowInterfaceContinue → interfaceService.fireInterfaceAction
 */
export function useWorkflowEventBridge(
  executeTriggerRef: RefObject<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>,
  applicationActionRef: RefObject<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>,
  runContext: { refreshState: (runId: string) => Promise<void> } | null,
) {
  // Listen for trigger execution requests from WorkflowPanelContent
  useEffect(() => {
    const handler = async (event: CustomEvent) => {
      const { requestId, triggerId, triggerType, payload } = event.detail;
      let result: string[] | undefined;
      try {
        if (executeTriggerRef.current) {
          result = await executeTriggerRef.current(triggerId, triggerType, payload);
        }
      } catch (err) {
        console.error('[useWorkflowEventBridge] Trigger execution failed:', err);
      }
      window.dispatchEvent(new CustomEvent('workflowExecuteTriggerResponse', {
        detail: { requestId, result },
      }));
    };
    window.addEventListener('workflowExecuteTriggerRequest', handler as EventListener);
    return () => window.removeEventListener('workflowExecuteTriggerRequest', handler as EventListener);
  }, [executeTriggerRef]);

  // Listen for application action requests from WorkflowPanelContent
  useEffect(() => {
    const handler = async (event: CustomEvent) => {
      const { triggerRef, data } = event.detail;
      try {
        if (applicationActionRef.current) {
          await applicationActionRef.current(triggerRef, data);
        }
      } catch (err) {
        console.error('[useWorkflowEventBridge] Application action failed:', err);
      }
    };
    window.addEventListener('workflowApplicationActionRequest', handler as EventListener);
    return () => window.removeEventListener('workflowApplicationActionRequest', handler as EventListener);
  }, [applicationActionRef]);

  // Listen for __continue events: resolve interface signal via fire API
  useEffect(() => {
    const handler = async (event: CustomEvent<{ runId: string; nodeId: string; actionKey: string; data: Record<string, unknown>; itemIndex?: number }>) => {
      const { runId, nodeId, actionKey, data, itemIndex } = event.detail;
      try {
        const { interfaceService } = await import('@/lib/api/orchestrator/interface.service');
        await interfaceService.fireInterfaceAction(runId, nodeId, actionKey, data, itemIndex);

        // Refresh run state after signal resolution to pick up new readySteps.
        if (runContext) {
          setTimeout(() => {
            runContext.refreshState(runId).catch(err => {
              console.warn('[useWorkflowEventBridge] Post-continue refresh failed:', err);
            });
          }, 1500);
        }
      } catch (err) {
        console.error('[useWorkflowEventBridge] __continue action failed:', err);
      }
    };
    window.addEventListener('workflowInterfaceContinue', handler as EventListener);
    return () => window.removeEventListener('workflowInterfaceContinue', handler as EventListener);
  }, [runContext]);
}
