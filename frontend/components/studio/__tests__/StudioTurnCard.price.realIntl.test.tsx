// @vitest-environment jsdom
/**
 * What a turn in the studio thread says it cost, in both editions.
 *
 * <p>The thread and the history grid sit in one column on an empty studio, ten pixels apart, and
 * they state the same charge. This card was written with its own copy of the price expression and
 * quoted CREDITS on a self-hosted install whose history card, directly below it, quoted dollars for
 * the identical asset. Both now go through one helper, and this suite is what holds them there:
 * a card that renders its own arithmetic again fails here rather than on someone's screen.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { STUDIO_MESSAGE_TYPE, type StudioRequestEnvelope } from '@/lib/generation/studioMessage';

const edition = vi.hoisted(() => ({ IS_CE: false, IS_CLOUD: true }));
vi.mock('@/lib/edition', () => edition);
vi.mock('@/lib/generation/formats', () => ({
  ProviderIcon: () => <span data-testid="provider-icon" />,
  FormatGlyph: () => <span data-testid="format-glyph" />,
}));
// The app's file viewer, reduced: real, it pulls a by-id fetch, a media element and an object-URL
// hook into a test about an amount of money.
vi.mock('@/components/app/FileDetailView', () => ({
  FileDetailView: () => <div data-testid="file-detail" />,
}));

const REQUEST: StudioRequestEnvelope = {
  // The wire discriminator, from the module that owns it rather than retyped: a literal that drifts
  // from it type-checks as a plain string here and matches nothing at runtime.
  type: STUDIO_MESSAGE_TYPE,
  role: 'request' as const,
  prompt: 'a lighthouse at dusk',
  model: 'flux-1.1-pro',
  kind: 'image',
};

async function renderTurn(opts: {
  ce: boolean;
  billedCredits?: number;
  billedMultiplier?: number;
  billedMultiplierReasons?: string[];
}) {
  edition.IS_CE = opts.ce;
  edition.IS_CLOUD = !opts.ce;
  vi.resetModules();
  const { StudioTurnCard } = await import('../StudioTurnCard');
  render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <StudioTurnCard
        request={REQUEST}
        result={{
          type: STUDIO_MESSAGE_TYPE,
          role: 'result',
          success: true,
          model: 'flux-1.1-pro',
          kind: 'image',
          ...(opts.billedCredits != null ? { billedCredits: opts.billedCredits } : {}),
          ...(opts.billedMultiplier != null ? { billedMultiplier: opts.billedMultiplier } : {}),
          ...(opts.billedMultiplierReasons
            ? { billedMultiplierReasons: opts.billedMultiplierReasons }
            : {}),
        }}
      />
    </NextIntlClientProvider>,
  );
}

const priceText = () => screen.queryByTitle(
  (enMessages as unknown as { generationHistory: { costTitle: string } }).generationHistory.costTitle,
)?.textContent?.trim();

afterEach(() => {
  cleanup();
  edition.IS_CE = false;
  edition.IS_CLOUD = true;
});

/**
 * The factor, on the FINISHED turn.
 *
 * <p>The composer shows it while the price is still an estimate. A card that drops it once the
 * charge is real states the only unexplained half of the arithmetic at the moment it matters least
 * and hides it at the moment it matters most: ten seconds at the published rate does not multiply
 * out to what was taken, and the reader has no way to check it but to generate again.
 */
describe('what a studio turn says moved its price', () => {
  it('states the factor the charge was computed with', async () => {
    await renderTurn({ ce: false, billedCredits: 240, billedMultiplier: 1.2 });

    expect(screen.getByText('x1.2')).toBeInTheDocument();
  });

  it('READS OUT the reason, in the same words the estimate used', async () => {
    // Two defects at once, and the old assertion could see neither.
    //
    // It was `getByTitle('resolution x1.2')`. A `title` on a role-less span is not an accessible
    // name and does not exist on touch at all: the argument is written out five files away in
    // StudioComposer, which uses an sr-only span, and this card shipped the thing that comment
    // forbids. Testing Library reads `title` off any element, so the query passed against a badge
    // announced as a naked "x1.2" - on the surface showing a charge that already happened.
    //
    // And it asserted the server's RAW line. The server writes reasons as contract names in
    // English (`resolution x1.2`), so the card said that beside an estimate reading "includes
    // Resolution x1.2", translated and list-joined per locale: one fact, two vocabularies, on one
    // screen. The card now words them through the same dictionary the estimate uses.
    await renderTurn({
      ce: false,
      billedCredits: 240,
      billedMultiplier: 1.2,
      billedMultiplierReasons: ['resolution x1.2'],
    });

    const badge = screen.getByText(/^x1\.2/);
    expect(badge).toHaveTextContent('Resolution x1.2');
    expect(badge).not.toHaveAttribute('aria-label');
  });

  it('hands back a reason it cannot parse rather than dropping it', async () => {
    // A future wire format, or a parameter whose name does not fit `param xN`. The reader gets the
    // server's own words instead of a badge with nothing to explain it.
    await renderTurn({
      ce: false,
      billedCredits: 240,
      billedMultiplier: 1.2,
      billedMultiplierReasons: ['something the client has never seen'],
    });

    expect(screen.getByText(/^x1\.2/))
      .toHaveTextContent('something the client has never seen');
  });

  it('states a DISCOUNT too, which the card used to drop', async () => {
    // A cheaper tier is a fact about the charge exactly as much as a dearer one, and the composer
    // announced it before the run. Dropped here, the card stated a size and an amount off by half
    // with no third number to reconcile them.
    await renderTurn({ ce: false, billedCredits: 60, billedMultiplier: 0.5 });

    expect(screen.getByText(/^x0\.5/)).toBeInTheDocument();
  });

  it('says nothing for a turn charged at the published rate', async () => {
    await renderTurn({ ce: false, billedCredits: 78 });

    expect(screen.queryByText(/^x\d/)).toBeNull();
  });
});

describe('what a studio turn says it cost', () => {
  it('states credits on cloud', async () => {
    await renderTurn({ ce: false, billedCredits: 78 });

    expect(priceText()).toBe('78 credits');
  });

  it('states DOLLARS on a self-hosted install, like the history card under it', async () => {
    // Reachable: the CE relay is exactly what puts a charge on a linked install's turn.
    await renderTurn({ ce: true, billedCredits: 78 });

    expect(priceText()).toBe('$0.078');
  });

  it('says nothing at all when the platform charged nothing', async () => {
    await renderTurn({ ce: true });

    expect(priceText()).toBeUndefined();
  });
});
