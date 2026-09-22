/**
 * The one channel that resolves an interface node's `__continue` signal.
 *
 * <p>Two surfaces raise it and they are far apart: the application itself (its
 * toolbar Continue button and the in-page bridge action) and the workflow
 * canvas (the Continue button on a parked interface node). Both go through the
 * SAME window event so both get the same handling - `useWorkflowEventBridge`
 * calls `interfaceService.fireInterfaceAction` and schedules the post-continue
 * state refresh. A canvas button that called the service directly would resolve
 * the signal and leave the canvas showing a node still parked until the next
 * poll, which is the shape of bug this module exists to avoid.
 *
 * <p>The event names live here once. They used to be string literals at the
 * dispatch site and at the listener, which is a rename waiting to go
 * half-applied and fail silently (a CustomEvent nobody listens to throws
 * nothing).
 */
export const INTERFACE_CONTINUE_EVENT = 'workflowInterfaceContinue';

/**
 * Answer to a continue that carried a `requestId`.
 *
 * <p>Added because the continue handler used to be write-only: it caught its
 * own errors, logged to the console and told the caller nothing. A 404 (the
 * signal was already resolved from the application, or the canvas held a stale
 * awaiting set) or a 403 (a read-only visitor) therefore looked exactly like a
 * success to whoever pressed the button. The sibling trigger handler in the
 * same hook has always acked; this gives `__continue` the same courtesy.
 */
export const INTERFACE_CONTINUE_RESPONSE_EVENT = 'workflowInterfaceContinueResponse';

export interface InterfaceContinueDetail {
  runId: string;
  /** Backend step id of the interface node, e.g. `interface:my_form`. */
  nodeId: string;
  /** `__continue`, or a mapped action key that resolves to it. */
  actionKey: string;
  data: Record<string, unknown>;
  /**
   * Split index of the item being continued. Omitted for a non-split interface,
   * where the backend resolves the node's latest-epoch signal.
   */
  itemIndex?: number;
  /**
   * The workflow this continue belongs to. Every mounted canvas listens, so a
   * dispatcher that can name its workflow SHOULD: `isEventForWorkflow` only
   * refuses an event that names a DIFFERENT workflow, so omitting it still
   * reaches every bridge (which is what the application surfaces rely on).
   */
  workflowId?: string;
  /**
   * Opt-in correlation id. When present the bridge answers on
   * {@link INTERFACE_CONTINUE_RESPONSE_EVENT}; when absent nothing is emitted,
   * so a dispatcher that does not want an answer pays nothing for this.
   */
  requestId?: string;
}

export interface InterfaceContinueResponse {
  requestId: string;
  /** THIS call is what moved the signal. */
  ok: boolean;
  /**
   * The signal was already resolved when the fire landed, so nothing moved -
   * and that is not a failure. Two ordinary ways in: the user had already
   * continued from the application, and the duplicate-bridge case
   * `isEventForWorkflow` documents (two canvases showing the SAME workflow both
   * answer, so one click fires twice and the loser finds the signal gone).
   *
   * It needs its own flag because the backend says it TWICE, in two shapes: an
   * HTTP 200 carrying `{"status":"already_resolved"}` (the signal row existed
   * and was resolved) and a 404 (the row is gone). Reading only the thrown
   * path called the 200 a success and cleared the button as if the run had
   * advanced, which is the exact confusion this channel exists to end.
   */
  alreadyResolved?: boolean;
  /** HTTP status of the failure, so the caller can pick a TRANSLATED message. */
  status?: number;
  /**
   * Raw failure text. For logs and debugging only: it is the server's or the
   * client's English (`HTTP 403: Forbidden`), so a UI must not show it.
   */
  error?: string;
}

/**
 * Ask the mounted workflow event bridge to continue past an interface node,
 * without waiting for an answer. Used by the application surfaces, which track
 * completion through the run state they already subscribe to.
 */
export function dispatchInterfaceContinue(detail: InterfaceContinueDetail): void {
  window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_EVENT, { detail }));
}

/**
 * Continue past an interface node and wait for the bridge's answer.
 *
 * <p>`timeoutMs` resolves `{ ok: true }` rather than reporting a failure, on
 * purpose: a missing answer means no bridge replied (or it is slower than the
 * window), which says nothing about whether the signal was resolved. Claiming
 * failure there would put a red error on a continue that worked. The caller
 * uses the timeout only to stop looking busy.
 */
export function requestInterfaceContinue(
  detail: Omit<InterfaceContinueDetail, 'requestId'>,
  timeoutMs = 10_000,
): Promise<InterfaceContinueResponse> {
  const requestId = `ic_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  return new Promise((resolve) => {
    let settled = false;
    const finish = (response: InterfaceContinueResponse) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      window.removeEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, listener as EventListener);
      resolve(response);
    };
    const listener = (event: CustomEvent<InterfaceContinueResponse>) => {
      if (event.detail?.requestId !== requestId) return;
      finish(event.detail);
    };
    const timer = window.setTimeout(() => finish({ requestId, ok: true }), timeoutMs);
    window.addEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, listener as EventListener);
    dispatchInterfaceContinue({ ...detail, requestId });
  });
}
