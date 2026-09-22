// @vitest-environment jsdom
/**
 * The shared `__continue` channel, which two very different surfaces now use:
 * the application (fire and forget - it watches the run state it already
 * subscribes to) and the workflow canvas node (which waits for the answer,
 * because a node has no other way to learn its fire was refused).
 *
 * <p>Both shapes are pinned here rather than only at the callers, because the
 * application path has no test of its own: `ApplicationTabContent.handleContinue`
 * switched from a raw `window.dispatchEvent` to this module, so a change to the
 * request helper that broke the plain dispatch would otherwise go unnoticed on
 * the surface that ships to every user of every published app.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  dispatchInterfaceContinue,
  requestInterfaceContinue,
  type InterfaceContinueDetail,
} from '../interfaceContinue';

let seen: InterfaceContinueDetail[];
const collect = (e: Event) => seen.push((e as CustomEvent<InterfaceContinueDetail>).detail);

const DETAIL = {
  runId: 'run_1',
  nodeId: 'interface:form',
  actionKey: '__continue',
  data: {},
  itemIndex: 2,
};

beforeEach(() => {
  seen = [];
  window.addEventListener(INTERFACE_CONTINUE_EVENT, collect);
});
afterEach(() => {
  window.removeEventListener(INTERFACE_CONTINUE_EVENT, collect);
  vi.useRealTimers();
});

describe('dispatchInterfaceContinue (the application path)', () => {
  it('emits the detail verbatim and asks for no answer', () => {
    dispatchInterfaceContinue(DETAIL);
    expect(seen).toHaveLength(1);
    expect(seen[0]).toEqual(DETAIL);
    // No requestId: the bridge stays silent, so a surface that installs no
    // listener is not paying for one.
    expect(seen[0].requestId).toBeUndefined();
  });

  it('carries a workflowId when the caller names one, and omits it otherwise', () => {
    dispatchInterfaceContinue({ ...DETAIL, workflowId: 'wf_1' });
    expect(seen[0].workflowId).toBe('wf_1');

    dispatchInterfaceContinue(DETAIL);
    // An unnamed event reaches EVERY bridge, which is what the application
    // surfaces rely on: their workflow id can differ from the canvas they sit in.
    expect(seen[1].workflowId).toBeUndefined();
  });
});

describe('requestInterfaceContinue (the canvas node path)', () => {
  it('mints a requestId and resolves with the matching answer', async () => {
    const answer = (e: Event) => {
      const { requestId } = (e as CustomEvent<InterfaceContinueDetail>).detail;
      window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, {
        detail: { requestId, ok: true },
      }));
    };
    window.addEventListener(INTERFACE_CONTINUE_EVENT, answer);
    try {
      const response = await requestInterfaceContinue(DETAIL);
      expect(seen[0].requestId).toBeTruthy();
      expect(response).toEqual({ requestId: seen[0].requestId, ok: true });
    } finally {
      window.removeEventListener(INTERFACE_CONTINUE_EVENT, answer);
    }
  });

  it('ignores an answer meant for another request', async () => {
    vi.useFakeTimers();
    const answerWrongId = () => {
      window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, {
        detail: { requestId: 'someone_else', ok: false, error: 'not mine' },
      }));
    };
    window.addEventListener(INTERFACE_CONTINUE_EVENT, answerWrongId);
    try {
      const pending = requestInterfaceContinue(DETAIL, 1000);
      await vi.advanceTimersByTimeAsync(1000);
      const response = await pending;
      expect(response.ok, 'the foreign failure must not be adopted').toBe(true);
      expect(response.error).toBeUndefined();
    } finally {
      window.removeEventListener(INTERFACE_CONTINUE_EVENT, answerWrongId);
    }
  });

  it('resolves ok on a timeout, because a silent bridge proves nothing', async () => {
    vi.useFakeTimers();
    const pending = requestInterfaceContinue(DETAIL, 500);
    await vi.advanceTimersByTimeAsync(500);
    await expect(pending).resolves.toMatchObject({ ok: true });
  });

  it('keeps the first answer and stops listening after it', async () => {
    let requestId: string | undefined;
    const answerTwice = (e: Event) => {
      requestId = (e as CustomEvent<InterfaceContinueDetail>).detail.requestId;
      const emit = (detail: Record<string, unknown>) =>
        window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, { detail }));
      emit({ requestId, ok: true });
      emit({ requestId, ok: false, error: 'late loser' });
    };
    window.addEventListener(INTERFACE_CONTINUE_EVENT, answerTwice);
    try {
      // Two canvases of the SAME workflow both answer one click (a documented
      // limit of the event scoping), so a second answer for one request is a
      // real shape, not a hypothetical.
      await expect(requestInterfaceContinue(DETAIL)).resolves.toEqual({ requestId, ok: true });
    } finally {
      window.removeEventListener(INTERFACE_CONTINUE_EVENT, answerTwice);
    }
  });
});
