// @vitest-environment jsdom
/**
 * The bar an application shows while its run is parked on a human.
 *
 * <p>It replaces the previous arrangement, where the only action lived inside a
 * toolbar collapsed by default and only ever knew about the interface node on
 * screen. What it must get right:
 *  - ONE blocker at a time, with a count of what is left, because a bar listing
 *    every pending approval of a five-item split would cover the application it
 *    exists to unblock;
 *  - an approval on a node the app is NOT showing is named as such, so the
 *    reader can tell "waiting on you, here" from "waiting elsewhere";
 *  - the author's own question is what gets shown when there is one;
 *  - a failed resolution is SAID and leaves the buttons usable, rather than
 *    spinning forever on an error the server already swallowed.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest';
import { act, cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { RunBlocker } from '@/lib/workflow/runBlockers';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      'actionBar.continuePrompt': 'This application is waiting for you to continue.',
      'actionBar.approvalPrompt': '"{node}" is waiting for your approval.',
      'actionBar.elsewhere': 'Elsewhere in the workflow: {node}',
      'actionBar.more': '{count} more waiting',
      'actionBar.failed': "That didn't go through. Try again.",
      'actionBar.alreadyResolved': 'This item has already been continued.',
      'actionBar.hide': 'Hide until you answer',
      'actionBar.reopen': '{count} waiting for you',
      epochBadge: 'Epoch {number}',
      continueInterfaceForbidden: "You're not allowed to continue this run.",
      'approvalBar.approve': 'Approve',
      'approvalBar.reject': 'Reject',
      continueInterface: 'Continue',
      continueInterfaceLoading: 'Loading the items still waiting here',
    };
    const tpl = templates[key] ?? key;
    return tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(values?.[k] ?? ''));
  },
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import { RunActionBar } from '../RunActionBar';

const approval = (signalId: number, nodeId = 'agent:review_draft', context?: string): RunBlocker => ({
  kind: 'approval', nodeId, signalId, epoch: 2, itemId: '0', context,
});
const cont = (nodeId = 'interface:form'): RunBlocker => ({ kind: 'interface_continue', nodeId });

type ResolveApproval = (
  blocker: Extract<RunBlocker, { kind: 'approval' }>,
  resolution: 'APPROVED' | 'REJECTED',
) => Promise<void>;

let onContinue: Mock<() => void>;
let onResolveApproval: Mock<ResolveApproval>;

function renderBar(blockers: RunBlocker[], extra: Record<string, unknown> = {}) {
  return render(
    <RunActionBar
      blockers={blockers}
      displayedInterfaceNodeId="interface:form"
      onContinue={onContinue}
      onResolveApproval={onResolveApproval}
      {...extra}
    />,
  );
}

beforeEach(() => {
  onContinue = vi.fn<() => void>();
  onResolveApproval = vi.fn<ResolveApproval>().mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('RunActionBar', () => {
  it('renders nothing when the run is not waiting on anyone', () => {
    const { container } = renderBar([]);
    expect(container.firstChild).toBeNull();
  });

  it('offers Continue for the interface on screen', () => {
    renderBar([cont()]);
    expect(screen.getByTestId('application-run-action-bar').dataset.blockerKind).toBe('interface_continue');
    expect(screen.getByTestId('application-run-action-prompt').textContent)
      .toBe('This application is waiting for you to continue.');
    fireEvent.click(screen.getByTestId('application-run-action-continue'));
    expect(onContinue).toHaveBeenCalledTimes(1);
    // It is not an approval, so no approve/reject.
    expect(screen.queryByTestId('application-run-action-approve')).toBeNull();
  });

  it('shows the question the workflow author wrote, when there is one', () => {
    renderBar([approval(1, 'agent:review_draft', 'Send the 3 reminders?')]);
    expect(screen.getByTestId('application-run-action-prompt').textContent).toBe('Send the 3 reminders?');
  });

  it('falls back to naming the node when the approval carries no question', () => {
    renderBar([approval(1, 'agent:review_draft')]);
    expect(screen.getByTestId('application-run-action-prompt').textContent)
      .toBe('"review draft" is waiting for your approval.');
  });

  it('says when the approval is parked somewhere the app is not showing', () => {
    renderBar([approval(1, 'agent:review_draft', 'Send it?')]);
    expect(screen.getByTestId('application-run-action-elsewhere').textContent)
      .toBe('Elsewhere in the workflow: review draft · Epoch 2');
  });

  it('does not print the node name twice when the prompt already named it', () => {
    // The DEFAULT approval - the author wrote no question - used to render
    // "review draft" is waiting for your approval.
    // Elsewhere in the workflow: review draft
    // one line under the other, because the second line was decided from the
    // node id alone. It is now decided from whether the prompt already said it.
    renderBar([approval(1, 'agent:review_draft')]);
    const second = screen.getByTestId('application-run-action-elsewhere').textContent ?? '';
    expect(second).toBe('Epoch 2');
    expect(second).not.toContain('review draft');
  });

  it('names the FIRE an approval belongs to, since several can be pending', () => {
    renderBar([approval(1, 'interface:form', 'Send it?')]);
    expect(screen.getByTestId('application-run-action-elsewhere').textContent).toBe('Epoch 2');
  });

  it('says nothing extra for a continue, which has no epoch of its own', () => {
    renderBar([cont()]);
    expect(screen.queryByTestId('application-run-action-elsewhere')).toBeNull();
  });

  it('does not say "elsewhere" when the approval IS the displayed node', () => {
    renderBar([approval(1, 'interface:form', 'Send it?')]);
    expect(screen.getByTestId('application-run-action-elsewhere').textContent)
      .not.toContain('Elsewhere');
  });

  it('resolves the approval it is showing, in both directions', async () => {
    const blocker = approval(7, 'agent:review_draft', 'Send it?');
    renderBar([blocker]);

    fireEvent.click(screen.getByTestId('application-run-action-approve'));
    await waitFor(() => expect(onResolveApproval).toHaveBeenCalledWith(blocker, 'APPROVED'));

    cleanup();
    onResolveApproval.mockClear();
    renderBar([blocker]);
    fireEvent.click(screen.getByTestId('application-run-action-reject'));
    await waitFor(() => expect(onResolveApproval).toHaveBeenCalledWith(blocker, 'REJECTED'));
  });

  it('asks about ONE blocker and counts the rest, instead of covering the app', () => {
    renderBar([approval(1), approval(2), cont()]);
    expect(screen.getAllByTestId('application-run-action-bar')).toHaveLength(1);
    expect(screen.getByTestId('application-run-action-remaining').textContent).toBe('2 more waiting');
  });

  it('shows no count when it is the last thing waiting', () => {
    renderBar([approval(1)]);
    expect(screen.queryByTestId('application-run-action-remaining')).toBeNull();
  });

  it('refuses a second click while the resolution is in flight', async () => {
    onResolveApproval = vi.fn<ResolveApproval>(() => new Promise<void>(() => {})); // never settles
    renderBar([approval(1)]);
    const approve = screen.getByTestId('application-run-action-approve') as HTMLButtonElement;
    fireEvent.click(approve);
    await waitFor(() => expect(approve.disabled).toBe(true));
    fireEvent.click(approve);
    expect(onResolveApproval).toHaveBeenCalledTimes(1);
    // The other direction is locked too - one answer per question.
    expect((screen.getByTestId('application-run-action-reject') as HTMLButtonElement).disabled).toBe(true);
  });

  it('says a refused resolution went nowhere, and stays usable', async () => {
    onResolveApproval = vi.fn<ResolveApproval>().mockRejectedValue(new Error('HTTP 403: Forbidden'));
    renderBar([approval(1)]);
    fireEvent.click(screen.getByTestId('application-run-action-approve'));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe("That didn't go through. Try again."));
    // Never the raw server text, which is English in all six locales.
    expect(screen.getByRole('alert').textContent).not.toContain('HTTP');
    expect((screen.getByTestId('application-run-action-approve') as HTMLButtonElement).disabled).toBe(false);
  });

  it('releases a stuck spinner rather than leaving both buttons dead', async () => {
    // The normal release is the run moving to another question. Nothing
    // guarantees that arrives - a re-hydrate can lag or fail quietly - and a
    // bar with two disabled buttons and a spinner is unusable with no way out.
    vi.useFakeTimers();
    try {
      onResolveApproval = vi.fn<ResolveApproval>(() => new Promise<void>(() => {}));
      renderBar([approval(1)]);
      fireEvent.click(screen.getByTestId('application-run-action-approve'));
      expect((screen.getByTestId('application-run-action-approve') as HTMLButtonElement).disabled).toBe(true);

      await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
      expect((screen.getByTestId('application-run-action-approve') as HTMLButtonElement).disabled).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('clears its busy and failed state when the run moves to the next blocker', async () => {
    onResolveApproval = vi.fn<ResolveApproval>().mockRejectedValue(new Error('nope'));
    const { rerender } = renderBar([approval(1)]);
    fireEvent.click(screen.getByTestId('application-run-action-approve'));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeNull());

    rerender(
      <RunActionBar
        blockers={[approval(2)]}
        displayedInterfaceNodeId="interface:form"
        onContinue={onContinue}
        onResolveApproval={onResolveApproval}
      />,
    );
    expect(screen.queryByRole('alert'), 'a stale error must not outlive the question it described').toBeNull();
  });

  it('disables Continue when it would be a no-op for the item on screen', () => {
    renderBar([cont()], { continueDisabled: true });
    const button = screen.getByTestId('application-run-action-continue') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    // ...and says the RIGHT thing: the queue is known and this item is already
    // resolved, which is not the same as "the queue has not arrived yet".
    expect(button.getAttribute('title')).toBe('This item has already been continued.');
    fireEvent.click(button);
    expect(onContinue).not.toHaveBeenCalled();
  });

  it('shows a busy Continue while the application awaits the bridge', () => {
    renderBar([cont()], { isContinuing: true });
    expect((screen.getByTestId('application-run-action-continue') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId('spinner')).toBeTruthy();
  });

  it('reports a refused Continue, which used to be indistinguishable from success', () => {
    renderBar([cont()], { continueFailure: 'failed' });
    expect(screen.getByTestId('application-run-action-failed').textContent)
      .toBe("That didn't go through. Try again.");
  });

  it('names a permission refusal rather than a generic one', () => {
    renderBar([cont()], { continueFailure: 'forbidden' });
    expect(screen.getByTestId('application-run-action-failed').textContent)
      .toBe("You're not allowed to continue this run.");
  });

  it('can be put away without answering, and comes back as a pill', () => {
    // A run can stay parked for hours and the bar sits over the application's
    // own bottom strip, so it must be possible to move it out of the way.
    renderBar([approval(1), approval(2)]);
    fireEvent.click(screen.getByTestId('application-run-action-collapse'));

    const pill = screen.getByTestId('application-run-action-bar');
    expect(pill.dataset.collapsed).toBe('true');
    expect(pill.textContent).toContain('2 waiting for you');
    // Collapsed, never dismissed: the run is still waiting, so the way back is
    // the pill itself.
    expect(screen.queryByTestId('application-run-action-approve')).toBeNull();
    fireEvent.click(pill);
    expect(screen.getByTestId('application-run-action-approve')).toBeTruthy();
  });

  it('re-opens itself for a NEW question, which the user has not put away', () => {
    const { rerender } = renderBar([approval(1)]);
    fireEvent.click(screen.getByTestId('application-run-action-collapse'));
    expect(screen.getByTestId('application-run-action-bar').dataset.collapsed).toBe('true');

    rerender(
      <RunActionBar
        blockers={[approval(2)]}
        displayedInterfaceNodeId="interface:form"
        onContinue={onContinue}
        onResolveApproval={onResolveApproval}
      />,
    );
    expect(screen.getByTestId('application-run-action-bar').dataset.collapsed).toBeUndefined();
  });
});
