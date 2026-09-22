// @vitest-environment jsdom
/**
 * The exact amount a past generation states it cost, in both editions and in two languages.
 *
 * <p><b>Why this suite is separate, and why it uses the real dictionaries.</b> The price is the one
 * value on this card that is TRANSFORMED on its way to the screen, and it is transformed
 * differently per edition: cloud states the credits the ledger holds, a self-hosted install states
 * the dollars those credits are worth, because that is what every other spend surface there shows.
 * A stubbed translator turns the plural message into a key and a comma-joined argument list, so a
 * suite built on one can assert that SOMETHING was rendered and never that the right number, in the
 * right unit, in the right grammar, reached a reader.
 *
 * <p>The three ways this goes wrong are each a claim about money: the wrong unit ("78 credits" on
 * an install whose usage page says $0.078), the wrong grammar ("1 credits"), and a value drawn at
 * all when nothing was charged.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';

/**
 * The edition, swapped per test.
 *
 * <p>`format-cost` reads {@code IS_CE} once at module load, so flipping this is only half the job:
 * every test re-imports the card through {@link vi.resetModules} so the module graph is rebuilt
 * against the flag it is about to assert. Without that, one edition's branch would be unreachable
 * for the whole file - which is exactly the hole this suite was written to close.
 */
const edition = vi.hoisted(() => ({ IS_CE: false, IS_CLOUD: true }));
vi.mock('@/lib/edition', () => edition);

// Both marks the card can draw: the provider's logo, and the format glyph it falls back to when a
// model has left the catalogue. A mock missing either one fails the whole file at render, which is
// how this suite told me the card had gained a second icon path.
vi.mock('@/lib/generation/formats', () => ({
  ProviderIcon: () => <span data-testid="provider-icon" />,
  FormatGlyph: () => <span data-testid="format-glyph" />,
}));

async function renderCard(opts: {
  ce: boolean;
  billedCredits?: number | null;
  locale?: 'en' | 'fr';
}) {
  edition.IS_CE = opts.ce;
  edition.IS_CLOUD = !opts.ce;
  // The APP locale, where the app itself reads it: the URL prefix. `formatCredits` and the CE
  // dollar formatter both go through `getClientLocale()`, so a test that only swapped the
  // dictionary would render French words around English grouping and call it a pass - which is the
  // exact defect the repo's locale rule exists to prevent, and it is invisible in English.
  window.history.pushState({}, '', `/${opts.locale ?? 'en'}/app/studio`);
  vi.resetModules();
  const { GenerationCard } = await import('../GenerationCard');
  const messages = opts.locale === 'fr' ? frMessages : enMessages;
  render(
    <NextIntlClientProvider locale={opts.locale ?? 'en'} messages={messages}>
      <GenerationCard
        title="a lighthouse at dusk"
        modelLine="FLUX 1.1 Pro"
        billedCredits={opts.billedCredits}
      />
    </NextIntlClientProvider>,
  );
}

type Messages = { generationHistory: { costTitle: string } };

/**
 * The price element, found by its own tooltip rather than by the text under test.
 *
 * <p>The tooltip is looked up in the SAME dictionary the render used: keyed on the English one, a
 * French render finds nothing and every assertion about it reads as "no price shown", which is the
 * failure mode this whole suite is meant to catch.
 */
const priceText = (locale: 'en' | 'fr' = 'en') => screen.queryByTitle(
  ((locale === 'fr' ? frMessages : enMessages) as unknown as Messages).generationHistory.costTitle,
)?.textContent?.trim();

afterEach(() => {
  cleanup();
  edition.IS_CE = false;
  edition.IS_CLOUD = true;
});

describe('what a generated asset says it cost', () => {
  it('states CREDITS on cloud, where credits are what the reader spends', async () => {
    await renderCard({ ce: false, billedCredits: 78 });

    expect(priceText()).toBe('78 credits');
  });

  it('states DOLLARS on a self-hosted install, at the same scale as its own usage page', async () => {
    // 1 credit = $0.001. Quoting credits here would put "78 credits" under an asset whose spend
    // reads $0.078 two screens away, on the one install that shows both.
    await renderCard({ ce: true, billedCredits: 78 });

    expect(priceText()).toBe('$0.078');
  });

  it('says "1 credit", not "1 credits", for a generation that cost exactly one', async () => {
    await renderCard({ ce: false, billedCredits: 1 });

    expect(priceText()).toBe('1 credit');
  });

  it('speaks the reader\'s language, and groups the number the way that language does', async () => {
    // A four-figure video: the French reader's own separator, and the French noun.
    await renderCard({ ce: false, billedCredits: 1234, locale: 'fr' });

    // A narrow no-break space in French grouping, so the check is on the parts rather than on a
    // character this file would have to spell.
    expect(priceText('fr')).toMatch(/^1\s?234 crédits$/);
  });

  it('says nothing at all when the platform charged nothing, in either edition', async () => {
    // The reader's own provider key paid. Absent is not zero, and it must not become a "$0.00"
    // on the edition that formats every amount as money.
    await renderCard({ ce: true });
    expect(priceText()).toBeUndefined();

    cleanup();
    await renderCard({ ce: false, billedCredits: null });
    expect(priceText()).toBeUndefined();
  });

  it('says nothing for zero or for a value that is not a number, in either edition', async () => {
    await renderCard({ ce: true, billedCredits: 0 });
    expect(priceText()).toBeUndefined();

    cleanup();
    await renderCard({ ce: false, billedCredits: Number.NaN });
    expect(priceText()).toBeUndefined();
  });
});
