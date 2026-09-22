// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { AIModel } from '@/hooks/useModels';
import type { ModelCostBasis } from '@/lib/billing/model-cost-estimate';
import { ModelOptionDisplay, ModelInfoPopover } from '../ModelInfo';

/**
 * The row says what a model will cost before it is picked. Three properties
 * matter, and all three fail silently if they regress: the figure has to appear
 * at all, it has to follow the SHAPE of work the surface configures (an agent
 * that calls tools is not a classify step), and it has to be absent wherever
 * there is nothing to estimate.
 *
 * The row is deliberately query-free - the basis is passed in, exactly like
 * `upgradeRequired` - so this renders with no query client behind it, which is
 * itself part of the contract.
 */

// The CACHE prices are part of the fixture, not decoration: a conversation spends
// most of its tokens on cache reads and writes, and a row that carried only
// `pricing` was what made the badge quote every one of them at the full input rate.
const model: AIModel = {
  id: 'claude-sonnet-5',
  name: 'Claude Sonnet 5',
  provider: 'anthropic',
  pricing: { input: 2, output: 10 },
  priceCacheWrite: 2.5,
  priceCacheRead: 0.2,
  supportsPromptCaching: true,
};

// The coefficients the server publishes: each profile's token workload folded
// together with the cloud billing multiplier (LlmCostEstimateService). These are the
// 1.333333 figures - a conversation is 10 plain input tokens, 39,600 cache writes,
// 138,200 cache reads and 3,400 output, each x 1.333333 / 1,000. The multiplier lives
// in exactly one place and reaches this component over the wire, so the fixture is only
// here to describe a real response shape; keeping a stale one would teach the next
// reader a margin the platform no longer charges.
//
// There is no agentConversation entry because there is no such profile: there is ONE
// conversation unit now, priced with its cache. On Claude Fable 5.1 this unit was
// 1,400 credits at the 2.0 lever and is about 933 at the 25% one.
const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: {
    chatConversation: {
      inputCoefficient: 0.0133333,
      cacheWriteCoefficient: 52.8,
      cacheReadCoefficient: 184.2666,
      outputCoefficient: 4.5333,
    },
    guardrailCheck: {
      inputCoefficient: 21.3333,
      cacheWriteCoefficient: 0,
      cacheReadCoefficient: 0,
      outputCoefficient: 0.1333,
    },
    classifyStep: {
      inputCoefficient: 1.6,
      cacheWriteCoefficient: 0,
      cacheReadCoefficient: 0,
      outputCoefficient: 0.08,
    },
  },
  cacheFallback: {
    anthropic: { cacheWriteWeight: 1.25, cacheReadWeight: 0.1, modelCacheWritePriceApplies: true },
    '*': { cacheWriteWeight: 1, cacheReadWeight: 1, modelCacheWritePriceApplies: false },
  },
};

afterEach(() => {
  cleanup();
});

function renderRow(props: Partial<React.ComponentProps<typeof ModelOptionDisplay>> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <ModelOptionDisplay model={model} {...props} />
    </NextIntlClientProvider>,
  );
}

describe('ModelOptionDisplay credit estimate', () => {
  it('shows what a short exchange with a configured agent on this model would cost', () => {
    renderRow({ costBasis: BASIS, costProfile: 'chatConversation' });

    expect(screen.getByText('~214 credits')).toBeInTheDocument();
  });

  it('prices the shape of work the surface configures, not one figure for all', () => {
    // Same model, same basis: only the surface differs. A classify picker that
    // quoted the conversation figure would overstate the cost by ~50x.
    renderRow({ costBasis: BASIS, costProfile: 'classifyStep' });

    expect(screen.getByText('~4 credits')).toBeInTheDocument();
    expect(screen.queryByText('~214 credits')).not.toBeInTheDocument();
  });

  it('defaults to the dearest shape, so an un-updated call site overstates rather than understates', () => {
    renderRow({ costBasis: BASIS });

    expect(screen.getByText('~214 credits')).toBeInTheDocument();
  });

  it('says nothing at all where credits are not metered, which is every CE install', () => {
    renderRow({ costBasis: { enabled: false, profiles: {} } });

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('says nothing before the basis has been answered', () => {
    renderRow();

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('says nothing for a model the catalogue does not price', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={{ id: 'x', name: 'Unpriced', provider: 'p' }} costBasis={BASIS} />
      </NextIntlClientProvider>,
    );

    expect(screen.queryByText(/ credits$/)).not.toBeInTheDocument();
  });

  it('keeps the estimate in the compact variant, where the price is dropped', () => {
    // The inspector variant hides the $/1M rates for width. The credits are the
    // one number a reader can act on there, so they survive the trim.
    renderRow({ costBasis: BASIS, variant: 'compact' });

    expect(screen.getByText('~214 credits')).toBeInTheDocument();
    expect(screen.queryByText(/per 1M/)).not.toBeInTheDocument();
  });

  it('explains the badge in a tooltip, with the figure bolded and no marker showing', async () => {
    // The DROPDOWN row, which is the more-visited of the two places this
    // sentence appears - the (i) card below is the touch path. Radix mounts a
    // tooltip's content only while it is open, so the badge is focused first;
    // read at rest, this assertion would pass over an empty document.
    renderRow({ costBasis: BASIS, costProfile: 'chatConversation' });

    fireEvent.focus(screen.getByText('~214 credits'));
    await screen.findByRole('tooltip');

    const bolded = Array.from(document.body.querySelectorAll('strong')).map((n) => n.textContent);
    expect(bolded, 'the credit figure is not emphasised in the row tooltip').toContain('214 credits');
    // A marker reaching the DOM means the render was skipped and a reader is
    // looking at raw asterisks.
    expect(document.body.textContent).not.toContain('**');
  });

  it('keeps the badge in a wrapping meta line rather than forcing the row wider', () => {
    // The row renders in a ~280px workflow inspector next to badges, icons, the
    // context window and the rates. The estimate holds together as one token
    // ("~214 credits" must never break after the tilde) but the LINE wraps, so a
    // narrow host grows taller instead of scrolling sideways.
    renderRow({ costBasis: BASIS });

    const badge = screen.getByText('~214 credits');
    expect(badge).toHaveClass('whitespace-nowrap');
    expect(badge.parentElement).toHaveClass('flex-wrap');
  });
});

describe('ModelOptionDisplay own-key charge', () => {
  // The basis as the server sends it to someone holding an Anthropic key their plan
  // allows: the providers whose saved key serves, and the flat fee per tier.
  const withOwnKey: ModelCostBasis = {
    ...BASIS,
    ownKey: {
      providers: ['anthropic'],
      feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 },
    },
  };
  const topTier: AIModel = { ...model, tier: 'top' };

  it('quotes the flat fee, not the token-rate estimate that route never pays', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={topTier} costBasis={withOwnKey} costProfile="chatConversation" />
      </NextIntlClientProvider>,
    );

    // Stated without a tilde: the fee does not move with the length of the turn.
    expect(screen.getByTestId('own-key-fee')).toHaveTextContent('10 credits');
    expect(screen.queryByText('~214 credits')).not.toBeInTheDocument();
  });

  it('quotes the platform price where it is BELOW the fee, because that is what the ledger caps to', () => {
    // A classify step on this model costs about 4 credits of tokens, under the 10-credit
    // top-tier fee. The ledger debits min(fee, consumption), so quoting the bare fee
    // would overcharge the reader by more than half on every row of a classify picker.
    // The figure moved from 6 to 4 when the platform lever went from 2.0 to 1.333333:
    // the cheaper the platform route gets, the more often the cap is what decides.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={topTier} costBasis={withOwnKey} costProfile="classifyStep" />
      </NextIntlClientProvider>,
    );

    // And marked as an estimate, which is what the capped half is.
    expect(screen.getByTestId('own-key-fee')).toHaveTextContent('~4 credits');
    expect(screen.queryByText('10 credits')).not.toBeInTheDocument();
  });

  it('says the ordinary estimate for a provider the caller holds no key for', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay
          model={{ ...topTier, provider: 'openai' }}
          costBasis={withOwnKey}
          costProfile="chatConversation"
        />
      </NextIntlClientProvider>,
    );

    expect(screen.queryByTestId('own-key-fee')).not.toBeInTheDocument();
    // 188 rather than 214: the same model on another provider resolves its missing cache
    // rates through the '*' fallback, which is the point of the fixture, not of this test.
    expect(screen.getByText('~188 credits')).toBeInTheDocument();
  });

  it('falls back to the unknown-tier fee for a model the catalogue does not band', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={model} costBasis={withOwnKey} costProfile="chatConversation" />
      </NextIntlClientProvider>,
    );

    expect(screen.getByTestId('own-key-fee')).toHaveTextContent('2 credits');
  });

  it('repeats the own-key figure in the card, the only path a touch device has to it', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={topTier} costBasis={withOwnKey} costProfile="chatConversation" />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    // The card must not keep calling it an estimated cost: on this route it is neither
    // estimated nor the platform price.
    expect(screen.getByTestId('own-key-fee-card')).toHaveTextContent('10 credits');
    expect(screen.getByText('Cost on your key')).toBeInTheDocument();
    expect(screen.queryByText('Estimated cost')).not.toBeInTheDocument();
  });

  it('marks the card figure as an estimate too where the cap decides it', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={topTier} costBasis={withOwnKey} costProfile="classifyStep" />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByTestId('own-key-fee-card')).toHaveTextContent('~4 credits');
  });
});

describe('ModelInfoPopover credit estimate', () => {
  it('repeats the estimate in the card, which is the only path a touch device has', () => {
    // The row's figure is explained by a hover tooltip, and a phone cannot hover.
    // This card opens on tap, so the number and its sentence live here too.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={BASIS} costProfile="chatConversation" />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('~214 credits')).toBeInTheDocument();
    // The card must say WHICH unit the figure prices, or the number is unreadable:
    // 214 credits for what? The wording names the shape (a short exchange with a
    // configured agent, its whole context included), which is also what stops anyone
    // reading it as a cap on an agent run.
    expect(screen.getByText(/short exchange with a configured agent/)).toBeInTheDocument();
  });

  it('bolds the figure in that sentence, and prints no emphasis marker', () => {
    // The sentence exists to qualify one number; the number is what the reader
    // came for. It is marked in the message (`**...**`) and rendered by
    // renderBoldMarkup, so a marker reaching the DOM means the render was
    // skipped and a customer is reading raw asterisks.
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={BASIS} costProfile="chatConversation" />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    const bolded = Array.from(document.body.querySelectorAll('strong')).map((n) => n.textContent);
    expect(bolded).toContain('214 credits');
    expect(document.body.textContent).not.toContain('**');
  });

  it('says nothing about credits in the card where there is nothing to estimate', () => {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelInfoPopover model={model} costBasis={{ enabled: false, profiles: {} }} />
      </NextIntlClientProvider>,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('Estimated cost')).not.toBeInTheDocument();
  });
});
