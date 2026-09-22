// @vitest-environment jsdom
/**
 * The bridge's answer to an interface `__continue`.
 *
 * <p>This handler used to be write-only: it caught its own errors, logged to
 * the console, and told the caller nothing. So a 403 (a read-only visitor) and
 * a 404 (the signal already resolved) both looked exactly like a success to
 * whoever pressed the button - the "green when wrong" class, on the one action
 * whose whole purpose is to move the run.
 *
 * <p>Five properties are pinned:
 *  - a success is acked, so the caller can stop looking busy on the ANSWER
 *    rather than on a timeout;
 *  - an HTTP 200 carrying `already_resolved` is NOT a success: the endpoint
 *    answers it when the signal was resolved before this fire landed, and
 *    nothing moved. Reading only the thrown path missed this entirely;
 *  - a failure is acked with its STATUS, because the message is the client's
 *    own English and a localized UI cannot show it;
 *  - an event that carries no `requestId` gets no answer at all, which is what
 *    keeps the application surfaces (which do not ask for one) free of a
 *    listener they never installed;
 *  - an event naming a different workflow is not handled, so a second mounted
 *    canvas neither fires nor answers.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  type InterfaceContinueResponse,
} from '@/lib/workflow/interfaceContinue';
import { ApiError } from '@/lib/api/api-client';

const fireInterfaceAction = vi.fn();
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: {
    fireInterfaceAction: (...args: unknown[]) => fireInterfaceAction(...args),
  },
}));

import { useWorkflowEventBridge } from '../useWorkflowEventBridge';

const refreshState = vi.fn().mockResolvedValue(undefined);

function Host({ workflowId }: { workflowId?: string }) {
  const executeTriggerRef = React.useRef(null);
  const applicationActionRef = React.useRef(null);
  useWorkflowEventBridge(executeTriggerRef, applicationActionRef, { refreshState }, workflowId);
  return null;
}

let answers: InterfaceContinueResponse[];
const collect = (e: Event) => answers.push((e as CustomEvent<InterfaceContinueResponse>).detail);

function continueEvent(detail: Record<string, unknown>) {
  window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_EVENT, {
    detail: {
      runId: 'run_1',
      nodeId: 'interface:form',
      actionKey: '__continue',
      data: {},
      itemIndex: 0,
      ...detail,
    },
  }));
}

beforeEach(() => {
  answers = [];
  fireInterfaceAction.mockReset().mockResolvedValue({ status: 'continued', nodeId: 'interface:form' });
  refreshState.mockClear();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  window.addEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, collect);
});
afterEach(() => {
  window.removeEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, collect);
  vi.restoreAllMocks();
  cleanup();
});

describe('useWorkflowEventBridge: interface __continue ack', () => {
  it('acks a successful fire, so the caller need not guess from a timeout', async () => {
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_1', requestId: 'req_1' });

    await waitFor(() => expect(answers).toHaveLength(1), { timeout: 2000 });
    expect(answers[0]).toEqual({ requestId: 'req_1', ok: true, alreadyResolved: false });
    expect(fireInterfaceAction).toHaveBeenCalledWith('run_1', 'interface:form', '__continue', {}, 0);
  });

  it('acks a refused fire with its STATUS, so the caller can pick a translated message', async () => {
    // The real shape: apiClient is fetch-based and throws ApiError, whose
    // message for a bodyless response is the client's own English
    // (`HTTP 403: Forbidden`). A fixture carrying an axios-style message would
    // hide that `status` is what a localized UI has to read.
    fireInterfaceAction.mockRejectedValue(new ApiError('HTTP 403: Forbidden', 403));
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_1', requestId: 'req_2' });

    await waitFor(() => expect(answers).toHaveLength(1), { timeout: 2000 });
    expect(answers[0].ok).toBe(false);
    expect(answers[0].status).toBe(403);
    expect(answers[0].alreadyResolved).toBe(false);
    // Raw text still travels, for the console - never for the UI.
    expect(answers[0].error).toContain('403');
  });

  it('does NOT call a 200 "already_resolved" a success - nothing moved', async () => {
    // The endpoint answers 200 with this status when the signal was resolved
    // before the fire landed (the user continued from the application, or two
    // canvases of the same workflow both answered one click). Reading only the
    // thrown path reported progress that did not happen.
    fireInterfaceAction.mockResolvedValue({ status: 'already_resolved', nodeId: 'interface:form' });
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_1', requestId: 'req_5' });

    await waitFor(() => expect(answers).toHaveLength(1), { timeout: 2000 });
    expect(answers[0].ok).toBe(false);
    expect(answers[0].alreadyResolved).toBe(true);
  });

  it('treats a 404 as already resolved too - the signal row is simply gone', async () => {
    fireInterfaceAction.mockRejectedValue(new ApiError('HTTP 404: Not Found', 404));
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_1', requestId: 'req_6' });

    await waitFor(() => expect(answers).toHaveLength(1), { timeout: 2000 });
    expect(answers[0].alreadyResolved).toBe(true);
    expect(answers[0].status).toBe(404);
  });

  it('answers nothing when the dispatcher did not ask, so existing callers pay nothing', async () => {
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_1' });

    await waitFor(() => expect(fireInterfaceAction).toHaveBeenCalledTimes(1), { timeout: 2000 });
    expect(answers).toHaveLength(0);
  });

  it('neither fires nor answers for an event naming a different workflow', async () => {
    render(<Host workflowId="wf_1" />);
    continueEvent({ workflowId: 'wf_other', requestId: 'req_3' });

    // Give the handler a chance to run before asserting it did not.
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(fireInterfaceAction).not.toHaveBeenCalled();
    expect(answers).toHaveLength(0);
  });

  it('still handles an event that names no workflow - the application surfaces send none', async () => {
    render(<Host workflowId="wf_1" />);
    continueEvent({ requestId: 'req_4' });

    await waitFor(() => expect(answers).toHaveLength(1), { timeout: 2000 });
    expect(answers[0].ok).toBe(true);
  });
});
