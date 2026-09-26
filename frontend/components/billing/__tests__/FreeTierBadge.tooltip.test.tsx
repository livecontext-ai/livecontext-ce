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

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import deMessages from '@/messages/de.json';
import esMessages from '@/messages/es.json';
import ptMessages from '@/messages/pt.json';
import zhMessages from '@/messages/zh.json';
import { FreeTierBadge } from '../FreeTierBadge';

/** Render the chip and open its tooltip, returning the portalled content. */
async function hoverChip(messages: object = enMessages) {
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <FreeTierBadge covered />
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
  cleanup();
});

describe('FreeTierBadge - the tooltip, from the real message files', () => {
  it('explains that the Free monthly credits pay for this model', async () => {
    const tip = await hoverChip();

    expect(tip).toHaveTextContent(freeTier(enMessages).tooltip);
  });

  it('regression: never mentions the retired separate AI allowance', async () => {
    // The Free plan used to carry a separate monthly AI pot, quoted here. It was
    // merged into the monthly credits, so the sentence must not promise a second pot.
    const tip = await hoverChip();

    expect(tip.textContent).not.toMatch(/AI allowance|AI credits/i);
  });

  it('shows the visible word and the short hidden label, not the sentence, on the chip', async () => {
    // The chip is inside a button on two surfaces, so the sentence must stay in
    // the tooltip; what the chip carries is a few words for a screen reader.
    await hoverChip();

    const chip = screen.getByTestId('free-tier-badge');
    expect(chip).toHaveTextContent(freeTier(enMessages).label);
    expect(chip).toHaveTextContent(freeTier(enMessages).srLabel);
    expect(chip.textContent).not.toContain(freeTier(enMessages).tooltip);
  });

  it('renders in every locale from the real catalogue', async () => {
    // A key that landed in the wrong namespace in one locale is invisible to a
    // key-parity check and shows the reader a raw message path.
    for (const messages of [enMessages, frMessages, deMessages, esMessages, ptMessages, zhMessages]) {
      cleanup();
      const tip = await hoverChip(messages);

      expect(tip.textContent).not.toContain('billing.freeTier');
      expect(tip.textContent?.trim().length).toBeGreaterThan(10);
    }
  });
});
