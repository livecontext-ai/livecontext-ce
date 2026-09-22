// @vitest-environment jsdom
/**
 * What an own-key row says next to its charge.
 *
 * <p>The row is one number and one badge, on one line. It used to be two lines: the credits, then
 * "about $0.03 at your provider (estimate)" underneath. That second line put a different currency,
 * a different biller and an estimate rather than a charge into a table of charges - and doubled
 * the height of every own-key row to do it.
 *
 * <p>Both halves are asserted, because only one of them is visible in a screenshot: the badge has
 * to BE there (without it nothing distinguishes the two routes, which bill from different pockets),
 * and the dollar figure has to be gone even when the entry still carries it, since the ledger keeps
 * writing `providerCostCredits` whatever this cell renders.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import type { CreditHistoryEntry } from '@/lib/api/services/quota-api.service';
import { OwnKeyRowNote } from '../OwnKeyRowNote';

const entry = (over: Partial<CreditHistoryEntry>): CreditHistoryEntry => ({
  id: 1,
  userId: 42,
  amount: -3,
  balanceAfter: 997,
  sourceType: 'AGENT_EXECUTION',
  sourceId: 'run-1',
  provider: 'anthropic',
  model: 'claude-sonnet-5',
  promptTokens: 1200,
  completionTokens: 300,
  cachedTokens: null,
  keyRoute: null,
  providerCostCredits: null,
  description: 'agent turn',
  createdAt: '2026-09-20T10:00:00Z',
  ...over,
});

const renderNote = (e: CreditHistoryEntry) =>
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OwnKeyRowNote entry={e} />
    </NextIntlClientProvider>,
  );

afterEach(() => {
  cleanup();
});

describe('OwnKeyRowNote', () => {
  it('marks a row that ran on the user own key', () => {
    renderNote(entry({ keyRoute: 'OWN_KEY' }));

    expect(screen.getByTestId('own-key-row-badge')).toHaveTextContent('Your key');
  });

  it('renders nothing at all on a platform-route row', () => {
    const { container } = renderNote(entry({ keyRoute: 'PLATFORM', providerCostCredits: 30_000 }));

    expect(container).toBeEmptyDOMElement();
  });

  it('states no dollar amount, even when the row carries the provider-side cost', () => {
    // 30,000 credits is $30 at list price: the exact figure the removed second line printed.
    const { container } = renderNote(entry({ keyRoute: 'OWN_KEY', providerCostCredits: 30_000 }));

    expect(container.textContent).toBe('Your key');
  });

  it('sits inline beside the amount rather than on a line of its own', () => {
    // The class IS the behaviour here: `block` is what put the badge under the credits and made
    // every own-key row twice as tall as its neighbours.
    const badge = renderNote(entry({ keyRoute: 'OWN_KEY' })).getByTestId('own-key-row-badge');

    expect(badge.className).toContain('inline-block');
    expect(badge.className.split(/\s+/)).not.toContain('block');
  });
});
