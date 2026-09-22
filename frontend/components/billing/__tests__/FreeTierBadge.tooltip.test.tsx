// @vitest-environment jsdom
/**
 * The sentence itself, rendered from the real message files.
 *
 * <p>Separate from the sibling suite because that one mocks `next-intl` wholesale
 * to assert key paths, and these cases need the opposite: the actual catalogue,
 * so that a renamed placeholder or a key that landed in the wrong namespace shows
 * up as a failing assertion rather than as `billing.freeTier.tooltip` printed at
 * a reader. Parity checks cannot catch either.
 *
 * <p>The tooltip is portalled and opens on a pointer, which is reachable here
 * with the pattern the palette's hover-card suite established.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const plan = vi.hoisted(() => ({ credits: 100, resolved: true }));
vi.mock('@/lib/hooks/useFreeAiCredits', () => ({
  useFreeAiCreditsAnswer: () => ({ credits: plan.credits, resolved: plan.resolved }),
}));

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import deMessages from '@/messages/de.json';
import esMessages from '@/messages/es.json';
import ptMessages from '@/messages/pt.json';
import zhMessages from '@/messages/zh.json';
import { ComposerFreeTierBadge, FreeTierBadge } from '../FreeTierBadge';

/** Render the chip and open its tooltip, returning the portalled content. */
async function hoverChip(props: { credits?: number } = {}, messages: object = enMessages) {
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <FreeTierBadge covered {...props} />
    </NextIntlClientProvider>,
  );
  const chip = screen.getByTestId('free-tier-badge');
  // `pointerMove` is what Radix's tooltip trigger listens on; the other two are
  // what the palette's hover-card suite sends, kept so the two read alike.
  fireEvent.pointerEnter(chip, { pointerType: 'mouse' });
  fireEvent.pointerMove(chip, { pointerType: 'mouse' });
  fireEvent.mouseEnter(chip);
  // The chip's own provider holds a 150ms delay, which waitFor outlasts.
  return waitFor(() => screen.getByRole('tooltip'));
}

const freeTier = (m: typeof enMessages) => m.billing.freeTier;

afterEach(() => {
  plan.credits = 100;
  plan.resolved = true;
  cleanup();
});

/** Hover the composer's own badge, which resolves the figure for itself. */
async function hoverComposerChip() {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ComposerFreeTierBadge />
    </NextIntlClientProvider>,
  );
  const chip = screen.getByTestId('free-tier-badge');
  fireEvent.pointerEnter(chip, { pointerType: 'mouse' });
  fireEvent.pointerMove(chip, { pointerType: 'mouse' });
  fireEvent.mouseEnter(chip);
  return waitFor(() => screen.getByRole('tooltip'));
}

describe('FreeTierBadge - the tooltip, from the real message files', () => {
  it('explains the allowance', async () => {
    const tip = await hoverChip();

    expect(tip).toHaveTextContent(freeTier(enMessages).tooltip);
  });

  it('quotes the figure it is given, interpolated', async () => {
    // The placeholder is the one part of the string that can break silently: a
    // rename leaves the sentence looking fine with `{credits}` in the middle of it.
    const tip = await hoverChip({ credits: 250 });

    expect(tip).toHaveTextContent('250');
    expect(tip.textContent).not.toContain('{credits}');
  });

  it('shows the visible word and the short hidden label, not the sentence, on the chip', async () => {
    // The chip is inside a button on two surfaces, so the sentence must stay in
    // the tooltip; what the chip carries is a few words for a screen reader.
    await hoverChip({ credits: 250 });

    const chip = screen.getByTestId('free-tier-badge');
    expect(chip).toHaveTextContent(freeTier(enMessages).label);
    expect(chip).toHaveTextContent(freeTier(enMessages).srLabel);
    expect(chip.textContent).not.toContain(freeTier(enMessages).tooltip);
  });

  it('carries the live allowance all the way into the composer sentence', async () => {
    // The join nothing else covered: the plan row answers, quotableCredits keeps
    // the figure, and the sentence the reader sees is the one that quotes it.
    plan.credits = 250;

    const tip = await hoverComposerChip();

    expect(tip).toHaveTextContent('250');
  });

  it('falls back to the figureless sentence when the plan row has not answered', async () => {
    plan.resolved = false;

    const tip = await hoverComposerChip();

    expect(tip).toHaveTextContent(freeTier(enMessages).tooltip);
    expect(tip.textContent).not.toContain('100');
  });

  it('renders in every locale, with the placeholder intact in each', async () => {
    // One assertion per catalogue: a wrong placeholder name in a single locale is
    // invisible to a key-parity check and shows the reader a raw brace.
    for (const messages of [enMessages, frMessages, deMessages, esMessages, ptMessages, zhMessages]) {
      cleanup();
      const tip = await hoverChip({ credits: 250 }, messages);

      expect(tip).toHaveTextContent('250');
      expect(tip.textContent).not.toContain('{credits}');
      expect(tip.textContent).not.toContain('billing.freeTier');
    }
  });
});
