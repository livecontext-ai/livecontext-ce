// @vitest-environment jsdom
/**
 * The composer against the REAL dictionaries, in two languages.
 *
 * <p>This suite exists for one bug class, and it is a class that has shipped here twice. The price
 * unit reaches the screen through `priceUnitLabel`, which prepends `source.` to the key ITSELF, so a
 * translator bound one level too deep produces `credentials.source.source.priceUnits.second` - which
 * resolves to nothing, and next-intl then prints the key path, on screen, in every locale. The
 * failure is silent: no throw, no warning, just machine text in front of a reader.
 *
 * <p>A stubbed translator cannot see it. The dialog this composer replaced had a suite for exactly
 * this, and deleting that suite with the dialog would have left the class unguarded on the surface
 * that inherited the code. So the assertion is a WORD a reader would recognise, in a language where
 * it differs from the key, rather than the absence of a prefix.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';

// A published rate per SECOND: the one shape whose unit has to travel through the unit dictionary.
const quoteState = vi.hoisted(() => ({
  value: {
    quote: {
      hasPricing: true,
      priceUnit: 'second',
      baseCredits: '0',
      unitCredits: '60',
    } as Record<string, unknown>,
    quantity: 5,
    // The question has been answered: the composer only states a price once it has.
    settled: true,
    stale: false,
    multiplier: 1,
  },
}));
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: () => quoteState.value,
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
vi.mock('@/components/studio/StudioPayerControl', () => ({
  StudioPayerControl: () => <div data-testid="payer" />,
}));

import { StudioComposer } from '../StudioComposer';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';
import { STUDIO_MESSAGE_TYPE } from '@/lib/generation/studioMessage';

const MODEL: GenerationModel = {
  model: 'seedance-2',
  kind: 'video',
  label: 'Seedance 2.0',
  provider: 'seedance',
  iconSlug: null,
  apiToolId: 'tool-1',
  integrationName: 'seedance',
  accepts: ['prompt', 'duration_seconds'],
  required: [],
  limits: {},
  billedOn: 'duration_seconds',
  measuredUnit: 'second',
  defaultQuantity: '5',
  price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
  async: true,
};

/** The same model, declaring a priced resolution, so the real factor pipeline has work to do. */
const MODULATED: GenerationModel = {
  ...MODEL,
  model: 'seedance-2-tiered',
  accepts: ['prompt', 'duration_seconds', 'resolution'],
  required: ['resolution'],
  limits: { resolution: { type: 'enum', values: ['720p', '1080p'] } },
  price: {
    unit: 'second', baseCredits: '0', unitCredits: '60',
    modifiers: { resolution: { by_value: { '720p': 1, '1080p': 2 } } },
  },
} as GenerationModel;

function renderIn(
  locale: 'en' | 'fr',
  model: GenerationModel = MODEL,
  params?: Record<string, unknown>,
) {
  const messages = (locale === 'fr' ? frMessages : enMessages) as Record<string, unknown>;
  return render(
    <NextIntlClientProvider locale={locale} messages={messages}>
      <StudioComposer
        models={[model]}
        selectedModel={model}
        onSelectModel={() => {}}
        onSubmit={async () => true}
        // Parameters are seated through `reuse`, the same door the "Modify" button on a past turn
        // uses. Setting them any other way would mean reaching into component state, and the point
        // of this suite is to exercise what a reader's own actions produce.
        reuse={params ? {
          type: STUDIO_MESSAGE_TYPE,
          role: 'request',
          prompt: 'a lighthouse at dusk',
          model: model.model,
          kind: model.kind,
          params,
        } : null}
        onReuseConsumed={() => {}}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  quoteState.value = {
    quote: { hasPricing: true, priceUnit: 'second', baseCredits: '0', unitCredits: '60' },
    quantity: 5,
    settled: true,
    stale: false,
    multiplier: 1,
  };
});

describe('StudioComposer - the real dictionaries', () => {
  it('names the price unit in English, through the unit dictionary', () => {
    renderIn('en');

    const price = screen.getByText(/credits per/i);
    expect(price.textContent).toContain('second');
    // The failure this guards is a key path on screen. Both halves are named because the wrong
    // binding produces the deeper one and the wrong namespace produces the shallower.
    expect(price.textContent).not.toContain('priceUnits');
    expect(price.textContent).not.toContain('credentials.');
  });

  it('translates that unit, which is the whole reason it goes through a dictionary', () => {
    renderIn('fr');

    // A real translated word, not the absence of a prefix: an English unit inside a French sentence
    // is exactly what a wrong-but-resolving binding would leave behind.
    const price = screen.getByText(/crédits par/i);
    expect(price.textContent).toContain('seconde');
    expect(price.textContent).not.toContain('priceUnits');
  });

  it('says what the model is called, so the whole namespace wiring is exercised', () => {
    // Guards the composer's own namespace as well as the shared one: a component rendered under a
    // provider it does not match shows key paths everywhere, and the price alone would not say so.
    renderIn('fr');

    expect(screen.getByPlaceholderText(/Seedance 2\.0/)).toBeInTheDocument();
    expect(screen.queryByText(/^studio\./)).toBeNull();
  });
});

/**
 * The factor sentence, end to end through the component.
 *
 * <p>The dedicated factor suite mocks `describePriceFactors` to a constant, so it proves the badge
 * is gated on the server's echo and nothing about the pipeline that produces the words: it would
 * stay green if the composer passed the wrong model, the wrong parameters, or an empty object into
 * it. Nothing anywhere exercised source -> reasons -> sentence, which is where the parameter label
 * and the list join live, and both of those have shipped broken (a key path on screen, and a
 * Chinese list with no separator).
 */
describe('StudioComposer - the factor sentence, unmocked', () => {
  it('names the CHOICE and its factor, read from the model the composer holds', () => {
    // The parameters are the composer's own defaults for this model; what matters is that the
    // sentence comes out of the real table rather than a stub, in the reader's language.
    quoteState.value = {
      ...quoteState.value,
      quote: { ...quoteState.value.quote, priceMultiplier: '2' },
      multiplier: 2,
    };

    renderIn('en', MODULATED, { resolution: '1080p' });

    // `price.factor` is "{param} x{factor}" and `price.factors` wraps it: a pipeline that lost the
    // dictionary would print `params.resolution` or the bare key path here.
    expect(screen.getByText(/includes/i).textContent).toMatch(/Resolution x2/i);
  });

  it('says it in FRENCH, which a stubbed sentence could never show', () => {
    quoteState.value = {
      ...quoteState.value,
      quote: { ...quoteState.value.quote, priceMultiplier: '2' },
      multiplier: 2,
    };

    renderIn('fr', MODULATED, { resolution: '1080p' });

    const note = screen.getByText(/dont /i);
    expect(note.textContent).toMatch(/x2/);
    expect(note.textContent).not.toContain('price.');
    expect(note.textContent).not.toContain('params.');
  });

  it('says nothing at all when the server applied no factor', () => {
    renderIn('en', MODULATED, { resolution: '1080p' });

    expect(screen.queryByText(/includes/i)).toBeNull();
  });
});
