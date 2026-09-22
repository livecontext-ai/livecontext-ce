// @vitest-environment jsdom
/**
 * What a brand-new account actually READS after onboarding, against the real
 * message catalogue.
 *
 * The modal's own suite stubs next-intl and asserts behaviour on message KEYS,
 * so it would pass just as happily with an empty catalogue or a sentence that
 * names one pot and forgets the other. That is the failure this file exists for:
 * the whole point of the screen is that the two monthly allowances are stated
 * side by side, and "stated" is a property of the rendered words, not of a key.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '../../../messages/en.json';

const plans = vi.hoisted(() => ({ value: undefined as unknown }));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useCreditBalance: () => ({
    monthlyCreditsAreWorkflowOnly: true,
    hasAnswered: true,
    isLoading: false,
  }),
  usePlans: () => ({ plans: plans.value }),
}));

import WelcomeGiftModal from '../WelcomeGiftModal';
import { armWelcomeGift } from '@/lib/onboarding/welcomeGiftHandoff';
import { FREE_MONTHLY_CREDITS } from '@/lib/billing/credit-allowance';

function renderGift() {
  return render(
    <NextIntlClientProvider locale="en" messages={en as any}>
      <WelcomeGiftModal />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  plans.value = [{ code: 'FREE', includedAiCredits: 100 }];
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe('what a brand-new account is shown after onboarding', () => {
  it('names the plan and both of its monthly pots, with a figure on each', () => {
    armWelcomeGift();

    renderGift();

    const modal = screen.getByTestId('welcome-gift-modal');
    // The plan the reader is on, said plainly. This screen replaced the
    // five-column comparison table precisely because a new account is not
    // choosing a plan, it is finding out what the one it has gives it.
    expect(modal.textContent).toContain('Free plan');

    // The two pots, each with its own figure. A screen that named only the
    // workflow credits reads as "chat spends your credits too", which is the
    // opposite of what happens and the exact question the reader has.
    const credits = screen.getByTestId('welcome-gift-credits');
    expect(credits.textContent).toContain('Workflow credits');
    expect(credits.textContent).toContain('1,000');

    const aiCredits = screen.getByTestId('welcome-gift-ai-credits');
    expect(aiCredits.textContent).toContain('AI credits');
    expect(aiCredits.textContent).toContain('100');

    // And what each one is FOR, since the two are not interchangeable.
    expect(credits.textContent).toContain('workflows');
    expect(aiCredits.textContent).toContain('chat and agent');
  });

  it('quotes the same workflow grant the rest of the product quotes', () => {
    // The figure on this card and the figure on the Free plan card are the same
    // product constant. Baking a different number into the copy here is how a
    // welcome screen starts promising something the renewal does not hand out.
    armWelcomeGift();

    renderGift();

    expect(screen.getByTestId('welcome-gift-credits').textContent)
      .toContain(FREE_MONTHLY_CREDITS.toLocaleString('en'));
    expect((en as any).pricing.planCards.features.creditsFree)
      .toContain(FREE_MONTHLY_CREDITS.toLocaleString('en'));
  });

  it('says the allowances come back, and that no card is needed', () => {
    // The gift is a recurring grant, not a one-off trial balance. A reader who
    // reads it as one-off spends it like one.
    armWelcomeGift();

    renderGift();

    const modal = screen.getByTestId('welcome-gift-modal');
    expect(modal.textContent).toContain('every month');
    expect(modal.textContent).toContain('No card needed');
  });

  it('shows nothing at all on an ordinary visit to the app', () => {
    renderGift();

    expect(screen.queryByTestId('welcome-gift-modal')).toBeNull();
  });
});
