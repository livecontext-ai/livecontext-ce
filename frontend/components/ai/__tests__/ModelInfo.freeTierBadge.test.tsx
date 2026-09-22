// @vitest-environment jsdom
/**
 * The free-tier chip reaches every LLM picker through the same ONE row as the
 * lock beside it.
 *
 * <p>`ModelOptionDisplay` is what the composer menu, the agent dialog, the chat
 * config panel and the four node inspectors all draw their rows with, so the
 * positive marker is placed there once rather than seven times, exactly as
 * `upgradeRequired` was. And for the same reason it is a VALUE, not a question
 * the row asks: the plan half of the answer belongs to the whole list, and a
 * query observer behind every option would re-read one cached answer per row.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Namespaced, so the two markers on this row can be told apart: both would
// otherwise render the bare key "label".
vi.mock('next-intl', () => ({
  useTranslations: (ns: string) => (k: string) => `${ns}.${k}`,
}));

import type { AIModel } from '@/hooks/useModels';
import { ModelInfoPopover, ModelOptionDisplay } from '../ModelInfo';

const model: AIModel = {
  id: 'gpt-x',
  name: 'GPT X',
  provider: 'openai',
  pricing: { input: 5, output: 25 },
};

afterEach(cleanup);

describe('ModelOptionDisplay - the free-tier marker', () => {
  it('marks a model the free-tier allowance pays for', () => {
    render(<ModelOptionDisplay model={model} freeTier />);

    expect(screen.getByTestId('free-tier-badge')).toHaveTextContent('billing.freeTier.label');
    // Beside the facts about the model, not in place of them.
    expect(screen.getByText('GPT X')).toBeInTheDocument();
  });

  it('leaves the row unmarked by default, which is what a paid plan sees', () => {
    render(<ModelOptionDisplay model={model} />);

    expect(screen.queryByTestId('free-tier-badge')).toBeNull();
    expect(screen.getByText('GPT X')).toBeInTheDocument();
  });

  it('draws whichever markers it is handed, and decides neither', () => {
    // The row is presentational: it has no balance and no plan, so it cannot be
    // the thing that keeps the chip and the lock apart. That is the verdict's job
    // and it is pinned where it is decidable, in useMonthlyCreditsCannotPay
    // ("the two markers can never appear on the same row"). What matters here is
    // that the row does not invent or suppress either one.
    render(<ModelOptionDisplay model={model} freeTier upgradeRequired />);

    expect(screen.getByTestId('free-tier-badge')).toBeInTheDocument();
    expect(screen.getByText('billing.upgradeRequired.label')).toBeInTheDocument();
  });

  it('greys the model NAME when the balance cannot pay, and fades nothing else', () => {
    // This is the "greyed out" the menus show. It rides on the name because the
    // name has contrast to spare (theme-primary down to slate-500, still above
    // AA), while the 11px meta line beneath is already near the floor - which is
    // why neither surface fades the row as a whole.
    const { container } = render(<ModelOptionDisplay model={model} upgradeRequired />);

    // slate-600: 6.57:1 on the darkest background a blocked row sits on, against
    // a 4.5:1 bar for 14px/500. slate-500 was 4.13 there, which is why the colour
    // is pinned and not just "some grey".
    expect(screen.getByText('GPT X')).toHaveClass('text-slate-600');
    expect(container.querySelector('[class*="opacity-"]')).toBeNull();
  });

  it('leaves the name at full strength for a model the account can pay for', () => {
    render(<ModelOptionDisplay model={model} freeTier />);

    expect(screen.getByText('GPT X')).not.toHaveClass('text-slate-600');
  });

  it('marks the compact variant too, which drops the price and not the markers', () => {
    // No production caller passes `variant` today (both pickers take the default),
    // so this guards the API rather than a screen: the compact shape exists to
    // shed the price and the spare capability icons in a narrow host, and a
    // marker about whether the model can run at all is not what it sheds.
    render(<ModelOptionDisplay model={model} variant="compact" freeTier />);

    expect(screen.getByTestId('free-tier-badge')).toBeInTheDocument();
  });

  it('repeats the explanation on the info card, which is the touch path', () => {
    // The chip explains itself through a hover tooltip, which a touch device can
    // never open - the same reason the credit estimate is repeated on this card.
    // Without it, a reader on a phone sees the word and never the reason.
    render(<ModelInfoPopover model={model} freeTier trigger={<button>info</button>} />);
    fireEvent.click(screen.getByRole('button', { name: 'info' }));

    expect(screen.getByTestId('free-tier-detail')).toHaveTextContent('billing.freeTier.tooltip');
  });

  it('leaves that card alone for a model the allowance does not cover', () => {
    render(<ModelInfoPopover model={model} trigger={<button>info</button>} />);
    fireEvent.click(screen.getByRole('button', { name: 'info' }));

    expect(screen.queryByTestId('free-tier-detail')).toBeNull();
  });
});
