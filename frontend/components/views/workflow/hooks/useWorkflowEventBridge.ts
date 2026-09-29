'use client';

import { useEffect, type RefObject } from 'react';
import { useTranslations } from 'next-intl';
import { useCanDriveRuns } from '@/lib/hooks/useCanDriveRuns';
import { isEventForWorkflow } from '@/lib/workflow/workflowEventScope';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  type InterfaceContinueDetail,
  type InterfaceContinueResponse,
} from '@/lib/workflow/interfaceContinue';
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
  /**
   * The workflow this bridge answers for. Every mounted canvas subscribes to
   * these events, so without it one button press in an application interface was
   * handled by every bridge on screen - and the application panel now routes its
   * actions through here, where it used to call its run context directly.
   */
  workflowId?: string,
) {
  const tCommon = useTranslations('common');
  // Firing a trigger, an app action or an interface __continue drives the run: a read-only
  // VIEWER is answered at once (translated toast + an explicit forbidden ack) instead of a
  // request the backend refuses and a spinner that never stops. A public share page is
  // never gated here: its visitor acts under the share link, not their own workspace role.
  const canMutate = useCanDriveRuns();
  const readOnlyMessage = tCommon('viewerReadOnly');
  const notifyReadOnly = () => {
    window.dispatchEvent(new CustomEvent('workflowToast', {
      detail: { type: 'warning', message: readOnlyMessage },
    }));
  };

  // Listen for trigger execution requests from WorkflowPanelContent
  useEffect(() => {
    const handler = async (event: CustomEvent) => {
      if (!isEventForWorkflow(event.detail, workflowId)) return;
      const { requestId, triggerId, triggerType, payload } = event.detail;
      let result: string[] | undefined;
      if (!canMutate) {
        notifyReadOnly();
        window.dispatchEvent(new CustomEvent('workflowExecuteTriggerResponse', {
          detail: { requestId, result: undefined, forbidden: true },
        }));
        return;
      }
      try {
        if (executeTriggerRef.current) {
          result = await executeTriggerRef.current(triggerId, triggerType, payload);
        }
      } catch (err) {
        console.error('[useWorkflowEventBridge] Trigger execution failed:', err);
        if ((err as { status?: number } | null)?.status === 403) notifyReadOnly();
      }
      window.dispatchEvent(new CustomEvent('workflowExecuteTriggerResponse', {
        detail: { requestId, result },
      }));
    };
    window.addEventListener('workflowExecuteTriggerRequest', handler as EventListener);
    return () => window.removeEventListener('workflowExecuteTriggerRequest', handler as EventListener);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [executeTriggerRef, workflowId, canMutate, readOnlyMessage]);

  // Listen for application action requests from WorkflowPanelContent
  useEffect(() => {
    const handler = async (event: CustomEvent) => {
      if (!isEventForWorkflow(event.detail, workflowId)) return;
      const { triggerRef, data } = event.detail;
      if (!canMutate) {
        notifyReadOnly();
        return;
      }
      try {
        if (applicationActionRef.current) {
          await applicationActionRef.current(triggerRef, data);
        }
      } catch (err) {
        console.error('[useWorkflowEventBridge] Application action failed:', err);
        if ((err as { status?: number } | null)?.status === 403) notifyReadOnly();
      }
    };
    window.addEventListener('workflowApplicationActionRequest', handler as EventListener);
    return () => window.removeEventListener('workflowApplicationActionRequest', handler as EventListener);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationActionRef, workflowId, canMutate, readOnlyMessage]);

  // Listen for __continue events: resolve interface signal via fire API
  useEffect(() => {
    const handler = async (event: CustomEvent<InterfaceContinueDetail>) => {
      if (!isEventForWorkflow(event.detail, workflowId)) return;
      const { runId, nodeId, actionKey, data, itemIndex, requestId } = event.detail;
      // Answered only when the dispatcher asked to be. Until this existed the
      // handler was write-only: it swallowed a 404 (signal already resolved
      // elsewhere, stale awaiting set) or a 403 (read-only visitor) into a
      // console line, so a caller could not tell a refusal from a success.
      const ack = (response: Omit<InterfaceContinueResponse, 'requestId'>) => {
        if (!requestId) return;
        window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, {
          detail: { requestId, ...response },
        }));
      };
      if (!canMutate) {
        // Same shape as a backend 403, so the continue button shows its "forbidden" state.
        ack({ ok: false, alreadyResolved: false, status: 403, error: 'forbidden' });
        return;
      }
      try {
        const { interfaceService } = await import('@/lib/api/orchestrator/interface.service');
        const result = await interfaceService.fireInterfaceAction(runId, nodeId, actionKey, data, itemIndex);
        // A 200 does NOT mean the run moved: the endpoint answers
        // `{"status":"already_resolved"}` with 200 when the signal was resolved
        // before this fire landed. Acking that as a plain success cleared the
        // caller's spinner and reported progress that did not happen.
        const alreadyResolved = result?.status === 'already_resolved';
        ack({ ok: !alreadyResolved, alreadyResolved });

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
        // `status` travels separately from `error`: the message is the client's
        // own English (`HTTP 404: Not Found` when the body is empty), which must
        // never reach a localized UI, while the code is what lets the caller
        // pick a translated sentence. A 404 means the signal row is gone, i.e.
        // resolved elsewhere - the same non-failure as the 200 above.
        const status = (err as { status?: number } | null)?.status;
        ack({
          ok: false,
          alreadyResolved: status === 404,
          status,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    };
    window.addEventListener(INTERFACE_CONTINUE_EVENT, handler as EventListener);
    return () => window.removeEventListener(INTERFACE_CONTINUE_EVENT, handler as EventListener);
  }, [runContext, workflowId, canMutate]);
}
