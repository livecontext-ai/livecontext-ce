// @vitest-environment jsdom
/**
 * VIEWER gate on the event bridge. Firing a trigger, an application action or an
 * interface `__continue` drives the run, which the backend refuses to a read-only
 * VIEWER. Before this gate the bridge sent the request anyway: the trigger caller got
 * no answer until its 30s timeout (a spinner that never stopped) and the app action
 * failed into a console line. Now a VIEWER is answered at once, with the translated
 * read-only toast, and nothing reaches the backend. MEMBER keeps the old behaviour.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  type InterfaceContinueResponse,
} from '@/lib/workflow/interfaceContinue';

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));
const fireInterfaceAction = vi.fn();
vi.mock('@/lib/api/orchestrator/interface.service', () => ({
  interfaceService: { fireInterfaceAction: (...a: unknown[]) => fireInterfaceAction(...a) },
}));

import { useWorkflowEventBridge } from '../useWorkflowEventBridge';
import { SharedConversationProvider } from '@/contexts/SharedConversationContext';

const executeTrigger = vi.fn();
const applicationAction = vi.fn();

function Host() {
  const executeTriggerRef = React.useRef(executeTrigger);
  const applicationActionRef = React.useRef(applicationAction);
  useWorkflowEventBridge(executeTriggerRef, applicationActionRef, null, 'wf-1');
  return null;
}

let toasts: Array<{ type: string; message: string }>;
let triggerAnswers: Array<Record<string, unknown>>;
let continueAnswers: InterfaceContinueResponse[];
const onToast = (e: Event) => toasts.push((e as CustomEvent).detail);
const onTrigger = (e: Event) => triggerAnswers.push((e as CustomEvent).detail);
const onContinue = (e: Event) => continueAnswers.push((e as CustomEvent).detail);

beforeEach(() => {
  gate.canMutate = true;
  toasts = [];
  triggerAnswers = [];
  continueAnswers = [];
  executeTrigger.mockReset().mockResolvedValue(['trigger:start']);
  applicationAction.mockReset().mockResolvedValue(undefined);
  fireInterfaceAction.mockReset().mockResolvedValue({ status: 'continued' });
  window.addEventListener('workflowToast', onToast);
  window.addEventListener('workflowExecuteTriggerResponse', onTrigger);
  window.addEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, onContinue);
});
afterEach(() => {
  window.removeEventListener('workflowToast', onToast);
  window.removeEventListener('workflowExecuteTriggerResponse', onTrigger);
  window.removeEventListener(INTERFACE_CONTINUE_RESPONSE_EVENT, onContinue);
  cleanup();
});

const fireTrigger = () => window.dispatchEvent(new CustomEvent('workflowExecuteTriggerRequest', {
  detail: { requestId: 'r1', triggerId: 'trigger:start', triggerType: 'form', payload: {}, workflowId: 'wf-1' },
}));
const fireAppAction = () => window.dispatchEvent(new CustomEvent('workflowApplicationActionRequest', {
  detail: { triggerRef: 'trigger:go', data: {}, workflowId: 'wf-1' },
}));
const fireContinue = () => window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_EVENT, {
  detail: { runId: 'run_1', nodeId: 'interface:f', actionKey: '__continue', data: {}, itemIndex: 0,
    requestId: 'c1', workflowId: 'wf-1' },
}));

describe('useWorkflowEventBridge - VIEWER gate', () => {
  it('VIEWER trigger fire: answered at once as forbidden, toast shown, nothing executed', async () => {
    gate.canMutate = false;
    render(<Host />);

    fireTrigger();

    await waitFor(() => expect(triggerAnswers).toHaveLength(1));
    expect(triggerAnswers[0]).toMatchObject({ requestId: 'r1', forbidden: true });
    expect(executeTrigger).not.toHaveBeenCalled();
    expect(toasts).toEqual([{ type: 'warning', message: 'viewerReadOnly' }]);
  });

  it('VIEWER application action: toast shown, nothing executed', async () => {
    gate.canMutate = false;
    render(<Host />);

    fireAppAction();

    await waitFor(() => expect(toasts).toHaveLength(1));
    expect(applicationAction).not.toHaveBeenCalled();
  });

  it('VIEWER interface __continue: acked with status 403 (the button shows forbidden), no request', async () => {
    gate.canMutate = false;
    render(<Host />);

    fireContinue();

    await waitFor(() => expect(continueAnswers).toHaveLength(1));
    expect(continueAnswers[0]).toMatchObject({ requestId: 'c1', ok: false, status: 403 });
    expect(fireInterfaceAction).not.toHaveBeenCalled();
  });

  it('MEMBER: the trigger executes and the result is answered, no toast', async () => {
    render(<Host />);

    fireTrigger();

    await waitFor(() => expect(triggerAnswers).toHaveLength(1));
    expect(executeTrigger).toHaveBeenCalledWith('trigger:start', 'form', {});
    expect(triggerAnswers[0]).toMatchObject({ requestId: 'r1', result: ['trigger:start'] });
    expect(toasts).toEqual([]);
  });

  it('MEMBER hitting a backend 403 on an app action still gets the read-only toast', async () => {
    applicationAction.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<Host />);

    fireAppAction();

    await waitFor(() => expect(toasts).toEqual([{ type: 'warning', message: 'viewerReadOnly' }]));
  });

  it('share page (/s/[token]) with a persisted VIEWER workspace: the trigger, app action and continue all go through', async () => {
    // A logged-in visitor whose active workspace is a VIEWER role opens a public app: the
    // share link, not that role, decides. Before the fix every click was refused locally.
    gate.canMutate = false;
    render(<SharedConversationProvider token="sl_abc"><Host /></SharedConversationProvider>);

    fireTrigger();
    fireAppAction();
    fireContinue();

    await waitFor(() => expect(triggerAnswers).toHaveLength(1));
    expect(triggerAnswers[0]).toMatchObject({ requestId: 'r1', result: ['trigger:start'] });
    expect(triggerAnswers[0]).not.toHaveProperty('forbidden');
    await waitFor(() => expect(applicationAction).toHaveBeenCalledWith('trigger:go', {}));
    await waitFor(() => expect(fireInterfaceAction).toHaveBeenCalledTimes(1));
    expect(toasts).toEqual([]);
  });
});
