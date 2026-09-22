// @vitest-environment jsdom
/**
 * Which key a model runs on, said on the row where the model is chosen.
 *
 * <p><b>The failure this pins.</b> An own-key row already stated its charge, but as a bare credit
 * figure shaped exactly like the platform estimate beside it - same words, same place. The only
 * thing marking it as a different route was a hover tooltip, and hover does not exist on a touch
 * device, so on every tablet and phone the two routes were indistinguishable. They bill from
 * different pockets: on one the provider invoices the tokens directly.
 *
 * <p>Three properties, each of which regresses silently: the badge appears exactly when the route
 * is the caller's own key, it is ABSENT on every platform row (a false "Your key" is the worse
 * error - it tells someone their provider is being billed when the platform is), and the narrow
 * variant keeps the word for a screen reader after dropping it for width.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { AIModel } from '@/hooks/useModels';
import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';
import { ModelOptionDisplay, ModelInfoPopover } from '../ModelInfo';

const model: AIModel = {
  id: 'claude-sonnet-5',
  name: 'Claude Sonnet 5',
  provider: 'anthropic',
  tier: 'high',
  pricing: { input: 2, output: 10 },
  priceCacheWrite: 2.5,
  priceCacheRead: 0.2,
  supportsPromptCaching: true,
};

/** A metered install, with the coefficients the server publishes. */
const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: {
    chatConversation: {
      inputCoefficient: 0.0133333,
      cacheWriteCoefficient: 52.8,
      cacheReadCoefficient: 184.2666,
      outputCoefficient: 4.5333,
    },
  },
  cacheFallback: {
    anthropic: { cacheWriteWeight: 1.25, cacheReadWeight: 0.1, modelCacheWritePriceApplies: true },
    '*': { cacheWriteWeight: 1, cacheReadWeight: 1, modelCacheWritePriceApplies: false },
  },
};

/** The same install, once the caller holds an Anthropic key their plan lets serve. */
const ON_MY_KEY: ModelCostBasis = {
  ...BASIS,
  ownKey: { providers: ['anthropic'], feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 } },
};

const renderRow = (props: Partial<React.ComponentProps<typeof ModelOptionDisplay>> = {}) =>
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <ModelOptionDisplay model={model} costBasis={BASIS} {...props} />
    </NextIntlClientProvider>,
  );

afterEach(() => {
  cleanup();
});

describe('the own-key badge on a model row', () => {
  it('marks the row when the next turn runs on the caller own key', () => {
    renderRow({ costBasis: ON_MY_KEY });

    expect(screen.getByTestId('own-key-badge')).toHaveTextContent('Your key');
  });

  it('marks the platform route instead on a model that key does not serve', () => {
    // The trap: a saved OpenAI key must not mark an Anthropic row as "Your key". `ownKeyChargeFor`
    // answers on the PROVIDER, and a badge drawn from "the user has some key" would mislabel the
    // route on every other provider in the same list - the expensive direction, since it says
    // someone else is being billed for the tokens.
    renderRow({
      costBasis: {
        ...BASIS,
        ownKey: { providers: ['openai'], feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 } },
      },
    });

    expect(screen.queryByTestId('own-key-badge')).not.toBeInTheDocument();
    // And it SAYS so, rather than staying silent: in a list that mixes the two routes an absent
    // mark reads as a row that forgot to say, not as an answer.
    expect(screen.getByTestId('platform-key-badge')).toHaveTextContent('LiveContext key');
  });

  it('marks neither route for a reader who has brought no key at all', () => {
    // One route, no question to answer: a badge on every row would be furniture. This is the
    // shape of the app for almost everyone, so it is the one that must stay quiet.
    renderRow({ costBasis: BASIS });

    expect(screen.queryByTestId('own-key-badge')).not.toBeInTheDocument();
    expect(screen.queryByTestId('platform-key-badge')).not.toBeInTheDocument();
  });

  it('says nothing where nothing is metered at all', () => {
    // A self-hosted install passes no basis; a row that invented a route there would be pure noise.
    renderRow({ costBasis: null });

    expect(screen.queryByTestId('own-key-badge')).not.toBeInTheDocument();
    expect(screen.queryByTestId('platform-key-badge')).not.toBeInTheDocument();
  });

  it('keeps the word for a screen reader when the row is too narrow to print it', () => {
    // ~280px in the workflow inspector: the label collapses to the key icon, and the text stays
    // in the accessibility tree rather than being deleted.
    renderRow({ costBasis: ON_MY_KEY, variant: 'compact' });

    const label = screen.getByText('Your key');
    expect(label).toBeInTheDocument();
    expect(label.className).toContain('sr-only');
  });

  it('prints the word on a row that has the width for it', () => {
    renderRow({ costBasis: ON_MY_KEY });

    expect(screen.getByText('Your key').className).not.toContain('sr-only');
  });

  it('explains the own-key route in a tooltip, from the component own lookup', async () => {
    // Rendered, not read out of the dictionary. Asserting `enMessages.modelInfo.…` would pass
    // over a typo INSIDE the component's `t('ownKeyBadgeTooltip')` call, which is the failure
    // worth catching: it ships as the raw path to whoever hovers, and locale parity cannot see
    // it either, since a key absent from all six files is still perfectly at parity. Radix mounts
    // a tooltip's content only while it is open, hence the focus first.
    renderRow({ costBasis: ON_MY_KEY });

    fireEvent.focus(screen.getByTestId('own-key-badge'));
    await screen.findByRole('tooltip');

    expect(document.body.textContent).toContain('This model runs on your own API key, not the platform key.');
    expect(document.body.textContent).not.toContain('modelInfo.');
  });

  it('explains the platform route in its own tooltip, not the own-key one', async () => {
    // The pair share a component, so one `t()` call feeding both would be invisible at rest and
    // would tell a reader on the platform route that their provider is being billed.
    renderRow({
      costBasis: {
        ...BASIS,
        ownKey: { providers: ['openai'], feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 } },
      },
    });

    fireEvent.focus(screen.getByTestId('platform-key-badge'));
    await screen.findByRole('tooltip');

    expect(document.body.textContent).toContain('This model runs on the LiveContext key');
    expect(document.body.textContent).not.toContain('your own API key');
  });

  it('repeats the mark in the detail card, the only path a touch device has to it', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={ON_MY_KEY} />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'View model details' }));

    expect(screen.getByTestId('own-key-badge')).toHaveTextContent('Your key');
  });
});
