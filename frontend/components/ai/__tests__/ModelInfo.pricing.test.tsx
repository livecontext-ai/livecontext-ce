// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import type { AIModel } from '@/hooks/useModels';
import { ModelOptionDisplay } from '../ModelInfo';

const model: AIModel = {
  id: 'priced-model',
  name: 'Priced Model',
  provider: 'openai',
  pricing: {
    input: 5,
    output: 25,
  },
};

afterEach(() => {
  cleanup();
});

function renderModel(locale: 'en' | 'fr', messages: Record<string, unknown>) {
  render(
    <NextIntlClientProvider locale={locale} messages={messages}>
      <ModelOptionDisplay model={model} />
    </NextIntlClientProvider>,
  );
}

describe('ModelOptionDisplay pricing', () => {
  it('renders one dollar sign per token price in English', () => {
    renderModel('en', enMessages);

    expect(screen.getByText('$5/$25 per 1M')).toBeInTheDocument();
    expect(screen.queryByText(/\$\$5|\$\$25/)).not.toBeInTheDocument();
  });

  it('renders one dollar sign per token price in French', () => {
    renderModel('fr', frMessages);

    expect(screen.getByText('$5/$25 / 1M')).toBeInTheDocument();
    expect(screen.queryByText(/\$\$5|\$\$25|\$5\$|\$25\$/)).not.toBeInTheDocument();
  });
});

/**
 * A decision model prices its input at $0.042 per 1M and bills no output at all.
 * Rendered through a formatter that only ever kept one decimal, both halves came
 * out "$0.0" - so the single row in the picker whose argument IS its price was
 * also the only row that read as free, indistinguishable from an unpriced one.
 */
describe('ModelOptionDisplay pricing - rates below one decimal', () => {
  const priced = (input: number, output: number): AIModel => ({
    id: 'jev-latest',
    name: 'Jev',
    provider: 'typesafe',
    pricing: { input, output },
  });

  function renderPriced(input: number, output: number) {
    render(
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <ModelOptionDisplay model={priced(input, output)} />
      </NextIntlClientProvider>,
    );
  }

  it('keeps a sub-cent input rate legible instead of rounding it to zero', () => {
    renderPriced(0.042, 0);

    expect(screen.getByText('$0.042/$0 per 1M')).toBeInTheDocument();
    expect(screen.queryByText(/\$0\.0\//)).not.toBeInTheDocument();
  });

  it('prints a genuinely free rate as $0, with no decimal to misread', () => {
    renderPriced(0, 0);

    expect(screen.getByText('$0/$0 per 1M')).toBeInTheDocument();
  });

  it('keeps two significant digits, so two cheap models stay distinguishable', () => {
    // The point of the band: 0.042 and 0.006 both used to render "$0.0".
    renderPriced(0.006, 0.0125);

    expect(screen.getByText('$0.006/$0.013 per 1M')).toBeInTheDocument();
  });

  it('still shows one decimal from a tenth upward, unchanged', () => {
    renderPriced(0.3, 0.9);

    expect(screen.getByText('$0.3/$0.9 per 1M')).toBeInTheDocument();
  });

  it('still rounds a dollar-plus rate to the integer, unchanged', () => {
    renderPriced(2, 10);

    expect(screen.getByText('$2/$10 per 1M')).toBeInTheDocument();
  });
});
