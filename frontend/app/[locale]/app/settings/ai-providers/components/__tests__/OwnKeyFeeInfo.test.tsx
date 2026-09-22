// @vitest-environment jsdom
/**
 * The (i) beside the own-keys panel: what a turn on your own key costs, per price band.
 *
 * <p><b>Why this exists at all.</b> The panel promised "a flat fee per agent turn" and never said
 * how much. The number was reachable only from a model row, and only AFTER a key had been saved -
 * so the one reader who needs it, the one deciding whether to bring a key, could not see it
 * anywhere. These four numbers are the offer.
 *
 * <p>What has to hold: the figures come from the server and are never restated here (the ladder is
 * an operator lever), the control disappears rather than opening onto an empty table on an install
 * that publishes none, and a band the server omitted is dropped rather than drawn as free.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';

const { basisRef } = vi.hoisted(() => ({ basisRef: { current: null as ModelCostBasis | null } }));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: basisRef.current, isLoading: false }),
}));

import OwnKeyFeeInfo from '../OwnKeyFeeInfo';

const METERED: ModelCostBasis = {
  enabled: true,
  profiles: {},
  ownKeyFeeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 },
};

const renderInfo = () =>
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OwnKeyFeeInfo />
    </NextIntlClientProvider>,
  );

const open = () => fireEvent.click(screen.getByRole('button', { name: 'See the price per turn' }));

beforeEach(() => {
  basisRef.current = METERED;
});

afterEach(() => {
  cleanup();
});

describe('OwnKeyFeeInfo', () => {
  it('states the four bands, with the fee the server published for each', () => {
    renderInfo();
    open();

    expect(screen.getByText('Price per turn on your key')).toBeInTheDocument();
    // The sentence that says who bills what. Rendered rather than looked up, because a wrong
    // namespace ships the raw key path and locale parity cannot see that.
    expect(screen.getByText(/Your provider bills you the tokens/)).toBeInTheDocument();
    expect(screen.getByText('Budget')).toBeInTheDocument();
    expect(screen.getByText('Mid tier')).toBeInTheDocument();
    expect(screen.getByText('High tier')).toBeInTheDocument();
    expect(screen.getByText('Top tier')).toBeInTheDocument();
    // In order, cheapest first: the ladder has to read as one climb, not four unrelated prices.
    expect(screen.getAllByTestId('own-key-fee-row').map((n) => n.textContent))
      .toEqual(['1', '2', '5', '10']);
  });

  it('names the unit once, over the column, rather than on every row', () => {
    // Per-row it produced "1 credits" on the budget band, and the obvious repair - a plural rule
    // - cannot apply to a value that is already a formatted string ("1,000", "<0.1"). A bare
    // column of numbers with no unit anywhere would be worse still, so the caption carries it.
    renderInfo();
    open();

    expect(screen.getByText('credits per turn')).toBeInTheDocument();
    expect(screen.queryByText(/\d+ credits/)).not.toBeInTheDocument();
  });

  it('never offers the unknown band, which is a server fallback and not a choice', () => {
    renderInfo();
    open();

    // `unknown` is the fee for a model with no published price. Listing it as a fifth row would
    // invite the question of which models are in it, and there is no answer a reader can act on.
    expect(screen.queryByText(/unknown/i)).not.toBeInTheDocument();
    expect(screen.getAllByTestId('own-key-fee-row')).toHaveLength(4);
  });

  it('renders nothing at all where the install publishes no ladder', () => {
    // Self-hosted meters no credits: the basis hook does not even fire there. An (i) that opens
    // onto an empty table is worse than no (i).
    basisRef.current = { enabled: false, profiles: {} };
    const { container } = renderInfo();

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing while the basis has not arrived', () => {
    basisRef.current = null;
    const { container } = renderInfo();

    expect(container).toBeEmptyDOMElement();
  });

  it('drops a band the server omitted rather than drawing it as free', () => {
    // A missing number is not a zero. "0 credits" beside a tier is a promise, and it is the one
    // shape of this bug that reads as a feature.
    basisRef.current = { enabled: true, profiles: {}, ownKeyFeeByTier: { budget: 1, top: 10 } };
    renderInfo();
    open();

    expect(screen.getAllByTestId('own-key-fee-row').map((n) => n.textContent)).toEqual(['1', '10']);
    expect(screen.queryByText('Mid tier')).not.toBeInTheDocument();
  });
});
