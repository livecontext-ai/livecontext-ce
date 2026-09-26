// @vitest-environment jsdom
/**
 * When the welcome gift opens, and - the part that bites - when it does NOT.
 *
 * The modal is mounted in the app layout, so it runs on every page of every
 * session. Two failures cost more than the modal itself: opening when nothing
 * armed it puts an overlay in front of the whole app, and deciding not to open
 * without releasing the hand-off strands the suggested-applications modal that
 * queues behind it. Every branch below asserts BOTH the screen and the hand-off.
 *
 * <p>The balance QUERY is stubbed, not the hook that reads it, so the real
 * `useMonthlyCreditsCannotPay` runs: what this modal gets wrong is which answer it
 * waits for, and stubbing that hook would be stubbing the question rather than
 * answering it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const edition = vi.hoisted(() => ({ isCe: false }));
const balance = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));
const plans = vi.hoisted(() => ({ value: undefined as unknown }));
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: () => balance.value,
  usePlans: () => ({ plans: plans.value }),
}));

import WelcomeGiftModal, { ANSWER_TIMEOUT_MS } from '../WelcomeGiftModal';
import {
  WELCOME_GIFT_DONE_EVENT,
  WELCOME_GIFT_FLAG,
  armWelcomeGift,
} from '@/lib/onboarding/welcomeGiftHandoff';

/** The balance payload shapes, named for what they mean rather than their fields. */
const FREE_ACCOUNT = { monthlyCreditsAreWorkflowOnly: true, hasAnswered: true, isLoading: false };
const PAID_ACCOUNT = { monthlyCreditsAreWorkflowOnly: false, hasAnswered: true, isLoading: false };
/** In flight: every field reads as its own default, which looks exactly like a paid account. */
const NOT_ANSWERED = { monthlyCreditsAreWorkflowOnly: false, hasAnswered: false, isLoading: true };

/** Records every release of the hand-off, which is what the next modal waits on. */
function watchHandoff() {
  const released = vi.fn();
  window.addEventListener(WELCOME_GIFT_DONE_EVENT, released);
  return {
    released,
    stop: () => window.removeEventListener(WELCOME_GIFT_DONE_EVENT, released),
  };
}

const isOpen = () => screen.queryByTestId('welcome-gift-modal') !== null;

beforeEach(() => {
  sessionStorage.clear();
  edition.isCe = false;
  balance.value = FREE_ACCOUNT;
  plans.value = [{ code: 'FREE', includedAiCredits: 100 }];
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  sessionStorage.clear();
});

describe('WelcomeGiftModal', () => {
  it('shows nothing, and releases nothing, on an ordinary visit', () => {
    // Mounted app-wide: without the flag it must be inert. Releasing the
    // hand-off here would be just as wrong as opening - the suggestions modal
    // reads the same flag to decide whether to wait at all.
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
    expect(handoff.released).not.toHaveBeenCalled();
    handoff.stop();
  });

  it('opens for a brand-new Free account and holds the flag until it is closed', () => {
    // The flag standing while the gift is on screen is what keeps the two
    // modals apart: clearing it at open time would release the suggestions
    // immediately and stack the two overlays.
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(true);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
    expect(handoff.released).not.toHaveBeenCalled();

    act(() => {
      screen.getByRole('button', { name: 'cta' }).click();
    });

    expect(isOpen()).toBe(false);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('reports welcome_plan_shown once when it opens and welcome_plan_dismissed when closed', () => {
    track.mockReset();
    armWelcomeGift();
    render(<React.StrictMode><WelcomeGiftModal /></React.StrictMode>);

    expect(track.mock.calls).toEqual([['welcome_plan_shown', { is_free_plan: true }]]);

    act(() => {
      screen.getByRole('button', { name: 'cta' }).click();
    });
    expect(track).toHaveBeenLastCalledWith('welcome_plan_dismissed', { is_free_plan: true });
  });

  it('reports nothing when the gift is skipped for a paid account', () => {
    track.mockReset();
    balance.value = PAID_ACCOUNT;
    armWelcomeGift();
    render(<WelcomeGiftModal />);

    expect(track).not.toHaveBeenCalled();
  });

  it('releases the hand-off when the reader presses Escape instead of the button', () => {
    // Escape and an outside click do not go through the CTA: they arrive on the
    // dialog's own onOpenChange. If that wire were dropped the gift would vanish
    // from the screen while the hand-off was never released, and the modal
    // queued behind it would wait for an event that is never coming.
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);
    expect(isOpen()).toBe(true);

    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' });

    expect(isOpen()).toBe(false);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('shows nothing to a paid account, and releases the waiter anyway', () => {
    // A paid plan has no free grant to announce. Skipping the gift without
    // releasing would leave the modal behind it armed forever, turning one
    // skipped modal into two.
    balance.value = PAID_ACCOUNT;
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('shows nothing self-hosted, and releases the waiter anyway', () => {
    // A self-hosted install bills against its linked cloud account, and an
    // unlinked one has no plan to state.
    edition.isCe = true;
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('waits for the plan answer rather than guessing from an empty payload', () => {
    // `monthlyCreditsAreWorkflowOnly` reads false while the request is in
    // flight, which is indistinguishable from a paid account. Deciding on that
    // would skip the gift for the very accounts it exists for.
    balance.value = NOT_ANSWERED;
    armWelcomeGift();
    const handoff = watchHandoff();

    const view = render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
    expect(handoff.released).not.toHaveBeenCalled();

    balance.value = FREE_ACCOUNT;
    view.rerender(<WelcomeGiftModal />);

    expect(isOpen()).toBe(true);
    handoff.stop();
  });

  it('gives up on a plan answer that never arrives, so nothing queues forever', () => {
    // The bounded wait. Showing no gift is recoverable; leaving the next modal
    // armed with nothing left to release it is not.
    vi.useFakeTimers();
    balance.value = NOT_ANSWERED;
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);
    expect(handoff.released).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(ANSWER_TIMEOUT_MS);
    });

    expect(isOpen()).toBe(false);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBeNull();
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('cannot be closed out from under a reader by its own timeout', () => {
    // THE case the settled ref exists for, and the only one that reaches it: the
    // arm effect's timer is still running while the gift is open, and a reader
    // takes longer than the bound to read it. Without the guard that timer would
    // release the hand-off under an open gift, and the suggested-applications
    // modal would appear ON TOP of it - the exact stacking the sequencing is
    // built to prevent. Every other test here is single-shotted by `pending`
    // before it ever consults the ref.
    vi.useFakeTimers();
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);
    expect(isOpen()).toBe(true);

    act(() => {
      vi.advanceTimersByTime(ANSWER_TIMEOUT_MS * 3);
    });

    expect(isOpen()).toBe(true);
    expect(sessionStorage.getItem(WELCOME_GIFT_FLAG)).toBe('1');
    expect(handoff.released).not.toHaveBeenCalled();
    handoff.stop();
  });

  it('opens once and releases once under React double-invoked effects', () => {
    // Development StrictMode runs every effect twice. Without the settled ref
    // the skip path would dispatch the hand-off twice, and the open path would
    // re-arm a modal the reader may already have dismissed.
    armWelcomeGift();
    const handoff = watchHandoff();

    render(
      <React.StrictMode>
        <WelcomeGiftModal />
      </React.StrictMode>,
    );

    expect(screen.getAllByTestId('welcome-gift-modal')).toHaveLength(1);
    expect(handoff.released).not.toHaveBeenCalled();

    act(() => {
      screen.getByRole('button', { name: 'cta' }).click();
    });

    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('releases exactly once under double-invoked effects on the skip path too', () => {
    edition.isCe = true;
    armWelcomeGift();
    const handoff = watchHandoff();

    render(
      <React.StrictMode>
        <WelcomeGiftModal />
      </React.StrictMode>,
    );

    expect(isOpen()).toBe(false);
    expect(handoff.released).toHaveBeenCalledTimes(1);
    handoff.stop();
  });

  it('does not reopen when a later answer contradicts the one it settled on', () => {
    // The balance query refetches on window focus. A reader who dismissed the
    // gift and came back to the tab must not be greeted by it again, and a
    // reader it was skipped for must not have it appear mid-session.
    balance.value = PAID_ACCOUNT;
    armWelcomeGift();

    const view = render(<WelcomeGiftModal />);
    expect(isOpen()).toBe(false);

    balance.value = FREE_ACCOUNT;
    view.rerender(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
  });

  it('regression: states ONE pool, with no separate AI credits row', () => {
    // The Free plan used to carry a separate monthly AI allowance shown as a second
    // row. It was merged into the monthly credits, so even a stale plan row that
    // still carries an AI figure must not bring a second pot back on screen.
    plans.value = [{ code: 'FREE', includedAiCredits: 250 }];
    armWelcomeGift();

    render(<WelcomeGiftModal />);

    expect(screen.getByTestId('welcome-gift-credits').textContent).toContain('1,000');
    expect(screen.queryByTestId('welcome-gift-ai-credits')).toBeNull();
    const modal = screen.getByTestId('welcome-gift-modal');
    expect(modal.textContent).toContain('renewal');
    expect(modal.textContent).not.toContain('freeAiCredits');
  });

  it('opens without waiting on the plans request, which it no longer reads', () => {
    // It used to wait for the live AI allowance figure; with one fixed pool the
    // plan verdict alone decides, so an empty or failed plans list cannot hold it.
    plans.value = [];
    armWelcomeGift();

    render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(true);
  });
});
