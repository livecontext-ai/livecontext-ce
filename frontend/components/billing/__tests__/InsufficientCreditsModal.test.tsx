// @vitest-environment jsdom
/**
 * Tests for the Cloud insufficient-credits modal: closed by default, opens on
 * its window event (via showInsufficientCreditsModal), RE-opens on every
 * dispatch after being closed (regression: no persistent dismiss), is a no-op
 * in CE mode, gates the "Top up instead" CTA on PAYG tier configuration, and
 * scopes the Free-plan note to the FREE plan only.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mocks = vi.hoisted(() => ({
  useSubscription: vi.fn(),
  usePaygTiers: vi.fn(),
  usePaygCheckout: vi.fn(),
  push: vi.fn(),
  // Mutable CE flag: the component reads isCeMode at render/effect time, so a
  // getter on the mocked module lets individual tests flip the edition.
  ceMode: { value: false },
  // The plan rows the Free column reads its AI allowance from. Undefined =
  // request still in flight, which is what most tests here are: they are about
  // the modal's own behaviour, not about the allowance.
  plans: { value: undefined as unknown },
}));

vi.mock('next-intl', () => ({
  useTranslations:
    (ns?: string) =>
    (key: string, values?: Record<string, unknown>) => {
      const path = ns ? `${ns}.${key}` : key;
      // Values echoed so a test can see which figure a line was handed. A key
      // called without values keeps its bare path, which is what every
      // exact-match query in this file relies on.
      return values ? `${path}(${Object.values(values).join(',')})` : path;
    },
  // The plan cards render FoundingPriceNote, which formats the announced price and
  // the deadline in the APP locale.
  useLocale: () => 'en',
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mocks.push }),
}));

vi.mock('@/lib/utils/locale', () => ({
  getClientLocale: () => 'en',
}));

vi.mock('@/lib/format-cost', () => ({
  get isCeMode() {
    return mocks.ceMode.value;
  },
  // Needed by the nested TopUpModal import chain.
  formatCreditsCompact: (v: number) => String(v),
}));

vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => mocks.useSubscription(),
  usePaygTiers: () => mocks.usePaygTiers(),
  usePaygCheckout: () => mocks.usePaygCheckout(),
  // The Free column states the AI allowance the plan row carries, so the card
  // quotes what the account will actually get rather than a constant an admin
  // may have moved since the build.
  usePlans: () => ({ plans: mocks.plans.value }),
}));

// Radix Slider requires ResizeObserver, which jsdom does not provide.
vi.mock('@/components/ui/slider', () => ({
  Slider: () => <div data-testid="credit-slider" />,
}));

vi.mock('@/components/pricing/DeploymentBadge', () => ({ default: () => null }));
vi.mock('@/components/pricing/FeatureLabel', () => ({
  default: ({ feature }: { feature: string }) => <span>{feature}</span>,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <div data-testid="loading-spinner" />,
}));

import InsufficientCreditsModal, {
  INSUFFICIENT_CREDITS_EVENT,
  showInsufficientCreditsModal,
} from '../InsufficientCreditsModal';
import { FREE_AI_CREDITS } from '@/lib/billing/pricing-constants';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const TITLE = 'modals.insufficientCredits.title';
const TOP_UP_CTA = 'billing.payg.topUpInstead';
const FREE_SCOPE_NOTE = 'modals.insufficientCredits.freeScopeNote';
const TOP_UP_MODAL_TITLE = 'billing.payg.modal.title';

const configuredTiers = [
  { tier: 'small', credits: 5_000, amountCents: 500, currency: 'USD', configured: true },
  { tier: 'medium', credits: 12_000, amountCents: 1250, currency: 'USD', configured: true },
  { tier: 'large', credits: 30_000, amountCents: 2500, currency: 'USD', configured: true },
];

const openViaEvent = () => {
  act(() => {
    showInsufficientCreditsModal();
  });
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('InsufficientCreditsModal', () => {
  beforeEach(() => {
    mocks.ceMode.value = false;
    mocks.push.mockReset();
    mocks.useSubscription
      .mockReset()
      .mockReturnValue({ createSubscription: vi.fn(), subscription: null });
    mocks.usePaygTiers
      .mockReset()
      .mockReturnValue({ tiers: [], configured: false, isLoading: false });
    mocks.usePaygCheckout
      .mockReset()
      .mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    // No plan rows by default: the request in flight, which is what most tests
    // here are, since they are about the modal rather than about the allowance.
    mocks.plans.value = undefined;
  });

  afterEach(() => cleanup());

  describe('event-driven open/close', () => {
    it('is closed by default and opens when showInsufficientCreditsModal() dispatches the event', () => {
      render(<InsufficientCreditsModal />);
      expect(screen.queryByText(TITLE)).toBeNull();

      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
    });

    it('also opens on a raw CustomEvent dispatch of the exported event name', () => {
      render(<InsufficientCreditsModal />);

      fireEvent(window, new CustomEvent(INSUFFICIENT_CREDITS_EVENT));

      expect(screen.getByText(TITLE)).toBeTruthy();
    });

    it('re-opens on every dispatch after being closed (no persistent dismiss)', () => {
      render(<InsufficientCreditsModal />);

      // First attempt: open then close via the dialog close button.
      openViaEvent();
      expect(screen.getByText(TITLE)).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Close' }));
      expect(screen.queryByText(TITLE)).toBeNull();

      // Second attempt MUST re-open the modal - dismissal is never persisted.
      openViaEvent();
      expect(screen.getByText(TITLE)).toBeTruthy();
    });
  });

  describe('CE mode', () => {
    it('renders nothing and ignores the event in CE mode', () => {
      mocks.ceMode.value = true;
      render(<InsufficientCreditsModal />);

      openViaEvent();

      expect(screen.queryByText(TITLE)).toBeNull();
    });
  });

  describe('Top up CTA gating', () => {
    it('shows the "Top up instead" CTA when PAYG tiers are configured', () => {
      mocks.usePaygTiers.mockReturnValue({
        tiers: configuredTiers,
        configured: true,
        isLoading: false,
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByRole('button', { name: new RegExp(TOP_UP_CTA) })).toBeTruthy();
    });

    it('hides the "Top up instead" CTA when no PAYG tier is configured', () => {
      mocks.usePaygTiers.mockReturnValue({ tiers: [], configured: false, isLoading: false });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
      expect(screen.queryByText(TOP_UP_CTA)).toBeNull();
    });

    it('opens the nested TopUpModal when the "Top up instead" CTA is clicked', () => {
      mocks.usePaygTiers.mockReturnValue({
        tiers: configuredTiers,
        configured: true,
        isLoading: false,
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();
      expect(screen.queryByText(TOP_UP_MODAL_TITLE)).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: new RegExp(TOP_UP_CTA) }));

      expect(screen.getByText(TOP_UP_MODAL_TITLE)).toBeTruthy();
    });
  });

  describe('Free-plan scoping note', () => {
    it('shows the Free-plan note when the subscription has no plan code (defaults to FREE)', () => {
      mocks.useSubscription.mockReturnValue({ createSubscription: vi.fn(), subscription: null });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(FREE_SCOPE_NOTE)).toBeTruthy();
    });

    it('shows the Free-plan note when the plan code is FREE (case-insensitive)', () => {
      mocks.useSubscription.mockReturnValue({
        createSubscription: vi.fn(),
        subscription: { subscription: { planCode: 'free' } },
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(FREE_SCOPE_NOTE)).toBeTruthy();
    });

    it('hides the Free-plan note for a paid plan', () => {
      mocks.useSubscription.mockReturnValue({
        createSubscription: vi.fn(),
        subscription: { subscription: { planCode: 'PRO' } },
      });
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(screen.getByText(TITLE)).toBeTruthy();
      expect(screen.queryByText(FREE_SCOPE_NOTE)).toBeNull();
    });
  });

  describe('what a credit buys, on the card the user is reading', () => {
    // A reader who has just run out of credits is asking exactly the question the
    // plan-comparison table answers. The modal offers the same answer, behind the same
    // "i", from the same message and the same facts - restating it in different words is
    // how two surfaces start making different pricing claims.
    const SHARED_TOOLTIP = 'pricing.compare.dimensions.creditsTooltip';
    /**
     * The Free pot has its OWN message, because it is its own pot: on FREE the monthly
     * bucket funds workflow nodes and nothing else, so the paid sentence would price it
     * with a debit it refuses. Both are plan-card messages; a credits line carries one.
     */
    const FREE_TOOLTIP = 'pricing.planCards.features.creditsFreeTooltip';

    function creditsLines(): string[] {
      return screen.getAllByText((_, node) => {
        const text = node?.textContent ?? '';
        return text.includes(SHARED_TOOLTIP) || text.includes(FREE_TOOLTIP);
      }, { selector: 'span' }).map((n) => n.textContent ?? '');
    }

    it('every plan card explains what its credits buy, not only the Free one', () => {
      render(<InsufficientCreditsModal />);
      openViaEvent();

      // Free + Starter + Pro + Team at the default slider position.
      expect(creditsLines().length).toBeGreaterThanOrEqual(4);
    });

    it('offers the explanation through the shared "||" tooltip convention, so FeatureLabel draws the "i"', () => {
      render(<InsufficientCreditsModal />);
      openViaEvent();

      for (const line of creditsLines()) {
        expect(line, 'a tooltip must be attached to a label, not rendered raw').toContain('||');
      }
    });

    it('explains the Free grant with the plan card message, never the paid one', () => {
      render(<InsufficientCreditsModal />);
      openViaEvent();

      const free = creditsLines().find((l) => l.includes('features.freeCredits'));
      expect(free).toBeTruthy();
      // The message that answers both questions for THIS pot: what the grant may be
      // spent on, and what a credit buys, priced in the unit the pot can actually fund.
      expect(free).toContain(FREE_TOOLTIP);
      // And NOT the paid sentence. It prices a short exchange with a configured agent,
      // which the FREE plan's monthly bucket refuses (CreditService funds only
      // WORKFLOW_NODE from it), so appending it here quoted a price this pot cannot pay
      // to the one reader who has just run out and is choosing what to buy.
      expect(free).not.toContain(SHARED_TOOLTIP);
    });
  });

  describe('the second pot the Free column holds', () => {
    // A reader refused for lack of credits is exactly the one who needs to know
    // that chat and agents do not draw from the pot that just ran out. A Free
    // column naming only the credits reads as "chat costs you credits too".
    // The allowance line appears in the Free column only, so the dialog's own
    // text is a sufficient and far less brittle subject than the card node.
    const dialogText = () => document.body.textContent ?? '';

    it('quotes the allowance an admin configured, not the seeded constant', () => {
      mocks.plans.value = [{ code: 'FREE', includedAiCredits: 250 }];
      render(<InsufficientCreditsModal />);
      openViaEvent();

      // The figure, not just the line: reading the live plan row is the whole
      // point, and a column that had the shipped 100 baked in would look
      // identical without it.
      expect(dialogText()).toContain('features.freeAiCredits(250)');
    });

    it('drops the line entirely when the free tier is closed', () => {
      // Allowance 0 is how an admin closes the free tier. A "0 AI credits"
      // bullet would look like a feature while advertising nothing, which is
      // the same rule the plan cards apply.
      mocks.plans.value = [{ code: 'FREE', includedAiCredits: 0 }];
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(dialogText()).toContain('features.freeCredits');
      expect(dialogText()).not.toContain('features.freeAiCredits');
    });

    it('falls back to the shipped figure while the plans request is in flight', () => {
      // No row yet is not "no allowance": showing nothing here would tell a
      // reader the free tier is closed for as long as the request takes.
      render(<InsufficientCreditsModal />);
      openViaEvent();

      expect(dialogText()).toContain(`features.freeAiCredits(${FREE_AI_CREDITS})`);
    });

    it('explains the allowance with the plan cards message, from the same facts', () => {
      // Same rule as the credits line above, and the one this modal used to
      // break: it carried a message of its OWN for this line, which said the
      // same thing in different words and quoted no figure at all. That message
      // no longer exists in any locale file, so a call site pointing back at it
      // renders its raw key path in the modal, in all six locales, with nothing
      // throwing. The key asserted here is the pricing card's, not the modal's.
      render(<InsufficientCreditsModal />);
      openViaEvent();

      const text = dialogText();
      expect(text, 'the allowance line must read the shared message')
        .toContain('pricing.planCards.features.aiCreditsFreeTooltip');
      // With the facts: an empty call would render the message with its
      // placeholders intact, which is the same defect one step later.
      expect(text).toMatch(/pricing\.planCards\.features\.aiCreditsFreeTooltip\([^)]+\)/);
      // And the message it replaced is gone for good.
      expect(text).not.toContain('modals.insufficientCredits.features.freeAiCreditsTooltip');
    });
  });
});
