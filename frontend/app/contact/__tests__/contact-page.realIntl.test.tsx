// @vitest-environment jsdom
/**
 * The unconfigured-captcha notice against the REAL dictionaries, in two languages.
 *
 * <p>Every other test of this page mocks next-intl with an identity translator and compares
 * message KEYS, which is what keeps those assertions independent of the English wording. The
 * cost is that they cannot see the one failure that matters for a NEW key: delete
 * `contact.unavailable` from all six locale files and all of them stay green while the page
 * renders the literal string "unavailable" to a visitor whose form has just refused them.
 *
 * <p>`__tests__/i18n-locale-parity.test.ts` catches "present in en, missing elsewhere". It
 * cannot catch "absent everywhere", because a key that exists in no file is not a parity gap.
 * So the assertion here is a WORD a reader would recognise, in each language, on the exact
 * surface the fix added.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import frMessages from '@/messages/fr.json';

vi.mock('next/navigation', () => ({
  useSearchParams: () => {
    throw new Error('useSearchParams must not be called in ContactPage (forces CSR bailout of the page)');
  },
}));

vi.mock('next/script', () => ({ default: () => null }));

async function renderWithMessages(messages: Record<string, unknown>, locale: string) {
  vi.resetModules();
  // No site key: the state this copy exists for, and the state every CE image ships in.
  delete process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
  const { default: ContactPage } = await import('../page');
  render(
    <NextIntlClientProvider locale={locale} messages={messages as never}>
      <ContactPage />
    </NextIntlClientProvider>,
  );
}

describe('ContactPage unavailable notice resolves in the real dictionaries', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    delete process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;
  });

  it('renders real English copy, not the raw message key', async () => {
    await renderWithMessages(enMessages as Record<string, unknown>, 'en');

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(/contact form is not working/i);
    expect(alert).toHaveTextContent('contact@livecontext.ai');
    // next-intl prints the key path when a key is missing; that is the silent failure.
    expect(alert.textContent).not.toBe('unavailable');
    expect(alert.textContent).not.toContain('contact.unavailable');
  });

  it('renders real French copy, where the words differ from the key', async () => {
    await renderWithMessages(frMessages as Record<string, unknown>, 'fr');

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(/formulaire de contact/i);
    expect(alert).toHaveTextContent('contact@livecontext.ai');
    expect(alert.textContent).not.toContain('unavailable');
  });
});
