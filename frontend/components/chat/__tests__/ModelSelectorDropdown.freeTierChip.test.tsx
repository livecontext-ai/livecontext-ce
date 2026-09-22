// @vitest-environment jsdom
/**
 * What a Free account is told in the composer, before and after it opens the menu.
 *
 * <p>Two halves of the same sentence. On the CLOSED composer, the model in hand
 * carries a "Free" chip when the plan's AI allowance pays for it: a new account
 * is primed onto such a model by {@code usePreferFreeTierModel} and would
 * otherwise have to open a menu it has no reason to open to learn that its first
 * turn costs nothing. Inside the menu, the models the balance cannot pay for are
 * DIMMED as well as locked, so the two groups are told apart at a glance.
 *
 * <p>Both decisions are made here because this component holds the catalogue,
 * and both are rendered from PROPS: it is deliberately free of translations and
 * data hooks (see its own header), so the words arrive as a node and the verdict
 * as a callback, exactly as {@code upgradeNotice} already did.
 *
 * <p>The dimmed rows stay SELECTABLE on purpose. "Blocked" means the balance
 * cannot pay right now, which a top-up changes; disabling the row would also
 * hide the notice under the list, which is the only thing in the menu that says
 * what to do about it.
 *
 * <p>Its sibling {@code ModelSelectorDropdown.freeTier} pins the V494 half that
 * came first: which models lead, and which ones carry the lock. This one pins
 * what was added on top of it.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
// Rendered rather than nulled: the provider icon's fade is half of what "greyed
// out" means on these rows, so its class has to be reachable.
vi.mock('next/image', () => ({
  default: ({ alt, className }: { alt: string, className?: string }) => (
    <span data-testid={`icon-${alt}`} className={className} />
  ),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: () => {} }));
// The row reports only which verdicts reached it; what it draws with them is
// ModelInfo's own suite.
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model, upgradeRequired, freeTier }: {
    model: { id: string }, upgradeRequired?: boolean, freeTier?: boolean,
  }) => (
    <span
      data-testid={`row-${model.id}`}
      data-upgrade={String(!!upgradeRequired)}
      data-free-tier={String(!!freeTier)}
    >
      {model.id}
    </span>
  ),
  // The card is the touch path for the chip's own sentence, so what reaches it
  // matters as much as what reaches the row.
  ModelInfoPopover: ({ model, freeTier }: { model: { id: string }, freeTier?: boolean }) => (
    <span data-testid={`card-${model.id}`} data-free-tier={String(!!freeTier)} />
  ),
}));

import { ModelSelectorDropdown } from '../ModelSelectorDropdown';

const FREE_MODEL = {
  id: 'gemini-flash', name: 'Gemini Flash', provider: 'google', iconSlug: 'google',
  freeTierEnabled: true,
};
const PAID_MODEL = {
  id: 'gpt-5', name: 'GPT 5', provider: 'openai', iconSlug: 'openai',
  freeTierEnabled: false,
};
const models = [FREE_MODEL, PAID_MODEL] as never[];

/** Shaped like the real one: the host hands it down unconditionally. */
const BADGE = <span data-testid="free-chip">Free</span>;

function renderComposer(props: Record<string, unknown> = {}) {
  const selected = (props.selectedModel as { id: string }) ?? { provider: 'google', id: 'gemini-flash' };
  return render(
    <ModelSelectorDropdown
      showModelSelector
      setShowModelSelector={() => {}}
      selectedModel={selected as never}
      selectedModelData={{ name: selected.id, id: selected.id }}
      availableModels={models}
      setSelectedModel={() => {}}
      changeModelTitle="change model"
      freeTierBadge={BADGE}
      {...props}
    />,
  );
}

/**
 * The REAL formulas from `useMonthlyCreditsCannotPay`, not a convenient stand-in,
 * parameterised by the one input that separates the two states a free account
 * lives in: an allowance with something in it, and a spent one.
 *
 * <p>A stub of the shape "blocked iff not free-tier" hides the whole bug this
 * pair exists to prevent, because it makes the two verdicts complementary by
 * definition. They are only complementary while `allowanceCanPay` holds.
 */
function verdicts(allowanceCanPay: boolean) {
  return {
    blockedForModel: (m: { freeTierEnabled?: boolean }) => !(allowanceCanPay && m.freeTierEnabled === true),
    freeTierForModel: (m: { freeTierEnabled?: boolean }) => allowanceCanPay && m.freeTierEnabled === true,
  };
}

/** A free account with credit left in its monthly AI pot. */
const WITH_ALLOWANCE = verdicts(true);
/** The same account later in the month, having spent it: nothing free, all locked. */
const SPENT_ALLOWANCE = verdicts(false);
/** A subscriber: its own credits pay for everything, so neither marker applies. */
const PAID_PLAN = { blockedForModel: () => false, freeTierForModel: () => false };

let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // The menu renders only once it has measured its trigger, and jsdom measures
  // everything as zero. The sibling suites pin a rect for the same reason.
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  const rect = {
    top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32,
    x: 1000, y: 600, toJSON: () => ({}),
  } as DOMRect;
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => rect);
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

describe('ModelSelectorDropdown - the free chip on the composer', () => {
  it('says the current model is free, on the composer itself', () => {
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    expect(screen.getByTestId('free-chip')).toBeInTheDocument();
  });

  it('says nothing when the model in hand is not covered', () => {
    renderComposer({
      prefersFreeTierModels: true,
      ...WITH_ALLOWANCE,
      selectedModel: { provider: 'openai', id: 'gpt-5' },
    });

    expect(screen.queryByTestId('free-chip')).toBeNull();
  });

  it('says nothing once the monthly allowance is spent', () => {
    // The state every active free account reaches every month, and the one a
    // plan-only verdict would get wrong: it would keep the chip on a model whose
    // turns are now refused, beside the lock that says so. The chip follows the
    // verdict that weighs the balance, which is what this pins at this surface.
    renderComposer({ prefersFreeTierModels: true, ...SPENT_ALLOWANCE });

    expect(screen.queryByTestId('free-chip')).toBeNull();
  });

  it('says nothing on a plan that pays for every model', () => {
    // A paid account is told neither thing: its own credits pay for every model,
    // so nothing is free and nothing is blocked.
    renderComposer({ prefersFreeTierModels: false, ...PAID_PLAN });

    expect(screen.queryByTestId('free-chip')).toBeNull();
  });

  it('says nothing while the selection is still empty', () => {
    // The verdict lands after the first paint and the selection is primed later
    // still, so there is a window with no model in hand. A chip there would claim
    // something about a model the reader has not been given yet.
    renderComposer({
      prefersFreeTierModels: true,
      ...WITH_ALLOWANCE,
      selectedModel: { provider: '', id: '' },
      selectedModelData: undefined,
    });

    expect(screen.queryByTestId('free-chip')).toBeNull();
  });

  it('keeps the chip out of the truncating name, so it survives a narrow panel', () => {
    // The model name is the elastic part of the composer row (a side panel can be
    // 320px wide), so a chip inside it is the first thing an ellipsis eats, at
    // exactly the width where a new account meets it.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    const chip = screen.getByTestId('free-chip');
    expect(chip.closest('.truncate')).toBeNull();
    expect(chip.parentElement).toHaveClass('shrink-0');
  });

  it('renders nothing extra when the host injects no badge', () => {
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE, freeTierBadge: undefined });

    expect(screen.queryByTestId('free-chip')).toBeNull();
  });

  it('marks nothing at all when the host passes no per-model verdict', () => {
    // A surface that has not been updated shows what it showed before: the chip is
    // never guessed from the plan, because guessing it is the bug above.
    renderComposer({ prefersFreeTierModels: true });

    expect(screen.queryByTestId('free-chip')).toBeNull();
    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-free-tier', 'false');
    }
  });
});

describe('ModelSelectorDropdown - the menu rows', () => {
  it('marks the covered rows as free', () => {
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    expect(screen.getByTestId('row-gemini-flash')).toHaveAttribute('data-free-tier', 'true');
    expect(screen.getByTestId('row-gpt-5')).toHaveAttribute('data-free-tier', 'false');
  });

  it('marks no row free once the allowance is spent, and locks them all', () => {
    // The row-level half of the same question: a chip beside a lock, on one row,
    // would say opposite things about the same money.
    renderComposer({ prefersFreeTierModels: true, ...SPENT_ALLOWANCE });

    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-free-tier', 'false');
      expect(row).toHaveAttribute('data-upgrade', 'true');
    }
  });

  it('marks the rows the balance cannot pay for, and only those', () => {
    // What the marker DRAWS is the shared row's business (its own suite asserts
    // the greyed name); what this surface owns is which rows carry it.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    const blocked = screen.getAllByTestId('model-row-blocked');
    expect(blocked).toHaveLength(1);
    expect(blocked[0]).toContainElement(screen.getByTestId('row-gpt-5'));
    expect(screen.getByTestId('row-gemini-flash').closest('[data-testid="model-row-blocked"]'))
      .toBeNull();
  });

  it('fades the provider icon of a blocked row, and only that row', () => {
    // The icon is the decorative half of the greying (the name is the other half,
    // inside the shared row). Asserted here because this is where the icon lives.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    expect(screen.getByTestId('icon-openai')).toHaveClass('opacity-50');
    expect(screen.getByTestId('icon-google')).not.toHaveClass('opacity-50');
  });

  it('never fades the row itself, which would take its 11px meta line under AA', () => {
    // The meta line is 11px slate-500: around 4.8:1 on white at full strength, so
    // there is nothing to give. Compositing the subtree - the shape this took
    // first - put it far under the 4.5:1 floor. What fades is the icon; what greys
    // is the name.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    expect(screen.getAllByTestId('model-row-blocked')[0].className).not.toMatch(/\bopacity-/);
  });

  it('keeps the info card reachable without a hover, which is the touch path', () => {
    // The card is where the chip's full sentence lives, and it is hover-revealed
    // for a pointer. A finger has no hover and a keyboard no pointer, so the
    // wrapper opts out of the fade for both.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    const card = screen.getByTestId('card-gemini-flash').parentElement as HTMLElement;
    expect(card.className).toContain('pointer-coarse:opacity-100');
    expect(card.className).toContain('focus-within:opacity-100');
  });

  it('marks nothing for an account whose credits can pay', () => {
    renderComposer({ prefersFreeTierModels: false, ...PAID_PLAN });

    expect(screen.queryAllByTestId('model-row-blocked')).toHaveLength(0);
  });

  it('hands the verdict to the info card too, which is the touch path', () => {
    // The card is where a touch reader gets the sentence behind the chip, so the
    // verdict has to reach it and not only the row.
    renderComposer({ prefersFreeTierModels: true, ...WITH_ALLOWANCE });

    expect(screen.getByTestId('card-gemini-flash')).toHaveAttribute('data-free-tier', 'true');
    expect(screen.getByTestId('card-gpt-5')).toHaveAttribute('data-free-tier', 'false');
  });

  it('regression: a dimmed row is still a choice, because a top-up makes it payable', () => {
    // The verdict is about the balance NOW. Disabling the row would also strand the
    // reader: the notice under the list, which is the only way out this menu offers,
    // is shown for the SELECTED model.
    const setSelectedModel = vi.fn();
    renderComposer({
      prefersFreeTierModels: true,
      ...WITH_ALLOWANCE,
      setSelectedModel,
    });

    fireEvent.click(screen.getByTestId('model-row-blocked'));

    expect(setSelectedModel).toHaveBeenCalledTimes(1);
    expect(setSelectedModel.mock.calls[0][0]).toMatchObject({ id: 'gpt-5' });
  });

  it('dims on the account-level verdict when no per-model one is given', () => {
    // The pre-V494 shape, still what a caller without the newer hook gets: the
    // dimming follows the same verdict as the lock, so the two cannot disagree on a
    // surface that was never updated.
    renderComposer({ upgradeRequired: true });

    expect(screen.queryAllByTestId('model-row-blocked')).toHaveLength(2);
  });
});
