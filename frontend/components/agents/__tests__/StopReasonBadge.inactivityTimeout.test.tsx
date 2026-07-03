/**
 * @vitest-environment jsdom
 *
 * StopReasonBadge - INACTIVITY_TIMEOUT rendering regression. The i18n key
 * agentStopReason.INACTIVITY_TIMEOUT was historically MISSING from the message
 * bundles, and the badge's try/catch fallback is dead with next-intl (it does
 * not throw on a missing key - it returns the raw key string), so prod rendered
 * the literal "agentStopReason.INACTIVITY_TIMEOUT". No test pinned this reason;
 * these do, in both the reference locale and one translated locale.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';
import { StopReasonBadge } from '../StopReasonBadge';

function renderBadge(locale: 'en' | 'fr', messages: Record<string, unknown>) {
  render(
    <NextIntlClientProvider locale={locale} messages={messages}>
      <StopReasonBadge stopReason="INACTIVITY_TIMEOUT" showLabel />
    </NextIntlClientProvider>,
  );
}

describe('StopReasonBadge - INACTIVITY_TIMEOUT', () => {
  afterEach(() => cleanup());

  it('renders the translated label, never the raw i18n key (en)', () => {
    renderBadge('en', enMessages);

    expect(screen.getByText(enMessages.agentStopReason.INACTIVITY_TIMEOUT)).toBeInTheDocument();
    expect(screen.queryByText(/agentStopReason\.INACTIVITY_TIMEOUT/)).not.toBeInTheDocument();
  });

  it('renders the translated label, never the raw i18n key (fr)', () => {
    renderBadge('fr', frMessages);

    expect(
      screen.getByText((frMessages as typeof enMessages).agentStopReason.INACTIVITY_TIMEOUT),
    ).toBeInTheDocument();
    expect(screen.queryByText(/agentStopReason\.INACTIVITY_TIMEOUT/)).not.toBeInTheDocument();
  });

  it('styles it as a FAILURE (red), distinct from the partial TIMEOUT', () => {
    renderBadge('en', enMessages);

    const badge = screen.getByLabelText(enMessages.agentStopReason.INACTIVITY_TIMEOUT);
    expect(badge.className).toContain('text-red-500');
  });
});
