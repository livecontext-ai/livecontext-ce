/**
 * @vitest-environment jsdom
 *
 * The same two markers the composer menu carries, on the picker an agent's
 * model is chosen with.
 *
 * <p>This picker is where an agent is pointed at a model, and an agent turn is
 * billed exactly like a chat turn: out of the Free plan's AI allowance when the
 * model is one a cloud admin opened to the free tier, and out of the
 * pay-as-you-go bucket otherwise. So the reader is told which is which here too,
 * rather than discovering it when the agent is refused on its first run.
 *
 * <p>Its sibling {@code ModelPicker.freeTierOrder} pins WHICH models lead; this
 * one pins what they look like once listed. Dimmed rows stay selectable for the
 * reason spelled out in the composer's suite: the verdict is about the balance
 * now, and a top-up changes it.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  prefersFreeTier: true,
  /** Whether the monthly AI allowance still has credit in it. */
  allowanceCanPay: true,
  providers: [] as unknown[],
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string, children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div data-testid="select">{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
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
  // Beside the chosen model, this card is where a touch reader gets the chip's
  // sentence, so what reaches it is part of the wiring.
  ModelInfoPopover: ({ model, freeTier }: { model: { id: string }, freeTier?: boolean }) => (
    <span data-testid={`card-${model.id}`} data-free-tier={String(!!freeTier)} />
  ),
}));
// The REAL formulas from useMonthlyCreditsCannotPay, driven by the two inputs that
// matter: the plan, and whether the monthly AI allowance still has anything in it.
// A stub of the shape "blocked iff not free-tier" would make the two verdicts
// complementary BY DEFINITION and hide the one state where they are not - a covered
// model whose pot is spent, which is where every free account ends each month.
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: h.prefersFreeTier,
    blockedForModel: (m: { freeTierEnabled?: boolean } | null | undefined) =>
      h.prefersFreeTier && !(h.allowanceCanPay && m?.freeTierEnabled === true),
    freeTierForModel: (m: { freeTierEnabled?: boolean } | null | undefined) =>
      h.prefersFreeTier && h.allowanceCanPay && m?.freeTierEnabled === true,
    get prefersFreeTierModels() { return h.prefersFreeTier; },
    verdictReady: true,
    isLoading: false,
  }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: null,
      defaultModel: null,
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

const model = (provider: string, id: string, freeTierEnabled = false): AIModel =>
  ({ id, name: id, provider, freeTierEnabled });
const provider = (name: string, models: AIModel[]): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  models,
} as AIProvider);

beforeEach(() => {
  h.prefersFreeTier = true;
  h.allowanceCanPay = true;
  h.providers = [
    provider('anthropic', [model('anthropic', 'haiku', true), model('anthropic', 'opus')]),
  ];
});
afterEach(cleanup);

function renderPicker() {
  render(<ModelPicker value={{ provider: 'anthropic', id: 'haiku' }} onChange={vi.fn()} />);
}

describe('ModelPicker - what a Free account sees when it points an agent at a model', () => {
  it('marks the covered model as free and leaves the others unmarked', () => {
    renderPicker();

    // The trigger re-renders the selection, so each id appears twice; every
    // instance must carry the same verdict.
    for (const row of screen.getAllByTestId('row-haiku')) {
      expect(row).toHaveAttribute('data-free-tier', 'true');
    }
    for (const row of screen.getAllByTestId('row-opus')) {
      expect(row).toHaveAttribute('data-free-tier', 'false');
    }
  });

  it('marks the models the allowance does not cover, and only those', () => {
    renderPicker();

    const blocked = screen.getAllByTestId('model-row-blocked');
    expect(blocked).toHaveLength(1);
    expect(blocked[0]).toContainElement(screen.getAllByTestId('row-opus')[0]);
  });

  it('never fades the row itself, which would take its 11px meta line under AA', () => {
    // Same floor as the composer menu, and the same first mistake: an 11px
    // slate-500 line has nothing to give. The greying rides the name inside the
    // shared row, and the fade rides the decorative icon.
    renderPicker();

    expect(screen.getAllByTestId('model-row-blocked')[0].className).not.toMatch(/\bopacity-/);
  });

  it('carries the markers into the CLOSED trigger, which shows the selection', () => {
    // The trigger re-renders the chosen model through the same shared row, so the
    // lock and the greyed name reach it too. What it does not get is the icon
    // fade: a faded closed control reads as a disabled field, which it is not.
    // The comment beside that code said the opposite for a while.
    render(<ModelPicker value={{ provider: 'anthropic', id: 'opus' }} onChange={vi.fn()} />);

    expect(screen.getAllByTestId('row-opus')[0]).toHaveAttribute('data-upgrade', 'true');
    expect(screen.queryAllByTestId('model-row-blocked')[0])
      .not.toContainElement(screen.getAllByTestId('row-opus')[0]);
  });

  it('hands the verdict to the info card beside the chosen model', () => {
    renderPicker();

    expect(screen.getByTestId('card-haiku')).toHaveAttribute('data-free-tier', 'true');
  });

  it('locks the same rows it marks, so a greyed row never reads as broken', () => {
    // Both come from the one verdict; asserted together because the marker is on
    // the wrapper and the lock is inside the row, so nothing else pairs them.
    renderPicker();

    const blocked = screen.getAllByTestId('model-row-blocked');
    expect(blocked).toHaveLength(1);
    expect(blocked[0]).toContainElement(screen.getAllByTestId('row-opus')[0]);
    expect(screen.getAllByTestId('row-opus')[0]).toHaveAttribute('data-upgrade', 'true');
    expect(screen.getAllByTestId('row-haiku')[0]).toHaveAttribute('data-upgrade', 'false');
  });

  it('stops marking a covered model once the allowance is spent', () => {
    // The agent-side half of the same question: a chip resolved from the plan and
    // the model's flag alone would promise a free run on a model whose pot is
    // empty, next to the lock saying the run will be refused. An agent pointed at
    // that model on that promise fails on its first turn.
    h.allowanceCanPay = false;

    renderPicker();

    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-free-tier', 'false');
      expect(row).toHaveAttribute('data-upgrade', 'true');
    }
  });

  it('marks nothing at all for an account whose credits pay for every model', () => {
    h.prefersFreeTier = false;

    renderPicker();

    expect(screen.queryAllByTestId('model-row-blocked')).toHaveLength(0);
    for (const row of screen.getAllByTestId(/^row-/)) {
      expect(row).toHaveAttribute('data-free-tier', 'false');
      expect(row).toHaveAttribute('data-upgrade', 'false');
    }
  });
});
