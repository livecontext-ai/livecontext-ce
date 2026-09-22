// @vitest-environment jsdom
/**
 * The Continue button an interface node shows on the canvas while it is parked
 * on its blocking `__continue` - the counterpart of a User Approval node's
 * Approve / Reject buttons.
 *
 * Five things are load-bearing and each has its own case:
 *  - it only exists while the node is AWAITING, because an interface that is
 *    not parked resolves nothing and the click would be a silent no-op;
 *  - it targets the signal the FIRE ENDPOINT will actually resolve. That is the
 *    NEWEST epoch (the endpoint takes an itemId and no epoch, then picks
 *    max(epoch) for that item), so a label built from the review order - epoch
 *    ascending - would name one fire and release another;
 *  - an EMPTY queue disables it instead of firing without an item, because the
 *    endpoint's no-item branch releases an arbitrary signal, which for a split
 *    means an item nobody named;
 *  - a refused fire is SAID. Before the bridge acked, a 403 or a 404 looked
 *    exactly like success;
 *  - it travels on the shared `workflowInterfaceContinue` event, named with its
 *    workflow, so the canvas bridge refreshes the run afterwards instead of the
 *    node resolving the signal behind the canvas's back.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  type InterfaceContinueDetail,
} from '@/lib/workflow/interfaceContinue';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      continueInterface: 'Continue',
      continueInterfaceTooltip: 'Continue the workflow past this interface',
      continueInterfaceRemaining: 'Continue epoch {epoch}, item {item} ({count} still waiting)',
      continueInterfaceLoading: 'Loading the items still waiting here',
      continueInterfaceFailed: "Couldn't continue.",
      continueInterfaceForbidden: "You're not allowed to continue this run.",
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));

import { InterfaceContinueButton } from '../InterfaceContinueButton';

/** Production shape: the hydrated queue keeps status PENDING rows (SignalWaitStatus). */
function signal(id: number, epoch: number | undefined, itemId: string | undefined): PendingSignal {
  return { id, nodeId: 'interface:form', signalType: 'INTERFACE_SIGNAL', status: 'PENDING', epoch, itemId };
}

const BASE = {
  stepId: 'interface:form',
  runId: 'run_1',
  workflowId: 'wf_1',
  awaiting: true,
};

let events: InterfaceContinueDetail[];
/** What the stand-in bridge answers. null = answer nothing (the timeout path). */
let bridgeAnswer: { ok: boolean; alreadyResolved?: boolean; status?: number; error?: string } | null;

const bridge = (e: Event) => {
  const detail = (e as CustomEvent<InterfaceContinueDetail>).detail;
  events.push(detail);
  if (!bridgeAnswer || !detail.requestId) return;
  window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, {
    detail: { requestId: detail.requestId, ...bridgeAnswer },
  }));
};

const button = () => screen.getByTestId('interface-node-continue') as HTMLButtonElement;

beforeEach(() => {
  events = [];
  bridgeAnswer = { ok: true };
  window.addEventListener(INTERFACE_CONTINUE_EVENT, bridge);
});
afterEach(() => {
  window.removeEventListener(INTERFACE_CONTINUE_EVENT, bridge);
  cleanup();
});

describe('InterfaceContinueButton', () => {
  it('renders nothing when the node is not awaiting its interface signal', () => {
    render(<InterfaceContinueButton {...BASE} awaiting={false} signals={[signal(1, 1, '0')]} />);
    expect(screen.queryByTestId('interface-node-continue')).toBeNull();
  });

  it('renders nothing without a run or a backend step id', () => {
    const { rerender } = render(<InterfaceContinueButton {...BASE} runId={null} signals={[signal(1, 1, '0')]} />);
    expect(screen.queryByTestId('interface-node-continue')).toBeNull();
    rerender(<InterfaceContinueButton {...BASE} stepId={undefined} signals={[signal(1, 1, '0')]} />);
    expect(screen.queryByTestId('interface-node-continue')).toBeNull();
  });

  it('continues the node when parked, naming its workflow so only its canvas answers', async () => {
    render(<InterfaceContinueButton {...BASE} signals={[signal(7, 3, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(events).toHaveLength(1));
    expect(events[0]).toMatchObject({
      runId: 'run_1',
      nodeId: 'interface:form',
      actionKey: '__continue',
      itemIndex: 0,
      workflowId: 'wf_1',
    });
    expect(events[0].requestId, 'without a requestId the bridge cannot answer').toBeTruthy();
  });

  it('targets the item the fire endpoint will resolve: NEWEST epoch, then lowest item', async () => {
    render(
      <InterfaceContinueButton
        {...BASE}
        signals={[signal(1, 1, '0'), signal(2, 3, '4'), signal(3, 3, '2')]}
      />,
    );
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(1));
    // Epoch 3 before epoch 1 (the endpoint takes max(epoch) for the item it is
    // given); inside epoch 3, item 2 before item 4. Review order would have
    // said item 0 of epoch 1 and released epoch 3 instead.
    expect(events[0].itemIndex).toBe(2);
  });

  it('names that epoch and item in the tooltip, so the label matches the action', () => {
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0'), signal(2, 3, '2')]} />);
    expect(button().getAttribute('title')).toBe('Continue epoch 3, item 3 (2 still waiting)');
    expect(screen.getByTestId('interface-node-continue-count').textContent).toBe('2');
  });

  it('continues ONE item per click, leaving the rest parked', async () => {
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0'), signal(2, 1, '1')]} />);
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(1));
  });

  it('omits the count for a single pending item', () => {
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    expect(screen.queryByTestId('interface-node-continue-count')).toBeNull();
    expect(button().getAttribute('title')).toBe('Continue the workflow past this interface');
  });

  it('refuses to fire while the pending list is unknown, instead of guessing an item', () => {
    // `awaiting` comes with the run state; the signal queue is hydrated by a
    // separate best-effort fetch. Empty therefore means "not known yet", and a
    // fire without an itemId releases an arbitrary signal - for a split, an item
    // the user never named.
    render(<InterfaceContinueButton {...BASE} signals={[]} />);
    expect(button().disabled).toBe(true);
    expect(button().getAttribute('title')).toBe('Loading the items still waiting here');
    expect(button().dataset.state).toBe('loading');

    fireEvent.click(button());
    expect(events).toHaveLength(0);
  });

  it('fires once the queue arrives', async () => {
    const { rerender } = render(<InterfaceContinueButton {...BASE} signals={[]} />);
    rerender(<InterfaceContinueButton {...BASE} signals={[signal(1, 2, '0')]} />);
    expect(button().disabled).toBe(false);
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(1));
  });

  it('says so when the fire is refused, instead of looking like it worked', async () => {
    bridgeAnswer = { ok: false, status: 500, error: 'HTTP 500: Internal Server Error' };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(button().dataset.state).toBe('error'));
    expect(button().textContent).toContain("Couldn't continue.");
    // The spinner is released with the answer - not left to a timeout.
    expect(button().disabled).toBe(false);
  });

  it('never shows the raw server text, which is English in all six locales', async () => {
    bridgeAnswer = { ok: false, status: 404, alreadyResolved: false, error: 'HTTP 404: Not Found' };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(button().dataset.state).toBe('error'));
    expect(button().getAttribute('title')).not.toContain('HTTP');
    expect(button().textContent).not.toContain('Not Found');
  });

  it('names the refusal when it is a permission one, not a generic failure', async () => {
    bridgeAnswer = { ok: false, status: 403, error: 'HTTP 403: Forbidden' };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(button().dataset.state).toBe('error'));
    expect(button().textContent).toContain("You're not allowed to continue this run.");
  });

  it('does NOT paint an error when the signal was already resolved elsewhere', async () => {
    // The run DID move - from the application, or from a second canvas of the
    // same workflow answering the same click. Only this call did not move it,
    // and the refresh will unpark the node.
    bridgeAnswer = { ok: false, alreadyResolved: true, status: 404 };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(button().dataset.state).toBe('ready'));
    expect(button().textContent).toContain('Continue');
    expect(button().disabled).toBe(false);
  });

  it('announces a refusal, since it is otherwise only drawn on the button', async () => {
    bridgeAnswer = { ok: false, status: 500 };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());

    await waitFor(() => expect(button().dataset.state).toBe('error'));
    const alert = button().querySelector('[role="alert"]');
    expect(alert, 'a screen-reader user hears nothing about the refusal otherwise').toBeTruthy();
    expect(alert?.textContent).toContain("Couldn't continue.");
  });

  it('does not re-arm mid-flight when another ITEM parks alongside the one in flight', async () => {
    // The count changes while the target does not, which is exactly what the
    // re-arm must ignore: releasing the button here let a SECOND fire go out
    // for the SAME item, leaving two promises in flight whose answers can paint
    // over each other.
    bridgeAnswer = null;
    const inFlight = signal(1, 1, '0');
    const { rerender } = render(<InterfaceContinueButton {...BASE} signals={[inFlight]} />);
    fireEvent.click(button());
    expect(button().dataset.state).toBe('continuing');

    rerender(<InterfaceContinueButton {...BASE} signals={[inFlight, signal(9, 1, '5')]} />);
    expect(button().dataset.state, 'the target is unchanged, so the click is still in flight').toBe('continuing');
    fireEvent.click(button());
    expect(events).toHaveLength(1);
  });

  it('DOES re-arm when the target itself changes, since that is different work', async () => {
    // A newer epoch parks: the endpoint resolves the newest, so the button now
    // points at that item. Firing it is a different item, not a duplicate.
    bridgeAnswer = null;
    const first = signal(1, 1, '0');
    const { rerender } = render(<InterfaceContinueButton {...BASE} signals={[first]} />);
    fireEvent.click(button());
    expect(button().dataset.state).toBe('continuing');

    rerender(<InterfaceContinueButton {...BASE} signals={[first, signal(9, 2, '3')]} />);
    expect(button().dataset.state).toBe('ready');
    fireEvent.click(button());
    expect(events).toHaveLength(2);
    expect(events[1].itemIndex).toBe(3);
  });

  it('clears the error and fires again on the next click', async () => {
    bridgeAnswer = { ok: false, status: 500, error: 'boom' };
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());
    await waitFor(() => expect(button().dataset.state).toBe('error'));

    bridgeAnswer = { ok: true };
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(2));
    await waitFor(() => expect(button().dataset.state).toBe('ready'));
  });

  it('does not claim failure when nothing answered - a silent bridge proves nothing', async () => {
    vi.useFakeTimers();
    try {
      bridgeAnswer = null; // no bridge mounted / slower than the window
      render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
      fireEvent.click(button());
      expect(button().dataset.state).toBe('continuing');

      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect(button().dataset.state, 'released, but NOT painted as an error').toBe('ready');
      expect(button().disabled).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('ignores a second click while the first continue is in flight', async () => {
    bridgeAnswer = null;
    render(<InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />);
    fireEvent.click(button());
    expect(button().disabled).toBe(true);
    fireEvent.click(button());
    expect(events).toHaveLength(1);
  });

  it('re-arms once the parked set changes, so the next split item can be continued', async () => {
    const { rerender } = render(
      <InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0'), signal(2, 1, '1')]} />,
    );
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(1));

    // Item 0 resolved; item 1 is still parked.
    rerender(<InterfaceContinueButton {...BASE} signals={[signal(2, 1, '1')]} />);
    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(2));
    expect(events[1].itemIndex).toBe(1);
  });

  it('stops looking busy the moment the node stops waiting', async () => {
    bridgeAnswer = null;
    const signals = [signal(1, 1, '0')];
    const { rerender } = render(<InterfaceContinueButton {...BASE} signals={signals} />);
    fireEvent.click(button());
    expect(button().dataset.state).toBe('continuing');

    // Same queue object, so only `awaiting` changed - this is the reset the run
    // state drives when the DAG moves past the node.
    rerender(<InterfaceContinueButton {...BASE} awaiting signals={signals} />);
    expect(button().dataset.state).toBe('continuing');
    rerender(<InterfaceContinueButton {...BASE} awaiting={false} signals={signals} />);
    expect(screen.queryByTestId('interface-node-continue')).toBeNull();
  });

  it('does not let the click select or drag the node underneath', async () => {
    // The button hangs off the node's box, so a click that bubbles would select
    // the node (and a drag would pan the canvas) on every Continue.
    const onNodeClick = vi.fn();
    render(
      <div onClick={onNodeClick}>
        <InterfaceContinueButton {...BASE} signals={[signal(1, 1, '0')]} />
      </div>,
    );
    expect(button().className).toContain('nodrag');
    expect(button().className).toContain('nopan');

    fireEvent.click(button());
    await waitFor(() => expect(events).toHaveLength(1));
    expect(onNodeClick).not.toHaveBeenCalled();
  });
});
