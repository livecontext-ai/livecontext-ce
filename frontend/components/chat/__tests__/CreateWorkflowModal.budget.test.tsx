// @vitest-environment jsdom
/**
 * The advanced fold on the create form, and what happens to the cap it collects.
 *
 * A workflow could not be capped until after it existed: the fold was 76 lines
 * inline in the EDIT modal, so giving it to this form meant copying them. The
 * cap is now collected here and persisted with a second call, and that second
 * call is the whole reason this file exists - it is the part that can fail on
 * its own, with a workflow already created.
 */
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, act } from '@testing-library/react';

const saveWorkflowPlan = vi.fn().mockResolvedValue({});
const updateWorkflow = vi.fn().mockResolvedValue({});
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    saveWorkflowPlan: (body: unknown) => saveWorkflowPlan(body),
    updateWorkflow: (id: string, patch: unknown) => updateWorkflow(id, patch),
  },
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/Toast', () => ({
  __esModule: true,
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
const track = vi.fn();
vi.mock('@/lib/analytics/analytics', () => ({ track: (...args: unknown[]) => track(...args) }));

import { CreateWorkflowModal } from '../CreateWorkflowModal';

const NEW_ID = '11111111-1111-1111-1111-111111111111';

beforeEach(() => {
  saveWorkflowPlan.mockClear().mockResolvedValue({});
  updateWorkflow.mockClear().mockResolvedValue({});
  track.mockClear();
  vi.spyOn(crypto, 'randomUUID').mockReturnValue(NEW_ID as `${string}-${string}-${string}-${string}-${string}`);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function open(overrides: { onWorkflowCreated?: () => void; onClose?: () => void } = {}) {
  const onWorkflowCreated = vi.fn();
  const onClose = vi.fn();
  render(
    <CreateWorkflowModal
      onClose={overrides.onClose ?? onClose}
      onWorkflowCreated={overrides.onWorkflowCreated ?? onWorkflowCreated}
    />
  );
  fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: 'My flow' } });
  return { onWorkflowCreated, onClose };
}

/** Unfold Advanced and type a cap. */
function typeCap(amount: string) {
  fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
  fireEvent.change(screen.getByTestId('budget-advanced-amount'), { target: { value: amount } });
}

const create = () => fireEvent.click(screen.getByRole('button', { name: 'create' }));


/**
 * Flush the effects of the last commit (no longer required before pressing Escape: see below).
 *
 * <p>The Escape listener closes over `isCreating`. It used to be installed by a PASSIVE effect,
 * so seeing the failure message did not mean the listener had been swapped for the
 * post-create one; under a full-suite load that gap opened and the old listener declined the
 * key (the test failed once in 12,527 here, and again on CI on 2026-09-26 in a test that did
 * not call this). The listener is now a layout effect, swapped in the same commit, and "takes
 * Escape pressed the instant the failure is on screen" pins that; this flush stays as a
 * harmless belt for the tests that already use it.
 */
async function settled() {
  await act(async () => {});
}

describe('the cap the create form collects', () => {
  it('is persisted after the workflow exists, on the row that owns it', async () => {
    // The plan endpoint takes a PLAN; the budget lives in the workflow row that
    // `updateWorkflow` owns - the same call the edit form makes.
    const { onWorkflowCreated } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalledWith(NEW_ID));
    expect(updateWorkflow).toHaveBeenCalledWith(NEW_ID, {
      budgetCredits: 2500,
      budgetPeriodMode: 'monthly',
    });
  });

  it('carries the cadence the user picked, not the default', async () => {
    open();
    typeCap('2500');
    fireEvent.change(screen.getByTestId('budget-advanced-period'), { target: { value: 'cumulative' } });
    create();

    await waitFor(() => expect(updateWorkflow).toHaveBeenCalled());
    expect(updateWorkflow.mock.calls[0][1]).toMatchObject({ budgetPeriodMode: 'cumulative' });
  });

  it('leaves the ordinary create at ONE request when no cap is typed', async () => {
    // The second call is the price of a cap, not of creating a workflow.
    const { onWorkflowCreated } = open();
    create();

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
    expect(updateWorkflow).not.toHaveBeenCalled();
  });

  it('sends nothing for a fold that was opened and left empty', async () => {
    const { onWorkflowCreated } = open();
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    create();

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
    expect(updateWorkflow).not.toHaveBeenCalled();
  });

  it('submits no cap from a fold that was closed', async () => {
    // A closed fold must not send a cap the user can no longer see: unlike the
    // edit form, nothing here re-opens it to reveal one.
    const { onWorkflowCreated } = open();
    typeCap('2500');
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    create();

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
    expect(updateWorkflow).not.toHaveBeenCalled();
  });

  it('keeps the amount through a collapse rather than deleting it', async () => {
    // The header is a FULL-WIDTH button and easy to hit by accident. Hiding the
    // cap is what stops it being submitted; erasing it with no undo is a poor
    // trade for tidiness, and an earlier version of this form did exactly that.
    open();
    typeCap('2500');
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));

    expect(screen.getByTestId('budget-advanced-amount')).toHaveValue(2500);
  });

  it('sends the amount again once the fold is reopened', async () => {
    const { onWorkflowCreated } = open();
    typeCap('2500');
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    create();

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
    expect(updateWorkflow).toHaveBeenCalledWith(NEW_ID, {
      budgetCredits: 2500,
      budgetPeriodMode: 'monthly',
    });
  });
});

describe('after a successful create', () => {
  it('resets every field the next open would inherit', async () => {
    // All three callers unmount today, so these resets are defence rather than
    // live behaviour - which is exactly why nothing else would notice them
    // going. `onClose` is a no-op here so the modal stays mounted.
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={() => {}} />);
    fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: 'My flow' } });
    typeCap('2500');
    fireEvent.change(screen.getByTestId('budget-advanced-period'), { target: { value: 'weekly' } });
    create();

    await waitFor(() => expect(updateWorkflow).toHaveBeenCalled());

    expect(screen.getByPlaceholderText('namePlaceholder')).toHaveValue('');
    // The fold is closed again, so its amount is not even rendered.
    expect(screen.queryByTestId('budget-advanced-amount')).toBeNull();
    // And reopening shows an empty field on the default cadence, not the last
    // workflow's cap.
    fireEvent.click(screen.getByTestId('budget-advanced-toggle'));
    expect(screen.getByTestId('budget-advanced-amount')).toHaveValue(null);
  });
});

describe('dismissing WHILE the create is in flight', () => {
  /**
   * The window the previous guards missed. Escape checked `isCreating` and
   * Cancel was disabled by it, but the backdrop had neither - and the backdrop
   * is the one exit with no visual state to show it is unavailable. Dismissing
   * there left the request running against an unmounted component: on failure
   * the caller never learned the id, on success it navigated the user into the
   * workflow they had just clicked away from.
   */
  function startCreateAndHold() {
    let release: (value: unknown) => void = () => {};
    saveWorkflowPlan.mockImplementation(
      () => new Promise((resolve) => {
        release = resolve;
      })
    );
    const handles = open();
    create();
    return { ...handles, release: () => release({}) };
  }

  const backdrop = () => document.querySelector('.fixed.inset-0') as Element;

  it('ignores the backdrop, so the request is never orphaned', async () => {
    const { onClose, onWorkflowCreated, release } = startCreateAndHold();

    fireEvent.click(backdrop());
    expect(onClose, 'the backdrop closed the modal mid-flight').not.toHaveBeenCalled();

    release();
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalledWith(NEW_ID));
  });

  it('ignores Escape for the same reason', async () => {
    const { onClose, onWorkflowCreated, release } = startCreateAndHold();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();

    release();
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
  });

  it('disables the Cancel button too, so all three exits agree', async () => {
    const { release, onWorkflowCreated } = startCreateAndHold();
    expect(screen.getByRole('button', { name: 'cancel' })).toBeDisabled();

    // Released and awaited: an unreleased promise leaves `handleCreate` running
    // into the next test's `cleanup()`, which is a plausible source of the
    // occasional flushPendingEffects flake and is nobody's idea of isolation.
    release();
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
  });
});

describe('dismissing before anything was created', () => {
  it('just closes, without inventing a workflow to hand over', async () => {
    // `dismiss` only hands over once something EXISTS; the ordinary cancel path
    // must not start calling the caller back.
    const { onWorkflowCreated, onClose } = open();

    fireEvent.click(document.querySelector('.fixed.inset-0') as Element);

    expect(onClose).toHaveBeenCalled();
    expect(onWorkflowCreated).not.toHaveBeenCalled();
  });
});

describe('when the workflow is created but its cap is not', () => {
  beforeEach(() => {
    updateWorkflow.mockRejectedValue(new Error('boom'));
  });

  it('says so on screen instead of navigating past it', async () => {
    // The failure this replaces was silent by construction: a toast raised here
    // renders into this modal's own portal, and the modal is unmounted in the
    // same tick by the caller navigating into the new builder.
    const { onWorkflowCreated, onClose } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    expect(onWorkflowCreated, 'the caller navigated before the user saw the message').not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('offers the one move that is left, and no second Create', async () => {
    // The workflow already exists. Creating again would make a duplicate, so
    // the button is gone; the way out is into the workflow that exists.
    open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-open-anyway')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'create' })).toBeNull();
  });

  it('hands the caller the workflow once the user takes that move', async () => {
    const { onWorkflowCreated, onClose } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-open-anyway')).toBeTruthy());
    fireEvent.click(screen.getByTestId('create-workflow-open-anyway'));

    expect(onWorkflowCreated).toHaveBeenCalledWith(NEW_ID);
    expect(onClose).toHaveBeenCalled();
  });

  it('hands the caller the workflow when the modal is dismissed instead', async () => {
    // The hole this closes: the backdrop called the raw `onClose`, so the
    // workflow existed but the caller never learned its id - it was never filed
    // into the folder the user was standing in, never appeared in the list, and
    // a duplicate was two clicks away.
    const { onWorkflowCreated, onClose } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    fireEvent.click(document.querySelector('.fixed.inset-0') as Element);

    expect(onWorkflowCreated).toHaveBeenCalledWith(NEW_ID);
    expect(onClose).toHaveBeenCalled();
  });

  it('leaves an Escape already handled above it alone', async () => {
    // The repo arbitrates Escape by `defaultPrevented`: without deferring, one
    // press dismissed this modal AND un-maximized the side panel behind it.
    const { onWorkflowCreated } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    await settled();
    const handled = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
    handled.preventDefault();
    document.dispatchEvent(handled);

    expect(onWorkflowCreated).not.toHaveBeenCalled();
  });

  it('MARKS the key it acts on, which is how the handlers below it defer', async () => {
    // `preventDefault` marks the event; it does not stop propagation, so a
    // handler that ignores the flag still runs. What this pins is the half the
    // repo's convention depends on: SidePanel and ChatCore both early-return on
    // `defaultPrevented`, so marking is what makes them stand down.
    open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    await settled();
    const event = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
    document.dispatchEvent(event);

    expect(event.defaultPrevented, 'the key was not claimed').toBe(true);
  });

  it('does not mark a key it refuses to act on', async () => {
    // Claiming first and refusing after meant that for the whole duration of a
    // create this modal marked Escape as handled and did nothing with it,
    // blocking every handler that defers to the flag.
    let release: (value: unknown) => void = () => {};
    saveWorkflowPlan.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    const { onWorkflowCreated } = open();
    create();

    const event = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
    document.dispatchEvent(event);
    expect(event.defaultPrevented, 'a key was claimed and then refused').toBe(false);

    // Released and awaited, so the create finishes inside this test rather than
    // during the next one's teardown.
    release({});
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
  });

  it('hands it over on Escape too, so a keyboard is not stuck with the broken exit', async () => {
    const { onWorkflowCreated } = open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onWorkflowCreated).toHaveBeenCalledWith(NEW_ID);
  });

  it('takes Escape pressed the instant the failure is on screen, not one effect later', async () => {
    // The race behind the flaky test above, made deterministic. The failure message and
    // the post-create Escape handler come from the same render; if the handler is
    // installed by a passive effect, it lands AFTER the DOM already shows the message, and
    // a key pressed in that gap reaches the mid-create handler, which declines it. Under a
    // loaded CI runner the gap was wide enough to fail the test above now and then. A
    // MutationObserver fires right after the commit, before React 19 runs passive effects,
    // so pressing Escape from it hits the gap every time. (If a future React flushed passive
    // effects in the same task as the commit, this would pass without the fix too.)
    const { onWorkflowCreated } = open();
    typeCap('2500');
    let pressed: KeyboardEvent | null = null;
    const observer = new MutationObserver(() => {
      if (pressed || !document.querySelector('[data-testid="create-workflow-budget-error"]')) return;
      pressed = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
      document.dispatchEvent(pressed);
    });
    observer.observe(document.body, { childList: true, subtree: true });
    try {
      create();
      await waitFor(() => expect(pressed, 'the failure message never appeared').not.toBeNull());
    } finally {
      observer.disconnect();
    }

    expect(pressed!.defaultPrevented, 'the modal did not take the key').toBe(true);
    expect(onWorkflowCreated, 'Escape pressed as the message appeared was dropped').toHaveBeenCalledWith(NEW_ID);
  });

  it('leaves nothing to type into once the workflow exists', async () => {
    // The alert says to set the cap from the builder; an enabled cap field two
    // elements above it is an invitation to type into nothing.
    open();
    typeCap('2500');
    create();

    await waitFor(() => expect(screen.getByTestId('create-workflow-budget-error')).toBeTruthy());
    expect(screen.getByPlaceholderText('namePlaceholder')).toBeDisabled();
    expect(screen.getByTestId('budget-advanced-amount')).toBeDisabled();
  });

  it('does not report a cap it failed to save', async () => {
    // Counting the cap here would over-count exactly the case worth measuring.
    open();
    typeCap('2500');
    create();

    await waitFor(() => expect(track).toHaveBeenCalled());
    const props = track.mock.calls.find((call) => call[0] === 'workflow_created')![1] as Record<string, unknown>;
    expect(props.has_budget).toBe(false);
    expect(props.budget_save_failed).toBe(true);
  });

  it('reports the cap on the happy path, so the two are told apart', async () => {
    updateWorkflow.mockResolvedValue({});
    open();
    typeCap('2500');
    create();

    await waitFor(() => expect(track).toHaveBeenCalled());
    const props = track.mock.calls.find((call) => call[0] === 'workflow_created')![1] as Record<string, unknown>;
    expect(props.has_budget).toBe(true);
    expect(props.budget_save_failed).toBe(false);
  });
});
