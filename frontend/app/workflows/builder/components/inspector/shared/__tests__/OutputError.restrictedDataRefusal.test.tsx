// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import { OutputError } from '../OutputError';

// A workflow agent node that refused to send Gmail / Google Drive data to its provider fails with
// a RESTRICTED_DATA_PROVIDER_NOT_ALLOWED message; the run inspector banner must explain the fix in
// the app locale while keeping the raw backend message for diagnosis.
const NODE_ERROR =
  "RESTRICTED_DATA_PROVIDER_NOT_ALLOWED: Data from Gmail or Google Drive cannot be sent to the model provider 'deepseek'.";

function renderWith(error: string, locale: 'en' | 'fr' = 'en') {
  return render(
    <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : fr}>
      <OutputError error={error} />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe('OutputError - restricted-data refusal', () => {
  it('shows the translated explanation above the raw node error', () => {
    renderWith(NODE_ERROR);

    expect(screen.getByTestId('restricted-data-refusal')).toHaveTextContent(en.errors.restrictedDataProvider);
    expect(screen.getByText(NODE_ERROR)).toBeInTheDocument();
  });

  it('follows the app locale', () => {
    renderWith(NODE_ERROR, 'fr');

    expect(screen.getByTestId('restricted-data-refusal')).toHaveTextContent(fr.errors.restrictedDataProvider);
  });

  it('renders only the raw message for any other node error', () => {
    renderWith('Out of credits');

    expect(screen.queryByTestId('restricted-data-refusal')).not.toBeInTheDocument();
    expect(screen.getByText('Out of credits')).toBeInTheDocument();
  });
});
