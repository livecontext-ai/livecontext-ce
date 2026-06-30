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
