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
 * <p>The two QUERIES are stubbed, not the hooks that read them, so the real
 * `useMonthlyCreditsCannotPay` and `useFreeAiCreditsAnswer` run: what this modal
 * gets wrong is which answers it waits for, and stubbing those two hooks would
 * be stubbing the question rather than answering it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const edition = vi.hoisted(() => ({ isCe: false }));
const balance = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));
const plans = vi.hoisted(() => ({ value: undefined as unknown }));

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
import { FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';
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

  it('waits for the ALLOWANCE answer too, rather than quoting the seeded figure', () => {
    // The plan list reads as an empty array both in flight and after a failed
    // request, and the allowance resolver answers the seeded 100 for both. On
    // any other surface that stand-in is a fair trade for rendering at once;
    // here it is the one number the screen exists to state, so opening early
    // would quote a figure this account may never have been granted.
    plans.value = [];
    armWelcomeGift();
    const handoff = watchHandoff();

    const view = render(<WelcomeGiftModal />);

    expect(isOpen()).toBe(false);
    expect(handoff.released).not.toHaveBeenCalled();

    plans.value = [{ code: 'FREE', includedAiCredits: 250 }];
    view.rerender(<WelcomeGiftModal />);

    expect(isOpen()).toBe(true);
    expect(screen.getByTestId('welcome-gift-ai-credits').textContent).toContain('250');
    expect(screen.getByTestId('welcome-gift-ai-credits').textContent)
      .not.toContain(String(FREE_AI_CREDITS));
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

  it('gives up on an allowance answer that never arrives, on the same bound', () => {
    // The second wait has to be bounded by the same timer as the first. A plan
    // request that succeeds and an allowance request that exhausts its retries
    // is a state the account can sit in indefinitely.
    vi.useFakeTimers();
    plans.value = [];
    armWelcomeGift();
    const handoff = watchHandoff();

    render(<WelcomeGiftModal />);
    expect(isOpen()).toBe(false);

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

  it('drops the AI row when an admin has closed the free tier', () => {
    // A row reading "0 AI credits" looks like a feature while advertising
    // nothing. Same rule the plan cards apply. The workflow credits stay: that
    // grant is untouched by the allowance being closed.
    plans.value = [{ code: 'FREE', includedAiCredits: 0 }];
    armWelcomeGift();

    render(<WelcomeGiftModal />);

    expect(screen.getByTestId('welcome-gift-credits')).toBeInTheDocument();
    expect(screen.queryByTestId('welcome-gift-ai-credits')).toBeNull();
  });

  it('adapts the renewal sentence when there is only one pot left to renew', () => {
    // "Both refill" describing a single row is a sentence about something the
    // reader cannot see. The two lines are separate keys in all six locales
    // rather than one with a count, because the shapes are different sentences
    // and not a plural of each other.
    plans.value = [{ code: 'FREE', includedAiCredits: 0 }];
    armWelcomeGift();

    render(<WelcomeGiftModal />);

    const modal = screen.getByTestId('welcome-gift-modal');
    expect(modal.textContent).toContain('renewalSingle');
    expect(modal.textContent).not.toContain('renewal,');
  });

  it('quotes the allowance an admin configured, not the shipped default', () => {
    plans.value = [{ code: 'FREE', includedAiCredits: 250 }];
    armWelcomeGift();

    render(<WelcomeGiftModal />);

    expect(screen.getByTestId('welcome-gift-ai-credits').textContent).toContain('250');
  });
});
